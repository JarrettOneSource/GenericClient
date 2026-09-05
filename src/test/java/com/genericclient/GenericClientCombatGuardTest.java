package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class GenericClientCombatGuardTest
{
	@Test
	public void expectedPoisonNeitherStartsNorRefreshesExistingDamageGrace()
	{
		GenericClientCombatGuard guard = new GenericClientCombatGuard(new FakeRuntime(), message -> { });
		guard.publishGameTick(GenericClientDamageTrackerTest.snapshot(10, 80, 6), policy("skilling"), true);
		guard.recordHitsplat(net.runelite.api.HitsplatID.POISON, 2);
		guard.publishGameTick(GenericClientDamageTrackerTest.snapshot(11, 78, 5), policy("skilling"), true);
		assertEquals("expected_poison", guard.status().get("damage_type"));
		assertFalse((Boolean) guard.status().get("damage_grace"));
		guard.recordHitsplat(net.runelite.api.HitsplatID.DAMAGE_ME, 2);
		guard.publishGameTick(GenericClientDamageTrackerTest.snapshot(12, 76, 5), policy("skilling"), true);
		assertEquals(112L, guard.observation().damageGraceUntilTick);
		guard.recordHitsplat(net.runelite.api.HitsplatID.POISON, 1);
		guard.publishGameTick(GenericClientDamageTrackerTest.snapshot(13, 75, 4), policy("skilling"), true);
		assertEquals("expected_poison", guard.status().get("damage_type"));
		assertEquals(112L, guard.observation().damageGraceUntilTick);
	}

	@Test
	public void prayerOwnershipOverrideRevokesAnAttemptWithoutSuppressingThreatObservation()
	{
		FakeRuntime runtime = new FakeRuntime();
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });
		CompletableFuture<Map<String, Object>> pending = new CompletableFuture<>();
		runtime.nextPrayerFuture = pending;
		guard.publishGameTick(snapshot(10, npc(1, 1, "Pirate", 23, 1, -1)), policy("travel"), true);
		GenericClientBehaviorPolicy declared = policy("travel").withOverrides(Map.of("prayer_owner", "script"));
		guard.publishGameTick(snapshot(11, npc(1, 1, "Pirate", 23, 1, -1)), declared, true);
		pending.complete(FakeRuntime.receipt("set", "prayer_state_verified"));
		assertFalse(runtime.prayerContexts.get(0).isInputAllowed());
		assertTrue(guard.observation().threatsPresent);
		assertEquals("script_owns_prayer", guard.status().get("last_result"));
		assertEquals("none", guard.status().get("owned_protection"));
	}

	@Test
	public void disablingGuardPrayerRevokesQueuedInputAndRejectsItsLateReceipt()
	{
		FakeRuntime runtime = new FakeRuntime();
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });
		CompletableFuture<Map<String, Object>> pending = new CompletableFuture<>();
		runtime.nextPrayerFuture = pending;
		guard.publishGameTick(snapshot(10L, npc(1, 1, "Pirate", 23, 1, -1)), policy("travel"), true);
		guard.configureScriptBehavior(false);
		pending.complete(FakeRuntime.receipt("set", "prayer_state_verified"));

		assertFalse(runtime.prayerContexts.get(0).isInputAllowed());
		assertEquals("none", guard.status().get("owned_protection"));
		assertEquals("none", guard.status().get("pending_protection"));
	}

	@Test
	public void physicalTakeoverPreventsIdlePrayerCleanup()
	{
		FakeRuntime runtime = new FakeRuntime();
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });
		guard.publishGameTick(snapshot(10L, npc(1, 1, "Pirate", 23, 1, -1)), policy("travel"), true);
		runtime.emergencyInputActive = true;
		guard.publishGameTick(snapshot(11L), policy("idle"), false);

		assertEquals(Collections.singletonList("protect_from_melee:true"), runtime.prayers);
		assertFalse(runtime.prayerContexts.get(0).isInputAllowed());
		assertEquals("waiting_for_input_owner", guard.status().get("last_result"));
	}

	@Test
	public void resetPreventsAnOldPrayerReceiptFromReplacingTheCurrentAttempt()
	{
		FakeRuntime runtime = new FakeRuntime();
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });
		CompletableFuture<Map<String, Object>> old = new CompletableFuture<>();
		runtime.nextPrayerFuture = old;
		guard.publishGameTick(snapshot(10L, npc(1, 1, "Pirate", 23, 1, -1)), policy("travel"), true);

		guard.reset();
		CompletableFuture<Map<String, Object>> current = new CompletableFuture<>();
		runtime.nextPrayerFuture = current;
		guard.publishGameTick(snapshot(11L, npc(2, 2, "Archer", 86, 5, -1)), policy("travel"), true);
		old.complete(FakeRuntime.receipt("set", "prayer_state_verified"));

		assertFalse(runtime.prayerContexts.get(0).isInputAllowed());
		assertTrue(runtime.prayerContexts.get(1).isInputAllowed());
		assertEquals("protect_from_missiles", guard.status().get("pending_protection"));
		assertEquals("none", guard.status().get("owned_protection"));
		current.complete(FakeRuntime.receipt("set", "prayer_state_verified"));
		assertEquals("protect_from_missiles", guard.status().get("owned_protection"));
	}

	@Test
	public void resetPreventsAnOldRestoreReceiptFromClearingTheCurrentRestore()
	{
		FakeRuntime runtime = new FakeRuntime();
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });
		CompletableFuture<Map<String, Object>> old = new CompletableFuture<>();
		runtime.prayerDepleted = true;
		runtime.nextRestoreFuture = old;
		guard.publishGameTick(snapshot(10L, npc(1, 1, "Pirate", 23, 1, -1)), policy("travel"), true);

		guard.reset();
		CompletableFuture<Map<String, Object>> current = new CompletableFuture<>();
		runtime.nextRestoreFuture = current;
		guard.publishGameTick(snapshot(11L, npc(2, 2, "Archer", 86, 5, -1)), policy("travel"), true);
		old.complete(FakeRuntime.receipt("dispatched", "item_interaction_dispatched"));

		assertEquals(true, guard.status().get("prayer_restore_pending"));
		current.complete(FakeRuntime.receipt("dispatched", "item_interaction_dispatched"));
		assertEquals(false, guard.status().get("prayer_restore_pending"));
		assertEquals("prayer_restore_dispatched", guard.status().get("last_result"));
	}

	@Test
	public void skillingPublishesThreatsAndDamageWithoutTakingScriptPrayer()
	{
		FakeRuntime runtime = new FakeRuntime();
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });
		guard.publishGameTick(snapshot(90L, 28, npc(1, 1447, "Pirate", 23, 2, 425)), policy("skilling"), true);
		assertTrue(runtime.prayers.isEmpty());
		assertTrue(guard.observation().threatsPresent);
		assertEquals(90L, guard.observation().damageGraceUntilTick);
		assertFalse((Boolean) guard.status().get("damage_detected"));
		guard.publishGameTick(snapshot(91L, 27), policy("skilling"), true);
		assertEquals(191L, guard.observation().damageGraceUntilTick);
		assertTrue((Boolean) guard.status().get("damage_detected"));
		guard.publishGameTick(snapshot(92L, 27), policy("skilling"), true);
		assertFalse((Boolean) guard.status().get("damage_detected"));
		assertEquals(191L, guard.observation().damageGraceUntilTick);
		guard.publishGameTick(snapshot(191L, 27), policy("skilling"), true);
		assertFalse((Boolean) guard.status().get("damage_grace"));
	}

	@Test
	public void damageWithoutAnAttackerStillStartsTheSafetyGrace()
	{
		FakeRuntime runtime = new FakeRuntime();
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });

		guard.publishGameTick(snapshot(80L, 28), policy("skilling"), true);
		guard.publishGameTick(snapshot(81L, 26), policy("skilling"), true);


		assertTrue((Boolean) guard.status().get("damage_detected"));
		assertTrue(((List<?>) guard.status().get("attackers")).isEmpty());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void unexpectedAttackChoosesTheHighestLevelThreatAndOwnsItsPrayer()
	{
		FakeRuntime runtime = new FakeRuntime();

		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });

		guard.publishGameTick(snapshot(99L, 28), policy("travel"), true);
		guard.publishGameTick(snapshot(100L, 27,
			npc(1, 5242, "Scorpion", 38, 1, 6254),
			npc(2, 5274, "Monkey Archer", 86, 12, -1)), policy("travel"), true);



		assertEquals(Collections.singletonList("protect_from_missiles:true"), runtime.prayers);

		Map<String, Object> active = guard.status();
		assertTrue((Boolean) active.get("unexpected_combat"));
		assertEquals("protect_from_missiles", active.get("owned_protection"));
		assertEquals(2, ((List<Map<String, Object>>) active.get("attackers")).size());

		guard.publishGameTick(snapshot(101L, 27), policy("travel"), true);
		guard.publishGameTick(snapshot(102L, 27), policy("travel"), true);
		assertEquals(1, runtime.prayers.size());
		guard.publishGameTick(snapshot(103L, 27), policy("travel"), true);

		assertEquals(Arrays.asList(
			"protect_from_missiles:true",
			"protect_from_missiles:false"), runtime.prayers);
		assertEquals("none", guard.status().get("owned_protection"));

		assertTrue((Boolean) guard.status().get("damage_grace"));

		guard.publishGameTick(snapshot(200L, 27), policy("travel"), true);

	}

	@Test
	public void declaredCombatUsesItsOwnPolicyWithoutGlobalSuppression()
	{
		FakeRuntime runtime = new FakeRuntime();
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });

		guard.publishGameTick(snapshot(200L), policy("combat"), true);

		assertTrue(runtime.prayers.isEmpty());
		assertTrue((Boolean) guard.status().get("damage_expected"));
		assertFalse((Boolean) guard.status().get("unexpected_combat"));


		guard.publishGameTick(snapshot(201L), policy("travel"), true);

		assertFalse((Boolean) guard.status().get("damage_grace"));
	}

	@Test
	public void incidentalCombatDoesNotReplaceHazardousTravel()
	{
		FakeRuntime runtime = new FakeRuntime();
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });

		guard.publishGameTick(snapshot(250L,
			npc(1, 5274, "Monkey Archer", 86, 9, -1)), policy("hazardous_travel"), true);

		assertFalse((Boolean) guard.status().get("unexpected_combat"));
		assertFalse((Boolean) guard.status().get("script_controls_protection"));
		assertEquals(Collections.singletonList("protect_from_missiles:true"),
			runtime.prayers);

		guard.publishGameTick(snapshot(251L,
			npc(2, 5276, "Monkey Guard", 167, 1, -1)), policy("hazardous_travel"), true);

		assertEquals(Arrays.asList(
			"protect_from_missiles:true",
			"protect_from_melee:true"), runtime.prayers);

	}

	@Test
	public void emergencyInputPrecedesProtectionSwitching()
	{
		FakeRuntime runtime = new FakeRuntime();
		runtime.emergencyInputActive = true;
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });

		guard.publishGameTick(snapshot(260L,
			npc(1, 5274, "Monkey Archer", 86, 9, -1)), policy("hazardous_travel"), true);

		assertTrue(runtime.prayers.isEmpty());
		assertEquals("waiting_for_input_owner", guard.status().get("last_result"));

		runtime.emergencyInputActive = false;
		guard.publishGameTick(snapshot(261L,
			npc(1, 5274, "Monkey Archer", 86, 9, -1)), policy("hazardous_travel"), true);

		assertEquals(Collections.singletonList("protect_from_missiles:true"),
			runtime.prayers);
	}

	@Test
	public void preexistingPrayerIsNotDisabledByTheGuard()
	{
		FakeRuntime runtime = new FakeRuntime();
		runtime.nextPrayerStatus = "unchanged";
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });

		guard.publishGameTick(snapshot(300L,
			npc(1, 5274, "Monkey Archer", 86, 9, -1)), policy("travel"), true);
		guard.publishGameTick(snapshot(303L), policy("travel"), true);

		assertEquals(Collections.singletonList("protect_from_missiles:true"), runtime.prayers);
		assertEquals("none", guard.status().get("owned_protection"));
	}

	@Test
	public void depletedPrayerConsumesOneRestoreThenRetriesProtection()
	{
		FakeRuntime runtime = new FakeRuntime();
		runtime.prayerDepleted = true;
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });

		guard.publishGameTick(snapshot(400L,
			npc(1, 5274, "Monkey Archer", 86, 9, -1)), policy("travel"), true);
		assertEquals(1, runtime.prayerRestores);
		assertEquals("prayer_restore_dispatched", guard.status().get("last_result"));

		guard.publishGameTick(snapshot(401L,
			npc(1, 5274, "Monkey Archer", 86, 9, -1)), policy("travel"), true);

		assertEquals(Arrays.asList(
			"protect_from_missiles:true",
			"protect_from_missiles:true"), runtime.prayers);
		assertEquals("protect_from_missiles", guard.status().get("owned_protection"));
	}

	@Test
	public void unmarkedMeleeNpcKeepsMeleeProtectionWhileClosingDistance()
	{
		FakeRuntime runtime = new FakeRuntime();
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });

		guard.publishGameTick(snapshot(500L,
			npc(1, 5243, "Jungle spider", 37, 4, -1)), policy("travel"), true);
		guard.publishGameTick(snapshot(501L,
			npc(1, 5243, "Jungle spider", 37, 1, 5327)), policy("travel"), true);

		assertEquals(Collections.singletonList("protect_from_melee:true"), runtime.prayers);
		assertEquals("protect_from_melee", guard.status().get("selected_protection"));
	}

	@Test
	public void idleClientReleasesProtectionAndIgnoresAttackers()
	{
		FakeRuntime runtime = new FakeRuntime();
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });

		guard.publishGameTick(snapshot(600L,
			npc(1, 5243, "Jungle spider", 37, 1, 5327)), policy("travel"), true);
		guard.publishGameTick(snapshot(601L,
			npc(1, 5243, "Jungle spider", 37, 1, 5327)), policy("idle"), false);

		assertEquals(Arrays.asList(
			"protect_from_melee:true",
			"protect_from_melee:false"), runtime.prayers);
		assertFalse((Boolean) guard.status().get("input_owned"));
		assertFalse((Boolean) guard.status().get("active"));
		assertTrue(((List<?>) guard.status().get("attackers")).isEmpty());
	}

	@Test
	public void idleClientDoesNotTouchProtectionItDidNotOwn()
	{
		FakeRuntime runtime = new FakeRuntime();
		runtime.activePrayer = "protect_from_melee";
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });
		guard.configureScriptBehavior(false);

		guard.publishGameTick(snapshot(650L), policy("idle"), false);

		assertTrue(runtime.prayers.isEmpty());
		assertEquals("protect_from_melee", runtime.activePrayer);
	}

	@Test
	public void idlePrayerReleaseFailureIsNotRetriedForever()
	{
		FakeRuntime runtime = new FakeRuntime();
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });
		guard.publishGameTick(snapshot(660L,
			npc(1, 5274, "Monkey Archer", 86, 9, -1)), policy("travel"), true);
		runtime.nextPrayerStatus = "rejected";

		guard.publishGameTick(snapshot(661L), policy("idle"), false);
		guard.publishGameTick(snapshot(662L), policy("idle"), false);
		guard.publishGameTick(snapshot(663L), policy("idle"), false);
		guard.publishGameTick(snapshot(664L), policy("idle"), false);

		assertEquals(Arrays.asList(
			"protect_from_missiles:true",
			"protect_from_missiles:false"), runtime.prayers);
	}

	@Test
	public void scriptCanOwnCombatPrayerUntilThePolicyResets()
	{
		FakeRuntime runtime = new FakeRuntime();
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });
		guard.configureScriptBehavior(false);

		guard.publishGameTick(snapshot(700L,
			npc(1, 5274, "Monkey Archer", 86, 9, -1)), policy("travel"), true);

		assertTrue(runtime.prayers.isEmpty());
		assertEquals(false, guard.status().get("automatic_prayer_enabled"));
		assertEquals("automatic_prayer_disabled_by_script", guard.status().get("last_result"));

		guard.resetScriptBehavior();
		guard.publishGameTick(snapshot(701L,
			npc(1, 5274, "Monkey Archer", 86, 9, -1)), policy("travel"), true);

		assertEquals(Collections.singletonList("protect_from_missiles:true"), runtime.prayers);
		assertEquals(true, guard.status().get("automatic_prayer_enabled"));
	}

	private static GenericClientBehaviorPolicy policy(String activity)
	{
		return GenericClientActivityContext.preset(GenericClientActivityContext.Activity.fromName(
			"idle".equals(activity) ? "general" : activity)).declaredPolicy;
	}

	private static GenericClientSnapshot snapshot(
		long tick,
		GenericClientNpcSnapshot... npcs)
	{
		return snapshot(tick, 28, npcs);
	}

	private static GenericClientSnapshot snapshot(
		long tick,
		int hitpoints,
		GenericClientNpcSnapshot... npcs)
	{
		GenericClientPlayerSnapshot player = new GenericClientPlayerSnapshot(1L,
			"genericBoss", 2762, 2805, 0, 0, -1, null,
			hitpoints, 28, 10_000, true, null);
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			player,
			Arrays.asList(npcs));
	}

	private static GenericClientNpcSnapshot npc(
		int index,
		int id,
		String name,
		int combatLevel,
		int distance,
		int animation)
	{
		return new GenericClientNpcSnapshot(2L,
			index,
			id,
			name,
			2762,
			2805 - distance,
			0,
			distance,
			combatLevel,
			animation,
			"genericBoss",
			Collections.singletonList("Attack"));
	}

	private static final class FakeRuntime implements GenericClientCombatGuard.Runtime
	{
		private final List<String> prayers = new ArrayList<>();
		private final List<GenericClientActivityContext> prayerContexts = new ArrayList<>();
		private String nextPrayerStatus = "set";
		private String activePrayer = "none";
		private boolean prayerDepleted;
		private boolean emergencyInputActive;
		private int prayerRestores;
		private CompletableFuture<Map<String, Object>> nextPrayerFuture;
		private CompletableFuture<Map<String, Object>> nextRestoreFuture;

		@Override
		public boolean isInputBlocked()
		{
			return emergencyInputActive;
		}

		@Override
		public void cancelInput(GenericClientActivityContext context)
		{
			assertFalse(context.isInputAllowed());
		}

		@Override
		public boolean isPrayerActive(String prayer)
		{
			return prayer.equals(activePrayer);
		}

		@Override
		public CompletableFuture<Map<String, Object>> setPrayer(String prayer, boolean enabled,
			GenericClientActivityContext context)
		{
			prayers.add(prayer + ":" + enabled);
			prayerContexts.add(context);
			if (nextPrayerFuture != null)
			{
				CompletableFuture<Map<String, Object>> pending = nextPrayerFuture;
				nextPrayerFuture = null;
				return pending;
			}
			if (enabled && prayerDepleted)
			{
				return CompletableFuture.completedFuture(
					receipt("rejected", "prayer_points_depleted"));
			}
			String status = nextPrayerStatus;
			nextPrayerStatus = "set";
			if ("set".equals(status) || "unchanged".equals(status))
			{
				activePrayer = enabled ? prayer : "none";
			}
			return CompletableFuture.completedFuture(receipt(status, "prayer_state_verified"));
		}

		@Override
		public CompletableFuture<Map<String, Object>> restorePrayer(GenericClientActivityContext context)
		{
			prayerRestores++;
			if (nextRestoreFuture != null)
			{
				CompletableFuture<Map<String, Object>> pending = nextRestoreFuture;
				nextRestoreFuture = null;
				return pending;
			}
			prayerDepleted = false;
			return CompletableFuture.completedFuture(receipt("dispatched", "item_interaction_dispatched"));
		}

		private static Map<String, Object> receipt(String status, String result)
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("status", status);
			value.put("result", result);
			return value;
		}
	}
}
