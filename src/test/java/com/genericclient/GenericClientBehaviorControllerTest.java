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
	private static final GenericClientActivityContext TRAVEL = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.TRAVEL);
	private static final GenericClientActivityContext COMBAT = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.COMBAT);
	private static final GenericClientActivityContext SKILLING = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.SKILLING);
	private static final GenericClientActivityContext NONE = GenericClientActivityContext.none();

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void invalidNewAccountStateCannotKeepThePreviousAccountOrPreventRetry() throws Exception
	{
		Path directory = temporaryFolder.newFolder("invalid-account-state").toPath();
		String nextId = GenericClientBehaviorProfile.fromAccountHash(99).getId();
		java.nio.file.Files.writeString(directory.resolve("state-" + nextId + ".json"),
			"{\"schema\":\"genericclient_behavior_state.v3\",\"profileId\":\"" + nextId + "\",\"longHazardBudget\":1}");
		try (GenericClientBehaviorController controller = controller(directory))
		{
			controller.activateAccount(42);
			org.junit.Assert.assertThrows(java.io.IOException.class, () -> controller.activateAccount(99));
			assertEquals(false, controller.status().get("available"));
			GenericClientBehaviorStore store = new GenericClientBehaviorStore(directory);
			store.save(new GenericClientBehaviorState(nextId, 1.0, 2.0), 1_000_000);
			controller.activateAccount(99);
			assertEquals(nextId, controller.currentProfile().getId());
			assertEquals(2.0, (Double) controller.status().get("micro_budget"), 0.0);
		}
	}

	@Test
	public void repeatedBoundariesWithoutActiveTimeCannotStartMicroBreaks() throws Exception
	{
		Fixture fixture = fixture(0.5, 0.0, 0.0);
		try
		{
			fixture.controller.activateAccount(findHash(profile -> profile.getMicroBreakProbability() >= 0.85));
			fixture.controller.setLoggedIn(true);
			for (int i = 0; i < 100; i++)
			{
				CompletableFuture<Map<String, Object>> result = fixture.controller.afterAction(TRAVEL);
				assertTrue("Boundaries cannot manufacture active time", result.isDone());
				assertEquals("no_break", result.get().get("status"));
			}
			assertEquals(0, fixture.effects.offscreenEdges.size());
			assertEquals(0L, fixture.controller.status().get("micro_break_count"));
		}
		finally { fixture.controller.close(); }
	}

	@Test
	public void denseAndSparseBoundariesWaitForTheSameSampledActiveTime() throws Exception
	{
		long account = findHash(profile -> profile.getMicroBreakProbability() >= 0.85);
		Fixture dense = fixture(0.5, 0.5);
		Fixture sparse = fixture(0.5, 0.5);
		try
		{
			dense.controller.activateAccount(account);
			sparse.controller.activateAccount(account);
			dense.controller.setLoggedIn(true);
			sparse.controller.setLoggedIn(true);
			double dueMillis = -Math.log(0.5) * 60_000 /
				GenericClientBehaviorProfile.fromAccountHash(account).microPressurePerMinute(TRAVEL.getActivity());
			for (int elapsed = 5_000; elapsed <= 200_000; elapsed += 5_000)
			{
				advanceActive(dense, 5_000);
				CompletableFuture<Map<String, Object>> boundary = dense.controller.afterAction(TRAVEL);
				if (elapsed < dueMillis) assertTrue("No threshold resampling at dense boundaries", boundary.isDone());
				if (!boundary.isDone()) dense.timer.runNext();
			}
			advanceActive(sparse, 200_000);
			assertFalse(sparse.controller.afterAction(TRAVEL).isDone());
			sparse.timer.runNext();
			assertEquals(1L, dense.controller.status().get("micro_break_count"));
			assertEquals(dense.controller.status().get("micro_break_count"), sparse.controller.status().get("micro_break_count"));
			assertEquals(dense.controller.status().get("total_active_millis"), sparse.controller.status().get("total_active_millis"));
			assertEquals(dense.controller.status().get("micro_budget"), sparse.controller.status().get("micro_budget"));
		}
		finally { dense.controller.close(); sparse.controller.close(); }
	}

	@Test
	public void ownedClockUsesActivityRatesAndIgnoresManualAndUnownedTime() throws Exception
	{
		Map<GenericClientActivityContext.Activity, Double> rates = Map.of(
			GenericClientActivityContext.Activity.GENERAL, 0.8,
			GenericClientActivityContext.Activity.QUESTING, 1.0,
			GenericClientActivityContext.Activity.SKILLING, 1.0,
			GenericClientActivityContext.Activity.TRAVEL, 0.6,
			GenericClientActivityContext.Activity.COMBAT, 1.0,
			GenericClientActivityContext.Activity.MANUAL, 0.0);
		for (Map.Entry<GenericClientActivityContext.Activity, Double> entry : rates.entrySet())
		{
			Fixture fixture = fixture(0.5, 0.5);
			try
			{
				fixture.controller.activateAccount(42);
				fixture.controller.setLoggedIn(true);
				GenericClientActivityContext context = GenericClientActivityContext.preset(entry.getKey());
				for (int i = 0; i < 12; i++)
				{
					fixture.clock.advance(5_000);
					fixture.controller.publishActiveTick(true, context);
				}
				double expected = GenericClientBehaviorProfile.fromAccountHash(42).getMicroBreakProbability() * 0.6 * entry.getValue();
				assertEquals(expected, (Double) fixture.controller.status().get("micro_pressure"), 1e-12);
				fixture.clock.advance(5_000);
				fixture.controller.publishActiveTick(false, context);
				fixture.controller.enterPhase("operator.phase", context);
				assertEquals(expected, (Double) fixture.controller.status().get("micro_pressure"), 1e-12);
			}
			finally { fixture.controller.close(); }
		}
	}

	@Test
	public void microPressureCanStartABreakWithCursorRelease() throws Exception
	{
		long accountHash = findHash(profile -> profile.getMicroBreakProbability() >= 0.85);
		Fixture fixture = fixture(0.5, 0.1, 0.5, 0.99, 0.0);
		try
		{
			fixture.controller.activateAccount(accountHash);
			fixture.controller.setLoggedIn(true);
			advanceActive(fixture, 60_000L);
			CompletableFuture<Map<String, Object>> pause = fixture.controller.afterAction(TRAVEL);

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
	@SuppressWarnings("unchecked")
	public void actionBoundariesCannotReleaseTheCursorWithoutABreak() throws Exception
	{
		long accountHash = findHash(profile ->
			profile.getCursorReleaseProbability() >= 0.50 && profile.getMicroBreakProbability() < 0.50);
		Fixture fixture = fixture(0.5, 0.0, 0.99);
		try
		{
			fixture.controller.activateAccount(accountHash);
			Map<String, Object> result = fixture.controller.afterAction(TRAVEL).get();

			assertEquals("no_break", result.get("status"));
			assertFalse(result.containsKey("cursor_release"));
			assertEquals(0, fixture.effects.offscreenEdges.size());
			assertEquals(0, fixture.timer.activeSize());
		}
		finally
		{
			fixture.controller.close();
		}
	}

	@Test
	public void microBreakCanStartWithoutReleasingTheCursor() throws Exception
	{
		long accountHash = findHash(profile -> profile.getMicroBreakProbability() >= 0.85);
		Fixture fixture = fixture(0.5, 0.1, 0.5, 0.99, 0.99);
		try
		{
			fixture.controller.activateAccount(accountHash);
			fixture.controller.setLoggedIn(true);
			advanceActive(fixture, 60_000L);
			CompletableFuture<Map<String, Object>> result = fixture.controller.afterAction(TRAVEL);

			assertFalse(result.isDone());
			assertEquals("micro_break", fixture.controller.status().get("state"));
			assertEquals(0, fixture.effects.offscreenEdges.size());
			fixture.timer.runNext();
			assertEquals("completed", result.get().get("status"));
		}
		finally
		{
			fixture.controller.close();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void skillingDoesNotReleaseTheCursorWithoutAMicroBreak() throws Exception
	{
		Fixture fixture = fixture(0.5, 0.99);
		try
		{
			fixture.controller.activateAccount(42L);
			Map<String, Object> result = fixture.controller.afterAction(SKILLING).get();

			assertEquals("no_break", result.get("status"));
			assertFalse(result.containsKey("cursor_release"));
			assertEquals(0, fixture.effects.offscreenEdges.size());
			assertEquals(0, fixture.timer.activeSize());
		}
		finally
		{
			fixture.controller.close();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void skillingConsidersCursorReleaseOnlyAfterAMicroBreakStarts() throws Exception
	{
		long accountHash = findHash(profile ->
			profile.getMicroBreakProbability() >= 0.85 &&
			profile.getCursorReleaseProbability() >= 0.50);
		Fixture fixture = fixture(0.5, 0.1, 0.5, 0.99, 0.0);
		try
		{
			fixture.controller.activateAccount(accountHash);
			fixture.controller.setLoggedIn(true);
			advanceActive(fixture, 60_000L);
			CompletableFuture<Map<String, Object>> action =
				fixture.controller.afterAction(SKILLING);

			assertFalse(action.isDone());
			assertEquals("micro_break", fixture.controller.status().get("state"));
			assertEquals(1, fixture.effects.offscreenEdges.size());
			fixture.timer.runNext();
			Map<String, Object> result = action.get();
			assertEquals("completed", result.get("status"));
			assertEquals("moved",
				((Map<String, Object>) result.get("cursor_release")).get("status"));
		}
		finally
		{
			fixture.controller.close();
		}
	}

	@Test
	public void combatSuppressesBreaksAndCursorRelease() throws Exception
	{
		Fixture fixture = fixture(0.5, 0.0, 0.0);
		try
		{
			fixture.controller.activateAccount(42L);
			assertEquals("bypassed", fixture.controller.beforeAction(COMBAT).get().get("status"));
			Map<String, Object> result = fixture.controller.afterAction(COMBAT).get();

			assertEquals("bypassed", result.get("status"));
			assertEquals(0, fixture.effects.offscreenEdges.size());
			assertEquals(0, fixture.timer.size());
		}
		finally
		{
			fixture.controller.close();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void resolvedDamageGraceBypassesBoundariesUntilItExpires() throws Exception
	{
		Fixture fixture = fixture(0.0, 0.0, 0.0);
		try
		{
			fixture.controller.activateAccount(42L);
			fixture.signals.set(new GenericClientPolicyResolver.Signals(true, 10, 110,
				false, false, false, false, true));
			GenericClientActivityContext context = TRAVEL.withResolver(fixture.controller.policies);
			assertEquals("bypassed", fixture.controller.beforeAction(context).get().get("status"));
			Map<String, Object> after = fixture.controller.afterAction(context).get();
			assertEquals("bypassed", after.get("status"));
			assertEquals("none", ((Map<String, Object>) after.get("policy")).get("cursor_release"));
			assertEquals(List.of("activity:travel", "damage_grace"), fixture.controller.status().get("policy_reasons"));
			assertEquals(0, fixture.effects.offscreenEdges.size());
			fixture.signals.set(new GenericClientPolicyResolver.Signals(true, 110, 110,
				false, false, false, false, true));
			assertEquals("ready", fixture.controller.beforeAction(context).get().get("status"));
			assertEquals(List.of("activity:travel"), fixture.controller.status().get("policy_reasons"));
		}
		finally { fixture.controller.close(); }
	}

	@Test
	public void breaksFalseBypassesBothSidesOfAnAction() throws Exception
	{
		Fixture fixture = fixture(0.5, 0.0, 0.0);
		try
		{
			fixture.controller.activateAccount(42L);
			assertEquals("bypassed", fixture.controller.beforeAction(NONE).get().get("status"));
			assertEquals("bypassed", fixture.controller.afterAction(NONE).get().get("status"));
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
		long accountHash = findHash(profile -> profile.getMicroBreakProbability() >= 0.85);
		Fixture fixture = fixture(0.75, 0.1, 0.5, 0.99, 0.99);
		try
		{
			fixture.controller.activateAccount(accountHash);
			fixture.controller.setLoggedIn(true);
			advanceActive(fixture, 120_000L);
			long before = ((Number) fixture.controller.status()
				.get("active_millis_since_long_break")).longValue();

			CompletableFuture<Map<String, Object>> pause = fixture.controller.afterAction(TRAVEL);
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

			assertEquals("bypassed", fixture.controller.afterAction(NONE).get().get("status"));
			CompletableFuture<Map<String, Object>> longBreak = fixture.controller.afterAction(TRAVEL);
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
			assertEquals(0, fixture.effects.offscreenEdges.size());
			assertEquals("suppressed_after_long_break",
				fixture.controller.afterAction(TRAVEL).get().get("status"));
			assertEquals(0L, fixture.controller.status().get("micro_break_count"));
		}
		finally
		{
			fixture.controller.close();
		}
	}

	@Test
	public void manualEndCompletesOnlyAnActiveLongBreak() throws Exception
	{
		long accountHash = findHash(profile -> profile.getLongCadenceMinutes() < 55.0 &&
			profile.getFavoredLongBreakMode() == GenericClientBehaviorProfile.LongBreakMode.LOGOUT);
		Fixture fixture = fixture(0.000000000001, 0.99, 0.5);
		try
		{
			fixture.controller.activateAccount(accountHash);
			fixture.controller.setLoggedIn(true);
			advanceActive(fixture, 25 * 60_000L);
			CompletableFuture<Map<String, Object>> action = fixture.controller.afterAction(TRAVEL);

			assertEquals("long_break", fixture.controller.status().get("state"));
			assertEquals("ended", fixture.controller.endLongBreak().get().get("status"));
			assertEquals("deferred", action.get().get("status"));
			assertEquals("long_break_deferred", fixture.controller.status().get("state"));
			assertEquals(25 * 60_000L, fixture.controller.status().get("active_millis_since_long_break"));
			assertEquals(1, fixture.effects.ensureLoginCalls);
			assertEquals(0, fixture.timer.activeSize());
		}
		finally
		{
			fixture.controller.close();
		}
	}

	@Test
	public void manualEndDoesNotDismissAMicroBreak() throws Exception
	{
		long accountHash = findHash(profile -> profile.getMicroBreakProbability() >= 0.85);
		Fixture fixture = fixture(0.5, 0.1, 0.5, 0.99, 0.99);
		try
		{
			fixture.controller.activateAccount(accountHash);
			fixture.controller.setLoggedIn(true);
			advanceActive(fixture, 60_000L);
			CompletableFuture<Map<String, Object>> action = fixture.controller.afterAction(TRAVEL);

			assertEquals("not_active", fixture.controller.endLongBreak().get().get("status"));
			assertEquals("micro_break", fixture.controller.status().get("state"));
			assertEquals(1, fixture.timer.activeSize());
			fixture.timer.runNext();
			assertEquals("completed", action.get().get("status"));
		}
		finally
		{
			fixture.controller.close();
		}
	}

	@Test
	public void activeBreakEndDismissesAMicroBreak() throws Exception
	{
		long accountHash = findHash(profile -> profile.getMicroBreakProbability() >= 0.85);
		Fixture fixture = fixture(0.5, 0.1, 0.5, 0.99, 0.99);
		try
		{
			fixture.controller.activateAccount(accountHash);
			fixture.controller.setLoggedIn(true);
			advanceActive(fixture, 60_000L);
			CompletableFuture<Map<String, Object>> action = fixture.controller.afterAction(TRAVEL);

			assertEquals("micro_break", fixture.controller.status().get("state"));
			assertEquals("ended", fixture.controller.endActiveBreak().get().get("status"));
			assertEquals("completed", action.get().get("status"));
			assertEquals("ready", fixture.controller.status().get("state"));
			assertEquals(0, fixture.timer.activeSize());
		}
		finally
		{
			fixture.controller.close();
		}
	}

	@Test
	public void manualEndWaitsForLogoutBeforeRestoringTheSession() throws Exception
	{
		long accountHash = findHash(profile -> profile.getLongCadenceMinutes() < 55.0 &&
			profile.getFavoredLongBreakMode() == GenericClientBehaviorProfile.LongBreakMode.LOGOUT);
		ManualClock clock = new ManualClock();
		ManualTimer timer = new ManualTimer();
		DelayedLogoutEffects effects = new DelayedLogoutEffects();
		java.util.concurrent.atomic.AtomicReference<GenericClientPolicyResolver.Signals> signals =
			new java.util.concurrent.atomic.AtomicReference<>(GenericClientPolicyResolver.Signals.CLEAR);
		GenericClientBehaviorController controller = new GenericClientBehaviorController(
			new GenericClientBehaviorStore(temporaryFolder.newFolder("delayed-logout").toPath()),
			effects,
			timer,
			clock,
			new SequenceRandom(0.000000000001, 0.99, 0.5),
			signals::get,
			message -> { });
		Fixture fixture = new Fixture(controller, clock, timer, effects, signals);
		try
		{
			controller.activateAccount(accountHash);
			controller.setLoggedIn(true);
			advanceActive(fixture, 25 * 60_000L);
			controller.afterAction(TRAVEL);
			CompletableFuture<Map<String, Object>> ended = controller.endLongBreak();

			assertFalse(ended.isDone());
			assertEquals(0, effects.ensureLoginCalls);
			effects.logout.complete("logged_out");
			assertEquals("ended", ended.get().get("status"));
			assertEquals(1, effects.ensureLoginCalls);
			assertEquals("long_break_deferred", controller.status().get("state"));
		}
		finally
		{
			controller.close();
		}
	}

	@Test
	public void phaseAddsPressureThenHonorsGlobalAndNamedCooldowns() throws Exception
	{
		long accountHash = findHash(profile ->
			profile.getMicroBreakProbability() >= 0.30 && profile.getPhaseShortChances() >= 2.0);
		Fixture fixture = fixture(0.9, 0.1, 0.5, 0.99);
		try
		{
			fixture.controller.activateAccount(accountHash);
			fixture.controller.setLoggedIn(true);
			fixture.controller.publishActiveTick(true, TRAVEL);
			CompletableFuture<Map<String, Object>> first =
				fixture.controller.enterPhase("banking.complete", TRAVEL);
			assertFalse(first.isDone());
			fixture.timer.runNext();
			first.get();

			Map<String, Object> cooldown = fixture.controller
				.enterPhase("banking.complete", TRAVEL).get();
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
		long accountHash = findHash(profile -> profile.getMicroBreakProbability() >= 0.85);
		Path directory = temporaryFolder.newFolder("restored").toPath();
		ManualClock clock = new ManualClock();
		ManualTimer firstTimer = new ManualTimer();
		FakeEffects firstEffects = new FakeEffects();
		GenericClientBehaviorController first = new GenericClientBehaviorController(
			new GenericClientBehaviorStore(directory),
			firstEffects,
			firstTimer,
			clock,
			new SequenceRandom(0.5, 0.0001, 0.5, 0.99),
			() -> GenericClientPolicyResolver.Signals.CLEAR,
			message -> { });
		first.activateAccount(accountHash);
		first.setLoggedIn(true);
		clock.advance(5_000L);
		first.publishActiveTick(true, TRAVEL);
		first.afterAction(TRAVEL);
		assertEquals("micro_break", first.status().get("state"));
		first.close();

		ManualTimer secondTimer = new ManualTimer();
		GenericClientBehaviorController restored = new GenericClientBehaviorController(
			new GenericClientBehaviorStore(directory),
			new FakeEffects(),
			secondTimer,
			clock,
			new SequenceRandom(0.5),
			() -> GenericClientPolicyResolver.Signals.CLEAR,
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
				0.85,
				9.0,
				0.30,
				240.0,
				35.0,
				3.5,
				GenericClientBehaviorProfile.LongBreakMode.LOGOUT,
				0.25,
				GenericClientBehaviorProfile.Edge.TOP,
				650,
				85,
				85,
				GenericClientBehaviorProfile.DialogueInputMode.MOUSE));

			Map<String, Object> custom = (Map<String, Object>) fixture.controller.status().get("profile");
			assertTrue((Boolean) custom.get("customized"));
			assertEquals(0.95, (Double) custom.get("micro_break_probability"), 0.0);
			assertEquals("top", custom.get("idle_edge"));
			assertEquals(650, fixture.controller.mouseMoveDurationMillis());
			assertEquals(85, fixture.controller.dialogueReadingPercent());

			fixture.controller.resetOverrides();
			Map<String, Object> restored = (Map<String, Object>) fixture.controller.status().get("profile");
			assertFalse((Boolean) restored.get("customized"));
			assertEquals(seeded.get("micro_break_probability"), restored.get("micro_break_probability"));
			assertEquals(seeded.get("idle_edge"), restored.get("idle_edge"));
			assertEquals(((Long) seeded.get("mouse_move_duration_millis")).intValue(),
				fixture.controller.mouseMoveDurationMillis());
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
			0.72,
			7.5,
			0.22,
			300.0,
			40.0,
			3.2,
			GenericClientBehaviorProfile.LongBreakMode.AFK,
			0.18,
			GenericClientBehaviorProfile.Edge.RIGHT,
			375,
			75,
			75,
			GenericClientBehaviorProfile.DialogueInputMode.KEYBOARD);
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
			assertEquals(0.88, (Double) profile.get("micro_break_probability"), 0.0);
			assertEquals(300.0, (Double) profile.get("long_cadence_minutes"), 0.0);
			assertEquals(375, second.mouseMoveDurationMillis());
			assertEquals(75, second.dialogueReadingPercent());
		}
		finally
		{
			second.close();
		}
	}

	@Test
	public void plainExecutionDoesNotRequireAnAccountBehaviorProfile() throws Exception
	{
		Fixture fixture = fixture(0.5);
		try
		{
			assertEquals("bypassed", fixture.controller.beforeAction(NONE).get().get("status"));
			assertEquals("bypassed", fixture.controller.afterAction(NONE).get().get("status"));
		}
		finally { fixture.controller.close(); }
	}

	@Test
	public void loggedInIdleTimeDoesNotAccrueLongPressure() throws Exception
	{
		Fixture fixture = fixture(0.5);
		try
		{
			fixture.controller.activateAccount(42L);
			fixture.controller.setLoggedIn(true);
			for (int i = 0; i < 720; i++)
			{
				fixture.clock.advance(5_000L);
				fixture.controller.publishActiveTick(false, TRAVEL);
			}
			assertEquals(0L, fixture.controller.status().get("active_millis_since_long_break"));
			advanceActive(fixture, 5_000L);
			assertEquals(5_000L, fixture.controller.status().get("active_millis_since_long_break"));
		}
		finally { fixture.controller.close(); }
	}

	@Test
	public void newSessionDefersDuePressureUntilGraceOrAnExplicitPhase() throws Exception
	{
		Fixture fixture = fixture(0.000000000001, 0.999999999999, 0.999);
		try
		{
			fixture.controller.activateAccount(findHash(profile -> profile.getLongCadenceMinutes() < 55.0));
			fixture.controller.setLoggedIn(true);
			advanceActive(fixture, 25 * 60_000L);
			fixture.controller.beginSession();
			assertEquals("ready", fixture.controller.beforeAction(TRAVEL).get().get("status"));
			assertEquals("no_break", fixture.controller.afterAction(TRAVEL).get().get("status"));
			assertEquals(0L, fixture.controller.status().get("long_break_count"));
			CompletableFuture<Map<String, Object>> phase = fixture.controller.enterPhase("safe.bank", TRAVEL);
			assertFalse(phase.isDone());
			assertEquals("long_break", fixture.controller.status().get("state"));
			fixture.timer.runNext();
			assertEquals("completed", phase.get().get("status"));
		}
		finally { fixture.controller.close(); }
	}

	@Test
	public void interruptedLongBreakKeepsDebtAndRetriesOnlyAtAPhaseAfterRefractory() throws Exception
	{
		double[] rolls = new double[40];
		java.util.Arrays.fill(rolls, 0.999999999999);
		rolls[0] = 0.000000000001;
		Fixture fixture = fixture(rolls);
		long accountHash = findHash(profile -> profile.getLongCadenceMinutes() < 55 && profile.getMicroBreakProbability() < 0.75);
		try
		{
			fixture.controller.activateAccount(accountHash);
			fixture.controller.setLoggedIn(true);
			advanceActive(fixture, 25 * 60_000L);
			CompletableFuture<Map<String, Object>> first = fixture.controller.afterAction(TRAVEL);
			assertFalse(first.isDone());
			fixture.clock.advance(25_000L);
			Map<String, Object> ended = fixture.controller.endLongBreak().get();
			assertEquals(25_000L, ended.get("elapsed_millis"));
			assertEquals("manual", ended.get("end_reason"));
			assertEquals("deferred", first.get().get("status"));
			long debt = ((Number) fixture.controller.status().get("active_millis_since_long_break")).longValue();
			assertEquals(25 * 60_000L, debt);
			assertTrue(fixture.controller.enterPhase("too.soon", TRAVEL).isDone());
			assertEquals(1L, fixture.controller.status().get("long_break_count"));
			long refractory = (long) Math.ceil(GenericClientBehaviorProfile.fromAccountHash(accountHash)
				.getLongRefractoryMinutes() * 60_000.0);
			advanceActive(fixture, refractory + 1);
			assertTrue(fixture.controller.afterAction(TRAVEL).isDone());
			assertEquals(1L, fixture.controller.status().get("long_break_count"));
			CompletableFuture<Map<String, Object>> retry = fixture.controller.enterPhase("safe.bank", TRAVEL);
			assertFalse(retry.isDone());
			fixture.timer.runNext();
			assertEquals("completed", retry.get().get("status"));
			assertEquals(0L, fixture.controller.status().get("active_millis_since_long_break"));
			assertEquals(false, fixture.controller.status().get("long_break_deferred"));
		}
		finally { fixture.controller.close(); }
	}

	private GenericClientBehaviorController controller(Path directory) throws Exception
	{
		return new GenericClientBehaviorController(
			new GenericClientBehaviorStore(directory),
			new FakeEffects(),
			new ManualTimer(),
			new ManualClock(),
			new SequenceRandom(0.5),
			() -> GenericClientPolicyResolver.Signals.CLEAR,
			message -> { });
	}

	private Fixture fixture(double... randomValues) throws Exception
	{
		ManualClock clock = new ManualClock();
		ManualTimer timer = new ManualTimer();
		FakeEffects effects = new FakeEffects();
		java.util.concurrent.atomic.AtomicReference<GenericClientPolicyResolver.Signals> signals =
			new java.util.concurrent.atomic.AtomicReference<>(GenericClientPolicyResolver.Signals.CLEAR);
		GenericClientBehaviorController controller = new GenericClientBehaviorController(
			new GenericClientBehaviorStore(temporaryFolder.newFolder().toPath()),
			effects,
			timer,
			clock,
			new SequenceRandom(randomValues),
			signals::get,
			message -> { });
		return new Fixture(controller, clock, timer, effects, signals);
	}

	private static void advanceActive(Fixture fixture, long millis)
	{
		fixture.controller.publishActiveTick(true, TRAVEL);
		long remaining = millis;
		while (remaining > 0L)
		{
			long step = Math.min(5_000L, remaining);
			fixture.clock.advance(step);
			fixture.controller.publishActiveTick(true, TRAVEL);
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
		private final java.util.concurrent.atomic.AtomicReference<GenericClientPolicyResolver.Signals> signals;

		private Fixture(
			GenericClientBehaviorController controller,
			ManualClock clock,
			ManualTimer timer,
			FakeEffects effects,
			java.util.concurrent.atomic.AtomicReference<GenericClientPolicyResolver.Signals> signals)
		{
			this.controller = controller;
			this.clock = clock;
			this.timer = timer;
			this.effects = effects;
			this.signals = signals;
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

		private int activeSize()
		{
			int count = 0;
			for (Task task : tasks)
			{
				if (!task.cancelled)
				{
					count++;
				}
			}
			return count;
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

	private static class FakeEffects implements GenericClientBehaviorController.BreakEffects
	{
		private final List<GenericClientBehaviorProfile.Edge> offscreenEdges = new ArrayList<>();
		protected int logoutCalls;
		protected int ensureLoginCalls;

		@Override
		public CompletableFuture<String> moveOffscreen(GenericClientBehaviorProfile.Edge edge, GenericClientActivityContext context)
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

	private static final class DelayedLogoutEffects extends FakeEffects
	{
		private final CompletableFuture<String> logout = new CompletableFuture<>();

		@Override
		public CompletableFuture<String> logout()
		{
			logoutCalls++;
			return logout;
		}
	}

	private static final class SequenceRandom extends java.util.Random
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
