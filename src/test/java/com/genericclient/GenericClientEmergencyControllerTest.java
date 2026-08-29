package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class GenericClientEmergencyControllerTest
{
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
			new GenericClientEmergencyController.Escape(
				new net.runelite.api.coords.WorldPoint(3225, 3218, 0), 3),
			false,
			false);

		controller.publishGameTick(snapshotWithHitpoints(6));

		assertEquals(1, escapes.get());
		assertEquals("emergency_escape_complete", controller.status().get("last_event"));
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
			new GenericClientEmergencyController.Escape(
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
			new GenericClientEmergencyController.Escape(
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
			new GenericClientEmergencyController.Escape(
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
			new GenericClientEmergencyController.Escape(
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
			new GenericClientEmergencyController.Escape(
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

	private static GenericClientSnapshot snapshotWithHitpoints(int hitpoints)
	{
		return new GenericClientSnapshot(
			1,
			"LOGGED_IN",
			231,
			new GenericClientSnapshot.PlayerSnapshot(
				"Player", 3200, 3200, 0, 0, -1, null,
				hitpoints, 10, 10_000, false, null),
			Collections.emptyList());
	}

	private static Map<String, Object> dispatched()
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "dispatched");
		receipt.put("result", "item_interaction_dispatched");
		receipt.put("click_count", 1L);
		return receipt;
	}

	private static Map<String, Object> rejected()
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "rejected");
		receipt.put("result", "missing_item");
		receipt.put("click_count", 0L);
		return receipt;
	}

	private static Map<String, Object> escapeStarted()
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "dispatched");
		receipt.put("result", "emergency_walk_started");
		receipt.put("click_count", 0L);
		return receipt;
	}
}
