package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientSyntheticMouseTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

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
			mouse.moveOffscreen(GenericClientBehaviorProfile.Edge.LEFT).get(2, TimeUnit.SECONDS);
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
			assertTrue(enteredPoints.get(0).y != outside.y);
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
		assertTrue(left.y != 100);
		assertTrue(right.x >= 800);
		assertTrue(right.y >= 0 && right.y < 600);
		assertTrue(right.y != 500);
		assertTrue(top.y < 0);
		assertTrue(top.x >= 0 && top.x < 800);
		assertTrue(top.x != 300);
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
			() -> random);
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
