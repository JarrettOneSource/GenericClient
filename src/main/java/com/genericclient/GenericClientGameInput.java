package com.genericclient;

import java.awt.Canvas;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.callback.ClientThread;

final class GenericClientGameInput implements AutoCloseable
{
	private static final int LOCAL_TILE_SIZE = Perspective.LOCAL_TILE_SIZE;
	private static final long HOVER_SETTLE_MILLIS = 250L;
	private static final long CONTEXT_MENU_SETTLE_MILLIS = 150L;
	private static final long CLICK_RESULT_TIMEOUT_MILLIS = 2500L;
	private static final int CONTEXT_MENU_ENTRY_HEIGHT = 15;
	private static final int MAX_TARGET_ATTEMPTS = 20;

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final Consumer<String> reporter;
	private final GenericClientSyntheticMouse syntheticMouse;
	private final AtomicBoolean running = new AtomicBoolean();
	private final List<ScheduledFuture<?>> pending = new CopyOnWriteArrayList<>();

	private volatile boolean awaitingMenuResult;
	private volatile WorldPoint targetWorldPoint;
	private volatile int expectedParam0;
	private volatile int expectedParam1;
	private volatile int expectedWorldViewId;
	private volatile int targetAttemptsRemaining;
	private volatile TargetSurface targetSurface;
	private volatile SelectionMode selectionMode;
	private volatile WorldPoint requestedWorldPoint;
	private volatile CompletableFuture<String> activeResult;

	GenericClientGameInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientSyntheticMouse syntheticMouse,
		Consumer<String> reporter)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.syntheticMouse = syntheticMouse;
		this.reporter = reporter;
	}

	CompletableFuture<String> walkToRandomTile()
	{
		return beginWalkClick(SelectionMode.RANDOM, null);
	}

	CompletableFuture<String> walkToTile(WorldPoint worldPoint)
	{
		if (worldPoint == null)
		{
			throw new IllegalArgumentException("Walk tile cannot be null");
		}
		return beginWalkClick(SelectionMode.SPECIFIED, worldPoint);
	}

	private CompletableFuture<String> beginWalkClick(SelectionMode mode, WorldPoint worldPoint)
	{
		CompletableFuture<String> result = new CompletableFuture<>();
		if (!running.compareAndSet(false, true))
		{
			String message = "WALK_CLICK_ALREADY_RUNNING";
			reporter.accept(message);
			result.complete(message);
			return result;
		}

		activeResult = result;
		awaitingMenuResult = false;
		targetWorldPoint = null;
		targetSurface = null;
		selectionMode = mode;
		requestedWorldPoint = worldPoint;
		targetAttemptsRemaining = MAX_TARGET_ATTEMPTS;
		reporter.accept(mode == SelectionMode.RANDOM
			? "WALK_CLICK_SELECTING_TILE"
			: "WALK_TILE_SELECTING tile=" + worldPoint);
		clientThread.invoke(this::selectTargetOnClientThread);
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

		awaitingMenuResult = false;
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
		finish(result);
	}

	void cancelWalkToTile()
	{
		if (running.get() && selectionMode == SelectionMode.SPECIFIED)
		{
			finish("WALK_TILE_CLICK_CANCELLED");
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
		if (selectionMode == SelectionMode.SPECIFIED)
		{
			WorldPoint requested = requestedWorldPoint;
			WorldPoint playerWorldPoint = player.getWorldLocation();
			if (requested == null || playerWorldPoint == null ||
				requested.getPlane() != playerWorldPoint.getPlane())
			{
				finish("WALK_TILE_CLICK_FAILED reason=target_plane_unavailable tile=" + requested);
				return;
			}
			LocalPoint local = LocalPoint.fromWorld(player.getWorldView(), requested);
			if (local == null)
			{
				finish("WALK_TILE_CLICK_FAILED reason=tile_not_in_scene tile=" + requested);
				return;
			}
			target = targetForLocalPoint(local, requested);
			if (target == null)
			{
				target = targetForMinimap(local, requested);
				if (target == null)
				{
					finish("WALK_TILE_CLICK_FAILED reason=no_clickable_projection tile=" + requested);
					return;
				}
			}
		}
		else
		{
			target = chooseRandomVisibleTile(player);
		}
		if (target == null)
		{
			finish("WALK_CLICK_FAILED reason=no_visible_nearby_tile");
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
			point.getX() >= client.getCanvasWidth() || point.getY() >= client.getCanvasHeight())
		{
			return null;
		}
		return new Target(point, worldPoint, TargetSurface.MINIMAP);
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
		syntheticMouse.move(destination).whenComplete((result, error) ->
		{
			if (error != null)
			{
				finish("WALK_CLICK_FAILED reason=synthetic_mouse_move message=" + error.getMessage());
				return;
			}
			schedule(() -> clientThread.invoke(() -> verifyHoverAndClick(target)), HOVER_SETTLE_MILLIS);
		});
	}

	boolean isRunning()
	{
		return running.get();
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
			dispatchMinimapClick(target);
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
			if (selectionMode == SelectionMode.SPECIFIED)
			{
				targetAttemptsRemaining--;
				if (targetAttemptsRemaining > 0)
				{
					reporter.accept("WALK_TILE_POINT_RETRY action=" + topEntry.getType() +
						" option=" + topEntry.getOption() + " tile=" + target.worldPoint +
						" attemptsRemaining=" + targetAttemptsRemaining);
					clientThread.invoke(this::selectTargetOnClientThread);
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
				clientThread.invoke(this::selectTargetOnClientThread);
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
		syntheticMouse.click(MouseEvent.BUTTON1).whenComplete((result, error) ->
		{
			if (error != null)
			{
				finish("WALK_TILE_CLICK_FAILED reason=synthetic_click message=" + error.getMessage());
				return;
			}
			finish("WALK_TILE_CLICK_EXECUTED surface=minimap selectedTile=" + target.worldPoint);
		});
	}

	private void dispatchCanvasWalk(Target target, MenuEntry entry, String dispatch)
	{
		awaitingMenuResult = true;
		expectedParam0 = entry.getParam0();
		expectedParam1 = entry.getParam1();
		expectedWorldViewId = entry.getWorldViewId();
		reporter.accept("WALK_CLICK_DISPATCH tile=" + target.worldPoint +
			" surface=canvas dispatch=" + dispatch +
			" action=" + entry.getType() + " option=" + entry.getOption() +
			" param0=" + expectedParam0 + " param1=" + expectedParam1 +
			" worldView=" + expectedWorldViewId);
		syntheticMouse.click(MouseEvent.BUTTON1).whenComplete((result, error) ->
		{
			if (error != null)
			{
				awaitingMenuResult = false;
				finish("WALK_CLICK_FAILED reason=synthetic_click message=" + error.getMessage());
				return;
			}
		});
		schedule(() ->
		{
			if (awaitingMenuResult)
			{
				awaitingMenuResult = false;
				finish("WALK_CLICK_FAILED reason=menu_event_timeout");
			}
		}, CLICK_RESULT_TIMEOUT_MILLIS);
	}

	private void openContextMenu(Target target, MenuEntry coveredEntry, MenuEntry walkEntry)
	{
		reporter.accept("WALK_CONTEXT_OPEN tile=" + target.worldPoint +
			" coveredBy=" + coveredEntry.getOption() +
			" walkParam0=" + walkEntry.getParam0() + " walkParam1=" + walkEntry.getParam1());
		syntheticMouse.click(MouseEvent.BUTTON3).whenComplete((result, error) ->
		{
			if (error != null)
			{
				finish("WALK_TILE_CLICK_FAILED reason=synthetic_context_open message=" + error.getMessage());
				return;
			}
			schedule(() -> clientThread.invoke(() -> moveToContextMenuWalk(target)), CONTEXT_MENU_SETTLE_MILLIS);
		});
	}

	private void moveToContextMenuWalk(Target target)
	{
		if (!running.get() || !client.isMenuOpen())
		{
			finish("WALK_TILE_CLICK_FAILED reason=context_menu_did_not_open tile=" + target.worldPoint);
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
		syntheticMouse.move(destination).whenComplete((result, error) ->
		{
			if (error != null)
			{
				finish("WALK_TILE_CLICK_FAILED reason=synthetic_context_move message=" + error.getMessage());
				return;
			}
			schedule(() -> clientThread.invoke(() -> clickContextMenuWalk(target)), HOVER_SETTLE_MILLIS);
		});
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

	private void schedule(Runnable runnable, long delayMillis)
	{
		ScheduledFuture<?> future = executor.schedule(() ->
		{
			if (running.get())
			{
				runnable.run();
			}
		}, delayMillis, TimeUnit.MILLISECONDS);
		pending.add(future);
	}

	private void finish(String result)
	{
		if (!running.getAndSet(false))
		{
			return;
		}
		awaitingMenuResult = false;
		cancelPending();
		reporter.accept(result);
		CompletableFuture<String> completion = activeResult;
		activeResult = null;
		if (completion != null)
		{
			completion.complete(result);
		}
	}

	private void cancelPending()
	{
		for (ScheduledFuture<?> future : pending)
		{
			future.cancel(false);
		}
		pending.clear();
	}

	@Override
	public void close()
	{
		if (running.get())
		{
			finish("WALK_CLICK_STOPPED");
		}
		else
		{
			cancelPending();
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
		SPECIFIED
	}
}
