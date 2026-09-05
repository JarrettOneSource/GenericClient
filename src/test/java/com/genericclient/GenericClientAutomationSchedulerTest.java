package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientAutomationSchedulerTest
{
	private static final String PROFILE = "0123456789abcdef";

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void selectsByPriorityThenKeepsTheExistingRuleLease() throws Exception
	{
		ManualClock clock = new ManualClock(Instant.parse("2026-08-31T12:00:00Z"));
		FakeRuntime runtime = new FakeRuntime("low-script", "high-script");
		GenericClientAutomationScheduler scheduler = scheduler("sticky", runtime, clock);
		try
		{
			scheduler.activateProfile(PROFILE).get(2, TimeUnit.SECONDS);
			scheduler.configure(config(twoRuleJson())).get(2, TimeUnit.SECONDS);
			scheduler.publishGameTick(snapshot(1, 20, 1_000L, true));
			waitFor(() -> "low".equals(runtime.state.getRuleId()));
			assertEquals("stops=" + runtime.stops.get() + " reasons=" + runtime.stopReasons +
				" status=" + scheduler.status(),
				1, runtime.starts.get());

			scheduler.publishGameTick(snapshot(2, 20, 0L, true));
			waitFor(() -> "running".equals(scheduler.status().get("mode")));
			assertEquals("low", runtime.state.getRuleId());
			assertEquals(1, runtime.starts.get());
		}
		finally
		{
			scheduler.close();
		}
	}

	@Test
	public void manualRunWinsAndManualStopPausesScheduling() throws Exception
	{
		ManualClock clock = new ManualClock(Instant.parse("2026-08-31T12:00:00Z"));
		FakeRuntime runtime = new FakeRuntime("low-script");
		runtime.startManual("low-script");
		GenericClientAutomationScheduler scheduler = scheduler("manual", runtime, clock);
		try
		{
			scheduler.activateProfile(PROFILE).get(2, TimeUnit.SECONDS);
			scheduler.configure(config(oneRuleJson("PT10M"))).get(2, TimeUnit.SECONDS);
			scheduler.publishGameTick(snapshot(1, 20, 1_000L, true));
			waitFor(() -> "manual".equals(scheduler.status().get("mode")));
			assertEquals(0, runtime.starts.get());

			runtime.manualStop();
			waitFor(() -> Boolean.TRUE.equals(scheduler.status().get("paused")));
			assertEquals("paused", scheduler.status().get("mode"));

			scheduler.setPaused(false, "test").get(2, TimeUnit.SECONDS);
			scheduler.publishGameTick(snapshot(2, 20, 1_000L, true));
			waitFor(() -> "low".equals(runtime.state.getRuleId()));
			assertEquals("stops=" + runtime.stops.get() + " reasons=" + runtime.stopReasons +
				" status=" + scheduler.status(),
				1, runtime.starts.get());
		}
		finally
		{
			scheduler.close();
		}
	}

	@Test
	public void randomEventAttentionBlocksAndStopsScheduledAutomation() throws Exception
	{
		ManualClock clock = new ManualClock(Instant.parse("2026-08-31T12:00:00Z"));
		FakeRuntime runtime = new FakeRuntime("low-script");
		GenericClientAutomationScheduler scheduler = scheduler("random-event", runtime, clock);
		try
		{
			scheduler.activateProfile(PROFILE).get(2, TimeUnit.SECONDS);
			scheduler.configure(config(oneRuleJson("PT10M"))).get(2, TimeUnit.SECONDS);
			scheduler.setAttentionRequired(true, "random_event");
			scheduler.publishGameTick(snapshot(1, 20, 1_000L, true));
			waitFor(() -> "attention_required".equals(scheduler.status().get("mode")));
			assertEquals(0, runtime.starts.get());

			scheduler.setAttentionRequired(false, "random_event_completed");
			waitFor(() -> runtime.starts.get() == 1);
			assertEquals("low", runtime.state.getRuleId());

			scheduler.setAttentionRequired(true, "second_random_event");
			waitFor(() -> runtime.stops.get() == 1 &&
				"attention_required".equals(scheduler.status().get("mode")));
			assertEquals("attention_required", scheduler.status().get("mode"));
			assertFalse(runtime.state.isRunning());
		}
		finally
		{
			scheduler.close();
		}
	}

	@Test
	public void terminalCooldownSurvivesSchedulerRestart() throws Exception
	{
		Path directory = temporaryFolder.newFolder("cooldown").toPath();
		ManualClock clock = new ManualClock(Instant.parse("2026-08-31T12:00:00Z"));
		FakeRuntime firstRuntime = new FakeRuntime("low-script");
		GenericClientAutomationScheduler first = scheduler(directory, firstRuntime, clock);
		first.activateProfile(PROFILE).get(2, TimeUnit.SECONDS);
		first.configure(config(oneRuleJson("PT10M"))).get(2, TimeUnit.SECONDS);
		first.publishGameTick(snapshot(1, 20, 1_000L, true));
		waitFor(() -> firstRuntime.starts.get() == 1);
		firstRuntime.complete("COMPLETED");
		first.publishGameTick(snapshot(2, 20, 1_000L, true));
		waitFor(() -> String.valueOf(first.status().get("last_event")).contains("run_completed"));
		assertEquals(1, firstRuntime.starts.get());
		first.close();

		FakeRuntime secondRuntime = new FakeRuntime("low-script");
		GenericClientAutomationScheduler second = scheduler(directory, secondRuntime, clock);
		try
		{
			second.activateProfile(PROFILE).get(2, TimeUnit.SECONDS);
			second.publishGameTick(snapshot(3, 20, 1_000L, true));
			Thread.sleep(40L);
			assertEquals(0, secondRuntime.starts.get());

			clock.advance(Duration.ofMinutes(11));
			second.publishGameTick(snapshot(4, 20, 1_000L, true));
			waitFor(() -> secondRuntime.starts.get() == 1);
			secondRuntime.complete("COMPLETED");
			second.publishGameTick(snapshot(5, 20, 1_000L, true));
			waitFor(() -> String.valueOf(second.status().get("last_event")).contains("run_completed"));
			assertEquals(1, secondRuntime.starts.get());
		}
		finally
		{
			second.close();
		}
	}

	@Test
	public void stopsItsOwnedRunWhenTheScheduleCloses() throws Exception
	{
		ManualClock clock = new ManualClock(Instant.parse("2026-08-31T16:59:00Z"));
		FakeRuntime runtime = new FakeRuntime("low-script");
		GenericClientAutomationScheduler scheduler = scheduler("closure", runtime, clock);
		try
		{
			scheduler.activateProfile(PROFILE).get(2, TimeUnit.SECONDS);
			scheduler.configure(config(oneRuleJson("PT10M"))).get(2, TimeUnit.SECONDS);
			scheduler.publishGameTick(snapshot(1, 20, 1_000L, true));
			waitFor(() -> runtime.starts.get() == 1);

			clock.set(Instant.parse("2026-08-31T17:00:00Z"));
			scheduler.publishGameTick(snapshot(2, 20, 1_000L, true));
			waitFor(() -> runtime.stops.get() == 1);
			assertFalse(runtime.state.isRunning());
		}
		finally
		{
			scheduler.close();
		}
	}

	@Test
	public void reevaluatesAtTheWallClockBoundaryWithoutAnotherGameTick() throws Exception
	{
		ManualClock clock = new ManualClock(Instant.parse("2026-08-31T07:59:59.800Z"));
		FakeRuntime runtime = new FakeRuntime("low-script");
		GenericClientAutomationScheduler scheduler = scheduler("boundary", runtime, clock);
		try
		{
			scheduler.activateProfile(PROFILE).get(2, TimeUnit.SECONDS);
			scheduler.configure(config(oneRuleJson("PT10M"))).get(2, TimeUnit.SECONDS);
			scheduler.publishGameTick(snapshot(1, 20, 1_000L, true));
			waitFor(() -> Instant.parse("2026-08-31T08:00:00Z").toString()
				.equals(scheduler.status().get("next_transition")));
			assertEquals(0, runtime.starts.get());

			clock.set(Instant.parse("2026-08-31T08:00:00Z"));
			waitFor(() -> runtime.starts.get() == 1);
			assertEquals("low", runtime.state.getRuleId());
		}
		finally
		{
			scheduler.close();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void reportsUnknownCashWithoutStartingTheRule() throws Exception
	{
		ManualClock clock = new ManualClock(Instant.parse("2026-08-31T12:00:00Z"));
		FakeRuntime runtime = new FakeRuntime("cash-script");
		GenericClientAutomationScheduler scheduler = scheduler("unknown", runtime, clock);
		try
		{
			scheduler.activateProfile(PROFILE).get(2, TimeUnit.SECONDS);
			scheduler.configure(config(cashRuleJson())).get(2, TimeUnit.SECONDS);
			scheduler.publishGameTick(snapshot(1, 40, 1_000L, false));
			waitFor(() -> firstRuleReason(scheduler).contains("bank wealth is unknown"));
			assertEquals(0, runtime.starts.get());
			List<Map<String, Object>> rules = (List<Map<String, Object>>) scheduler.status().get("rules");
			assertEquals("unknown", rules.get(0).get("truth"));
			assertTrue(String.valueOf(rules.get(0)),
				String.valueOf(rules.get(0).get("reason")).contains("bank wealth is unknown"));
		}
		finally
		{
			scheduler.close();
		}
	}

	@Test
	public void holdsAnExistingLeaseButDoesNotUseStaleFactsAfterLogout() throws Exception
	{
		ManualClock clock = new ManualClock(Instant.parse("2026-08-31T12:00:00Z"));
		FakeRuntime runtime = new FakeRuntime("low-script");
		GenericClientAutomationScheduler scheduler = scheduler("logout", runtime, clock);
		try
		{
			scheduler.activateProfile(PROFILE).get(2, TimeUnit.SECONDS);
			scheduler.configure(config(oneRuleJson("PT10M"))).get(2, TimeUnit.SECONDS);
			scheduler.publishGameTick(snapshot(1, 20, 1_000L, true));
			waitFor(() -> runtime.starts.get() == 1);

			scheduler.clearSnapshot();
			waitFor(() -> "holding".equals(scheduler.status().get("mode")));
			assertEquals(0, runtime.stops.get());
			assertTrue(String.valueOf(scheduler.status().get("detail")).contains("unavailable"));
		}
		finally
		{
			scheduler.close();
		}
	}

	private GenericClientAutomationScheduler scheduler(
		String directory,
		FakeRuntime runtime,
		ManualClock clock) throws Exception
	{
		return scheduler(temporaryFolder.newFolder(directory).toPath(), runtime, clock);
	}

	private static GenericClientAutomationScheduler scheduler(
		Path directory,
		FakeRuntime runtime,
		ManualClock clock) throws Exception
	{
		return new GenericClientAutomationScheduler(
			new GenericClientAutomationStore(directory, "UTC"),
			runtime,
			new GenericClientRuleEngine(),
			clock,
			Executors.newSingleThreadScheduledExecutor(),
			message -> { });
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> config(String json)
	{
		return new Gson().fromJson(json, Map.class);
	}

	private static String oneRuleJson(String retry)
	{
		return base("[{\"id\":\"low\",\"priority\":10,\"when\":{\"all\":[" +
			"{\"schedule\":\"work\"},{\"fact\":\"skills.strength.level\",\"lt\":30}]}," +
			"\"run\":{\"script\":\"low-script\"},\"retry_after\":\"" + retry + "\"}]");
	}

	private static String twoRuleJson()
	{
		return base("[{\"id\":\"high\",\"priority\":100,\"when\":{\"all\":[" +
			"{\"schedule\":\"work\"},{\"fact\":\"cash.known_total_value\",\"lt\":100}]}," +
			"\"run\":{\"script\":\"high-script\"}},{\"id\":\"low\",\"priority\":10," +
			"\"when\":{\"all\":[{\"schedule\":\"work\"}," +
			"{\"fact\":\"skills.strength.level\",\"lt\":30}]}," +
			"\"run\":{\"script\":\"low-script\"}}]");
	}

	private static String cashRuleJson()
	{
		return base("[{\"id\":\"cash\",\"priority\":10,\"when\":{\"all\":[" +
			"{\"schedule\":\"work\"},{\"fact\":\"cash.known_total_value\",\"lt\":5000000}]}," +
			"\"run\":{\"script\":\"cash-script\"}}]");
	}

	private static String base(String rules)
	{
		return "{\"schema\":\"genericclient_automation.v1\",\"zone\":\"UTC\",\"enabled\":true," +
			"\"schedules\":{\"work\":{\"days\":[\"MONDAY\"],\"windows\":[{" +
			"\"from\":\"08:00\",\"until\":\"17:00\"}]}},\"rules\":" + rules + "}";
	}

	private static GenericClientSnapshot snapshot(
		long tick,
		int strength,
		long cash,
		boolean bankKnown)
	{
		GenericClientAccountSnapshot.ContainerSnapshot inventory =
			new GenericClientAccountSnapshot.ContainerSnapshot(true, 28, Collections.emptyList());
		List<GenericClientAccountSnapshot.ItemSnapshot> bankItems = new ArrayList<>();
		if (bankKnown)
		{
			bankItems.add(new GenericClientAccountSnapshot.ItemSnapshot(
				0, null, net.runelite.api.gameval.ItemID.COINS, (int) cash, "Coins",
				true, true, true, Collections.emptyList()));
		}
		GenericClientAccountSnapshot.BankSnapshot bank = bankKnown
			? new GenericClientAccountSnapshot.BankSnapshot("cached", tick,
				new GenericClientAccountSnapshot.ContainerSnapshot(true, 800, bankItems))
			: GenericClientAccountSnapshot.BankSnapshot.unknown();
		GenericClientAccountSnapshot account = new GenericClientAccountSnapshot(
			true,
			strength,
			Collections.singletonList(new GenericClientAccountSnapshot.SkillSnapshot(
				"strength", strength, strength, 1_000)),
			inventory,
			GenericClientAccountSnapshot.ContainerSnapshot.unavailable(),
			bank,
			GenericClientAccountSnapshot.QuestListSnapshot.unavailable(),
			GenericClientAccountSnapshot.GrandExchangeSnapshot.unavailable(),
			GenericClientAccountSnapshot.CashSnapshot.from(inventory, bank));
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientWorldSnapshot.PlayerSnapshot("Player", 3200, 3200, 0, -1),
			Collections.emptyList(),
			account);
	}

	private static void waitFor(Check check) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (!check.get() && System.nanoTime() < deadline)
		{
			Thread.sleep(10L);
		}
		assertTrue(check.get());
	}

	@SuppressWarnings("unchecked")
	private static String firstRuleReason(GenericClientAutomationScheduler scheduler)
	{
		Object raw = scheduler.status().get("rules");
		if (!(raw instanceof List) || ((List<?>) raw).isEmpty())
		{
			return "";
		}
		return String.valueOf(((Map<String, Object>) ((List<?>) raw).get(0)).get("reason"));
	}

	@FunctionalInterface
	private interface Check
	{
		boolean get();
	}

	private static final class ManualClock extends Clock
	{
		private volatile Instant instant;

		private ManualClock(Instant instant)
		{
			this.instant = instant;
		}

		private void set(Instant instant)
		{
			this.instant = instant;
		}

		private void advance(Duration duration)
		{
			instant = instant.plus(duration);
		}

		@Override
		public ZoneId getZone()
		{
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			return this;
		}

		@Override
		public Instant instant()
		{
			return instant;
		}
	}

	private static final class FakeRuntime implements GenericClientAutomationScheduler.Runtime
	{
		private final List<Map<String, Object>> scripts = new ArrayList<>();
		private final AtomicInteger starts = new AtomicInteger();
		private final AtomicInteger stops = new AtomicInteger();
		private final List<String> stopReasons = new ArrayList<>();
		private long nextId;
		private volatile GenericClientLuaRun.State state = GenericClientLuaRun.State.none();
		private volatile Runnable manualStopListener = () -> { };

		private FakeRuntime(String... scriptIds)
		{
			for (String id : scriptIds)
			{
				Map<String, Object> value = new LinkedHashMap<>();
				value.put("id", id);
				scripts.add(value);
			}
		}

		private void startManual(String scriptId)
		{
			state = new GenericClientLuaRun.State(++nextId, "manual", scriptId, "WAITING", true);
		}

		private void manualStop()
		{
			manualStopListener.run();
			state = GenericClientLuaRun.State.none();
		}

		private void complete(String status)
		{
			state = new GenericClientLuaRun.State(
				state.getRunId(), state.getOwner(), state.getScriptId(), status, false);
		}

		@Override
		public List<Map<String, Object>> listScriptValues()
		{
			return scripts;
		}

		@Override
		public CompletableFuture<List<GenericClientScriptInput>> describe(String scriptId)
		{
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		@Override
		public CompletableFuture<String> startScheduled(
			String ruleId,
			String scriptId,
			Map<String, Object> inputs)
		{
			if (state.isRunning())
			{
				return CompletableFuture.completedFuture("LUA_START_SKIPPED");
			}
			starts.incrementAndGet();
			state = new GenericClientLuaRun.State(
				++nextId, "rule:" + ruleId, scriptId, "WAITING", true);
			return CompletableFuture.completedFuture("LUA_STARTED owner=rule:" + ruleId);
		}

		@Override
		public CompletableFuture<String> stopScheduled(String ruleId, String reason)
		{
			if (ruleId.equals(state.getRuleId()) && state.isRunning())
			{
				stops.incrementAndGet();
				stopReasons.add(reason);
				state = GenericClientLuaRun.State.none();
				return CompletableFuture.completedFuture("LUA_STOPPED");
			}
			return CompletableFuture.completedFuture("LUA_STOP_SKIPPED");
		}

		@Override
		public GenericClientLuaRun.State getRunState()
		{
			return state;
		}

		@Override
		public void setManualStopListener(Runnable listener)
		{
			manualStopListener = listener == null ? () -> { } : listener;
		}
	}
}
