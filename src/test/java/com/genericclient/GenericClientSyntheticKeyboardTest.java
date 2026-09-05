package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Canvas;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.Test;

public class GenericClientSyntheticKeyboardTest
{
	@Test
	public void escapeOwnsTheKeyboardWhileWaitingForTheEventThread() throws Exception
	{
		CountDownLatch eventThreadBlocked = new CountDownLatch(1);
		CountDownLatch releaseEventThread = new CountDownLatch(1);
		CountDownLatch admitted = new CountDownLatch(1);
		Canvas canvas = new Canvas()
		{
			@Override public boolean isShowing() { admitted.countDown(); return true; }
		};
		List<Integer> pressed = new ArrayList<>();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		try (GenericClientSyntheticKeyboard keyboard = new GenericClientSyntheticKeyboard(canvas, executor,
			ignored -> { }, event -> { if (event.getID() == KeyEvent.KEY_PRESSED) pressed.add(event.getKeyCode()); }))
		{
			SwingUtilities.invokeLater(() -> {
				eventThreadBlocked.countDown();
				try { releaseEventThread.await(); }
				catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
			});
			assertTrue(eventThreadBlocked.await(3, TimeUnit.SECONDS));
			CompletableFuture<String> escape = CompletableFuture.supplyAsync(keyboard::pressEscape).thenCompose(result -> result);
			try
			{
				assertTrue(admitted.await(3, TimeUnit.SECONDS));
				assertTrue(keyboard.pressDigit(2, 10_000L).isCompletedExceptionally());
				assertTrue(keyboard.isTyping());
			}
			finally { releaseEventThread.countDown(); }
			assertEquals("SYNTHETIC_ESCAPE", escape.get(3, TimeUnit.SECONDS));
			assertFalse(keyboard.isTyping());
			assertEquals(List.of(KeyEvent.VK_ESCAPE), pressed);
		}
		finally { releaseEventThread.countDown(); executor.shutdownNow(); }
	}

	@Test
	public void cancelledAwaitCannotTypeFromADelayedCompositeStep() throws Exception
	{
		Canvas canvas = new Canvas() { @Override public boolean isShowing() { return true; } };
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		java.util.concurrent.atomic.AtomicInteger events = new java.util.concurrent.atomic.AtomicInteger();
		GenericClientSyntheticKeyboard keyboard = new GenericClientSyntheticKeyboard(
			canvas, executor, ignored -> { }, event -> { events.incrementAndGet(); });
		GenericClientActionBoundary.Ticket ticket = new GenericClientActionBoundary.Ticket();
		try
		{
			java.util.concurrent.CompletableFuture<String> typing = keyboard.type("42", true, 200L,
				GenericClientActivityContext.none().withTicket(ticket));
			ticket.cancel();
			assertTrue(typing.handle((value, error) -> error != null).get(2, TimeUnit.SECONDS));
			assertEquals(0, events.get());
			assertFalse(keyboard.isTyping());
		}
		finally { keyboard.close(); executor.shutdownNow(); }
	}

	@Test
	public void convertsWordsPerMinuteUsingFiveCharactersPerWord()
	{
		assertEquals(240L, GenericClientSyntheticKeyboard.keyIntervalMillis(50));
		assertEquals(120L, GenericClientSyntheticKeyboard.keyIntervalMillis(100));
	}

	@Test
	public void sendsTextAndEnterDirectlyToCanvasKeyListeners() throws Exception
	{
		Canvas canvas = new Canvas()
		{
			@Override
			public boolean isShowing()
			{
				return true;
			}

			@Override
			public boolean isFocusOwner()
			{
				return true;
			}
		};
		StringBuilder typed = new StringBuilder();
		AtomicInteger enters = new AtomicInteger();
		KeyAdapter listener = new KeyAdapter()
		{
			@Override
			public void keyTyped(KeyEvent event)
			{
				typed.append(event.getKeyChar());
			}

			@Override
			public void keyPressed(KeyEvent event)
			{
				if (event.getKeyCode() == KeyEvent.VK_ENTER)
				{
					enters.incrementAndGet();
				}
			}
		};
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		GenericClientSyntheticKeyboard keyboard = new GenericClientSyntheticKeyboard(
			canvas,
			executor,
			ignored -> { },
			event ->
			{
				assertTrue(event instanceof GenericClientSyntheticKeyEvent);
				switch (event.getID())
				{
					case KeyEvent.KEY_PRESSED:
						listener.keyPressed(event);
						break;
					case KeyEvent.KEY_TYPED:
						listener.keyTyped(event);
						break;
					case KeyEvent.KEY_RELEASED:
						listener.keyReleased(event);
						break;
					default:
						throw new AssertionError("Unexpected key event: " + event.getID());
				}
			});
		try
		{
			keyboard.typeAndEnter("42").get(2, TimeUnit.SECONDS);

			assertEquals("42", typed.toString());
			assertEquals(1, enters.get());
		}
		finally
		{
			keyboard.close();
			executor.shutdownNow();
		}
	}

	@Test
	public void cancelStopsTypingWithoutClosingTheKeyboard()
	{
		Canvas canvas = new Canvas()
		{
			@Override
			public boolean isShowing()
			{
				return true;
			}
		};
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		GenericClientSyntheticKeyboard keyboard = new GenericClientSyntheticKeyboard(
			canvas, executor, ignored -> { });
		try
		{
			java.util.concurrent.CompletableFuture<String> typing =
				keyboard.typeAndEnter("1234567890");

			keyboard.cancel("random_event");

			assertTrue(typing.isCompletedExceptionally());
			assertFalse(keyboard.isTyping());
		}
		finally
		{
			keyboard.close();
			executor.shutdownNow();
		}
	}

	@Test
	public void dialogueDigitUsesAHotkeyWithoutTypingChatText() throws Exception
	{
		Canvas canvas = new Canvas()
		{
			@Override
			public boolean isShowing()
			{
				return true;
			}
		};
		AtomicInteger pressed = new AtomicInteger();
		AtomicInteger typed = new AtomicInteger();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		GenericClientSyntheticKeyboard keyboard = new GenericClientSyntheticKeyboard(
			canvas,
			executor,
			ignored -> { },
			event ->
			{
				assertTrue(event instanceof GenericClientSyntheticKeyEvent);
				if (event.getID() == KeyEvent.KEY_PRESSED && event.getKeyCode() == KeyEvent.VK_2)
				{
					pressed.incrementAndGet();
				}
				if (event.getID() == KeyEvent.KEY_TYPED)
				{
					typed.incrementAndGet();
				}
			});
		try
		{
			keyboard.pressDigit(2, 0L).get(2, TimeUnit.SECONDS);
			assertEquals(1, pressed.get());
			assertEquals(0, typed.get());
		}
		finally
		{
			keyboard.close();
			executor.shutdownNow();
		}
	}
}
