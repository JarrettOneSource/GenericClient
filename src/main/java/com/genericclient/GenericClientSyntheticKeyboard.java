package com.genericclient;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import javax.swing.SwingUtilities;

final class GenericClientSyntheticKeyboard implements AutoCloseable
{
	private final Canvas canvas;
	private final ScheduledExecutorService executor;
	private final Consumer<String> reporter;
	private final IntSupplier wordsPerMinute;
	private final Consumer<KeyEvent> eventDispatcher;
	private final List<ScheduledFuture<?>> pending = new CopyOnWriteArrayList<>();
	private boolean typing;
	private boolean closed;
	private CompletableFuture<String> activeResult;

	GenericClientSyntheticKeyboard(Canvas canvas, ScheduledExecutorService executor)
	{
		this(
			canvas,
			executor,
			ignored -> { },
			() -> GenericClientBehaviorProfile.DEFAULT_TYPING_WORDS_PER_MINUTE,
			canvas::dispatchEvent);
	}

	GenericClientSyntheticKeyboard(
		Canvas canvas,
		ScheduledExecutorService executor,
		Consumer<String> reporter)
	{
		this(
			canvas,
			executor,
			reporter,
			() -> GenericClientBehaviorProfile.DEFAULT_TYPING_WORDS_PER_MINUTE,
			canvas::dispatchEvent);
	}

	GenericClientSyntheticKeyboard(
		Canvas canvas,
		ScheduledExecutorService executor,
		Consumer<String> reporter,
		IntSupplier wordsPerMinute)
	{
		this(canvas, executor, reporter, wordsPerMinute, canvas::dispatchEvent);
	}

	GenericClientSyntheticKeyboard(
		Canvas canvas,
		ScheduledExecutorService executor,
		Consumer<String> reporter,
		Consumer<KeyEvent> eventDispatcher)
	{
		this(
			canvas,
			executor,
			reporter,
			() -> GenericClientBehaviorProfile.DEFAULT_TYPING_WORDS_PER_MINUTE,
			eventDispatcher);
	}

	GenericClientSyntheticKeyboard(
		Canvas canvas,
		ScheduledExecutorService executor,
		Consumer<String> reporter,
		IntSupplier wordsPerMinute,
		Consumer<KeyEvent> eventDispatcher)
	{
		this.canvas = canvas;
		this.executor = executor;
		this.reporter = reporter;
		this.wordsPerMinute = wordsPerMinute;
		this.eventDispatcher = eventDispatcher;
	}

	CompletableFuture<String> typeAndEnter(String text)
	{
		return type(text, true, 0L);
	}

	CompletableFuture<String> typeAndEnter(String text, long initialDelayMillis)
	{
		return type(text, true, initialDelayMillis);
	}

	CompletableFuture<String> type(String text)
	{
		return type(text, false, 0L);
	}

	private CompletableFuture<String> type(
		String text,
		boolean submit,
		long initialDelayMillis)
	{
		if (text == null || text.isEmpty())
		{
			throw new IllegalArgumentException("Synthetic keyboard text cannot be empty");
		}
		List<Character> characters = new ArrayList<>(text.length());
		if (initialDelayMillis < 0L || initialDelayMillis > 5_000L)
		{
			throw new IllegalArgumentException("Synthetic keyboard delay must be between 0 and 5000ms");
		}
		for (int index = 0; index < text.length(); index++)
		{
			char character = text.charAt(index);
			if (character < 32 || character > 126)
			{
				throw new IllegalArgumentException("Synthetic keyboard supports printable ASCII text");
			}
			characters.add(character);
		}

		CompletableFuture<String> result = new CompletableFuture<>();
		synchronized (this)
		{
			if (closed)
			{
				result.completeExceptionally(new IllegalStateException("Synthetic keyboard is closed"));
				return result;
			}
			if (typing)
			{
				result.completeExceptionally(new IllegalStateException("Synthetic keyboard is already typing"));
				return result;
			}
			if (canvas == null || !canvas.isShowing())
			{
				result.completeExceptionally(new IllegalStateException("Client canvas is unavailable"));
				return result;
			}
			typing = true;
			activeResult = result;
		}
		int wpm = Math.max(
			GenericClientBehaviorProfile.TYPING_WORDS_PER_MINUTE_MIN,
			Math.min(GenericClientBehaviorProfile.TYPING_WORDS_PER_MINUTE_MAX,
				wordsPerMinute.getAsInt()));
		long intervalMillis = keyIntervalMillis(wpm);
		reporter.accept("SYNTHETIC_KEYBOARD_STARTED characters=" + text.length() +
			" listeners=" + canvas.getKeyListeners().length +
			" focusOwner=" + canvas.isFocusOwner() +
			" initialDelayMs=" + initialDelayMillis +
			" wpm=" + wpm);

		long nextDelay = initialDelayMillis;
		for (int index = 0; index < characters.size(); index++)
		{
			char character = characters.get(index);
			schedule(() -> dispatchCharacter(character), nextDelay);
			nextDelay += jitteredInterval(intervalMillis);
		}
		if (submit)
		{
			schedule(this::dispatchEnter, nextDelay);
			nextDelay += jitteredInterval(intervalMillis);
		}
		schedule(() -> finish(result, text, submit), nextDelay);
		return result;
	}

