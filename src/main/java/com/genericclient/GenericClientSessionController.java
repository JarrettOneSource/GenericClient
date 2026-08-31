package com.genericclient;

import java.awt.Canvas;
import java.awt.EventQueue;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;

@SuppressWarnings("deprecation")
final class GenericClientSessionController implements AutoCloseable
{
	private static final long DEFAULT_POLL_MILLIS = 250L;
	private static final long DEFAULT_TIMEOUT_MILLIS = 20_000L;
	private static final long LOGIN_WORLD_SETTLE_MILLIS = 1_000L;
	private static final int[] LOGOUT_BUTTONS =
	{
		InterfaceID.Logout.LOGOUT,
		WidgetInfo.LOGOUT_BUTTON.getId()
	};
	private static final int[] LOGOUT_TABS =
	{
		WidgetInfo.FIXED_VIEWPORT_LOGOUT_TAB.getId(),
		WidgetInfo.RESIZABLE_VIEWPORT_LOGOUT_TAB.getId(),
		WidgetInfo.RESIZABLE_MINIMAP_LOGOUT_BUTTON.getId(),
		WidgetInfo.RESIZABLE_VIEWPORT_BOTTOM_LINE_LOGOUT_BUTTON.getId()
	};
	private static final int[] CLICK_TO_PLAY_WIDGETS =
	{
		InterfaceID.WelcomeScreen.PLAY,
		InterfaceID.WelcomeScreen.PLAY_HIGHLIGHT,
		InterfaceID.WelcomeScreen.CLICKHERE_TEXT,
		InterfaceID.WelcomeScreen.BANNER_HIGHLIGHT
	};
	private static final int[] WELCOME_ROOT_WIDGETS =
	{
		InterfaceID.WelcomeScreen.UNIVERSE,
		InterfaceID.WelcomeScreen.CONTENT,
		InterfaceID.WelcomeScreen.BANNER
	};

	private final SessionView view;
	private final Input input;
	private final ScheduledExecutorService executor;
	private final Consumer<String> reporter;
	private final long pollMillis;
	private final long timeoutMillis;
	private final List<ScheduledFuture<?>> pending = new CopyOnWriteArrayList<>();

	private CompletableFuture<String> activeOperation;
	private boolean loginClickIssued;
	private long loginWorldSettleDeadline;
	private int loginScreenAttempt;
	private int logoutEscapeAttempts;
	private boolean closed;

	GenericClientSessionController(
		SessionView view,
		Input input,
		ScheduledExecutorService executor,
		Consumer<String> reporter)
	{
		this(view, input, executor, reporter, DEFAULT_POLL_MILLIS, DEFAULT_TIMEOUT_MILLIS);
	}

	GenericClientSessionController(
		SessionView view,
		Input input,
		ScheduledExecutorService executor,
		Consumer<String> reporter,
		long pollMillis,
		long timeoutMillis)
	{
		this.view = view;
		this.input = input;
		this.executor = executor;
		this.reporter = reporter;
		this.pollMillis = pollMillis;
		this.timeoutMillis = timeoutMillis;
	}

	static SessionView runeliteView(Client client, ClientThread clientThread)
	{
		return new SessionView()
		{
			@Override
			public GameState gameState()
			{
				return client.getGameState();
			}

			@Override
			public String launcherDisplayName()
			{
				return client.getLauncherDisplayName();
			}

			@Override
			public int canvasWidth()
			{
				return client.getCanvasWidth();
			}

			@Override
			public int canvasHeight()
			{
				return client.getCanvasHeight();
			}

			@Override
			public CompletableFuture<Rectangle> visibleWidget(int... candidates)
			{
				CompletableFuture<Rectangle> result = new CompletableFuture<>();
				clientThread.invoke(() ->
				{
					for (int candidate : candidates)
					{
						Widget widget = client.getWidget(candidate);
						if (widget != null && !widget.isHidden() && !widget.isSelfHidden())
						{
							Rectangle bounds = widget.getBounds();
							if (bounds != null && bounds.width > 0 && bounds.height > 0)
							{
								result.complete(new Rectangle(bounds));
								return;
							}
						}
					}
					result.complete(null);
				});
				return result;
			}

			@Override
			public CompletableFuture<Boolean> worldReady()
			{
				CompletableFuture<Boolean> result = new CompletableFuture<>();
				clientThread.invoke(() ->
				{
					Player player = client.getLocalPlayer();
					LocalPoint local = player == null ? null : player.getLocalLocation();
					result.complete(local != null && Perspective.getCanvasTilePoly(client, local) != null);
				});
				return result;
			}
		};
	}

