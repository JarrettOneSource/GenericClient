package com.genericclient;

import java.awt.Canvas;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

final class GenericClientGameInput implements AutoCloseable, GenericClientWalker.WalkInput
{
	private static final int LOCAL_TILE_SIZE = Perspective.LOCAL_TILE_SIZE;
	private static final long HOVER_SETTLE_MILLIS = 250L;
	private static final long CONTEXT_MENU_SETTLE_MILLIS = 150L;
	private static final long CLICK_RESULT_TIMEOUT_MILLIS = 2500L;
	private static final long CAMERA_TURN_TIMEOUT_MILLIS = 1_200L;
	private static final long CAMERA_POLL_MILLIS = 40L;
	private static final int CAMERA_SETTLED_UNITS = 192;
	private static final int CONTEXT_MENU_ENTRY_HEIGHT = 15;
	private static final int MAX_TARGET_ATTEMPTS = 20;
	private static final int MINIMAP_CLICK_MARGIN = 10;
	static final int CAMERA_FULL_TURN = 1 << 14;
	static final int CAMERA_YAW_MASK = CAMERA_FULL_TURN - 1;
	static final int CAMERA_INTERACTION_PITCH = 383 << 3;

	private final Client client;
	private final ClientThread clientThread;
	private final Consumer<String> reporter;
	private final GenericClientSyntheticMouse syntheticMouse;
	private final AtomicBoolean running = new AtomicBoolean();
	private final AtomicLong routeRequestSequence = new AtomicLong();
	private final AtomicLong cancellationSequence = new AtomicLong();

	private volatile boolean awaitingMenuResult;
	private volatile boolean clickFinished;
	private volatile String observedWalkResult;
	private volatile WorldPoint targetWorldPoint;
	private volatile int expectedParam0;
	private volatile int expectedParam1;
	private volatile int expectedWorldViewId;
	private volatile int targetAttemptsRemaining;
	private volatile TargetSurface targetSurface;
	private volatile SelectionMode selectionMode;
	private volatile List<WorldPoint> requestedWorldPoints = Collections.emptyList();
	private volatile CompletableFuture<RawWalkResult> activeResult;
	private final GenericClientInputCallbacks callbacks;
	private volatile boolean clickDispatched;
	private volatile boolean cameraPrepared;
	private volatile double activeReachFraction = 1.0;
	private volatile int cameraTargetYaw;
	private volatile long cameraTurnDeadlineNanos;
	private volatile GenericClientActivityContext activeActivityContext =
		GenericClientActivityContext.none();
	private volatile boolean closed;

	GenericClientGameInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientSyntheticMouse syntheticMouse,
		Consumer<String> reporter)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.callbacks = new GenericClientInputCallbacks(this, () -> this.activeResult, executor);
		this.syntheticMouse = syntheticMouse;
		this.reporter = reporter;
	}

	CompletableFuture<GenericClientInteractionResult> walkToRandomTile(GenericClientActivityContext activityContext)
	{
		return performWalkInteraction(SelectionMode.RANDOM, Collections.emptyList(), activityContext, 0L, 1.0);
	}

	CompletableFuture<GenericClientInteractionResult> walkToFarthest(
		List<WorldPoint> candidates,
		GenericClientActivityContext activityContext)
	{
		return walkToFarthest(candidates, activityContext, 1.0);
	}

	@Override
	public CompletableFuture<GenericClientInteractionResult> walkToFarthest(
		List<WorldPoint> candidates, GenericClientActivityContext activityContext, double reachFraction)
	{
		if (!Double.isFinite(reachFraction) || reachFraction <= 0.0 || reachFraction > 1.0)
			throw new IllegalArgumentException("Walk reach fraction must be in (0,1]");
		if (candidates == null || candidates.isEmpty())
		{
			throw new IllegalArgumentException("Walk route candidates cannot be empty");
		}
		List<WorldPoint> copy = new ArrayList<>(candidates.size());
		for (WorldPoint candidate : candidates)
		{
			if (candidate == null)
			{
				throw new IllegalArgumentException("Walk route candidates cannot contain null");
			}
			copy.add(candidate);
		}
		return performWalkInteraction(
			SelectionMode.ROUTE,
			Collections.unmodifiableList(copy),
			activityContext,
			routeRequestSequence.incrementAndGet(), reachFraction);
	}

	private CompletableFuture<GenericClientInteractionResult> performWalkInteraction(
		SelectionMode mode,
		List<WorldPoint> candidates,
		GenericClientActivityContext activityContext,
		long requestId, double reachFraction)
	{
		long cancellationId = cancellationSequence.get();
		return perform(
			() -> beginWalkClickUnlessCancelled(
				mode, candidates, requestId, cancellationId, activityContext, reachFraction)).thenApply(result ->
			{
				reporter.accept("WALK_INTERACTION_COMPLETED target=" + result.getTarget() +
					" clicked=" + result.isClickDispatched() +
					" result=" + result.getDetail());
				return result;
			});
	}

	private CompletableFuture<RawWalkResult> beginWalkClickUnlessCancelled(
		SelectionMode mode,
		List<WorldPoint> candidates,
		long requestId,
		long cancellationId,
		GenericClientActivityContext activityContext, double reachFraction)
	{
		if (closed || !activityContext.isInputAllowed() || cancellationSequence.get() != cancellationId ||
			(mode == SelectionMode.ROUTE && routeRequestSequence.get() != requestId))
		{
			return CompletableFuture.completedFuture(new RawWalkResult(
				null,
				mode == SelectionMode.ROUTE ? "WALK_TILE_CLICK_CANCELLED" : "WALK_CLICK_STOPPED",
				false));
		}
		return beginWalkClick(mode, candidates, activityContext, reachFraction);
	}

	static CompletableFuture<GenericClientInteractionResult> perform(
		Supplier<CompletableFuture<RawWalkResult>> interaction)
	{
		return interaction.get().thenApply(raw -> new GenericClientInteractionResult(
			raw.target, raw.detail, raw.clickDispatched, Collections.emptyMap(), Collections.emptyMap()));
	}

	private synchronized CompletableFuture<RawWalkResult> beginWalkClick(
		SelectionMode mode,
		List<WorldPoint> candidates,
		GenericClientActivityContext activityContext, double reachFraction)
	{
		CompletableFuture<RawWalkResult> result = new CompletableFuture<>();
		if (!running.compareAndSet(false, true))
		{
			String message = "WALK_CLICK_ALREADY_RUNNING";
			reporter.accept(message);
			result.complete(new RawWalkResult(null, message, false));
			return result;
		}

		activeResult = result;
		awaitingMenuResult = false;
		clickFinished = false;
		observedWalkResult = null;
		targetWorldPoint = null;
		targetSurface = null;
		selectionMode = mode;
		requestedWorldPoints = candidates;
		targetAttemptsRemaining = MAX_TARGET_ATTEMPTS;
		clickDispatched = false;
		cameraPrepared = false;
		activeActivityContext = activityContext;
		activeReachFraction = reachFraction;
		reporter.accept(mode == SelectionMode.RANDOM
			? "WALK_CLICK_SELECTING_TILE"
			: "WALK_ROUTE_SELECTING candidates=" + candidates.size());
		invokeCurrent(this::selectTargetOnClientThread);
		return result;
	}

	void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!awaitingMenuResult)
		{
			return;
		}
		if (event.getMenuAction() != MenuAction.WALK ||
			event.getParam0() != expectedParam0 ||
			event.getParam1() != expectedParam1 ||
			event.getMenuEntry().getWorldViewId() != expectedWorldViewId)
		{
			reporter.accept(String.format(
				"WALK_CLICK_IGNORED action=%s param0=%d param1=%d worldView=%d",
				event.getMenuAction(),
				event.getParam0(),
				event.getParam1(),
				event.getMenuEntry().getWorldViewId()));
			return;
		}

		String result = String.format(
			"%s surface=%s action=%s option=%s target=%s selectedTile=%s param0=%d param1=%d",
			selectionMode == SelectionMode.RANDOM ? "WALK_CLICK_EXECUTED" : "WALK_TILE_CLICK_EXECUTED",
			targetSurface,
			event.getMenuAction(),
			event.getMenuOption(),
			event.getMenuTarget(),
			targetWorldPoint,
			event.getParam0(),
			event.getParam1());
		synchronized (this)
		{
			awaitingMenuResult = false;
			observedWalkResult = result;
		}
		finishCanvasClickIfReady();
	}

	@Override
	public synchronized void cancelWalkToTile(GenericClientActivityContext owner)
	{
		if (!activeActivityContext.ownsSameInput(owner)) return;
		routeRequestSequence.incrementAndGet();
		if (running.get() && selectionMode == SelectionMode.ROUTE)
		{
			finish("WALK_TILE_CLICK_CANCELLED");
		}
	}

	synchronized void cancel(String reason)
	{
		cancellationSequence.incrementAndGet();
		routeRequestSequence.incrementAndGet();
		if (running.get())
		{
			finish("WALK_CLICK_CANCELLED reason=" + reason);
		}
	}

	private void selectTargetOnClientThread()
	{
		if (!running.get())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			finish("WALK_CLICK_FAILED reason=client_not_logged_in");
			return;
		}

		Player player = client.getLocalPlayer();
		if (player == null)
		{
			finish("WALK_CLICK_FAILED reason=local_player_unavailable");
			return;
		}

		Target target;
		if (selectionMode == SelectionMode.ROUTE)
		{
			target = chooseFarthestRouteTarget(player);
		}
		else
		{
			if (requestedWorldPoints.isEmpty())
			{
				target = chooseRandomVisibleTile(player);
				if (target != null)
				{
					requestedWorldPoints = Collections.singletonList(target.worldPoint);
				}
			}
			else
			{
				target = targetForWorldPoint(player, requestedWorldPoints.get(0));
			}
		}
		if (target == null)
		{
			finish(selectionMode == SelectionMode.ROUTE
				? "WALK_TILE_CLICK_FAILED reason=no_projectable_route_target"
				: "WALK_CLICK_FAILED reason=no_visible_nearby_tile");
			return;
		}
		if (!cameraPrepared && beginCameraTurn(player, target.worldPoint))
		{
			return;
		}

		targetWorldPoint = target.worldPoint;
		targetSurface = target.surface;
		reporter.accept("WALK_CLICK_TARGET tile=" + target.worldPoint +
			" surface=" + target.surface +
			" canvas=" + target.canvasPoint.getX() + "," + target.canvasPoint.getY());
		Target selectedTarget = target;
		beginSyntheticMouseMove(selectedTarget);
	}

	private Target chooseFarthestRouteTarget(Player player)
	{
		for (int index = 0; index < requestedWorldPoints.size(); index++)
		{
			Target farthest = targetForWorldPoint(player, requestedWorldPoints.get(index));
			if (farthest == null) continue;
			if (activeReachFraction == 1.0) return farthest;
			int nearer = nearerRouteIndex(index, requestedWorldPoints.size(), activeReachFraction);
			for (int candidate = nearer; candidate < requestedWorldPoints.size(); candidate++)
			{
				Target target = targetForWorldPoint(player, requestedWorldPoints.get(candidate));
				if (target != null) return target;
			}
			return farthest;
		}
		return null;
	}

	static int nearerRouteIndex(int firstVisible, int candidates, double fraction)
	{
		return Math.max(firstVisible, candidates - Math.max(1, (int) Math.ceil((candidates - firstVisible) * fraction)));
	}

	private Target targetForWorldPoint(Player player, WorldPoint worldPoint)
	{
		WorldPoint playerWorldPoint = player.getWorldLocation();
		if (playerWorldPoint == null || worldPoint.getPlane() != playerWorldPoint.getPlane())
		{
			return null;
		}
		LocalPoint local = LocalPoint.fromWorld(player.getWorldView(), worldPoint);
		if (local == null)
		{
			return null;
		}
		Target minimap = targetForMinimap(local, worldPoint);
		if (selectionMode == SelectionMode.ROUTE && minimap != null)
		{
			return minimap;
		}
		Target canvas = targetForLocalPoint(local, worldPoint);
		return canvas == null ? minimap : canvas;
	}

	private boolean beginCameraTurn(Player player, WorldPoint target)
	{
		WorldPoint origin = player.getWorldLocation();
		if (origin == null || origin.equals(target))
		{
			cameraPrepared = true;
			return false;
		}
		cameraTargetYaw = yawToward(origin, target);
		int currentYaw = client.getCameraYaw();
		if (angularDistance(currentYaw, cameraTargetYaw) <= CAMERA_SETTLED_UNITS)
		{
			cameraPrepared = true;
			return false;
		}

		cameraTurnDeadlineNanos = System.nanoTime() +
			TimeUnit.MILLISECONDS.toNanos(CAMERA_TURN_TIMEOUT_MILLIS);
		client.setCameraYawTarget(cameraTargetYaw);
		reporter.accept("CAMERA_TURN_STARTED from=" + currentYaw + " target=" + cameraTargetYaw +
			" tile=" + target);
		callbacks.schedule(() -> invokeCurrent(this::checkCameraTurnOnClientThread), CAMERA_POLL_MILLIS);
		return true;
	}

	private void checkCameraTurnOnClientThread()
	{
		if (!running.get())
		{
			return;
		}
		int currentYaw = client.getCameraYaw();
		int remaining = angularDistance(currentYaw, cameraTargetYaw);
		if (remaining <= CAMERA_SETTLED_UNITS || System.nanoTime() >= cameraTurnDeadlineNanos)
		{
			cameraPrepared = true;
			reporter.accept("CAMERA_TURN_COMPLETED yaw=" + currentYaw +
				" target=" + cameraTargetYaw + " remaining=" + remaining);
			selectTargetOnClientThread();
			return;
		}
		callbacks.schedule(() -> invokeCurrent(this::checkCameraTurnOnClientThread), CAMERA_POLL_MILLIS);
	}

	static int yawToward(WorldPoint origin, WorldPoint target)
	{
		double radians = Math.atan2(
			target.getX() - origin.getX(),
			target.getY() - origin.getY());
		return (int) Math.round(radians * (CAMERA_FULL_TURN / 2.0) / Math.PI) &
			CAMERA_YAW_MASK;
	}

	static int angularDistance(int first, int second)
	{
		int distance = Math.abs((first - second) & CAMERA_YAW_MASK);
		return Math.min(distance, CAMERA_FULL_TURN - distance);
	}

	private Target chooseRandomVisibleTile(Player player)
	{
		LocalPoint playerLocation = player.getLocalLocation();
		if (playerLocation == null)
		{
			return null;
		}

		List<int[]> offsets = new ArrayList<>();
		for (int dx = -5; dx <= 5; dx++)
		{
			for (int dy = -5; dy <= 5; dy++)
			{
				int distance = Math.max(Math.abs(dx), Math.abs(dy));
				if (distance >= 2 && distance <= 5)
				{
					offsets.add(new int[]{dx, dy});
				}
			}
		}
		Collections.shuffle(offsets);

		for (int[] offset : offsets)
		{
			LocalPoint local = new LocalPoint(
				playerLocation.getX() + offset[0] * LOCAL_TILE_SIZE,
				playerLocation.getY() + offset[1] * LOCAL_TILE_SIZE,
				playerLocation.getWorldView());
			WorldPoint worldPoint = WorldPoint.fromLocal(client, local);
			Target target = targetForLocalPoint(local, worldPoint);
			if (target != null)
			{
				return target;
			}
		}
		return null;
	}

	private Target targetForLocalPoint(LocalPoint local, WorldPoint worldPoint)
	{
		Polygon polygon = Perspective.getCanvasTilePoly(client, local);
		if (polygon == null)
		{
			return null;
		}

		java.awt.Point point = randomPointInside(polygon);
		return point == null
			? null
			: new Target(new net.runelite.api.Point(point.x, point.y), worldPoint, TargetSurface.CANVAS);
	}

	private Target targetForMinimap(LocalPoint local, WorldPoint worldPoint)
	{
		net.runelite.api.Point point = Perspective.localToMinimap(client, local);
		if (point == null || point.getX() < 0 || point.getY() < 0 ||
			point.getX() >= client.getCanvasWidth() || point.getY() >= client.getCanvasHeight() ||
			!insideMinimapClickArea(point.getX(), point.getY()))
		{
			return null;
		}
		return new Target(point, worldPoint, TargetSurface.MINIMAP);
	}

	private boolean insideMinimapClickArea(int x, int y)
	{
		Widget minimap = GenericClientWidgets.visibleMinimap(client);
		if (minimap == null)
		{
			return false;
		}
		Rectangle bounds = minimap.getBounds();
		if (bounds == null || !bounds.contains(x, y))
		{
			return false;
		}
		double radiusX = bounds.width / 2.0 - MINIMAP_CLICK_MARGIN;
		double radiusY = bounds.height / 2.0 - MINIMAP_CLICK_MARGIN;
		if (radiusX <= 0.0 || radiusY <= 0.0)
		{
			return false;
		}
		double dx = x - bounds.getCenterX();
		double dy = y - bounds.getCenterY();
		return dx * dx / (radiusX * radiusX) +
			dy * dy / (radiusY * radiusY) <= 1.0;
	}

	private java.awt.Point randomPointInside(Polygon polygon)
	{
		Rectangle bounds = polygon.getBounds();
		for (int attempt = 0; attempt < 12; attempt++)
		{
			int x = ThreadLocalRandom.current().nextInt(bounds.x, bounds.x + Math.max(1, bounds.width));
			int y = ThreadLocalRandom.current().nextInt(bounds.y, bounds.y + Math.max(1, bounds.height));
			if (polygon.contains(x, y) && insideViewport(x, y))
			{
				return new java.awt.Point(x, y);
			}
		}
		return null;
	}

	private boolean insideViewport(int x, int y)
	{
		int left = client.getViewportXOffset() + 35;
		int top = client.getViewportYOffset() + 35;
		int right = client.getViewportXOffset() + client.getViewportWidth() - 35;
		int bottom = client.getViewportYOffset() + client.getViewportHeight() - 35;
		return x >= left && x <= right && y >= top && y <= bottom;
	}

	private void beginSyntheticMouseMove(Target target)
	{
		if (!running.get())
		{
			return;
		}
		Canvas canvas = client.getCanvas();
		if (canvas == null || !canvas.isShowing())
		{
			finish("WALK_CLICK_FAILED reason=canvas_not_showing");
			return;
		}

		java.awt.Point destination = new java.awt.Point(
			target.canvasPoint.getX(),
			target.canvasPoint.getY());
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		if (Math.abs(mouse.getX() - destination.x) <= 12 &&
			Math.abs(mouse.getY() - destination.y) <= 12)
		{
			reporter.accept("WALK_CURSOR_RETAINED surface=" + target.surface +
				" canvas=" + mouse.getX() + "," + mouse.getY());
			callbacks.schedule(() -> invokeCurrent(() -> verifyHoverAndClick(target)), hoverSettleMillis());
			return;
		}
		syntheticMouse.move(destination, activeActivityContext).whenComplete(callbacks.bind((result, error) ->
		{
			if (error != null)
			{
				finish("WALK_CLICK_FAILED reason=synthetic_mouse_move message=" + error.getMessage());
				return;
			}
			callbacks.schedule(() -> invokeCurrent(() -> verifyHoverAndClick(target)), hoverSettleMillis());
		}));
	}

	boolean isRunning()
	{
		return running.get();
	}

	private void verifyMinimapClick(Target target, net.runelite.api.Point mouse)
	{		if (!insideMinimapClickArea(mouse.getX(), mouse.getY()))
		{
			targetAttemptsRemaining--;
			if (targetAttemptsRemaining > 0)
			{
				reporter.accept("WALK_MINIMAP_POINT_REJECTED canvas=" +
					mouse.getX() + "," + mouse.getY() +
					" attemptsRemaining=" + targetAttemptsRemaining);
				invokeCurrent(this::selectTargetOnClientThread);
			}
			else
			{
				finish("WALK_TILE_CLICK_FAILED reason=minimap_click_area_rejected");
			}
			return;
		}
		dispatchMinimapClick(target);
	}

	private void verifyHoverAndClick(Target target)
	{
		if (!running.get())
		{
			return;
		}
		if (client.isMenuOpen())
		{
			finish("WALK_CLICK_FAILED reason=context_menu_open");
			return;
		}

		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		if (Math.abs(mouse.getX() - target.canvasPoint.getX()) > 20 ||
			Math.abs(mouse.getY() - target.canvasPoint.getY()) > 20)
		{
			finish("WALK_CLICK_FAILED reason=mouse_missed_target current=" +
				mouse.getX() + "," + mouse.getY());
			return;
		}
		if (target.surface == TargetSurface.MINIMAP)
		{
			verifyMinimapClick(target, mouse);
			return;
		}

		MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (entries.length == 0)
		{
			finish("WALK_CLICK_FAILED reason=no_menu_entry");
			return;
		}
		MenuEntry topEntry = entries[entries.length - 1];
		if (topEntry.getType() != MenuAction.WALK)
		{
			MenuEntry walkEntry = findWalkEntry(entries);
			if (walkEntry != null)
			{
				openContextMenu(target, topEntry, walkEntry);
				return;
			}
			if (selectionMode == SelectionMode.ROUTE)
			{
				targetAttemptsRemaining--;
				if (targetAttemptsRemaining > 0)
				{
					reporter.accept("WALK_TILE_POINT_RETRY action=" + topEntry.getType() +
						" option=" + topEntry.getOption() + " tile=" + target.worldPoint +
						" attemptsRemaining=" + targetAttemptsRemaining);
					invokeCurrent(this::selectTargetOnClientThread);
				}
				else
				{
					finish("WALK_TILE_CLICK_FAILED reason=not_walk_target action=" + topEntry.getType() +
						" option=" + topEntry.getOption() + " tile=" + target.worldPoint +
						" attempts=" + MAX_TARGET_ATTEMPTS);
				}
				return;
			}
			targetAttemptsRemaining--;
			if (targetAttemptsRemaining > 0)
			{
				reporter.accept("WALK_CLICK_RETRY action=" + topEntry.getType() +
					" option=" + topEntry.getOption() + " attemptsRemaining=" + targetAttemptsRemaining);
				invokeCurrent(this::selectTargetOnClientThread);
			}
			else
			{
				finish("WALK_CLICK_FAILED reason=no_walk_target attempts=" + MAX_TARGET_ATTEMPTS);
			}
			return;
		}

		dispatchCanvasWalk(target, topEntry, "left_click");
	}

	private void dispatchMinimapClick(Target target)
	{
		reporter.accept("WALK_CLICK_DISPATCH tile=" + target.worldPoint + " surface=minimap");
		clickDispatched = true;
		syntheticMouse.click(MouseEvent.BUTTON1, activeActivityContext).whenComplete(callbacks.bind((result, error) ->
		{
			if (error != null)
			{
				finish("WALK_TILE_CLICK_FAILED reason=synthetic_click message=" + error.getMessage());
				return;
			}
			finish("WALK_TILE_CLICK_EXECUTED surface=minimap selectedTile=" + target.worldPoint);
		}));
	}

	private void dispatchCanvasWalk(Target target, MenuEntry entry, String dispatch)
	{
		awaitingMenuResult = true;
		clickFinished = false;
		observedWalkResult = null;
		expectedParam0 = entry.getParam0();
		expectedParam1 = entry.getParam1();
		expectedWorldViewId = entry.getWorldViewId();
		reporter.accept("WALK_CLICK_DISPATCH tile=" + target.worldPoint +
			" surface=canvas dispatch=" + dispatch +
			" action=" + entry.getType() + " option=" + entry.getOption() +
			" param0=" + expectedParam0 + " param1=" + expectedParam1 +
			" worldView=" + expectedWorldViewId);
		clickDispatched = true;
		syntheticMouse.click(MouseEvent.BUTTON1, activeActivityContext).whenComplete(callbacks.bind((result, error) ->
		{
			if (error != null)
			{
				awaitingMenuResult = false;
				finish("WALK_CLICK_FAILED reason=synthetic_click message=" + error.getMessage());
				return;
			}
			clickFinished = true;
			finishCanvasClickIfReady();
		}));
		callbacks.schedule(() ->
		{
			if (awaitingMenuResult || !clickFinished)
			{
				awaitingMenuResult = false;
				finish(observedWalkResult == null
					? "WALK_CLICK_FAILED reason=menu_event_timeout"
					: "WALK_CLICK_FAILED reason=synthetic_click_timeout");
			}
		}, CLICK_RESULT_TIMEOUT_MILLIS);
	}

	private void finishCanvasClickIfReady()
	{
		final String result;
		synchronized (this)
		{
			if (!running.get() || !clickFinished || observedWalkResult == null)
			{
				return;
			}
			result = observedWalkResult;
			observedWalkResult = null;
		}
		finish(result);
	}

	private void openContextMenu(Target target, MenuEntry coveredEntry, MenuEntry walkEntry)
	{
		reporter.accept("WALK_CONTEXT_OPEN tile=" + target.worldPoint +
			" coveredBy=" + coveredEntry.getOption() +
			" walkParam0=" + walkEntry.getParam0() + " walkParam1=" + walkEntry.getParam1());
		syntheticMouse.click(MouseEvent.BUTTON3, activeActivityContext).whenComplete(callbacks.bind((result, error) ->
		{
			if (error != null)
			{
				finish("WALK_TILE_CLICK_FAILED reason=synthetic_context_open message=" + error.getMessage());
				return;
			}
			callbacks.schedule(() -> invokeCurrent(() -> moveToContextMenuWalk(target)),
				contextMenuSettleMillis());
		}));
	}

	private void moveToContextMenuWalk(Target target)
	{
		if (!running.get() || !client.isMenuOpen())
		{
			targetAttemptsRemaining--;
			if (running.get() && targetAttemptsRemaining > 0)
			{
				reporter.accept("WALK_CONTEXT_REOPEN tile=" + target.worldPoint +
					" attemptsRemaining=" + targetAttemptsRemaining);
				selectTargetOnClientThread();
			}
			else
			{
				finish("WALK_TILE_CLICK_FAILED reason=context_menu_did_not_open tile=" + target.worldPoint);
			}
			return;
		}
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		int walkIndex = findWalkEntryIndex(entries);
		if (walkIndex < 0)
		{
			finish("WALK_TILE_CLICK_FAILED reason=context_menu_has_no_walk tile=" + target.worldPoint);
			return;
		}

		int headerHeight = Math.max(0,
			client.getMenu().getMenuHeight() - entries.length * CONTEXT_MENU_ENTRY_HEIGHT);
		int rowFromTop = entries.length - 1 - walkIndex;
		int menuX = client.getMenu().getMenuX() + client.getMenu().getMenuWidth() / 2;
		int menuY = client.getMenu().getMenuY() + headerHeight +
			rowFromTop * CONTEXT_MENU_ENTRY_HEIGHT + CONTEXT_MENU_ENTRY_HEIGHT / 2;
		java.awt.Point destination = new java.awt.Point(menuX, menuY);
		syntheticMouse.move(destination, activeActivityContext).whenComplete(callbacks.bind((result, error) ->
		{
			if (error != null)
			{
				finish("WALK_TILE_CLICK_FAILED reason=synthetic_context_move message=" + error.getMessage());
				return;
			}
			callbacks.schedule(() -> invokeCurrent(() -> clickContextMenuWalk(target)), hoverSettleMillis());
		}));
	}

	private void clickContextMenuWalk(Target target)
	{
		if (!running.get() || !client.isMenuOpen())
		{
			finish("WALK_TILE_CLICK_FAILED reason=context_menu_closed tile=" + target.worldPoint);
			return;
		}
		MenuEntry walkEntry = findWalkEntry(client.getMenu().getMenuEntries());
		if (walkEntry == null)
		{
			finish("WALK_TILE_CLICK_FAILED reason=context_menu_has_no_walk tile=" + target.worldPoint);
			return;
		}
		dispatchCanvasWalk(target, walkEntry, "context_menu");
	}

	private static MenuEntry findWalkEntry(MenuEntry[] entries)
	{
		int index = findWalkEntryIndex(entries);
		return index < 0 ? null : entries[index];
	}

	private static int findWalkEntryIndex(MenuEntry[] entries)
	{
		for (int index = entries.length - 1; index >= 0; index--)
		{
			if (entries[index].getType() == MenuAction.WALK)
			{
				return index;
			}
		}
		return -1;
	}

	private void invokeCurrent(Runnable action)
	{
		clientThread.invoke(callbacks.bind(() -> {
			if (activeActivityContext.isInputAllowed()) action.run();
			else finish(selectionMode == SelectionMode.ROUTE ? "WALK_TILE_CLICK_CANCELLED" : "WALK_CLICK_STOPPED");
		}));
	}

	private long hoverSettleMillis()
	{
		return activeActivityContext.refreshesWalkClicks() ? 50L : HOVER_SETTLE_MILLIS;
	}

	private long contextMenuSettleMillis()
	{
		return activeActivityContext.refreshesWalkClicks() ? 75L : CONTEXT_MENU_SETTLE_MILLIS;
	}

	private synchronized void finish(String result)
	{
		if (!running.getAndSet(false))
		{
			return;
		}
		awaitingMenuResult = false;
		clickFinished = false;
		observedWalkResult = null;
		activeActivityContext = GenericClientActivityContext.none();
		callbacks.cancelPending();
		reporter.accept(result);
		CompletableFuture<RawWalkResult> completion = activeResult;
		activeResult = null;
		if (completion != null)
		{
			RawWalkResult receipt = new RawWalkResult(targetWorldPoint, result, clickDispatched);
			completion.completeAsync(() -> receipt);
		}
	}

	@Override
	public synchronized void close()
	{
		closed = true;
		cancellationSequence.incrementAndGet();
		routeRequestSequence.incrementAndGet();
		if (running.get())
		{
			finish("WALK_CLICK_STOPPED");
		}
		else
		{
			callbacks.cancelPending();
		}
	}

	private static final class Target
	{
		private final net.runelite.api.Point canvasPoint;
		private final WorldPoint worldPoint;
		private final TargetSurface surface;

		private Target(
			net.runelite.api.Point canvasPoint,
			WorldPoint worldPoint,
			TargetSurface surface)
		{
			this.canvasPoint = canvasPoint;
			this.worldPoint = worldPoint;
			this.surface = surface;
		}
	}

	private enum TargetSurface
	{
		CANVAS,
		MINIMAP
	}

	private enum SelectionMode
	{
		RANDOM,
		ROUTE
	}

	static final class RawWalkResult
	{
		private final WorldPoint target;
		private final String detail;
		private final boolean clickDispatched;

		RawWalkResult(WorldPoint target, String detail, boolean clickDispatched)
		{
			this.target = target;
			this.detail = detail;
			this.clickDispatched = clickDispatched;
		}
	}
}
