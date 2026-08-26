package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetInfo;

@SuppressWarnings("deprecation")
public class GenericClientSessionControllerTest
{
	@Test
	public void logsOutThroughTheVisibleLogoutButton() throws Exception
	{
		FakeView view = new FakeView();
		view.gameState = GameState.LOGGED_IN;
		view.widgets.put(InterfaceID.Logout.LOGOUT, new Rectangle(100, 200, 80, 30));
		FakeInput input = new FakeInput();
		input.onClick = () -> view.gameState = GameState.LOGIN_SCREEN;

		try (Fixture fixture = fixture(view, input))
		{
			assertEquals("SESSION_LOGGED_OUT", fixture.controller.logout().get(2, TimeUnit.SECONDS));
			assertEquals(new Point(140, 215), input.moves.get(0));
			assertEquals(1, input.clicks.get());
		}
	}

	@Test
	public void opensTheLogoutTabBeforeClickingTheLogoutButton() throws Exception
	{
		FakeView view = new FakeView();
		view.gameState = GameState.LOGGED_IN;
		view.widgets.put(WidgetInfo.FIXED_VIEWPORT_LOGOUT_TAB.getId(), new Rectangle(10, 10, 20, 20));
		FakeInput input = new FakeInput();
		input.onClick = () ->
		{
			if (input.clicks.get() == 1)
			{
				view.widgets.put(InterfaceID.Logout.LOGOUT, new Rectangle(200, 220, 100, 40));
			}
			else
			{
				view.gameState = GameState.LOGIN_SCREEN;
			}
		};

		try (Fixture fixture = fixture(view, input))
		{
			assertEquals("SESSION_LOGGED_OUT", fixture.controller.logout().get(2, TimeUnit.SECONDS));
			assertEquals(2, input.clicks.get());
			assertEquals(new Point(20, 20), input.moves.get(0));
			assertEquals(new Point(250, 240), input.moves.get(1));
		}
	}

	@Test
	public void usesTheJagexPlayButtonThenDismissesClickToPlay() throws Exception
	{
		FakeView view = new FakeView();
		view.gameState = GameState.LOGIN_SCREEN;
		view.launcherDisplayName = "Player";
		view.canvasWidth = 765;
		view.canvasHeight = 503;
		FakeInput input = new FakeInput();
		input.onClick = () ->
		{
			if (input.clicks.get() == 1)
			{
				view.gameState = GameState.LOGGED_IN;
				view.widgets.put(InterfaceID.WelcomeScreen.PLAY, new Rectangle(300, 300, 170, 60));
			}
			else
			{
				view.widgets.remove(InterfaceID.WelcomeScreen.PLAY);
			}
		};

		try (Fixture fixture = fixture(view, input))
		{
			assertEquals("SESSION_LOGGED_IN_AND_PLAYING",
				fixture.controller.ensureLoggedIn().get(3, TimeUnit.SECONDS));
			assertEquals(new Point(382, 251), input.moves.get(0));
			assertEquals(new Point(385, 330), input.moves.get(1));
			assertEquals(2, input.clicks.get());
		}
	}

	@Test
	public void dismissesALoginErrorBeforeRetryingTheJagexPlayButton() throws Exception
	{
		FakeView view = new FakeView();
		view.gameState = GameState.LOGIN_SCREEN;
		view.launcherDisplayName = "Player";
		view.canvasWidth = 765;
		view.canvasHeight = 503;
		FakeInput input = new FakeInput();
		input.onClick = () ->
		{
			if (input.clicks.get() == 3)
			{
				view.gameState = GameState.LOGGED_IN;
			}
		};

		try (Fixture fixture = fixture(view, input))
		{
			assertEquals("SESSION_LOGGED_IN",
				fixture.controller.ensureLoggedIn().get(5, TimeUnit.SECONDS));
			assertEquals(new Point(382, 251), input.moves.get(0));
			assertEquals(new Point(382, 302), input.moves.get(1));
			assertEquals(new Point(382, 251), input.moves.get(2));
		}
	}

	@Test
	public void completesImmediatelyWhenAlreadyInTheWorld() throws Exception
	{
		FakeView view = new FakeView();
		view.gameState = GameState.LOGGED_IN;
		FakeInput input = new FakeInput();

		try (Fixture fixture = fixture(view, input))
		{
			assertEquals("SESSION_LOGGED_IN", fixture.controller.ensureLoggedIn().get(1, TimeUnit.SECONDS));
			assertEquals(0, input.clicks.get());
		}
	}

