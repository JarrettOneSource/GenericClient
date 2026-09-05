package com.genericclient;

import static com.genericclient.GenericClientEmergencyTestFixtures.dispatched;
import static com.genericclient.GenericClientEmergencyTestFixtures.escapeStarted;
import static com.genericclient.GenericClientEmergencyTestFixtures.snapshotWithHitpoints;
import static com.genericclient.GenericClientEmergencyTestFixtures.snapshotWithInventory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class GenericClientSafetyNetTest
{
	@Test
	public void safetyNetDoesNotEscapeAgainAtConfiguredDestination() throws Exception
	{
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
			reason -> CompletableFuture.completedFuture(null),
			message -> { });
		controller.configure(
			4,
			Collections.singletonList(
				new GenericClientEmergencyController.Consumable(379, "Eat", 12)),
			new GenericClientEmergencyEscape(
				new net.runelite.api.coords.WorldPoint(3200, 3200, 0), 3),
			true,
			true);
		controller.publishGameTick(snapshotWithHitpoints(28, 28), true);

		Map<String, Object> receipt = controller.recoverNow().get(2, TimeUnit.SECONDS);

		assertEquals("complete", receipt.get("status"));
		assertEquals("safety_recovery_not_needed_at_destination", receipt.get("result"));
		assertEquals(0, consumptions.get());
		assertEquals(0, escapes.get());
		assertFalse((Boolean) controller.status().get("armed"));
	}

	@Test
	public void safetyNetDoesNotEscapeAHealthyPlayerAwayFromDestination() throws Exception
	{
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
		controller.publishGameTick(snapshotWithHitpoints(28, 28), true);

		Map<String, Object> receipt = controller.recoverNow().get(2, TimeUnit.SECONDS);

		assertEquals("complete", receipt.get("status"));
		assertEquals("safety_recovery_not_needed_no_emergency", receipt.get("result"));
		assertEquals(0, consumptions.get());
		assertEquals(0, escapes.get());
		assertTrue((Boolean) controller.status().get("armed"));
		assertEquals("safety_recovery_not_needed_no_emergency",
			controller.status().get("last_event"));
	}

	@Test
	public void safetyNetCanForceAnApprovedEscapeWhenCombatStartsAtFullHealth() throws Exception
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
				assertEquals("safety_net_forced_escape", reason);
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
		controller.publishGameTick(snapshotWithHitpoints(28, 28), true);

		Map<String, Object> receipt = controller.forceEscapeNow().get(2, TimeUnit.SECONDS);

		assertEquals("dispatched", receipt.get("status"));
		assertEquals("emergency_escape_complete", receipt.get("result"));
		assertEquals(0, consumptions.get());
		assertEquals(1, escapes.get());
		assertEquals(1, stops.get());
		assertFalse((Boolean) controller.status().get("armed"));
	}

	@Test
	public void safetyNetSkipsEscapeWhenFoodClearsTheEmergency() throws Exception
	{
		AtomicInteger escapes = new AtomicInteger();
		GenericClientEmergencyController controller = new GenericClientEmergencyController(
			(itemId, action) -> CompletableFuture.completedFuture(dispatched()),
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
		controller.configureScriptBehavior(false, false);
		controller.publishGameTick(snapshotWithInventory(1, 4, 28, 379, 1));
		controller.resetScriptBehavior();

		CompletableFuture<Map<String, Object>> recovery = controller.recoverNow();
		assertFalse(recovery.isDone());

		controller.publishGameTick(snapshotWithInventory(2, 28, 28, 379, 0));
		Map<String, Object> receipt = recovery.get(2, TimeUnit.SECONDS);

		assertEquals(0, escapes.get());
		assertEquals("emergency_escape_no_longer_needed", receipt.get("result"));
	}
}
