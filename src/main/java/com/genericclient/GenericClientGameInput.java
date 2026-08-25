package com.genericclient;

import java.awt.AWTException;
import java.awt.Canvas;
import java.awt.MouseInfo;
import java.awt.PointerInfo;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
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
	private static final int MOVE_STEPS = 24;
	private static final long MOVE_STEP_MILLIS = 18L;
	private static final long HOVER_SETTLE_MILLIS = 250L;
	private static final long CLICK_RESULT_TIMEOUT_MILLIS = 2500L;
	private static final int MAX_TARGET_ATTEMPTS = 8;

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final Consumer<String> reporter;
	private final AtomicBoolean running = new AtomicBoolean();
	private final List<ScheduledFuture<?>> pending = new CopyOnWriteArrayList<>();

	private volatile boolean awaitingMenuResult;
	private volatile WorldPoint targetWorldPoint;
	private volatile int expectedParam0;
	private volatile int expectedParam1;
	private volatile int expectedWorldViewId;
	private volatile int targetAttemptsRemaining;
	private volatile Robot robot;

	GenericClientGameInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		Consumer<String> reporter)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.reporter = reporter;
	}

	void walkToRandomTile()
	{
		if (!running.compareAndSet(false, true))
		{
			reporter.accept("WALK_CLICK_ALREADY_RUNNING");
			return;
		}

		awaitingMenuResult = false;
		targetWorldPoint = null;
		targetAttemptsRemaining = MAX_TARGET_ATTEMPTS;
		reporter.accept("WALK_CLICK_SELECTING_TILE");
		clientThread.invoke(this::selectTargetOnClientThread);
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
			"WALK_CLICK_EXECUTED action=%s option=%s target=%s selectedTile=%s param0=%d param1=%d",
			event.getMenuAction(),
			event.getMenuOption(),
			event.getMenuTarget(),
			targetWorldPoint,
			event.getParam0(),
			event.getParam1());
		finish(result);
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

		Target target = chooseRandomVisibleTile(player);
		if (target == null)
		{
			finish("WALK_CLICK_FAILED reason=no_visible_nearby_tile");
			return;
		}

		targetWorldPoint = target.worldPoint;
		reporter.accept("WALK_CLICK_TARGET tile=" + target.worldPoint +
			" canvas=" + target.canvasPoint.getX() + "," + target.canvasPoint.getY());
		SwingUtilities.invokeLater(() -> beginNativeMouseMove(target));
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
			Polygon polygon = Perspective.getCanvasTilePoly(client, local);
			if (polygon == null)
			{
				continue;
			}

			Rectangle bounds = polygon.getBounds();
			int x = (int) bounds.getCenterX();
			int y = (int) bounds.getCenterY();
			if (!polygon.contains(x, y) || !insideViewport(x, y))
			{
				continue;
			}

			WorldPoint worldPoint = WorldPoint.fromLocal(client, local);
			return new Target(new net.runelite.api.Point(x, y), worldPoint);
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

	private void beginNativeMouseMove(Target target)
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

		try
		{
			java.awt.Point canvasOrigin = canvas.getLocationOnScreen();
			java.awt.Point destination = new java.awt.Point(
				canvasOrigin.x + target.canvasPoint.getX(),
				canvasOrigin.y + target.canvasPoint.getY());
			PointerInfo pointerInfo = MouseInfo.getPointerInfo();
			if (pointerInfo == null)
			{
				finish("WALK_CLICK_FAILED reason=mouse_pointer_unavailable");
				return;
			}
			animateMouse(pointerInfo.getLocation(), destination, target);
		}
		catch (RuntimeException exception)
		{
			finish("WALK_CLICK_FAILED reason=canvas_position message=" + exception.getMessage());
		}
	}

	private void animateMouse(java.awt.Point start, java.awt.Point destination, Target target)
	{
		final Robot nativeRobot;
		try
		{
			nativeRobot = getRobot();
		}
		catch (AWTException | SecurityException exception)
		{
			finish("WALK_CLICK_FAILED reason=robot_unavailable message=" + exception.getMessage());
			return;
		}

		for (int step = 1; step <= MOVE_STEPS; step++)
		{
			final int currentStep = step;
			schedule(() ->
			{
				double progress = currentStep / (double) MOVE_STEPS;
				double eased = progress * progress * (3.0 - 2.0 * progress);
				int x = start.x + (int) Math.round((destination.x - start.x) * eased);
				int y = start.y + (int) Math.round((destination.y - start.y) * eased);
				nativeRobot.mouseMove(x, y);
			}, step * MOVE_STEP_MILLIS);
		}

		long verificationDelay = MOVE_STEPS * MOVE_STEP_MILLIS + HOVER_SETTLE_MILLIS;
		schedule(() -> clientThread.invoke(() -> verifyHoverAndClick(target)), verificationDelay);
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

		MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (entries.length == 0)
		{
			finish("WALK_CLICK_FAILED reason=no_menu_entry");
			return;
		}
		MenuEntry topEntry = entries[entries.length - 1];
		if (topEntry.getType() != MenuAction.WALK)
		{
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

		awaitingMenuResult = true;
		expectedParam0 = topEntry.getParam0();
		expectedParam1 = topEntry.getParam1();
		expectedWorldViewId = topEntry.getWorldViewId();
		reporter.accept("WALK_CLICK_DISPATCH tile=" + target.worldPoint +
			" action=" + topEntry.getType() + " option=" + topEntry.getOption() +
			" param0=" + expectedParam0 + " param1=" + expectedParam1 +
			" worldView=" + expectedWorldViewId);
		executor.execute(() ->
		{
			if (!running.get())
			{
				return;
			}
			Robot nativeRobot = robot;
			if (nativeRobot == null)
			{
				finish("WALK_CLICK_FAILED reason=robot_unavailable_before_click");
				return;
			}
			nativeRobot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
			nativeRobot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
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

	private Robot getRobot() throws AWTException
	{
		Robot existing = robot;
		if (existing != null)
		{
			return existing;
		}
		synchronized (this)
		{
			if (robot == null)
			{
				robot = new Robot();
			}
			return robot;
		}
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
		awaitingMenuResult = false;
		running.set(false);
		cancelPending();
		reporter.accept(result);
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
		finish("WALK_CLICK_STOPPED");
	}

	private static final class Target
	{
		private final net.runelite.api.Point canvasPoint;
		private final WorldPoint worldPoint;

		private Target(net.runelite.api.Point canvasPoint, WorldPoint worldPoint)
		{
			this.canvasPoint = canvasPoint;
			this.worldPoint = worldPoint;
		}
	}
}
