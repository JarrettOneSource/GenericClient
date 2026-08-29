package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Canvas;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class GenericClientSyntheticKeyboardTest
{
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
}
