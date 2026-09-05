package com.genericclient;

import static com.genericclient.GenericClientWalkTestFixtures.snapshot;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientWalkTransitionsTest
{
	@Test
	public void waitsForTheBasementLandingBeforeCompletingTheJourney() throws Exception
	{
		WorldPoint start = new WorldPoint(2906, 3476, 0);
		WorldPoint landing = new WorldPoint(2906, 9876, 0);
		List<GenericClientTransport.Step> dispatched = new ArrayList<>();
		CompletableFuture<Map<String, Object>> input = new CompletableFuture<>();
		GenericClientWalkTestFixtures.FakeWalkInput walking = new GenericClientWalkTestFixtures.FakeWalkInput();
		try (GenericClientWalker walker = GenericClientTestSupport.walkerWithTransitions(
			walking, (step, frame, context) -> { dispatched.add(step); return input; }))
		{
			walker.publishGameTick(ladderSnapshot(0, start));
			CompletableFuture<Map<String, Object>> result = walker.walkTo(new GenericClientWalkRequest(
				landing, 0, 100, GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (dispatched.isEmpty() && System.nanoTime() < deadline)
			{
				walker.publishGameTick(ladderSnapshot(1, start));
				Thread.sleep(5);
			}
			assertEquals(1, dispatched.size());
			GenericClientTransport.ObjectStep action = (GenericClientTransport.ObjectStep) dispatched.get(0);
			assertEquals(24718, action.id);
			assertEquals("Climb-down", action.action);
			assertTrue(walking.targets.isEmpty());
			assertFalse(result.isDone());
			input.complete(Map.of("status", "dispatched", "click_count", 1));
			walker.publishGameTick(ladderSnapshot(2, start));
			assertFalse(result.isDone());
			walker.publishGameTick(snapshot(3, landing));
			Map<String, Object> receipt = result.get(3, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			List<?> transitions = (List<?>) receipt.get("transports");
			assertEquals(1, transitions.size());
			assertEquals("arrived", ((Map<?, ?>) transitions.get(0)).get("status"));
		}
	}

	@Test
	public void interruptsRevokeTheClimbBeforeALateDispatchCallback() throws Exception
	{
		WorldPoint origin = new WorldPoint(2906, 3476, 0);
		List<GenericClientActivityContext> owners = new ArrayList<>();
		CompletableFuture<Map<String, Object>> input = new CompletableFuture<>();
		GenericClientWalkTestFixtures.FakeWalkInput walking = new GenericClientWalkTestFixtures.FakeWalkInput();
		try (GenericClientWalker walker = GenericClientTestSupport.walkerWithTransitions(walking,
			(step, frame, context) -> { owners.add(context); return input; }))
		{
			walker.publishGameTick(ladderSnapshot(0, origin));
			CompletableFuture<Map<String, Object>> result = walker.walkTo(new GenericClientWalkRequest(
				new WorldPoint(2906, 9876, 0), 0, 100, GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.parse(java.util.Map.of("dialogue", true)), List.of(), null));
			awaitInput(walker, origin, owners);
			walker.publishGameTick(GenericClientWalkTestFixtures.dialogueSnapshot(2, origin));
			Map<String, Object> receipt = result.get(3, TimeUnit.SECONDS);
			assertEquals("interrupted", receipt.get("status"));
			assertEquals("dialogue", receipt.get("reason"));
			assertFalse(owners.get(0).isInputAllowed());
			input.complete(Map.of("status", "dispatched"));
			List<?> records = (List<?>) receipt.get("transports");
			Map<?, ?> record = (Map<?, ?>) records.get(0);
			assertEquals("interrupted", record.get("status"));
			assertEquals(List.of(), record.get("actions"));
		}
	}

	@Test
	public void doesNotCountNativeInputTimeAgainstTheTransitionOrWalkBudget() throws Exception
	{
		WorldPoint origin = new WorldPoint(2906, 3476, 0);
		List<GenericClientActivityContext> owners = new ArrayList<>();
		CompletableFuture<Map<String, Object>> input = new CompletableFuture<>();
		try (GenericClientWalker walker = GenericClientTestSupport.walkerWithTransitions(new GenericClientWalkTestFixtures.FakeWalkInput(),
			(step, frame, context) -> { owners.add(context); return input; }))
		{
			walker.publishGameTick(ladderSnapshot(0, origin));
			CompletableFuture<Map<String, Object>> result = walker.walkTo(new GenericClientWalkRequest(
				new WorldPoint(2906, 9876, 0), 0, 60, GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null));
			awaitInput(walker, origin, owners);
			walker.publishGameTick(ladderSnapshot(100, origin));
			assertFalse(result.isDone());
			input.complete(Map.of("status", "dispatched"));
			walker.publishGameTick(snapshot(102, new WorldPoint(2906, 9876, 0)));
			Map<String, Object> receipt = result.get(3, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			assertEquals(102L, receipt.get("game_ticks"));
			assertEquals(3L, receipt.get("active_game_ticks"));
			Map<?, ?> transition = (Map<?, ?>) ((List<?>) receipt.get("transports")).get(0);
			assertEquals(101L, transition.get("game_ticks"));
		}
	}

	@Test
	public void capsClicksBeforeTheDiscontinuousEdge() throws Exception
	{
		WorldPoint start = new WorldPoint(2905, 3476, 0);
		WorldPoint origin = new WorldPoint(2906, 3476, 0);
		GenericClientWalkTestFixtures.FakeWalkInput walking = new GenericClientWalkTestFixtures.FakeWalkInput();
		try (GenericClientWalker walker = GenericClientTestSupport.walkerWithTransitions(walking,
			(step, frame, context) -> CompletableFuture.failedFuture(new AssertionError("Climb cannot start before reaching its origin"))))
		{
			walker.publishGameTick(ladderSnapshot(0, start));
			walker.walkTo(new GenericClientWalkRequest(new WorldPoint(2906, 9876, 0), 0, 100,
				GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (walking.targets.isEmpty() && System.nanoTime() < deadline)
			{
				walker.publishGameTick(ladderSnapshot(1, start));
				Thread.sleep(5);
			}
			assertEquals(List.of(origin), walking.targets);
		}
	}

	@Test
	public void aNativeCompletionDuringSceneLoadingWaitsForTheNewLandingFrame() throws Exception
	{
		WorldPoint origin = new WorldPoint(2906, 3476, 0);
		WorldPoint landing = new WorldPoint(2906, 9876, 0);
		List<GenericClientActivityContext> owners = new ArrayList<>();
		CompletableFuture<Map<String, Object>> input = new CompletableFuture<>();
		try (GenericClientWalker walker = GenericClientTestSupport.walkerWithTransitions(new GenericClientWalkTestFixtures.FakeWalkInput(),
			(step, frame, context) -> { owners.add(context); return input; }))
		{
			walker.publishGameTick(ladderSnapshot(0, origin));
			CompletableFuture<Map<String, Object>> result = walker.walkTo(new GenericClientWalkRequest(
				landing, 0, 60, GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null));
			awaitInput(walker, origin, owners);
			walker.publishGameTick(ladderSnapshot(100, origin));
			walker.clearSnapshot();
			input.complete(Map.of("status", "dispatched"));
			assertFalse(result.isDone());
			walker.publishGameTick(snapshot(102, landing));
			Map<String, Object> receipt = result.get(3, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			assertEquals(3L, receipt.get("active_game_ticks"));
		}
	}

	@Test
	public void observesALandingDuringPauseWithoutRevivingTheCancelledClimb() throws Exception
	{
		WorldPoint origin = new WorldPoint(2906, 3476, 0);
		WorldPoint landing = new WorldPoint(2906, 9876, 0);
		List<GenericClientActivityContext> owners = new ArrayList<>();
		CompletableFuture<Map<String, Object>> input = new CompletableFuture<>();
		try (GenericClientWalker walker = GenericClientTestSupport.walkerWithTransitions(new GenericClientWalkTestFixtures.FakeWalkInput(),
			(step, frame, context) -> { owners.add(context); return input; }))
		{
			walker.publishGameTick(ladderSnapshot(0, origin));
			CompletableFuture<Map<String, Object>> result = walker.walkTo(new GenericClientWalkRequest(
				landing, 0, 60, GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null));
			awaitInput(walker, origin, owners);
			walker.publishGameTick(ladderSnapshot(10, origin));
			walker.pauseActiveInput("emergency");
			assertFalse(owners.get(0).isInputAllowed());
			walker.publishGameTick(snapshot(100, landing));
			walker.resumeActiveInput("recovered");
			walker.publishGameTick(snapshot(101, landing));
			Map<String, Object> receipt = result.get(3, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			Map<?, ?> transition = (Map<?, ?>) ((List<?>) receipt.get("transports")).get(0);
			assertEquals("arrived", transition.get("status"));
			assertEquals(2L, receipt.get("active_game_ticks"));
			assertEquals(1L, transition.get("active_game_ticks"));
			input.complete(Map.of("status", "dispatched"));
			assertEquals(List.of(), transition.get("actions"));
		}
	}

	@Test
	public void resumesByWaitingForTheAttemptedClimbWithoutRepeatingIt() throws Exception
	{
		WorldPoint origin = new WorldPoint(2906, 3476, 0);
		WorldPoint landing = new WorldPoint(2906, 9876, 0);
		List<GenericClientActivityContext> owners = new ArrayList<>();
		try (GenericClientWalker walker = GenericClientTestSupport.walkerWithTransitions(new GenericClientWalkTestFixtures.FakeWalkInput(),
			(step, frame, context) -> { owners.add(context); return new CompletableFuture<>(); }))
		{
			walker.publishGameTick(ladderSnapshot(0, origin));
			CompletableFuture<Map<String, Object>> result = walker.walkTo(new GenericClientWalkRequest(
				landing, 0, 60, GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null));
			awaitInput(walker, origin, owners);
			walker.publishGameTick(ladderSnapshot(40, origin));
			walker.pauseActiveInput("emergency");
			walker.publishGameTick(ladderSnapshot(140, origin));
			walker.resumeActiveInput("recovered");
			walker.publishGameTick(ladderSnapshot(141, origin));
			assertEquals(1, owners.size());
			walker.publishGameTick(snapshot(143, landing));
			Map<String, Object> receipt = result.get(3, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			assertEquals(4L, receipt.get("active_game_ticks"));
		}
	}

	@Test
	public void aRunToggleCannotCompeteWithATransportWaitingToLand() throws Exception
	{
		WorldPoint origin = new WorldPoint(2906, 3476, 0);
		List<Boolean> runChanges = new ArrayList<>();
		GenericClientWalkTestFixtures.RecordingObstacleInput input = new GenericClientWalkTestFixtures.RecordingObstacleInput();
		try (GenericClientWalker walker = GenericClientTestSupport.walker(new GenericClientWalkTestFixtures.FakeWalkInput(), input,
			(enabled, context) -> { runChanges.add(enabled); return CompletableFuture.completedFuture(Map.of("status", "complete")); },
			(reason, context) -> { }, GenericClientCollisionMap.loadBundled(), message -> { }))
		{
			walker.publishGameTick(ladderSnapshot(0, origin));
			walker.walkTo(new GenericClientWalkRequest(new WorldPoint(2906, 9876, 0), 0, 100,
				GenericClientActivityContext.none(), true, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (input.interactions == 0 && System.nanoTime() < deadline)
			{
				walker.publishGameTick(ladderSnapshot(1, origin));
				Thread.sleep(5);
			}
			assertEquals(1, input.interactions);
			walker.publishGameTick(GenericClientWalkTestFixtures.runSnapshot(2, origin, 10000, false));
			assertTrue(runChanges.isEmpty());
		}
	}

	@Test
	public void aContinuationDoesNotRepeatAnInterruptedClimbBeforeObservingItsLanding() throws Exception
	{
		WorldPoint origin = new WorldPoint(2906, 3476, 0);
		WorldPoint landing = new WorldPoint(2906, 9876, 0);
		List<GenericClientActivityContext> owners = new ArrayList<>();
		CompletableFuture<Map<String, Object>> input = new CompletableFuture<>();
		try (GenericClientWalker walker = GenericClientTestSupport.walkerWithTransitions(new GenericClientWalkTestFixtures.FakeWalkInput(),
			(step, frame, context) -> { owners.add(context); return input; }))
		{
			walker.publishGameTick(ladderSnapshot(0, origin));
			CompletableFuture<Map<String, Object>> result = walker.walkTo(new GenericClientWalkRequest(
				landing, 0, 100, GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.parse(java.util.Map.of("dialogue", true)), List.of(), null));
			awaitInput(walker, origin, owners);
			walker.publishGameTick(GenericClientWalkTestFixtures.dialogueSnapshot(2, origin));
			Map<String, Object> interrupted = result.get(3, TimeUnit.SECONDS);
			walker.publishGameTick(ladderSnapshot(3, origin));
			CompletableFuture<Map<String, Object>> resumed = walker.walkTo(new GenericClientWalkRequest(
				landing, 0, 100, GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.parse(java.util.Map.of("dialogue", true)), List.of(), (String) interrupted.get("continuation")));
			for (int tick = 4; tick <= 8; tick++)
			{
				walker.publishGameTick(ladderSnapshot(tick, origin));
				Thread.sleep(10);
			}
			assertEquals(1, owners.size());
			input.complete(Map.of("status", "dispatched"));
			walker.publishGameTick(snapshot(9, landing));
			Map<String, Object> receipt = resumed.get(3, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			Map<?, ?> transition = (Map<?, ?>) ((List<?>) receipt.get("transports")).get(0);
			assertEquals(true, transition.get("resumed"));
			assertEquals("arrived", transition.get("status"));
		}
	}

	@Test
	public void interruptionDuringAnEmergencyPausePreservesTheRemainingTransportBudget() throws Exception
	{
		WorldPoint origin = new WorldPoint(2906, 3476, 0);
		WorldPoint landing = new WorldPoint(2906, 9876, 0);
		List<GenericClientActivityContext> owners = new ArrayList<>();
		try (GenericClientWalker walker = GenericClientTestSupport.walkerWithTransitions(new GenericClientWalkTestFixtures.FakeWalkInput(),
			(step, frame, context) -> { owners.add(context); return CompletableFuture.completedFuture(Map.of("status", "dispatched")); }))
		{
			walker.publishGameTick(ladderSnapshot(0, origin));
			CompletableFuture<Map<String, Object>> first = walker.walkTo(new GenericClientWalkRequest(
				landing, 0, 100, GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.parse(java.util.Map.of("dialogue", true)), List.of(), null));
			awaitInput(walker, origin, owners);
			walker.publishGameTick(ladderSnapshot(2, origin));
			walker.pauseActiveInput("emergency");
			walker.publishGameTick(GenericClientWalkTestFixtures.dialogueSnapshot(102, origin));
			Map<String, Object> interrupted = first.get(3, TimeUnit.SECONDS);
			assertEquals(2L, interrupted.get("active_game_ticks"));
			walker.publishGameTick(ladderSnapshot(103, origin));
			CompletableFuture<Map<String, Object>> resumed = walker.walkTo(new GenericClientWalkRequest(
				landing, 0, 100, GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.parse(java.util.Map.of("dialogue", true)), List.of(), (String) interrupted.get("continuation")));
			walker.publishGameTick(ladderSnapshot(104, origin));
			assertFalse(resumed.isDone());
			assertEquals(1, owners.size());
			walker.publishGameTick(snapshot(105, landing));
			assertEquals("arrived", resumed.get(3, TimeUnit.SECONDS).get("status"));
		}
	}

	@Test
	public void resumingDoesNotResetTheActiveTickLimitForAnUnverifiedLanding() throws Exception
	{
		WorldPoint origin = new WorldPoint(2906, 3476, 0);
		WorldPoint landing = new WorldPoint(2906, 9876, 0);
		List<GenericClientActivityContext> owners = new ArrayList<>();
		try (GenericClientWalker walker = GenericClientTestSupport.walkerWithTransitions(new GenericClientWalkTestFixtures.FakeWalkInput(),
			(step, frame, context) -> { owners.add(context); return CompletableFuture.completedFuture(Map.of("status", "dispatched")); }))
		{
			walker.publishGameTick(ladderSnapshot(0, origin));
			CompletableFuture<Map<String, Object>> first = walker.walkTo(new GenericClientWalkRequest(
				landing, 0, 100, GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.parse(java.util.Map.of("dialogue", true)), List.of(), null));
			awaitInput(walker, origin, owners);
			walker.publishGameTick(GenericClientWalkTestFixtures.dialogueSnapshot(59, origin));
			Map<String, Object> interrupted = first.get(3, TimeUnit.SECONDS);
			walker.publishGameTick(ladderSnapshot(100, origin));
			CompletableFuture<Map<String, Object>> resumed = walker.walkTo(new GenericClientWalkRequest(
				landing, 0, 100, GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.parse(java.util.Map.of("dialogue", true)), List.of(), (String) interrupted.get("continuation")));
			walker.publishGameTick(ladderSnapshot(101, origin));
			assertFalse(resumed.isDone());
			walker.publishGameTick(ladderSnapshot(102, origin));
			Map<String, Object> receipt = resumed.get(3, TimeUnit.SECONDS);
			Map<?, ?> transition = (Map<?, ?>) ((List<?>) receipt.get("transports")).get(0);
			assertEquals("failed", transition.get("status"));
			assertEquals("arrival_unverified", transition.get("reason"));
			assertEquals(2L, transition.get("active_game_ticks"));
			assertEquals(1, owners.size());
		}
	}

	@Test
	public void aRejectedMissingOrFailedNativeReceiptBlocksTheServiceBeforeReplanning() throws Exception
	{
		WorldPoint origin = new WorldPoint(2906, 3476, 0);
		List<Map.Entry<CompletableFuture<Map<String, Object>>, String>> failures = List.of(
			Map.entry(CompletableFuture.completedFuture(Map.of("status", "rejected", "result", "matching_object_not_found")), "matching_object_not_found"),
			Map.entry(CompletableFuture.completedFuture(null), "missing_receipt"),
			Map.entry(CompletableFuture.failedFuture(new IllegalStateException("camera_unavailable")), "camera_unavailable"));
		for (Map.Entry<CompletableFuture<Map<String, Object>>, String> failure : failures)
		{
			List<GenericClientActivityContext> owners = new ArrayList<>();
			try (GenericClientWalker walker = GenericClientTestSupport.walkerWithTransitions(new GenericClientWalkTestFixtures.FakeWalkInput(),
				(step, frame, context) -> { owners.add(context); return failure.getKey(); }))
			{
				walker.publishGameTick(ladderSnapshot(0, origin));
				CompletableFuture<Map<String, Object>> result = walker.walkTo(new GenericClientWalkRequest(
					new WorldPoint(2906, 9876, 0), 0, 100, GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null));
				awaitInput(walker, origin, owners);
				walker.publishGameTick(ladderSnapshot(2, origin));
				Map<String, Object> receipt = result.get(3, TimeUnit.SECONDS);
				Map<?, ?> transport = (Map<?, ?>) ((List<?>) receipt.get("transports")).get(0);
				assertEquals("failed", transport.get("status"));
				assertEquals(failure.getValue(), transport.get("reason"));
				assertEquals(2L, receipt.get("plans"));
				assertEquals(1, owners.size());
			}
		}
	}

	@Test
	public void anAbsentLadderTimesOutWithoutSubmittingAnInput() throws Exception
	{
		WorldPoint origin = new WorldPoint(2906, 3476, 0);
		GenericClientWalkTestFixtures.RecordingObstacleInput inputs = new GenericClientWalkTestFixtures.RecordingObstacleInput();
		List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
		try (GenericClientWalker walker = GenericClientTestSupport.walker(new GenericClientWalkTestFixtures.FakeWalkInput(), inputs,
			GenericClientCollisionMap.loadBundled(), reports::add))
		{
			walker.publishGameTick(snapshot(0, origin));
			CompletableFuture<Map<String, Object>> result = walker.walkTo(new GenericClientWalkRequest(
				new WorldPoint(2906, 9876, 0), 0, 100, GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (reports.stream().noneMatch(message -> message.startsWith("WALK_PLANNED")) && System.nanoTime() < deadline) Thread.sleep(5);
			assertTrue(reports.stream().anyMatch(message -> message.startsWith("WALK_PLANNED")));
			walker.publishGameTick(snapshot(1, origin));
			walker.publishGameTick(snapshot(60, origin));
			assertFalse(result.isDone());
			walker.publishGameTick(snapshot(61, origin));
			Map<String, Object> receipt = result.get(3, TimeUnit.SECONDS);
			Map<?, ?> transport = (Map<?, ?>) ((List<?>) receipt.get("transports")).get(0);
			assertEquals("target_not_available", transport.get("reason"));
			assertEquals(60L, transport.get("active_game_ticks"));
			assertEquals(0, inputs.interactions);
		}
	}

	private static void awaitInput(GenericClientWalker walker, WorldPoint origin, List<GenericClientActivityContext> owners) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (owners.isEmpty() && System.nanoTime() < deadline)
		{
			walker.publishGameTick(ladderSnapshot(1, origin));
			Thread.sleep(5);
		}
		assertEquals(1, owners.size());
	}

	private static GenericClientSnapshot ladderSnapshot(long tick, WorldPoint player)
	{
		GenericClientQuestSnapshot quest = new GenericClientQuestSnapshot(true, new int[0],
			List.of(new GenericClientQuestSnapshot.ObjectSnapshot(3L, 24718, "Ladder", "game",
				2907, 3476, 0, 1, List.of("Climb-down"))), GenericClientQuestSnapshot.DialogueSnapshot.closed());
		return new GenericClientSnapshot(tick, "LOGGED_IN", 240,
			new GenericClientPlayerSnapshot(1L,"walker-test", player.getX(), player.getY(), player.getPlane(), 0),
			List.of(), GenericClientAccountSnapshot.empty(), quest);
	}
}