	static Input syntheticInput(GenericClientSyntheticMouse mouse, Canvas canvas)
	{
		return new Input()
		{
			@Override
			public CompletableFuture<String> move(Point point)
			{
				return mouse.move(point);
			}

			@Override
			public CompletableFuture<String> click()
			{
				return mouse.click(MouseEvent.BUTTON1);
			}

			@Override
			public CompletableFuture<String> pressEscape()
			{
				CompletableFuture<String> result = new CompletableFuture<>();
				EventQueue.invokeLater(() ->
				{
					if (canvas == null || !canvas.isShowing())
					{
						result.completeExceptionally(new IllegalStateException("Client canvas is unavailable"));
						return;
					}
					long when = System.currentTimeMillis();
					canvas.dispatchEvent(new KeyEvent(
						canvas, KeyEvent.KEY_PRESSED, when, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED));
					canvas.dispatchEvent(new KeyEvent(
						canvas, KeyEvent.KEY_RELEASED, when, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED));
					result.complete("escape");
				});
				return result;
			}
		};
	}

	CompletableFuture<String> logout()
	{
		CompletableFuture<String> operation;
		synchronized (this)
		{
			if (closed)
			{
				return failed("Session controller is closed");
			}
			if (view.gameState() != GameState.LOGGED_IN)
			{
				return CompletableFuture.completedFuture("SESSION_ALREADY_LOGGED_OUT");
			}
			if (activeOperation != null)
			{
				return failed("Another session operation is already running");
			}
			activeOperation = new CompletableFuture<>();
			operation = activeOperation;
			logoutEscapeAttempts = 0;
		}
		reporter.accept("SESSION_LOGOUT_STARTED");
		long deadline = System.currentTimeMillis() + timeoutMillis;
		openLogoutPanel(deadline);
		return operation;
	}

	CompletableFuture<String> ensureLoggedIn()
	{
		CompletableFuture<String> operation;
		synchronized (this)
		{
			if (closed)
			{
				return failed("Session controller is closed");
			}
			if (activeOperation != null)
			{
				return activeOperation;
			}
			activeOperation = new CompletableFuture<>();
			operation = activeOperation;
			loginScreenAttempt = 0;
			loginWorldSettleDeadline = 0L;
		}
		reporter.accept("SESSION_LOGIN_STARTED");
		long deadline = System.currentTimeMillis() + timeoutMillis;
		advanceLogin(deadline);
		return operation;
	}

	private void openLogoutPanel(long deadline)
	{
		if (expired(deadline))
		{
			failActive("Timed out opening the logout panel");
			return;
		}
		view.visibleWidget(LOGOUT_BUTTONS).whenComplete(
			(button, error) -> handleLogoutButton(deadline, button, error));
	}

	private void handleLogoutButton(long deadline, Rectangle button, Throwable error)
	{
		if (error != null)
		{
			failActive(error.getMessage());
			return;
		}
		if (button != null)
		{
			clickBounds(button).whenComplete(
				(ignored, clickError) -> handleLogoutButtonClick(deadline, clickError));
			return;
		}
		view.visibleWidget(LOGOUT_TABS).whenComplete(
			(tab, tabError) -> handleLogoutTab(deadline, tab, tabError));
	}

	private void handleLogoutButtonClick(long deadline, Throwable error)
	{
		if (error != null)
		{
			failActive(error.getMessage());
			return;
		}
		waitForLoggedOut(deadline);
	}

	private void handleLogoutTab(long deadline, Rectangle tab, Throwable error)
	{
		if (error != null)
		{
			failActive(error.getMessage());
			return;
		}
		if (tab != null)
		{
			clickBounds(tab).whenComplete(
				(ignored, clickError) -> continueOpeningLogoutPanel(deadline, clickError));
			return;
		}
		if (logoutEscapeAttempts >= 3)
		{
			failActive("No visible logout tab after closing modal interfaces");
			return;
		}
		logoutEscapeAttempts++;
		reporter.accept("SESSION_LOGOUT_MODAL_CLOSE attempt=" + logoutEscapeAttempts);
		input.pressEscape().whenComplete(
			(ignored, escapeError) -> continueOpeningLogoutPanel(deadline, escapeError));
	}