	@Test
	public void usesCanvasFallbackWhenTheWelcomeRootHasNoPlayBounds() throws Exception
	{
		FakeView view = new FakeView();
		view.gameState = GameState.LOGGED_IN;
		view.canvasWidth = 765;
		view.canvasHeight = 503;
		view.widgets.put(InterfaceID.WelcomeScreen.UNIVERSE, new Rectangle(0, 0, 765, 503));
		FakeInput input = new FakeInput();
		input.onClick = () -> view.widgets.remove(InterfaceID.WelcomeScreen.UNIVERSE);

		try (Fixture fixture = fixture(view, input))
		{
			assertEquals("SESSION_LOGGED_IN_AND_PLAYING",
				fixture.controller.ensureLoggedIn().get(1, TimeUnit.SECONDS));
			assertEquals(new Point(382, 337), input.moves.get(0));
		}
	}

	@Test
	public void refusesAutomaticLoginWithoutAJagexLauncherSession() throws Exception
	{
		FakeView view = new FakeView();
		view.gameState = GameState.LOGIN_SCREEN;
		FakeInput input = new FakeInput();

		try (Fixture fixture = fixture(view, input))
		{
			try
			{
				fixture.controller.ensureLoggedIn().get(1, TimeUnit.SECONDS);
				throw new AssertionError("Expected missing launcher session to fail");
			}
			catch (ExecutionException expected)
			{
				assertTrue(expected.getCause().getMessage().contains("Jagex Launcher"));
			}
		}
	}

	@Test
	public void loginPointIsAlwaysTheCanvasCenter()
	{
		assertEquals(new Point(382, 251), GenericClientSessionController.loginButtonPoint(765, 503));
		assertEquals(new Point(0, 0), GenericClientSessionController.loginButtonPoint(0, 0));
		assertEquals(new Point(382, 337),
			GenericClientSessionController.clickToPlayFallbackPoint(765, 503));
		assertEquals(new Point(382, 302),
			GenericClientSessionController.loginConfirmationPoint(765, 503));
	}

	private static Fixture fixture(FakeView view, FakeInput input)
	{
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		GenericClientSessionController controller = new GenericClientSessionController(
			view,
			input,
			executor,
			message -> { },
			10L,
			5_000L);
		return new Fixture(controller, executor);
	}

	private static final class Fixture implements AutoCloseable
	{
		private final GenericClientSessionController controller;
		private final ScheduledExecutorService executor;

		private Fixture(GenericClientSessionController controller, ScheduledExecutorService executor)
		{
			this.controller = controller;
			this.executor = executor;
		}

		@Override
		public void close()
		{
			controller.close();
			executor.shutdownNow();
		}
	}

	private static final class FakeView implements GenericClientSessionController.SessionView
	{
		private volatile GameState gameState = GameState.LOGIN_SCREEN;
		private String launcherDisplayName;
		private int canvasWidth = 800;
		private int canvasHeight = 600;
		private final Map<Integer, Rectangle> widgets = new HashMap<>();

		@Override
		public GameState gameState()
		{
			return gameState;
		}

		@Override
		public String launcherDisplayName()
		{
			return launcherDisplayName;
		}

		@Override
		public int canvasWidth()
		{
			return canvasWidth;
		}

		@Override
		public int canvasHeight()
		{
			return canvasHeight;
		}

		@Override
		public CompletableFuture<Rectangle> visibleWidget(int... candidates)
		{
			for (int candidate : candidates)
			{
				Rectangle rectangle = widgets.get(candidate);
				if (rectangle != null)
				{
					return CompletableFuture.completedFuture(new Rectangle(rectangle));
				}
			}
			return CompletableFuture.completedFuture(null);
		}
	}

	private static final class FakeInput implements GenericClientSessionController.Input
	{
		private final java.util.List<Point> moves = new java.util.ArrayList<>();
		private final AtomicInteger clicks = new AtomicInteger();
		private Runnable onClick = () -> { };

		@Override
		public CompletableFuture<String> move(Point point)
		{
			moves.add(new Point(point));
			return CompletableFuture.completedFuture("moved");
		}

		@Override
		public CompletableFuture<String> click()
		{
			clicks.incrementAndGet();
			onClick.run();
			return CompletableFuture.completedFuture("clicked");
		}
	}
}