	static long keyIntervalMillis(int wordsPerMinute)
	{
		if (wordsPerMinute < 1)
		{
			throw new IllegalArgumentException("Words per minute must be positive");
		}
		return Math.max(25L, Math.round(60_000.0 / (wordsPerMinute * 5.0)));
	}

	private static long jitteredInterval(long baseMillis)
	{
		return Math.max(25L, Math.round(baseMillis *
			ThreadLocalRandom.current().nextDouble(0.75, 1.25)));
	}

	CompletableFuture<String> pressEscape()
	{
		CompletableFuture<String> result = new CompletableFuture<>();
		synchronized (this)
		{
			if (closed)
			{
				result.completeExceptionally(new IllegalStateException("Synthetic keyboard is closed"));
				return result;
			}
			if (typing)
			{
				result.completeExceptionally(new IllegalStateException("Synthetic keyboard is already typing"));
				return result;
			}
			if (canvas == null || !canvas.isShowing())
			{
				result.completeExceptionally(new IllegalStateException("Client canvas is unavailable"));
				return result;
			}
		}
		runOnEdt(() -> dispatchKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, false));
		result.complete("SYNTHETIC_ESCAPE");
		return result;
	}

	synchronized boolean isTyping()
	{
		return typing;
	}

	private void dispatchCharacter(char character)
	{
		int keyCode = KeyEvent.getExtendedKeyCodeForChar(character);
		runOnEdt(() -> dispatchKey(keyCode, character, true));
	}

	private void dispatchEnter()
	{
		runOnEdt(() -> dispatchKey(KeyEvent.VK_ENTER, '\n', false));
	}

	private void dispatchKey(int keyCode, char character, boolean typed)
	{
		long when = System.currentTimeMillis();
		eventDispatcher.accept(new KeyEvent(
			canvas, KeyEvent.KEY_PRESSED, when, 0, keyCode, character));
		if (typed)
		{
			eventDispatcher.accept(new KeyEvent(
				canvas, KeyEvent.KEY_TYPED, when, 0, KeyEvent.VK_UNDEFINED, character));
		}
		eventDispatcher.accept(new KeyEvent(
			canvas, KeyEvent.KEY_RELEASED, when, 0, keyCode, character));
	}

	private void schedule(Runnable runnable, long delayMillis)
	{
		ScheduledFuture<?> future = executor.schedule(() ->
		{
			synchronized (GenericClientSyntheticKeyboard.this)
			{
				if (closed || !typing)
				{
					return;
				}
			}
			try
			{
				runnable.run();
			}
			catch (RuntimeException exception)
			{
				fail(exception);
			}
		}, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
		pending.add(future);
	}

	private synchronized void fail(RuntimeException exception)
	{
		if (!typing)
		{
			return;
		}
		typing = false;
		for (ScheduledFuture<?> future : pending)
		{
			future.cancel(false);
		}
		pending.clear();
		CompletableFuture<String> result = activeResult;
		activeResult = null;
		if (result != null)
		{
			result.completeExceptionally(exception);
		}
	}

	private void finish(CompletableFuture<String> result, String text, boolean submitted)
	{
		synchronized (this)
		{
			if (activeResult != result)
			{
				return;
			}
			typing = false;
			activeResult = null;
			pending.removeIf(ScheduledFuture::isDone);
		}
		result.complete((submitted ? "SYNTHETIC_TEXT_SUBMITTED" : "SYNTHETIC_TEXT_TYPED") +
			" characters=" + text.length());
		reporter.accept((submitted ? "SYNTHETIC_TEXT_SUBMITTED" : "SYNTHETIC_TEXT_TYPED") +
			" characters=" + text.length());
	}

	private static void runOnEdt(Runnable runnable)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			runnable.run();
			return;
		}
		try
		{
			SwingUtilities.invokeAndWait(runnable);
		}
		catch (Exception exception)
		{
			throw new IllegalStateException("Unable to dispatch synthetic keyboard input", exception);
		}
	}

	@Override
	public synchronized void close()
	{
		closed = true;
		for (ScheduledFuture<?> future : pending)
		{
			future.cancel(false);
		}
		pending.clear();
		typing = false;
		CompletableFuture<String> result = activeResult;
		activeResult = null;
		if (result != null)
		{
			result.completeExceptionally(new IllegalStateException("Synthetic keyboard is closed"));
		}
	}
}