	private void continueOpeningLogoutPanel(long deadline, Throwable error)
	{
		if (error != null)
		{
			failActive(error.getMessage());
			return;
		}
		schedule(() -> openLogoutPanel(deadline), pollMillis);
	}

	private void waitForLoggedOut(long deadline)
	{
		if (view.gameState() != GameState.LOGGED_IN)
		{
			completeActive("SESSION_LOGGED_OUT");
			return;
		}
		if (expired(deadline))
		{
			failActive("Timed out waiting for logout");
			return;
		}
		schedule(() -> waitForLoggedOut(deadline), pollMillis);
	}

	private void advanceLogin(long deadline)
	{
		if (expired(deadline))
		{
			failActive("Timed out waiting for Jagex-session login");
			return;
		}
		GameState gameState = view.gameState();
		if (gameState == GameState.LOGGED_IN)
		{
			if (loginClickIssued)
			{
				loginClickIssued = false;
				loginWorldSettleDeadline = System.currentTimeMillis() + LOGIN_WORLD_SETTLE_MILLIS;
				schedule(() -> dismissClickToPlay(deadline), 500L);
			}
			else
			{
				dismissClickToPlay(deadline);
			}
			return;
		}
		if (gameState == GameState.LOGIN_SCREEN)
		{
			if (view.launcherDisplayName() == null || view.launcherDisplayName().trim().isEmpty())
			{
				failActive("Jagex Launcher session is unavailable");
				return;
			}
			Point target = loginScreenAttempt++ % 2 == 0
				? loginButtonPoint(view.canvasWidth(), view.canvasHeight())
				: loginConfirmationPoint(view.canvasWidth(), view.canvasHeight());
			loginClickIssued = true;
			clickPoint(target).whenComplete((ignored, error) ->
			{
				if (error != null)
				{
					failActive(error.getMessage());
					return;
				}
				schedule(() -> advanceLogin(deadline), 1_000L);
			});
			return;
		}
		schedule(() -> advanceLogin(deadline), pollMillis);
	}

	private void dismissClickToPlay(long deadline)
	{
		view.visibleWidget(CLICK_TO_PLAY_WIDGETS).whenComplete(
			(widget, error) -> handleClickToPlay(deadline, widget, error));
	}

	private void handleClickToPlay(long deadline, Rectangle widget, Throwable error)
	{
		if (error != null)
		{
			failActive(error.getMessage());
			return;
		}
		if (widget != null)
		{
			clickBounds(widget).whenComplete(
				(ignored, clickError) -> waitAfterClick(deadline, clickError));
			return;
		}
		view.visibleWidget(WELCOME_ROOT_WIDGETS).whenComplete(
			(root, rootError) -> handleWelcomeRoot(deadline, root, rootError));
	}

	private void handleWelcomeRoot(long deadline, Rectangle root, Throwable error)
	{
		if (error != null)
		{
			failActive(error.getMessage());
			return;
		}
		if (root != null)
		{
			clickFallbackAndWait(deadline);
			return;
		}
		view.worldReady().whenComplete(
			(ready, readyError) -> handleCurrentWorldReadiness(deadline, ready, readyError));
	}

	private void handleCurrentWorldReadiness(long deadline, Boolean ready, Throwable error)
	{
		if (error != null)
		{
			failActive(error.getMessage());
			return;
		}
		if (!Boolean.TRUE.equals(ready))
		{
			clickFallbackAndWait(deadline);
			return;
		}
		if (System.currentTimeMillis() < loginWorldSettleDeadline)
		{
			schedule(() -> dismissClickToPlay(deadline), pollMillis);
			return;
		}
		completeActive("SESSION_LOGGED_IN");
	}

	private void clickFallbackAndWait(long deadline)
	{
		clickPoint(clickToPlayFallbackPoint(view.canvasWidth(), view.canvasHeight()))
			.whenComplete((ignored, error) -> waitAfterClick(deadline, error));
	}

	private void waitAfterClick(long deadline, Throwable error)
	{
		if (error != null)
		{
			failActive(error.getMessage());
			return;
		}
		waitForWorldReady(deadline);
	}

	private void waitForWorldReady(long deadline)
	{
		if (expired(deadline))
		{
			failActive("Timed out dismissing click-to-play");
			return;
		}
		view.visibleWidget(CLICK_TO_PLAY_WIDGETS).whenComplete(
			(play, playError) -> handlePlayWhileWaiting(deadline, play, playError));
	}

