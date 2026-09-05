package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Canvas;
import java.awt.Point;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientSyntheticMouseTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void cancellingAnOldRestOwnerCannotCancelItsReplacement() throws Exception
	{
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		executor.submit(() -> { release.await(); return null; });
		try (GenericClientSyntheticMouse mouse = mouse(canvas(), executor, new Point(100, 100), 500))
		{
			GenericClientActivityContext firstOwner = GenericClientActivityContext.none().openInputScope();
			GenericClientActivityContext nextOwner = GenericClientActivityContext.none().openInputScope();
			java.util.concurrent.CompletableFuture<String> first = mouse.moveRest(new Point(200, 200), 500, firstOwner);
			java.util.concurrent.CompletableFuture<String> next = mouse.moveOffscreen(GenericClientBehaviorProfile.Edge.LEFT, nextOwner);
			assertTrue(first.isCompletedExceptionally());
			mouse.cancelRest("old_window", firstOwner);
			assertFalse(next.isDone());
			assertTrue(mouse.isMoving());
			mouse.cancelRest("new_window", nextOwner);
			assertTrue(next.isCompletedExceptionally());
			assertFalse(mouse.isMoving());
		}
		finally { release.countDown(); executor.shutdownNow(); }
	}

	@Test
	public void globalCancellationRevokesAQueuedClickWithoutAnAwaitTicket() throws Exception
	{
		Canvas canvas = canvas();
		List<Integer> events = new ArrayList<>();
		canvas.addMouseListener(listener(events));
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		java.util.concurrent.CountDownLatch queued = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		try (GenericClientSyntheticMouse mouse = mouse(canvas, executor, new Point(100, 100), 500))
		{
			javax.swing.SwingUtilities.invokeLater(() -> {
				queued.countDown();
				try { release.await(); }
				catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
			});
			assertTrue(queued.await(2, TimeUnit.SECONDS));
			java.util.concurrent.CompletableFuture<String> click = mouse.click(MouseEvent.BUTTON1);
			mouse.cancel("manual_takeover");
			release.countDown();
			assertTrue(click.handle((receipt, error) -> error != null).get(2, TimeUnit.SECONDS));
			assertFalse(events.contains(MouseEvent.MOUSE_PRESSED));
			assertFalse(mouse.isActionActive());
			assertEquals("SYNTHETIC_LEFT_CLICK", mouse.click(MouseEvent.BUTTON1).get(2, TimeUnit.SECONDS));
		}
		finally { release.countDown(); executor.shutdownNow(); }
	}

	@Test
	public void aQueuedClickKeepsRestOutUntilTheCanvasReceivesIt() throws Exception
	{
		Canvas canvas = canvas();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		java.util.concurrent.CountDownLatch queued = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		try (GenericClientSyntheticMouse mouse = mouse(canvas, executor, new Point(100, 100), 500))
		{
			javax.swing.SwingUtilities.invokeLater(() -> {
				queued.countDown();
				try { release.await(); }
				catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
			});
			assertTrue(queued.await(2, TimeUnit.SECONDS));
			java.util.concurrent.CompletableFuture<String> click = mouse.click(MouseEvent.BUTTON1);
			assertTrue(mouse.isActionActive());
			assertTrue(mouse.moveRest(new Point(200, 200), 100, GenericClientActivityContext.none()).isCompletedExceptionally());
			release.countDown();
			assertEquals("SYNTHETIC_LEFT_CLICK", click.get(2, TimeUnit.SECONDS));
			assertFalse(mouse.isActionActive());
			assertEquals(new Point(100, 100), mouse.getLastClick());
		}
		finally { release.countDown(); executor.shutdownNow(); }
	}

	@Test
	public void aClickPreemptsRestWithoutLettingQueuedMotionMoveItsTarget() throws Exception
	{
		Canvas canvas = canvas();
		List<Integer> events = new ArrayList<>();
		canvas.addMouseListener(listener(events));
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		executor.submit(() -> { release.await(); return null; });
		try (GenericClientSyntheticMouse mouse = mouse(canvas, executor, new Point(100, 100), 500))
		{
			java.util.concurrent.CompletableFuture<String> rest = mouse.moveRest(mouse.offscreenTarget(GenericClientBehaviorProfile.Edge.LEFT), 500, GenericClientActivityContext.none());
			mouse.click(MouseEvent.BUTTON1).get(2, TimeUnit.SECONDS);
			assertTrue(rest.isCompletedExceptionally());
			assertFalse(mouse.isMoving());
			release.countDown();
			executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
			javax.swing.SwingUtilities.invokeAndWait(() -> { });
			assertEquals(new Point(100, 100), mouse.getPosition());
			assertTrue(events.contains(MouseEvent.MOUSE_PRESSED));
		}
		finally { release.countDown(); executor.shutdownNow(); }
	}

	@Test
	public void cancelledActionCannotFinishAMoveOrDispatchAQueuedClick() throws Exception
	{
		Canvas canvas = canvas();
		List<Integer> events = new ArrayList<>();
		canvas.addMouseListener(listener(events));
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		GenericClientSyntheticMouse mouse = mouse(canvas, executor, new Point(10, 10), 500);
		GenericClientActionBoundary.Ticket ticket = new GenericClientActionBoundary.Ticket();
		GenericClientActivityContext context = GenericClientActivityContext.none().withTicket(ticket);
		try
		{
			java.util.concurrent.CompletableFuture<String> move = mouse.move(new Point(200, 150), context);
			ticket.cancel();
			assertTrue(move.handle((value, error) -> error != null).get(2, TimeUnit.SECONDS));
			assertTrue(mouse.click(MouseEvent.BUTTON1, context).handle((value, error) -> error != null)
				.get(2, TimeUnit.SECONDS));
			assertFalse(events.contains(MouseEvent.MOUSE_PRESSED));
			assertFalse(mouse.isMoving());
		}
		finally { mouse.close(); executor.shutdownNow(); }
	}

	@Test
	public void movesAndClicksThroughCanvasEventsInOrder() throws Exception
	{
		Canvas canvas = canvas();
		List<Integer> events = new ArrayList<>();
		MouseAdapter listener = listener(events);
		canvas.addMouseMotionListener(listener);
		canvas.addMouseListener(listener);
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		GenericClientSyntheticMouse mouse = mouse(canvas, executor, new Point(10, 10), 25);
		try
		{
			mouse.move(new Point(200, 150)).get(2, TimeUnit.SECONDS);
			mouse.click(MouseEvent.BUTTON1).get(2, TimeUnit.SECONDS);

			assertEquals(new Point(200, 150), mouse.getPosition());
			assertFalse(mouse.isOutside());
			assertTrue(events.contains(MouseEvent.MOUSE_MOVED));
			int pressed = events.lastIndexOf(MouseEvent.MOUSE_PRESSED);
			int released = events.lastIndexOf(MouseEvent.MOUSE_RELEASED);
			int clicked = events.lastIndexOf(MouseEvent.MOUSE_CLICKED);
			assertTrue(pressed >= 0 && pressed < released && released < clicked);
		}
		finally
		{
			mouse.close();
			executor.shutdownNow();
		}
	}

	@Test
	public void leavesAndReentersTheCanvasWithoutMovingAnOsPointer() throws Exception
	{
		Canvas canvas = canvas();
		List<Integer> events = new ArrayList<>();
		List<Point> enteredPoints = new ArrayList<>();
		MouseAdapter listener = listener(events);
		canvas.addMouseMotionListener(listener);
		canvas.addMouseListener(listener);
		canvas.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent event)
			{
				enteredPoints.add(event.getPoint());
			}
		});
		canvas.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent event)
			{
				events.add(event.getID());
			}

			@Override
			public void focusLost(FocusEvent event)
			{
				events.add(event.getID());
			}
		});
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		GenericClientSyntheticMouse mouse = mouse(canvas, executor, new Point(100, 100), 25);
		try
		{
			mouse.moveOffscreen(GenericClientBehaviorProfile.Edge.LEFT, GenericClientActivityContext.none()).get(2, TimeUnit.SECONDS);
			Point outside = mouse.getPosition();
			assertTrue(outside.x < 0);
			assertTrue(mouse.isOutside());
			assertTrue(events.contains(MouseEvent.MOUSE_EXITED));
			assertTrue(events.contains(FocusEvent.FOCUS_LOST));

			events.clear();
			mouse.move(new Point(80, 90)).get(2, TimeUnit.SECONDS);
			assertFalse(mouse.isOutside());
			assertTrue(events.contains(FocusEvent.FOCUS_GAINED));
			assertTrue(events.contains(MouseEvent.MOUSE_ENTERED));
			assertEquals(1, enteredPoints.size());
			assertNotEquals(outside.y, enteredPoints.get(0).y);
			assertEquals(new Point(80, 90), mouse.getPosition());
		}
		finally
		{
			mouse.close();
			executor.shutdownNow();
		}
	}

	@Test
	public void reentersFromADifferentPointOnTheSameEdge()
	{
		Point left = GenericClientSyntheticMouse.randomizedReentryStart(
			new Point(-20, 100), 800, 600, new Random(7));
		Point right = GenericClientSyntheticMouse.randomizedReentryStart(
			new Point(820, 500), 800, 600, new Random(8));
		Point top = GenericClientSyntheticMouse.randomizedReentryStart(
			new Point(300, -20), 800, 600, new Random(9));

		assertTrue(left.x < 0);
		assertTrue(left.y >= 0 && left.y < 600);
		assertNotEquals(100, left.y);
		assertTrue(right.x >= 800);
		assertTrue(right.y >= 0 && right.y < 600);
		assertNotEquals(500, right.y);
		assertTrue(top.y < 0);
		assertTrue(top.x >= 0 && top.x < 800);
		assertNotEquals(300, top.x);
	}

	@Test
	public void reconcilesAnOffscreenPositionAfterTheCanvasGrows() throws Exception
	{
		Canvas canvas = canvas();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		GenericClientSyntheticMouse mouse = mouse(canvas, executor, new Point(820, 100), 25);
		try
		{
			assertTrue(mouse.isOutside());

			canvas.setSize(900, 600);
			mouse.move(new Point(100, 100)).get(2, TimeUnit.SECONDS);

			assertFalse(mouse.isOutside());
			assertEquals(new Point(100, 100), mouse.getPosition());
		}
		finally
		{
			mouse.close();
			executor.shutdownNow();
		}
	}

	@Test
	public void emitsMarkerEventsWithCorrectRightButtonFields() throws Exception
	{
		Canvas canvas = canvas();
		List<MouseEvent> events = new ArrayList<>();
		canvas.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent event)
			{
				events.add(event);
			}

			@Override
			public void mouseReleased(MouseEvent event)
			{
				events.add(event);
			}

			@Override
			public void mouseClicked(MouseEvent event)
			{
				events.add(event);
			}
		});
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		GenericClientSyntheticMouse mouse = mouse(canvas, executor, new Point(50, 60), 25);
		try
		{
			mouse.click(MouseEvent.BUTTON3).get(2, TimeUnit.SECONDS);
			assertEquals(3, events.size());
			for (MouseEvent event : events)
			{
				assertTrue(event instanceof GenericClientSyntheticMouseEvent);
				assertEquals(MouseEvent.BUTTON3, event.getButton());
				assertTrue(event.isPopupTrigger());
				assertEquals(50, event.getX());
				assertEquals(60, event.getY());
			}
		}
		finally
		{
			mouse.close();
			executor.shutdownNow();
		}
	}

	@Test
	public void closeCancelsAnActiveMovement() throws Exception
	{
		Canvas canvas = canvas();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		GenericClientSyntheticMouse mouse = mouse(canvas, executor, new Point(10, 10), 500);
		java.util.concurrent.CompletableFuture<String> movement = mouse.move(new Point(700, 500));

		mouse.close();

		assertTrue(movement.isCompletedExceptionally());
		executor.shutdownNow();
	}

	@Test
	public void cancelStopsAnActiveMovementWithoutClosingTheMouse() throws Exception
	{
		Canvas canvas = canvas();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		GenericClientSyntheticMouse mouse = mouse(canvas, executor, new Point(10, 10), 500);
		try
		{
			java.util.concurrent.CompletableFuture<String> movement =
				mouse.move(new Point(700, 500));

			AtomicReference<Boolean> callbackHeldLock = new AtomicReference<>();
			movement.whenComplete((value, error) -> callbackHeldLock.set(Thread.holdsLock(mouse)));
			mouse.cancel("random_event");

			assertTrue(movement.isCompletedExceptionally());
			assertEquals(Boolean.FALSE, callbackHeldLock.get());
			assertFalse(mouse.isMoving());
			assertEquals("SYNTHETIC_LEFT_CLICK",
				mouse.click(MouseEvent.BUTTON1).get(2, TimeUnit.SECONDS));
		}
		finally
		{
			mouse.close();
			executor.shutdownNow();
		}
	}

	@Test
	public void normalInputPreemptsAnIdleOffscreenPark() throws Exception
	{
		Canvas canvas = canvas();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		GenericClientSyntheticMouse mouse = mouse(canvas, executor, new Point(100, 100), 500);
		try
		{
			java.util.concurrent.CompletableFuture<String> idle =
				mouse.moveRest(mouse.offscreenTarget(GenericClientBehaviorProfile.Edge.RIGHT), 500, GenericClientActivityContext.none());
			java.util.concurrent.CompletableFuture<String> action = mouse.move(new Point(300, 250));

			assertTrue(idle.isCompletedExceptionally());
			assertTrue(action.get(2, TimeUnit.SECONDS).contains("destination=300,250"));
			assertFalse(mouse.isOutside());
		}
		finally
		{
			mouse.close();
			executor.shutdownNow();
		}
	}

	@Test
	public void realCanvasMovementRefreshesTheNextSyntheticStartPosition() throws Exception
	{
		Canvas canvas = canvas();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		GenericClientSyntheticMouse mouse = mouse(canvas, executor, new Point(10, 10), 25);
		try
		{
			canvas.dispatchEvent(new MouseEvent(
				canvas,
				MouseEvent.MOUSE_MOVED,
				System.currentTimeMillis(),
				0,
				333,
				222,
				0,
				false,
				MouseEvent.NOBUTTON));

			assertEquals(new Point(333, 222), mouse.getPosition());
			assertFalse(mouse.isOutside());
		}
		finally
		{
			mouse.close();
			executor.shutdownNow();
		}
	}

	@Test
	public void realCanvasMovementCanImmediatelyPreemptSyntheticMovement() throws Exception
	{
		Canvas canvas = canvas();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		AtomicInteger takeovers = new AtomicInteger();
		AtomicReference<GenericClientSyntheticMouse> reference = new AtomicReference<>();
		GenericClientMouseProfile profile = loadProfile();
		GenericClientSyntheticMouse mouse = new GenericClientSyntheticMouse(
			canvas,
			executor,
			() -> profile,
			() -> 500,
			new Point(10, 10),
			new GenericClientMouseEffectOverlay(
				() -> GenericClientMouseEffect.TRAIL,
				canvas::getWidth,
				canvas::getHeight,
				System::currentTimeMillis),
			message -> { },
			() -> new Random(7),
			point ->
			{
				takeovers.incrementAndGet();
				reference.get().cancel("manual_mouse_preemption");
			});
		reference.set(mouse);
		try
		{
			java.util.concurrent.CompletableFuture<String> movement =
				mouse.move(new Point(700, 500));
			canvas.dispatchEvent(new MouseEvent(
				canvas,
				MouseEvent.MOUSE_MOVED,
				System.currentTimeMillis(),
				0,
				333,
				222,
				0,
				false,
				MouseEvent.NOBUTTON));

			assertTrue(movement.isCompletedExceptionally());
			assertFalse(mouse.isMoving());
			assertEquals(new Point(333, 222), mouse.getPosition());
			assertEquals(1, takeovers.get());
		}
		finally
		{
			mouse.close();
			executor.shutdownNow();
		}
	}

	private GenericClientSyntheticMouse mouse(
		Canvas canvas,
		ScheduledExecutorService executor,
		Point start,
		int duration) throws Exception
	{
		GenericClientMouseProfile profile = loadProfile();
		Random random = new Random(7);
		return new GenericClientSyntheticMouse(
			canvas,
			executor,
			() -> profile,
			() -> duration,
			start,
			new GenericClientMouseEffectOverlay(
				() -> GenericClientMouseEffect.TRAIL,
				canvas::getWidth,
				canvas::getHeight,
				System::currentTimeMillis),
			message -> { },
			() -> random,
			point -> { });
	}

	private GenericClientMouseProfile loadProfile() throws Exception
	{
		Path file = temporaryFolder.newFile().toPath();
		try (InputStream input = getClass().getResourceAsStream("/com/genericclient/mouse/default.json"))
		{
			Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		return GenericClientMouseProfile.load(file);
	}

	private static Canvas canvas()
	{
		Canvas canvas = new Canvas();
		canvas.setSize(800, 600);
		return canvas;
	}

	private static MouseAdapter listener(List<Integer> events)
	{
		return new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent event)
			{
				events.add(event.getID());
			}

			@Override
			public void mouseEntered(MouseEvent event)
			{
				events.add(event.getID());
			}

			@Override
			public void mouseExited(MouseEvent event)
			{
				events.add(event.getID());
			}

			@Override
			public void mousePressed(MouseEvent event)
			{
				events.add(event.getID());
			}

			@Override
			public void mouseReleased(MouseEvent event)
			{
				events.add(event.getID());
			}

			@Override
			public void mouseClicked(MouseEvent event)
			{
				events.add(event.getID());
			}
		};
	}
}
