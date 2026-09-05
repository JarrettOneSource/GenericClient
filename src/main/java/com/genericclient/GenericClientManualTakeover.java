package com.genericclient;

import static com.genericclient.GenericClientErrors.rootMessage;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.runelite.client.input.KeyListener;

final class GenericClientManualTakeover implements KeyListener
{
	static final long MOUSE_IDLE_MILLIS = 1_500L;
	private static final String ESCAPE_REASON = "manual_escape";

	private final Runtime runtime;
	private final Consumer<String> reporter;
	private boolean active;
	private boolean pauseInFlight;
	private boolean paused;
	private boolean escapeInFlight;
	private long cycle;
	private long mouseSequence;
	private Cancellable mouseRelease;

	GenericClientManualTakeover(Runtime runtime, Consumer<String> reporter)
	{
		this.runtime = runtime;
		this.reporter = reporter;
	}

	void onPhysicalMouseMovement(Point point)
	{
		boolean firstActivity;
		boolean shouldPause;
		long currentCycle;
		long currentSequence;
		synchronized (this)
		{
			firstActivity = !active;
			if (firstActivity)
			{
				active = true;
				cycle++;
			}
			currentCycle = cycle;
			currentSequence = ++mouseSequence;
			if (mouseRelease != null)
			{
				mouseRelease.cancel();
			}
			mouseRelease = runtime.schedule(
				() -> releaseMouse(currentCycle, currentSequence), MOUSE_IDLE_MILLIS);
			shouldPause = !pauseInFlight && !paused &&
				(runtime.isAutomationActive() || runtime.hasActiveInput());
			if (shouldPause)
			{
				pauseInFlight = true;
			}
		}

		if (firstActivity)
		{
			reporter.accept("MANUAL_MOUSE_PREEMPTION x=" + point.x + " y=" + point.y);
		}
		if (shouldPause)
		{
			pause(currentCycle);
		}
	}

	@Override
	public void keyPressed(KeyEvent event)
	{
		if (event instanceof GenericClientSyntheticKeyEvent ||
			event.getKeyCode() != KeyEvent.VK_ESCAPE)
		{
			return;
		}
		event.consume();
		stopForEscape();
	}

	@Override
	public void keyReleased(KeyEvent event)
	{
	}

	@Override
	public void keyTyped(KeyEvent event)
	{
	}

	@Override
	public boolean isEnabledOnLoginScreen()
	{
		return true;
	}

	synchronized void resetForAutomationStart()
	{
		cycle++;
		mouseSequence++;
		active = false;
		pauseInFlight = false;
		paused = false;
		escapeInFlight = false;
		if (mouseRelease != null)
		{
			mouseRelease.cancel();
			mouseRelease = null;
		}
	}

	synchronized boolean isActive()
	{
		return active;
	}

	synchronized void close()
	{
		cycle++;
		mouseSequence++;
		active = false;
		pauseInFlight = false;
		paused = false;
		if (mouseRelease != null)
		{
			mouseRelease.cancel();
			mouseRelease = null;
		}
	}

	private void pause(long expectedCycle)
	{
		final CompletableFuture<String> pause;
		try
		{
			pause = runtime.pauseAutomation();
		}
		catch (RuntimeException exception)
		{
			pauseFinished(expectedCycle, null, exception);
			return;
		}
		pause.whenComplete((result, error) -> pauseFinished(expectedCycle, result, error));
	}

	private void pauseFinished(long expectedCycle, String result, Throwable error)
	{
		boolean resume;
		synchronized (this)
		{
			if (expectedCycle != cycle)
			{
				return;
			}
			pauseInFlight = false;
			paused = error == null;
			resume = paused && !active;
			if (resume)
			{
				paused = false;
			}
		}
		reporter.accept(error == null
			? "MANUAL_MOUSE_PAUSED result=" + result
			: "MANUAL_MOUSE_PAUSE_FAILED message=" + rootMessage(error));
		if (resume)
		{
			resume(expectedCycle);
		}
	}

	private void releaseMouse(long expectedCycle, long expectedSequence)
	{
		boolean resume;
		synchronized (this)
		{
			if (expectedCycle != cycle || expectedSequence != mouseSequence)
			{
				return;
			}
			mouseRelease = null;
			active = false;
			resume = paused;
			paused = false;
		}
		reporter.accept("MANUAL_MOUSE_RELEASED idleMillis=" + MOUSE_IDLE_MILLIS);
		if (resume)
		{
			resume(expectedCycle);
		}
	}

	private void resume(long expectedCycle)
	{
		final CompletableFuture<String> resumed;
		try
		{
			resumed = runtime.resumeAutomation();
		}
		catch (RuntimeException exception)
		{
			reporter.accept("MANUAL_MOUSE_RESUME_FAILED message=" + rootMessage(exception));
			return;
		}
		resumed.whenComplete((result, error) ->
		{
			synchronized (GenericClientManualTakeover.this)
			{
				if (expectedCycle != cycle)
				{
					return;
				}
			}
			reporter.accept(error == null
				? "MANUAL_MOUSE_RESUMED result=" + result
				: "MANUAL_MOUSE_RESUME_FAILED message=" + rootMessage(error));
		});
	}

	private void stopForEscape()
	{
		synchronized (this)
		{
			if (escapeInFlight)
			{
				return;
			}
			escapeInFlight = true;
			active = true;
			cycle++;
			mouseSequence++;
			pauseInFlight = false;
			paused = false;
			if (mouseRelease != null)
			{
				mouseRelease.cancel();
				mouseRelease = null;
			}
		}

		runtime.cancelActiveActions(ESCAPE_REASON);
		reporter.accept("MANUAL_ESCAPE");
		final CompletableFuture<String> stopped;
		try
		{
			stopped = runtime.stopAutomation();
		}
		catch (RuntimeException exception)
		{
			escapeFinished(null, exception);
			return;
		}
		stopped.whenComplete(this::escapeFinished);
	}

	private void escapeFinished(String result, Throwable error)
	{
		synchronized (this)
		{
			escapeInFlight = false;
		}
		reporter.accept(error == null
			? "MANUAL_ESCAPE_COMPLETE result=" + result
			: "MANUAL_ESCAPE_FAILED message=" + rootMessage(error));
	}


	interface Cancellable
	{
		void cancel();
	}

	interface Runtime
	{
		boolean isAutomationActive();

		boolean hasActiveInput();

		CompletableFuture<String> pauseAutomation();

		CompletableFuture<String> resumeAutomation();

		void cancelActiveActions(String reason);

		CompletableFuture<String> stopAutomation();

		Cancellable schedule(Runnable action, long delayMillis);
	}
}