	private void handlePlayWhileWaiting(long deadline, Rectangle play, Throwable error)
	{
		if (error != null)
		{
			failActive(error.getMessage());
			return;
		}
		if (play != null)
		{
			schedule(() -> waitForWorldReady(deadline), pollMillis);
			return;
		}
		view.visibleWidget(WELCOME_ROOT_WIDGETS).whenComplete(
			(root, rootError) -> handleWelcomeWhileWaiting(deadline, root, rootError));
	}

	private void handleWelcomeWhileWaiting(long deadline, Rectangle root, Throwable error)
	{
		if (error != null)
		{
			failActive(error.getMessage());
			return;
		}
		if (root != null)
		{
			schedule(() -> waitForWorldReady(deadline), pollMillis);
			return;
		}
		view.worldReady().whenComplete(
			(ready, readyError) -> handleWorldReady(deadline, ready, readyError));
	}

	private void handleWorldReady(long deadline, Boolean ready, Throwable error)
	{
		if (error != null)
		{
			failActive(error.getMessage());
			return;
		}
		if (Boolean.TRUE.equals(ready))
		{
			completeActive("SESSION_LOGGED_IN_AND_PLAYING");
			return;
		}
		schedule(() -> waitForWorldReady(deadline), pollMillis);
	}

	private CompletableFuture<String> clickBounds(Rectangle bounds)
	{
		return clickPoint(new Point(
			bounds.x + bounds.width / 2,
			bounds.y + bounds.height / 2));
	}

	private CompletableFuture<String> clickPoint(Point point)
	{
		return input.move(point).thenCompose(ignored -> input.click());
	}

	static Point loginButtonPoint(int canvasWidth, int canvasHeight)
	{
		return new Point(Math.max(0, canvasWidth / 2), Math.max(0, canvasHeight / 2));
	}

	static Point clickToPlayFallbackPoint(int canvasWidth, int canvasHeight)
	{
		return new Point(
			Math.max(0, canvasWidth / 2),
			Math.max(0, (int) Math.round(canvasHeight * 0.67)));
	}

	static Point loginConfirmationPoint(int canvasWidth, int canvasHeight)
	{
		return new Point(
			Math.max(0, canvasWidth / 2),
			Math.max(0, (int) Math.round(canvasHeight * 0.60)));
	}

	private void schedule(Runnable runnable, long delayMillis)
	{
		ScheduledFuture<?> future = executor.schedule(() ->
		{
			synchronized (GenericClientSessionController.this)
			{
				if (closed || activeOperation == null)
				{
					return;
				}
			}
			runnable.run();
		}, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
		pending.add(future);
	}

	private boolean expired(long deadline)
	{
		return System.currentTimeMillis() >= deadline;
	}

	private void completeActive(String result)
	{
		CompletableFuture<String> completion;
		synchronized (this)
		{
			completion = activeOperation;
			activeOperation = null;
			pending.removeIf(ScheduledFuture::isDone);
		}
		if (completion != null)
		{
			reporter.accept(result);
			completion.complete(result);
		}
	}

	private void failActive(String message)
	{
		CompletableFuture<String> completion;
		synchronized (this)
		{
			completion = activeOperation;
			activeOperation = null;
			pending.removeIf(ScheduledFuture::isDone);
		}
		if (completion != null)
		{
			completion.completeExceptionally(new IllegalStateException(message));
		}
	}

	private static CompletableFuture<String> failed(String message)
	{
		CompletableFuture<String> result = new CompletableFuture<>();
		result.completeExceptionally(new IllegalStateException(message));
		return result;
	}

	@Override
	public synchronized void close()
	{
		if (closed)
		{
			return;
		}
		closed = true;
		for (ScheduledFuture<?> future : pending)
		{
			future.cancel(false);
		}
		pending.clear();
		if (activeOperation != null)
		{
			activeOperation.completeExceptionally(new IllegalStateException("Session controller closed"));
			activeOperation = null;
		}
	}

	interface SessionView
	{
		GameState gameState();

		String launcherDisplayName();

		int canvasWidth();

		int canvasHeight();

		CompletableFuture<Rectangle> visibleWidget(int... candidates);

		CompletableFuture<Boolean> worldReady();
	}

	interface Input
	{
		CompletableFuture<String> move(Point point);

		CompletableFuture<String> click();

		CompletableFuture<String> pressEscape();
	}
}
