package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientBehaviorControllerTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void aggressiveProfilePausesAfterAParentActionAndMovesOffscreen() throws Exception
	{
		long accountHash = findHash(profile -> profile.getShortReleaseProbability() >= 0.85);
		Fixture fixture = fixture(0.5, 0.0, 0.99);
		try
		{
			fixture.controller.activateAccount(accountHash);
			fixture.controller.setLoggedIn(true);
			CompletableFuture<Map<String, Object>> pause = fixture.controller.afterAction(true);

			assertFalse(pause.isDone());
			assertEquals("micro_break", fixture.controller.status().get("state"));
			assertEquals(1, fixture.effects.offscreenEdges.size());
			assertEquals(GenericClientBehaviorProfile.fromAccountHash(accountHash).getIdleEdge(),
				fixture.effects.offscreenEdges.get(0));
			assertTrue(fixture.timer.nextDelayMillis() >= 1_000L);
			assertTrue(fixture.timer.nextDelayMillis() < 120_000L);

			fixture.timer.runNext();
			assertEquals("completed", pause.get().get("status"));
			assertEquals("ready", fixture.controller.status().get("state"));
			assertEquals(1L, fixture.controller.status().get("micro_break_count"));
		}
		finally
		{
			fixture.controller.close();
		}
	}

	@Test
	public void breaksFalseBypassesBothSidesOfAnAction() throws Exception
	{
		Fixture fixture = fixture(0.5, 0.0, 0.0);
		try
		{
			fixture.controller.activateAccount(42L);
			assertEquals("bypassed", fixture.controller.beforeAction(false).get().get("status"));
			assertEquals("bypassed", fixture.controller.afterAction(false).get().get("status"));
			assertEquals(0, fixture.effects.offscreenEdges.size());
			assertEquals(0, fixture.timer.size());
		}
		finally
		{
			fixture.controller.close();
		}
	}

	@Test
	public void microBreakDoesNotResetLongPressure() throws Exception
	{
		long accountHash = findHash(profile -> profile.getShortReleaseProbability() >= 0.85);
		Fixture fixture = fixture(0.75, 0.0, 0.99);
		try
		{
			fixture.controller.activateAccount(accountHash);
			fixture.controller.setLoggedIn(true);
			advanceActive(fixture, 120_000L);
			long before = ((Number) fixture.controller.status()
				.get("active_millis_since_long_break")).longValue();

			CompletableFuture<Map<String, Object>> pause = fixture.controller.afterAction(true);
			fixture.timer.runNext();
			pause.get();

			long after = ((Number) fixture.controller.status()
				.get("active_millis_since_long_break")).longValue();
			assertEquals(before, after);
		}
		finally
		{
			fixture.controller.close();
		}
	}

	@Test
	public void accumulatedLongPressureFiresAtTheNextEligibleBoundary() throws Exception
	{
		long accountHash = findHash(profile -> profile.getLongCadenceMinutes() < 55.0 &&
			profile.getFavoredLongBreakMode() == GenericClientBehaviorProfile.LongBreakMode.LOGOUT);
		Fixture fixture = fixture(0.000000000001, 0.99, 0.5);
		try
		{
			fixture.controller.activateAccount(accountHash);
			fixture.controller.setLoggedIn(true);
			advanceActive(fixture, 25 * 60_000L);

			assertEquals("bypassed", fixture.controller.afterAction(false).get().get("status"));
			CompletableFuture<Map<String, Object>> longBreak = fixture.controller.afterAction(true);
			assertFalse(longBreak.isDone());
			assertEquals("long_break", fixture.controller.status().get("state"));
			assertTrue(fixture.timer.nextDelayMillis() >= 3 * 60_000L);
			assertTrue(fixture.timer.nextDelayMillis() <= 60 * 60_000L);

			fixture.timer.runNext();
			assertEquals("completed", longBreak.get().get("status"));
			assertEquals(0L, fixture.controller.status().get("active_millis_since_long_break"));
			assertEquals(1L, fixture.controller.status().get("long_break_count"));
			assertEquals(1, fixture.effects.ensureLoginCalls);
			assertEquals(1, fixture.effects.logoutCalls);
			assertEquals(2, fixture.effects.offscreenEdges.size());
			assertEquals("suppressed_after_long_break",
				fixture.controller.afterAction(true).get().get("status"));
			assertEquals(0L, fixture.controller.status().get("micro_break_count"));
		}
		finally
		{
			fixture.controller.close();
		}
	}

	@Test
	public void phaseRollsHeavilyThenHonorsGlobalAndNamedCooldowns() throws Exception
	{
		long accountHash = findHash(profile ->
			profile.getShortReleaseProbability() >= 0.30 && profile.getPhaseShortChances() >= 2.0);
		Fixture fixture = fixture(0.9, 0.0, 0.99);
		try
		{
			fixture.controller.activateAccount(accountHash);
			fixture.controller.setLoggedIn(true);
			CompletableFuture<Map<String, Object>> first =
				fixture.controller.enterPhase("banking.complete", true);
			assertFalse(first.isDone());
			fixture.timer.runNext();
			first.get();

			Map<String, Object> cooldown = fixture.controller
				.enterPhase("banking.complete", true).get();
			assertEquals("phase_cooldown", cooldown.get("status"));
			assertEquals(1L, fixture.controller.status().get("micro_break_count"));
		}
		finally
		{
			fixture.controller.close();
		}
	}

	@Test
	public void persistedFutureBreakIsRestoredAndCompleted() throws Exception
	{
		long accountHash = findHash(profile -> profile.getShortReleaseProbability() >= 0.85);
		Path directory = temporaryFolder.newFolder("restored").toPath();
		ManualClock clock = new ManualClock();
		ManualTimer firstTimer = new ManualTimer();
		FakeEffects firstEffects = new FakeEffects();
		GenericClientBehaviorController first = new GenericClientBehaviorController(
			new GenericClientBehaviorStore(directory),
			firstEffects,
			firstTimer,
			clock,
			new SequenceRandom(0.5, 0.0, 0.99),
			message -> { });
		first.activateAccount(accountHash);
		first.afterAction(true);
		assertEquals("micro_break", first.status().get("state"));
		first.close();

		ManualTimer secondTimer = new ManualTimer();
		GenericClientBehaviorController restored = new GenericClientBehaviorController(
			new GenericClientBehaviorStore(directory),
			new FakeEffects(),
			secondTimer,
			clock,
			new SequenceRandom(0.5),
			message -> { });
		try
		{
			restored.activateAccount(accountHash);
			assertEquals("micro_break", restored.status().get("state"));
			assertTrue(secondTimer.nextDelayMillis() > 0L);
			secondTimer.runNext();
			assertEquals("ready", restored.status().get("state"));
		}
		finally
		{
			restored.close();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void manualOverridesApplyImmediatelyAndResetToTheSeed() throws Exception
	{
		Fixture fixture = fixture(0.5);
		try
		{
			fixture.controller.activateAccount(4242L);
			Map<String, Object> seeded = (Map<String, Object>) fixture.controller.status().get("profile");
			fixture.controller.saveOverrides(new GenericClientBehaviorOverrides(
				0.95,
				9.0,
				0.30,
				240.0,
				35.0,
				3.5,
				GenericClientBehaviorProfile.LongBreakMode.LOGOUT,
				0.25,
				GenericClientBehaviorProfile.Edge.TOP));

			Map<String, Object> custom = (Map<String, Object>) fixture.controller.status().get("profile");
			assertTrue((Boolean) custom.get("customized"));
			assertEquals(0.95, (Double) custom.get("short_release_probability"), 0.0);
			assertEquals("top", custom.get("idle_edge"));

			fixture.controller.resetOverrides();
			Map<String, Object> restored = (Map<String, Object>) fixture.controller.status().get("profile");
			assertFalse((Boolean) restored.get("customized"));
			assertEquals(seeded.get("short_release_probability"), restored.get("short_release_probability"));
			assertEquals(seeded.get("idle_edge"), restored.get("idle_edge"));
		}
		finally
		{
			fixture.controller.close();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void manualOverridesReloadWithTheSameAccount() throws Exception
	{
		Path directory = temporaryFolder.newFolder("override-reload").toPath();
		GenericClientBehaviorOverrides overrides = new GenericClientBehaviorOverrides(
			0.88,
			7.5,
			0.22,
			300.0,
			40.0,
			3.2,
			GenericClientBehaviorProfile.LongBreakMode.AFK,
			0.18,
			GenericClientBehaviorProfile.Edge.RIGHT);
		GenericClientBehaviorController first = controller(directory);
		first.activateAccount(8080L);
		first.saveOverrides(overrides);
		first.close();

		GenericClientBehaviorController second = controller(directory);
		try
		{
			second.activateAccount(8080L);
			Map<String, Object> profile = (Map<String, Object>) second.status().get("profile");
			assertTrue((Boolean) profile.get("customized"));
			assertEquals(0.88, (Double) profile.get("short_release_probability"), 0.0);
			assertEquals(300.0, (Double) profile.get("long_cadence_minutes"), 0.0);
		}
		finally
		{
			second.close();
		}
	}

	private GenericClientBehaviorController controller(Path directory) throws Exception
	{
		return new GenericClientBehaviorController(
			new GenericClientBehaviorStore(directory),
			new FakeEffects(),
			new ManualTimer(),
			new ManualClock(),
			new SequenceRandom(0.5),
			message -> { });
	}

	private Fixture fixture(double... randomValues) throws Exception
	{
		ManualClock clock = new ManualClock();
		ManualTimer timer = new ManualTimer();
		FakeEffects effects = new FakeEffects();
		GenericClientBehaviorController controller = new GenericClientBehaviorController(
			new GenericClientBehaviorStore(temporaryFolder.newFolder("controller").toPath()),
			effects,
			timer,
			clock,
			new SequenceRandom(randomValues),
			message -> { });
		return new Fixture(controller, clock, timer, effects);
	}

	private static void advanceActive(Fixture fixture, long millis)
	{
		fixture.controller.publishActiveTick();
		long remaining = millis;
		while (remaining > 0L)
		{
			long step = Math.min(5_000L, remaining);
			fixture.clock.advance(step);
			fixture.controller.publishActiveTick();
			remaining -= step;
		}
	}

	private static long findHash(Predicate<GenericClientBehaviorProfile> predicate)
	{
		for (long accountHash = 0L; accountHash < 1_000_000L; accountHash++)
		{
			if (predicate.test(GenericClientBehaviorProfile.fromAccountHash(accountHash)))
			{
				return accountHash;
			}
		}
		throw new AssertionError("Unable to find a matching behavior profile");
	}

	private static final class Fixture
	{
		private final GenericClientBehaviorController controller;
		private final ManualClock clock;
		private final ManualTimer timer;
		private final FakeEffects effects;

		private Fixture(
			GenericClientBehaviorController controller,
			ManualClock clock,
			ManualTimer timer,
			FakeEffects effects)
		{
			this.controller = controller;
			this.clock = clock;
			this.timer = timer;
			this.effects = effects;
		}
	}

	private static final class ManualClock implements GenericClientBehaviorController.Clock
	{
		private long millis = 1_000_000L;
		private long nanos = 1_000_000L;

		@Override
		public long epochMillis()
		{
			return millis;
		}

		@Override
		public long nanoTime()
		{
			return nanos;
		}

		private void advance(long deltaMillis)
		{
			millis += deltaMillis;
			nanos += deltaMillis * 1_000_000L;
		}
	}

	private static final class ManualTimer implements GenericClientBehaviorController.Timer
	{
		private final List<Task> tasks = new ArrayList<>();

		@Override
		public GenericClientBehaviorController.Cancellable schedule(Runnable task, long delayMillis)
		{
			Task value = new Task(task, delayMillis);
			tasks.add(value);
			return () -> value.cancelled = true;
		}

		private int size()
		{
			return tasks.size();
		}

		private long nextDelayMillis()
		{
			for (Task task : tasks)
			{
				if (!task.cancelled)
				{
					return task.delayMillis;
				}
			}
			throw new AssertionError("No scheduled behavior task");
		}

		private void runNext()
		{
			for (Task task : tasks)
			{
				if (!task.cancelled)
				{
					task.cancelled = true;
					task.runnable.run();
					return;
				}
			}
			throw new AssertionError("No scheduled behavior task");
		}
	}

	private static final class Task
	{
		private final Runnable runnable;
		private final long delayMillis;
		private boolean cancelled;

		private Task(Runnable runnable, long delayMillis)
		{
			this.runnable = runnable;
			this.delayMillis = delayMillis;
		}
	}

	private static final class FakeEffects implements GenericClientBehaviorController.BreakEffects
	{
		private final List<GenericClientBehaviorProfile.Edge> offscreenEdges = new ArrayList<>();
		private int logoutCalls;
		private int ensureLoginCalls;

		@Override
		public CompletableFuture<String> moveOffscreen(GenericClientBehaviorProfile.Edge edge)
		{
			offscreenEdges.add(edge);
			return CompletableFuture.completedFuture("offscreen");
		}

		@Override
		public CompletableFuture<String> logout()
		{
			logoutCalls++;
			return CompletableFuture.completedFuture("logged_out");
		}

		@Override
		public CompletableFuture<String> ensureLoggedIn()
		{
			ensureLoginCalls++;
			return CompletableFuture.completedFuture("logged_in");
		}
	}

	private static final class SequenceRandom implements GenericClientBehaviorController.RandomSource
	{
		private final ArrayDeque<Double> values = new ArrayDeque<>();

		private SequenceRandom(double... values)
		{
			for (double value : values)
			{
				this.values.add(value);
			}
		}

		@Override
		public double nextDouble()
		{
			return values.isEmpty() ? 0.5 : values.removeFirst();
		}

		@Override
		public double nextGaussian()
		{
			return 0.0;
		}
	}
}
