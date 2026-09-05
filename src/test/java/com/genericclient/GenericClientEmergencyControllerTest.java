package com.genericclient;

import static com.genericclient.GenericClientEmergencyTestFixtures.dispatched;
import static com.genericclient.GenericClientEmergencyTestFixtures.escapeStarted;
import static com.genericclient.GenericClientEmergencyTestFixtures.rejected;
import static com.genericclient.GenericClientEmergencyTestFixtures.snapshotWithHitpoints;
import static com.genericclient.GenericClientEmergencyTestFixtures.snapshotWithInventory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class GenericClientEmergencyControllerTest
{
	@Test
	public void idleClientDisarmsInheritedSafetyConfiguration()
	{
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) -> CompletableFuture.completedFuture(dispatched()),
			escape -> CompletableFuture.completedFuture(escapeStarted()),
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> CompletableFuture.completedFuture(null),
			message -> { });
		controller.configure(
			4,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(379, "Eat", 12)),
			null,
			true,
			true);

		controller.publishGameTick(snapshotWithHitpoints(20, 28), false);

		assertFalse((Boolean) controller.status().get("armed"));
		assertFalse((Boolean) controller.status().get("input_owned"));
		assertEquals("idle", controller.status().get("last_event"));
	}

	@Test
	public void lowHitpointsPreemptWorkEndBreakAndDispatchApprovedConsumable()
	{
		AtomicInteger consumptions = new AtomicInteger();
		AtomicInteger endedBreaks = new AtomicInteger();
		AtomicInteger stops = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) ->
			{
				assertEquals(1993, itemId);
				assertEquals("Drink", action);
				consumptions.incrementAndGet();
				return CompletableFuture.completedFuture(dispatched());
			},
			escape -> CompletableFuture.completedFuture(escapeStarted()),
			() ->
			{
				endedBreaks.incrementAndGet();
				return CompletableFuture.completedFuture(Collections.emptyMap());
			},
			reason ->
			{
				assertEquals("emergency_low_hitpoints", reason);
				stops.incrementAndGet();
				return CompletableFuture.completedFuture(null);
			},
			message -> { });
		controller.configure(4, Collections.singletonList(
			new GenericClientEmergencyController.Consumable(1993, "Drink", 11)), null, false, false);

		controller.publishGameTick(snapshotWithHitpoints(4));

		assertEquals(1, stops.get());
		assertEquals(1, endedBreaks.get());
		assertEquals(1, consumptions.get());
		assertEquals("emergency_consumable_dispatched", controller.status().get("last_event"));
		assertFalse((Boolean) controller.status().get("recovering"));
	}

	@Test
	public void hitpointsAboveThresholdDoNotInterruptAnything()
	{
		AtomicInteger actions = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) ->
			{
				actions.incrementAndGet();
				return CompletableFuture.completedFuture(dispatched());
			},
			escape -> CompletableFuture.completedFuture(escapeStarted()),
			() ->
			{
				actions.incrementAndGet();
				return CompletableFuture.completedFuture(Collections.emptyMap());
			},
			reason ->
			{
				actions.incrementAndGet();
				return CompletableFuture.completedFuture(null);
			},
			message -> { });
		controller.configure(4, Collections.singletonList(
			new GenericClientEmergencyController.Consumable(1993, "Drink", 11)), null, false, false);

		controller.publishGameTick(snapshotWithHitpoints(5));

		assertEquals(0, actions.get());
		assertEquals("armed", controller.status().get("last_event"));
		assertEquals(5L, controller.status().get("last_hitpoints"));
	}

	@Test
	public void scriptCanDisableEmergencyInputAndResetItForTheNextRun()
	{
		AtomicInteger consumptions = new AtomicInteger();
		AtomicInteger escapes = new AtomicInteger();
		AtomicInteger stops = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) ->
			{
				consumptions.incrementAndGet();
				return CompletableFuture.completedFuture(dispatched());
			},
			escape ->
			{
				escapes.incrementAndGet();
				return CompletableFuture.completedFuture(escapeStarted());
			},
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason ->
			{
				stops.incrementAndGet();
				return CompletableFuture.completedFuture(null);
			},
			message -> { });
		controller.configure(
			4,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(379, "Eat", 12)),
			new GenericClientEmergencyEscape(
				new net.runelite.api.coords.WorldPoint(2440, 3089, 0), 3),
			true,
			true);
		controller.configureScriptBehavior(false, false);

		controller.publishGameTick(snapshotWithHitpoints(4, 28), true);

		assertEquals(0, consumptions.get());
		assertEquals(0, escapes.get());
		assertEquals(0, stops.get());
		assertEquals("emergency_behavior_disabled_by_script",
			controller.status().get("last_event"));
		assertEquals(false, controller.status().get("automatic_consumables_enabled"));
		assertEquals(false, controller.status().get("automatic_escape_enabled"));

		controller.resetScriptBehavior();
		controller.publishGameTick(snapshotWithHitpoints(4, 28), true);

		assertEquals(1, consumptions.get());
		assertEquals(true, controller.status().get("automatic_consumables_enabled"));
		assertEquals(true, controller.status().get("automatic_escape_enabled"));
	}

	@Test
	public void disabledEmergencyEscapeNeverTeleportsWhenFoodIsUnavailable()
	{
		AtomicInteger escapes = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) -> CompletableFuture.completedFuture(rejected()),
			escape ->
			{
				escapes.incrementAndGet();
				return CompletableFuture.completedFuture(escapeStarted());
			},
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> CompletableFuture.completedFuture(null),
			message -> { });
		controller.configure(
			4,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(379, "Eat", 12)),
			new GenericClientEmergencyEscape(
				new net.runelite.api.coords.WorldPoint(2440, 3089, 0), 3),
			true,
			true);
		controller.configureScriptBehavior(true, false);

		controller.publishGameTick(snapshotWithHitpoints(4, 28), true);

		assertEquals(0, escapes.get());
		assertEquals(false, controller.status().get("automatic_escape_enabled"));
	}

	@Test
	public void waitsForScriptShutdownBeforeStartingRecoveryInput()
	{
		CompletableFuture<Void> stopped = new CompletableFuture<>();
		AtomicInteger endedBreaks = new AtomicInteger();
		AtomicInteger consumptions = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) ->
			{
				consumptions.incrementAndGet();
				return CompletableFuture.completedFuture(dispatched());
			},
			escape -> CompletableFuture.completedFuture(escapeStarted()),
			() ->
			{
				endedBreaks.incrementAndGet();
				return CompletableFuture.completedFuture(Collections.emptyMap());
			},
			reason -> stopped,
			message -> { });
		controller.configure(4, Collections.singletonList(
			new GenericClientEmergencyController.Consumable(1993, "Drink", 11)), null, false, false);

		controller.publishGameTick(snapshotWithHitpoints(4));

		assertEquals(0, endedBreaks.get());
		assertEquals(0, consumptions.get());
		assertTrue((Boolean) controller.status().get("recovering"));

		stopped.complete(null);

		assertEquals(1, endedBreaks.get());
		assertEquals(1, consumptions.get());
		assertFalse((Boolean) controller.status().get("recovering"));
	}

	@Test
	public void manualEscapeCancelsPendingRecoveryBeforeAnyInput()
	{
		CompletableFuture<Void> stopped = new CompletableFuture<>();
		AtomicInteger consumptions = new AtomicInteger();
		AtomicInteger escapes = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) ->
			{
				consumptions.incrementAndGet();
				return CompletableFuture.completedFuture(dispatched());
			},
			escape ->
			{
				escapes.incrementAndGet();
				return CompletableFuture.completedFuture(escapeStarted());
			},
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> stopped,
			message -> { });
		controller.configure(
			12,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(379, "Eat", 12)),
			new GenericClientEmergencyEscape(
				new net.runelite.api.coords.WorldPoint(2440, 3089, 0), 3),
			false,
			true);

		controller.publishGameTick(snapshotWithHitpoints(12, 28), true);
		assertTrue((Boolean) controller.status().get("recovering"));

		controller.disarmForManualEscape();
		stopped.complete(null);

		assertEquals(0, consumptions.get());
		assertEquals(0, escapes.get());
		assertFalse((Boolean) controller.status().get("armed"));
		assertFalse((Boolean) controller.status().get("recovering"));
		assertEquals("manual_control", controller.status().get("last_event"));
	}

	@Test
	public void clearDisarmsTheGuard()
	{
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) -> CompletableFuture.completedFuture(dispatched()),
			escape -> CompletableFuture.completedFuture(escapeStarted()),
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> CompletableFuture.completedFuture(null),
			message -> { });
		controller.configure(4, Collections.singletonList(
			new GenericClientEmergencyController.Consumable(1993, "Drink", 11)), null, false, false);

		controller.clear();

		assertFalse((Boolean) controller.status().get("armed"));
		assertEquals("unarmed", controller.status().get("last_event"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void requiresAtLeastOneApprovedConsumable()
	{
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) -> CompletableFuture.completedFuture(dispatched()),
			escape -> CompletableFuture.completedFuture(escapeStarted()),
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> CompletableFuture.completedFuture(null),
			message -> { });

		controller.configure(4, Collections.emptyList(), null, false, false);
	}

	@Test
	public void startsConfiguredEscapeWhenNoConsumableCanBeUsed()
	{
		AtomicInteger escapes = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) -> CompletableFuture.completedFuture(rejected()),
			escape ->
			{
				escapes.incrementAndGet();
				return CompletableFuture.completedFuture(escapeStarted());
			},
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> CompletableFuture.completedFuture(null),
			message -> { });
		controller.configure(
			6,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(1993, "Drink", 11)),
			new GenericClientEmergencyEscape(
				new net.runelite.api.coords.WorldPoint(3225, 3218, 0), 3),
			false,
			false);

		controller.publishGameTick(snapshotWithHitpoints(6));

		assertEquals(1, escapes.get());
		assertEquals("emergency_escape_complete", controller.status().get("last_event"));
		assertFalse((Boolean) controller.status().get("armed"));
	}

	@Test
	public void missingFoodAboveTheHardFloorDoesNotStopOrEscape()
	{
		AtomicInteger stops = new AtomicInteger();
		AtomicInteger escapes = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) -> CompletableFuture.completedFuture(rejected()),
			escape ->
			{
				escapes.incrementAndGet();
				return CompletableFuture.completedFuture(escapeStarted());
			},
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason ->
			{
				assertEquals("emergency_consumable_unavailable", reason);
				stops.incrementAndGet();
				return CompletableFuture.completedFuture(null);
			},
			message -> { });
		controller.configure(
			4,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(379, "Eat", 12)),
			GenericClientEmergencyEscape.inventoryDialogue(
				2564,
				"Rub",
				"Castle Wars Arena",
				new net.runelite.api.coords.WorldPoint(2440, 3089, 0),
				10),
			true,
			true);

		controller.publishGameTick(snapshotWithHitpoints(14, 28));

		assertEquals(0, stops.get());
		assertEquals(0, escapes.get());
		assertEquals("no_approved_emergency_consumable_available",
			controller.status().get("last_event"));
		assertTrue((Boolean) controller.status().get("armed"));
	}

	@Test
	public void remainsRecoveringUntilEscapeCompletes()
	{
		CompletableFuture<Map<String, Object>> escape = new CompletableFuture<>();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) -> CompletableFuture.completedFuture(rejected()),
			ignored -> escape,
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> CompletableFuture.completedFuture(null),
			message -> { });
		controller.configure(
			6,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(1993, "Drink", 11)),
			new GenericClientEmergencyEscape(
				new net.runelite.api.coords.WorldPoint(3225, 3218, 0), 3),
			false,
			false);

		controller.publishGameTick(snapshotWithHitpoints(6));

		assertTrue((Boolean) controller.status().get("recovering"));
		assertEquals("escaping", controller.status().get("last_event"));

		escape.complete(escapeStarted());

		assertFalse((Boolean) controller.status().get("recovering"));
	}

	@Test
	public void consumesExactHealingAboveTheHardFloorAndContinuesCombat()
	{
		AtomicInteger consumptions = new AtomicInteger();
		AtomicInteger stops = new AtomicInteger();
		AtomicInteger escapes = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) ->
			{
				consumptions.incrementAndGet();
				return CompletableFuture.completedFuture(dispatched());
			},
			escape ->
			{
				escapes.incrementAndGet();
				return CompletableFuture.completedFuture(escapeStarted());
			},
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason ->
			{
				stops.incrementAndGet();
				return CompletableFuture.completedFuture(null);
			},
			message -> { });
		controller.configure(
			2,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(1234, "Eat", 6)),
			new GenericClientEmergencyEscape(
				new net.runelite.api.coords.WorldPoint(3225, 3218, 0), 3),
			true,
			false);

		controller.publishGameTick(snapshotWithHitpoints(4));

		assertEquals(1, consumptions.get());
		assertEquals(0, stops.get());
		assertEquals(0, escapes.get());
		assertEquals("emergency_consumable_dispatched", controller.status().get("last_event"));
		assertEquals(true, controller.status().get("continue_after_consumable"));
	}

	@Test
	public void rejectedOpportunisticHealDoesNotEscalateToEscape()
	{
		AtomicInteger stops = new AtomicInteger();
		AtomicInteger escapes = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) -> CompletableFuture.completedFuture(rejected()),
			escape ->
			{
				escapes.incrementAndGet();
				return CompletableFuture.completedFuture(escapeStarted());
			},
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason ->
			{
				stops.incrementAndGet();
				return CompletableFuture.completedFuture(null);
			},
			message -> { });
		controller.configure(
			2,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(1234, "Eat", 6)),
			new GenericClientEmergencyEscape(
				new net.runelite.api.coords.WorldPoint(3225, 3218, 0), 3),
			true,
			false);

		controller.publishGameTick(snapshotWithInventory(1, 4, 10, 1234, 1));

		assertEquals(0, stops.get());
		assertEquals(0, escapes.get());
		assertEquals("no_approved_emergency_consumable_available",
			controller.status().get("last_event"));
	}

	@Test
	public void pausesAndResumesCompositeInputAroundContinuingHeal()
	{
		AtomicInteger pauses = new AtomicInteger();
		AtomicInteger resumes = new AtomicInteger();
		AtomicInteger consumptions = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) ->
			{
				assertEquals(1, pauses.get());
				assertEquals(0, resumes.get());
				consumptions.incrementAndGet();
				return CompletableFuture.completedFuture(dispatched());
			},
			escape -> CompletableFuture.completedFuture(escapeStarted()),
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			new GenericClientEmergencyController.InputControl()
			{
				@Override
				public CompletableFuture<?> pause(String reason)
				{
					assertEquals("emergency_consumable", reason);
					pauses.incrementAndGet();
					return CompletableFuture.completedFuture(null);
				}

				@Override
				public CompletableFuture<?> resume(String reason)
				{
					assertEquals("emergency_consumable", reason);
					resumes.incrementAndGet();
					return CompletableFuture.completedFuture(null);
				}
			},
			reason -> CompletableFuture.completedFuture(null),
			message -> { });
		controller.configure(
			2,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(1234, "Eat", 6)),
			null,
			true,
			false);

		controller.publishGameTick(snapshotWithInventory(1, 4, 10, 1234, 1));

		assertEquals(1, pauses.get());
		assertEquals(1, consumptions.get());
		assertEquals(0, resumes.get());
		assertTrue((Boolean) controller.status().get("recovering"));
		assertTrue((Boolean) controller.status().get("consumable_pending"));

		controller.publishGameTick(snapshotWithInventory(2, 10, 10, 1234, 0));

		assertEquals(1, resumes.get());
		assertFalse((Boolean) controller.status().get("recovering"));
		assertFalse((Boolean) controller.status().get("consumable_pending"));
	}

	@Test
	public void clearCancelsAnActiveRecoveryAndIgnoresItsLateCompletion()
	{
		CompletableFuture<Map<String, Object>> escape = new CompletableFuture<>();
		AtomicInteger stops = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) -> CompletableFuture.completedFuture(rejected()),
			ignored -> escape,
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason ->
			{
				stops.incrementAndGet();
				return CompletableFuture.completedFuture(null);
			},
			message -> { });
		controller.configure(
			6,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(1993, "Drink", 11)),
			new GenericClientEmergencyEscape(
				new net.runelite.api.coords.WorldPoint(3225, 3218, 0), 3),
			false,
			false);
		controller.publishGameTick(snapshotWithHitpoints(6));

		assertTrue((Boolean) controller.status().get("recovering"));
		controller.clear().join();
		assertFalse((Boolean) controller.status().get("recovering"));
		assertEquals("unarmed", controller.status().get("last_event"));
		assertEquals(2, stops.get());

		escape.complete(escapeStarted());
		assertEquals("unarmed", controller.status().get("last_event"));
	}

	@Test
	public void keepsTheBreakBlockingLuaUntilEmergencyFoodIsDispatched()
	{
		CompletableFuture<Map<String, Object>> food = new CompletableFuture<>();
		AtomicInteger endedBreaks = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) -> food,
			escape -> CompletableFuture.completedFuture(escapeStarted()),
			() ->
			{
				endedBreaks.incrementAndGet();
				return CompletableFuture.completedFuture(Collections.emptyMap());
			},
			reason -> CompletableFuture.completedFuture(null),
			message -> { });
		controller.configure(
			15,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(1993, "Drink", 11)),
			null,
			true,
			true);

		controller.publishGameTick(snapshotWithHitpoints(15));

		assertEquals(0, endedBreaks.get());
		assertTrue((Boolean) controller.status().get("recovering"));

		food.complete(dispatched());

		assertEquals(1, endedBreaks.get());
		assertFalse((Boolean) controller.status().get("recovering"));
	}

	@Test
	public void stopsAndEscapesWhenOnlyFoodWouldOverheal()
	{
		AtomicInteger consumptions = new AtomicInteger();
		AtomicInteger stops = new AtomicInteger();
		AtomicInteger escapes = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) ->
			{
				consumptions.incrementAndGet();
				return CompletableFuture.completedFuture(dispatched());
			},
			escape ->
			{
				escapes.incrementAndGet();
				return CompletableFuture.completedFuture(escapeStarted());
			},
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason ->
			{
				assertEquals("emergency_consumable_unavailable", reason);
				stops.incrementAndGet();
				return CompletableFuture.completedFuture(null);
			},
			message -> { });
		controller.configure(
			4,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(1993, "Drink", 7)),
			new GenericClientEmergencyEscape(
				new net.runelite.api.coords.WorldPoint(3225, 3218, 0), 3),
			true,
			false);

		controller.publishGameTick(snapshotWithHitpoints(4));

		assertEquals(0, consumptions.get());
		assertEquals(1, stops.get());
		assertEquals(1, escapes.get());
		assertEquals("emergency_escape_complete", controller.status().get("last_event"));
	}

	@Test
	public void permitsExplicitLowLevelOverhealAtTheHardFloor()
	{
		AtomicInteger consumptions = new AtomicInteger();
		AtomicInteger stops = new AtomicInteger();
		AtomicInteger escapes = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) ->
			{
				consumptions.incrementAndGet();
				return CompletableFuture.completedFuture(dispatched());
			},
			escape ->
			{
				escapes.incrementAndGet();
				return CompletableFuture.completedFuture(escapeStarted());
			},
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason ->
			{
				stops.incrementAndGet();
				return CompletableFuture.completedFuture(null);
			},
			message -> { });
		controller.configure(
			3,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(1993, "Drink", 11)),
			new GenericClientEmergencyEscape(
				new net.runelite.api.coords.WorldPoint(3225, 3218, 0), 3),
			true,
			true);

		controller.publishGameTick(snapshotWithHitpoints(3));

		assertEquals(1, consumptions.get());
		assertEquals(0, stops.get());
		assertEquals(0, escapes.get());
		assertEquals("emergency_consumable_dispatched", controller.status().get("last_event"));
		assertEquals(true, controller.status().get("allow_overheal"));
	}

	@Test
	public void forcesFoodBelowThirtyPercentWithoutScriptSpecificOverheal()
	{
		AtomicInteger consumptions = new AtomicInteger();
		AtomicInteger stops = new AtomicInteger();
		AtomicInteger escapes = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) ->
			{
				consumptions.incrementAndGet();
				return CompletableFuture.completedFuture(dispatched());
			},
			escape ->
			{
				escapes.incrementAndGet();
				return CompletableFuture.completedFuture(escapeStarted());
			},
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason ->
			{
				stops.incrementAndGet();
				return CompletableFuture.completedFuture(null);
			},
			message -> { });
		controller.configure(
			1,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(1993, "Drink", 11)),
			new GenericClientEmergencyEscape(
				new net.runelite.api.coords.WorldPoint(3225, 3218, 0), 3),
			false,
			false);

		controller.publishGameTick(snapshotWithHitpoints(2));

		assertEquals(1, consumptions.get());
		assertEquals(0, stops.get());
		assertEquals(0, escapes.get());
		assertEquals("emergency_consumable_dispatched", controller.status().get("last_event"));
		assertEquals(30L, controller.status().get("forced_heal_percent"));
	}

	@Test
	public void waitsForTheConsumableToBeObservedBeforeEatingAgain()
	{
		AtomicInteger consumptions = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) ->
			{
				consumptions.incrementAndGet();
				return CompletableFuture.completedFuture(dispatched());
			},
			escape -> CompletableFuture.completedFuture(escapeStarted()),
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> CompletableFuture.completedFuture(null),
			message -> { });
		controller.configure(
			4,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(379, "Eat", 12)),
			null,
			true,
			true);

		controller.publishGameTick(snapshotWithInventory(1, 16, 28, 379, 2));
		controller.publishGameTick(snapshotWithInventory(2, 16, 28, 379, 2));
		controller.publishGameTick(snapshotWithInventory(3, 16, 28, 379, 2));

		assertEquals(1, consumptions.get());

		controller.publishGameTick(snapshotWithInventory(4, 28, 28, 379, 1));
		controller.publishGameTick(snapshotWithInventory(5, 16, 28, 379, 1));

		assertEquals(2, consumptions.get());
	}

	@Test
	public void unobservedHardFloorFoodEscapesOnlyAfterTheObservationBarrier()
	{
		AtomicInteger stops = new AtomicInteger();
		AtomicInteger escapes = new AtomicInteger();
		AtomicInteger resumes = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) -> CompletableFuture.completedFuture(dispatched()),
			escape ->
			{
				escapes.incrementAndGet();
				return CompletableFuture.completedFuture(escapeStarted());
			},
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			new GenericClientEmergencyController.InputControl()
			{
				@Override
				public CompletableFuture<?> pause(String reason)
				{
					return CompletableFuture.completedFuture(null);
				}

				@Override
				public CompletableFuture<?> resume(String reason)
				{
					resumes.incrementAndGet();
					return CompletableFuture.completedFuture(null);
				}
			},
			reason ->
			{
				stops.incrementAndGet();
				return CompletableFuture.completedFuture(null);
			},
			message -> { });
		controller.configure(
			4,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(379, "Eat", 12)),
			new GenericClientEmergencyEscape(
				new net.runelite.api.coords.WorldPoint(2440, 3089, 0), 3),
			true,
			true);

		for (long tick = 1; tick <= 4; tick++)
		{
			controller.publishGameTick(snapshotWithInventory(tick, 4, 28, 379, 1));
		}

		assertEquals(0, stops.get());
		assertEquals(0, escapes.get());
		assertEquals(0, resumes.get());
		assertTrue((Boolean) controller.status().get("recovering"));

		controller.publishGameTick(snapshotWithInventory(5, 4, 28, 379, 1));

		assertEquals(1, stops.get());
		assertEquals(1, escapes.get());
		assertEquals(0, resumes.get());
		assertFalse((Boolean) controller.status().get("recovering"));
	}
}
