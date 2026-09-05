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
		return type(text, true, 0L, GenericClientActivityContext.none());
	}

	CompletableFuture<String> typeAndEnter(String text, long initialDelayMillis)
	{
		return type(text, true, initialDelayMillis, GenericClientActivityContext.none());
	}

	CompletableFuture<String> type(String text)
	{
		return type(text, false, 0L, GenericClientActivityContext.none());
	}

	CompletableFuture<String> type(
		String text,
		boolean submit,
		long initialDelayMillis, GenericClientActivityContext context)
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

		CompletableFuture<String> result = beginInput(context);
		if (result.isDone()) return result;
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
			schedule(() -> dispatchCharacter(character), nextDelay, result, context);
			nextDelay += jitteredInterval(intervalMillis);
		}
		if (submit)
		{
			schedule(this::dispatchEnter, nextDelay, result, context);
			nextDelay += jitteredInterval(intervalMillis);
		}
		schedule(() -> finish(result,
			(submit ? "SYNTHETIC_TEXT_SUBMITTED" : "SYNTHETIC_TEXT_TYPED") + " characters=" + text.length()),
			nextDelay, result, context);
		return result;
	}

	private synchronized CompletableFuture<String> beginInput(GenericClientActivityContext context)
	{
		if (closed || !context.isInputAllowed())
			return CompletableFuture.failedFuture(new IllegalStateException(closed ? "Synthetic keyboard is closed" : "action_cancelled"));
		if (typing)
			return CompletableFuture.failedFuture(new IllegalStateException("Synthetic keyboard is already typing"));
		if (canvas == null || !canvas.isShowing())
			return CompletableFuture.failedFuture(new IllegalStateException("Client canvas is unavailable"));
		typing = true;
		activeResult = new CompletableFuture<>();
		return activeResult;
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
		return pressEscape(GenericClientActivityContext.none());
	}

	CompletableFuture<String> pressEscape(GenericClientActivityContext context)
	{
		CompletableFuture<String> result = beginInput(context);
		if (result.isDone()) return result;
		dispatch(result, context, () -> dispatchKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, false));
		finish(result, "SYNTHETIC_ESCAPE");
		return result;
	}

	CompletableFuture<String> pressSpace(long initialDelayMillis)
	{
		return pressSpace(initialDelayMillis, GenericClientActivityContext.none());
	}

	CompletableFuture<String> pressSpace(long initialDelayMillis, GenericClientActivityContext context)
	{
		return pressKey(KeyEvent.VK_SPACE, "SPACE", initialDelayMillis, context);
	}

	CompletableFuture<String> pressDigit(int digit, long initialDelayMillis)
	{
		return pressDigit(digit, initialDelayMillis, GenericClientActivityContext.none());
	}

	CompletableFuture<String> pressDigit(int digit, long initialDelayMillis, GenericClientActivityContext context)
	{
		if (digit < 1 || digit > 9)
		{
			throw new IllegalArgumentException("Dialogue option key must be between 1 and 9");
		}
		return pressKey(KeyEvent.VK_0 + digit, Integer.toString(digit), initialDelayMillis, context);
	}

	private CompletableFuture<String> pressKey(
		int keyCode,
		String label,
		long initialDelayMillis, GenericClientActivityContext context)
	{
		if (initialDelayMillis < 0L || initialDelayMillis > 10_000L)
		{
			throw new IllegalArgumentException("Synthetic keyboard delay must be between 0 and 10000ms");
		}
		CompletableFuture<String> result = beginInput(context);
		if (result.isDone()) return result;
		reporter.accept("SYNTHETIC_KEY_STARTED key=" + label +
			" initialDelayMs=" + initialDelayMillis);
		schedule(() -> dispatchKey(keyCode, KeyEvent.CHAR_UNDEFINED, false), initialDelayMillis, result, context);
		schedule(() -> finish(result, "SYNTHETIC_KEY_PRESSED key=" + label), initialDelayMillis + 25L, result, context);
		return result;
	}

	synchronized boolean isTyping()
	{
		return typing;
	}

	void cancel(String reason)
	{
		CompletableFuture<String> result;
		synchronized (this)
		{
			if (!typing) return;
			result = detachTyping();
		}
		if (result != null) result.completeExceptionally(new IllegalStateException("Synthetic keyboard cancelled: " + reason));
		reporter.accept("SYNTHETIC_KEYBOARD_CANCELLED reason=" + reason);
	}

	private CompletableFuture<String> detachTyping()
	{
		typing = false;
		for (ScheduledFuture<?> future : pending) future.cancel(false);
		pending.clear();
		CompletableFuture<String> result = activeResult;
		activeResult = null;
		return result;
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
		eventDispatcher.accept(new GenericClientSyntheticKeyEvent(
			canvas, KeyEvent.KEY_PRESSED, when, 0, keyCode, character));
		if (typed)
		{
			eventDispatcher.accept(new GenericClientSyntheticKeyEvent(
				canvas, KeyEvent.KEY_TYPED, when, 0, KeyEvent.VK_UNDEFINED, character));
		}
		eventDispatcher.accept(new GenericClientSyntheticKeyEvent(
			canvas, KeyEvent.KEY_RELEASED, when, 0, keyCode, character));
	}

	private void schedule(Runnable runnable, long delayMillis, CompletableFuture<String> owner,
		GenericClientActivityContext context)
	{
		pending.add(executor.schedule(() -> dispatch(owner, context, runnable),
			Math.max(0L, delayMillis), TimeUnit.MILLISECONDS));
	}

	private void dispatch(CompletableFuture<String> owner, GenericClientActivityContext context, Runnable action)
	{
		try
		{
			runOnEdt(() ->
			{
				synchronized (this)
				{
					if (closed || !typing || activeResult != owner) return;
				}
				if (!context.isInputAllowed())
				{
					fail(owner, new IllegalStateException("action_cancelled"));
					return;
				}
				action.run();
			});
		}
		catch (RuntimeException exception) { fail(owner, exception); }
	}

	private void fail(CompletableFuture<String> owner, RuntimeException exception)
	{
		CompletableFuture<String> result;
		synchronized (this)
		{
			if (!typing || activeResult != owner) return;
			result = detachTyping();
		}
		if (result != null) result.completeExceptionally(exception);
	}

	private void finish(CompletableFuture<String> owner, String receipt)
	{
		synchronized (this)
		{
			if (activeResult != owner) return;
			detachTyping();
		}
		owner.complete(receipt);
		reporter.accept(receipt);
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
	public void close()
	{
		CompletableFuture<String> result;
		synchronized (this)
		{
			closed = true;
			result = detachTyping();
		}
		if (result != null) result.completeExceptionally(new IllegalStateException("Synthetic keyboard is closed"));
	}
}
