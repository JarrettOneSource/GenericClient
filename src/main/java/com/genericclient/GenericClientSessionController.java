package com.genericclient;

import java.awt.Point;
import java.awt.Rectangle;
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
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;

@SuppressWarnings("deprecation")
final class GenericClientSessionController implements AutoCloseable
{
	private static final long DEFAULT_POLL_MILLIS = 250L;
	private static final long DEFAULT_TIMEOUT_MILLIS = 20_000L;
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
	private static final int[] CLICK_TO_PLAY_WIDGETS = {InterfaceID.WelcomeScreen.PLAY};
	private static final int[] WELCOME_ROOT_WIDGETS = {InterfaceID.WelcomeScreen.UNIVERSE};

	private final SessionView view;
	private final Input input;
	private final ScheduledExecutorService executor;
	private final Consumer<String> reporter;
	private final long pollMillis;
	private final long timeoutMillis;
	private final List<ScheduledFuture<?>> pending = new CopyOnWriteArrayList<>();

	private CompletableFuture<String> activeOperation;
	private boolean loginClickIssued;
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
		};
	}

	static Input syntheticInput(GenericClientSyntheticMouse mouse)
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
		view.visibleWidget(LOGOUT_BUTTONS).whenComplete((button, error) ->
		{
			if (error != null)
			{
				failActive(error.getMessage());
				return;
			}
			if (button != null)
			{
				clickBounds(button).whenComplete((ignored, clickError) ->
				{
					if (clickError != null)
					{
						failActive(clickError.getMessage());
						return;
					}
					waitForLoggedOut(deadline);
				});
				return;
			}
			view.visibleWidget(LOGOUT_TABS).whenComplete((tab, tabError) ->
			{
				if (tabError != null || tab == null)
				{
					failActive(tabError == null ? "No visible logout tab" : tabError.getMessage());
					return;
				}
				clickBounds(tab).whenComplete((ignored, clickError) ->
				{
					if (clickError != null)
					{
						failActive(clickError.getMessage());
						return;
					}
					schedule(() -> openLogoutPanel(deadline), pollMillis);
				});
			});
		});
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
			loginClickIssued = true;
			clickPoint(loginButtonPoint(view.canvasWidth(), view.canvasHeight())).whenComplete((ignored, error) ->
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
		view.visibleWidget(CLICK_TO_PLAY_WIDGETS).whenComplete((widget, error) ->
		{
			if (error != null)
			{
				failActive(error.getMessage());
				return;
			}
			if (widget == null)
			{
				view.visibleWidget(WELCOME_ROOT_WIDGETS).whenComplete((root, rootError) ->
				{
					if (rootError != null)
					{
						failActive(rootError.getMessage());
						return;
					}
					if (root == null)
					{
						completeActive("SESSION_LOGGED_IN");
						return;
					}
					clickPoint(clickToPlayFallbackPoint(view.canvasWidth(), view.canvasHeight()))
						.whenComplete((ignored, fallbackError) ->
						{
							if (fallbackError != null)
							{
								failActive(fallbackError.getMessage());
								return;
							}
							completeActive("SESSION_LOGGED_IN_AND_PLAYING");
						});
				});
				return;
			}
			clickBounds(widget).whenComplete((ignored, clickError) ->
			{
				if (clickError != null)
				{
					failActive(clickError.getMessage());
					return;
				}
				completeActive("SESSION_LOGGED_IN_AND_PLAYING");
			});
		});
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
	}

	interface Input
	{
		CompletableFuture<String> move(Point point);

		CompletableFuture<String> click();
	}
}
