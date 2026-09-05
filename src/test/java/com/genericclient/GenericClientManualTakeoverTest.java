package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Canvas;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class GenericClientManualTakeoverTest
{
	@Test
	public void physicalMousePausesThenResumesWithoutStoppingAutomation()
	{
		List<String> events = new ArrayList<>();
		FakeRuntime runtime = new FakeRuntime(events, true, true);
		GenericClientManualTakeover takeover = new GenericClientManualTakeover(
			runtime, events::add);

		takeover.onPhysicalMouseMovement(new Point(41, 73));

		assertTrue(takeover.isActive());
		assertEquals(1, runtime.pauses);
		assertEquals(0, runtime.resumes);
		assertEquals(0, runtime.stops);

		runtime.runScheduled();

		assertFalse(takeover.isActive());
		assertEquals(1, runtime.pauses);
		assertEquals(1, runtime.resumes);
		assertEquals(0, runtime.stops);
		assertTrue(events.stream().anyMatch(value -> value.startsWith("MANUAL_MOUSE_RELEASED")));
	}

	@Test
	public void continuedMouseMovementExtendsThePreemptionWindow()
	{
		List<String> events = new ArrayList<>();
		FakeRuntime runtime = new FakeRuntime(events, true, true);
		GenericClientManualTakeover takeover = new GenericClientManualTakeover(
			runtime, events::add);

		takeover.onPhysicalMouseMovement(new Point(10, 20));
		takeover.onPhysicalMouseMovement(new Point(30, 40));
		runtime.runScheduled();

		assertEquals(1, runtime.pauses);
		assertEquals(1, runtime.resumes);
		assertEquals(0, runtime.stops);
	}

	@Test
	public void onlyPhysicalEscapeStopsAutomation()
	{
		List<String> events = new ArrayList<>();
		FakeRuntime runtime = new FakeRuntime(events, true, true);
		GenericClientManualTakeover takeover = new GenericClientManualTakeover(
			runtime, events::add);
		Canvas canvas = new Canvas();

		takeover.keyPressed(new GenericClientSyntheticKeyEvent(
			canvas,
			KeyEvent.KEY_PRESSED,
			1L,
			0,
			KeyEvent.VK_ESCAPE,
			KeyEvent.CHAR_UNDEFINED));
		assertEquals(0, runtime.stops);

		KeyEvent physicalEscape = new KeyEvent(
			canvas,
			KeyEvent.KEY_PRESSED,
			2L,
			0,
			KeyEvent.VK_ESCAPE,
			KeyEvent.CHAR_UNDEFINED);
		takeover.keyPressed(physicalEscape);

		assertTrue(physicalEscape.isConsumed());
		assertTrue(takeover.isActive());
		assertEquals(1, runtime.stops);
		assertTrue(events.contains("cancel:manual_escape"));

		runtime.activate();
		takeover.resetForAutomationStart();
		assertFalse(takeover.isActive());
	}

	private static final class FakeRuntime implements GenericClientManualTakeover.Runtime
	{
		private final List<String> events;
		private final List<ScheduledAction> scheduled = new ArrayList<>();
		private boolean automationActive;
		private boolean inputActive;
		private int pauses;
		private int resumes;
		private int stops;

		private FakeRuntime(
			List<String> events,
			boolean automationActive,
			boolean inputActive)
		{
			this.events = events;
			this.automationActive = automationActive;
			this.inputActive = inputActive;
		}

		@Override
		public boolean isAutomationActive()
		{
			return automationActive;
		}

		@Override
		public boolean hasActiveInput()
		{
			return inputActive;
		}

		@Override
		public CompletableFuture<String> pauseAutomation()
		{
			pauses++;
			inputActive = false;
			events.add("pause");
			return CompletableFuture.completedFuture("SCRIPT_PAUSED");
		}

		@Override
		public CompletableFuture<String> resumeAutomation()
		{
			resumes++;
			inputActive = true;
			events.add("resume");
			return CompletableFuture.completedFuture("SCRIPT_RESUMED");
		}

		@Override
		public void cancelActiveActions(String reason)
		{
			events.add("cancel:" + reason);
		}

		@Override
		public CompletableFuture<String> stopAutomation()
		{
			stops++;
			automationActive = false;
			inputActive = false;
			events.add("stop");
			return CompletableFuture.completedFuture("SCRIPT_STOPPED");
		}

		@Override
		public GenericClientManualTakeover.Cancellable schedule(
			Runnable action,
			long delayMillis)
		{
			assertEquals(GenericClientManualTakeover.MOUSE_IDLE_MILLIS, delayMillis);
			ScheduledAction scheduledAction = new ScheduledAction(action);
			scheduled.add(scheduledAction);
			return () -> scheduledAction.cancelled = true;
		}

		private void activate()
		{
			automationActive = true;
			inputActive = true;
		}

		private void runScheduled()
		{
			List<ScheduledAction> ready = new ArrayList<>(scheduled);
			scheduled.clear();
			for (ScheduledAction action : ready)
			{
				if (!action.cancelled)
				{
					action.action.run();
				}
			}
		}
	}

	private static final class ScheduledAction
	{
		private final Runnable action;
		private boolean cancelled;

		private ScheduledAction(Runnable action)
		{
			this.action = action;
		}
	}
}
