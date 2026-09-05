package com.genericclient;

import static com.genericclient.GenericClientWalkTestFixtures.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientWalkerTest
{
	@Test
	public void excludesRequestedAvoidTilesFromThePlannedRoute() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			WorldPoint avoided = new WorldPoint(3204, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			WorldPoint destination = new WorldPoint(3207, 3428, 0);
			CompletableFuture<Map<String, Object>> completion = walker.walkTo(new GenericClientWalkRequest(destination, 0, 60, context(false),
				true, Collections.singletonList(avoided), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));

			waitForFirstClick(walker, input, start);
			for (WorldPoint candidate : input.candidateBatches.get(0))
			{
				assertNotEquals(avoided, candidate);
			}
			walker.publishGameTick(snapshot(1, destination));
			Map<String, Object> receipt = completion.get(3, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			assertEquals(1L, receipt.get("avoid_tiles"));
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void canLeaveAStartingTileThatIsAlsoAvoided() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			WorldPoint destination = new WorldPoint(3205, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			walker.walkTo(new GenericClientWalkRequest(destination, 0, 60, context(false),
				true, Collections.singletonList(start), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));

			waitForFirstClick(walker, input, start);
			assertEquals(destination, input.candidateBatches.get(0).get(0));
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void scalesRecoveryBudgetForLongRoutesWithinABoundedMaximum()
	{
		assertEquals(6, GenericClientWalkJourney.recoveryPlanLimit(
			new WorldPoint(3200, 3200, 0), new WorldPoint(3215, 3200, 0)));
		assertEquals(24, GenericClientWalkJourney.recoveryPlanLimit(
			new WorldPoint(3000, 3200, 0), new WorldPoint(3300, 3200, 0)));
		assertEquals(32, GenericClientWalkJourney.recoveryPlanLimit(
			new WorldPoint(1000, 1000, 0), new WorldPoint(3000, 3000, 0)));
	}

	@Test
	public void normalCadenceRefreshesBeforeReachingThePreviousTarget() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			WorldPoint destination = new WorldPoint(3230, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			CompletableFuture<?> completion = walker.walkTo(new GenericClientWalkRequest(destination, 0, 60, context(false),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));

			waitForFirstClick(walker, input, start);
			assertEquals(destination, input.candidateBatches.get(0).get(0));
			assertEquals(Boolean.FALSE, input.breakPolicies.get(0));
			WorldPoint firstTarget = input.targets.get(0);
			assertTrue(distance(start, firstTarget) >= 8);
			WorldPoint partialProgress = stepToward(start, firstTarget);
			assertTrue(distance(partialProgress, firstTarget) > 2);

			walker.publishGameTick(snapshot(2, partialProgress));
			walker.publishGameTick(snapshot(3, partialProgress));

			assertEquals(1, input.targets.size());
			assertFalse(completion.isDone());

			walker.publishGameTick(snapshot(7, partialProgress));
			assertEquals(2, input.targets.size());
			assertEquals(Boolean.FALSE, input.breakPolicies.get(1));
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void normalCadenceAlsoRefreshesTheFinalTargetUntilArrival() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			WorldPoint destination = new WorldPoint(3207, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			CompletableFuture<Map<String, Object>> completion = walker.walkTo(new GenericClientWalkRequest(destination, 0, 60, context(true),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));

			waitForFirstClick(walker, input, start);
			assertEquals(Boolean.TRUE, input.breakPolicies.get(0));
			assertEquals(destination, input.targets.get(0));
			WorldPoint oneTileAway = stepToward(
				new WorldPoint(destination.getX() - 2, destination.getY(), destination.getPlane()),
				destination);
			walker.publishGameTick(snapshot(2, oneTileAway));
			walker.publishGameTick(snapshot(3, oneTileAway));

			assertEquals(1, input.targets.size());
			walker.publishGameTick(snapshot(7, oneTileAway));
			assertEquals(2, input.targets.size());
			walker.publishGameTick(snapshot(8, destination));
			assertEquals("arrived", completion.get(2, TimeUnit.SECONDS).get("status"));
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void plannedLongClickGapAtAnAcceptedTargetDoesNotTriggerStallRecovery() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput(5);
		List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
		GenericClientWalker walker = GenericClientTestSupport.walker(input, new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(), reports::add);
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			GenericClientWalkRequest request = new GenericClientWalkRequest(new WorldPoint(3230, 3428, 0),
				0, 200, context(false), false, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null);
			walker.walkTo(request, new GenericClientWalker.ClickBoundary()
			{
				@Override public CompletableFuture<GenericClientInteractionResult> execute(
					GenericClientActivityContext scope,
					java.util.function.Supplier<CompletableFuture<GenericClientInteractionResult>> action) { return action.get(); }
				@Override public int nextClickDelayTicks() { return 20; }
			});
			waitForFirstClick(walker, input, start);
			WorldPoint accepted = input.targets.get(0);
			for (int tick = 2; tick < 21; tick++) walker.publishGameTick(snapshot(tick, accepted));
			assertEquals(1, input.targets.size());
			assertFalse(reports.stream().anyMatch(value -> value.contains("WALK_PATH_RETRY")));
			walker.publishGameTick(snapshot(21, accepted));
			assertEquals(2, input.targets.size());
		}
		finally { walker.close(); }
	}

	@Test
	public void hazardousTravelRefreshesTheFinalWaypointUntilArrival() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			WorldPoint destination = new WorldPoint(3207, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			CompletableFuture<Map<String, Object>> completion = walker.walkTo(new GenericClientWalkRequest(destination, 0, 60, hazardousContext(),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));

			waitForFirstClick(walker, input, start);
			assertEquals(destination, input.targets.get(0));
			walker.publishGameTick(snapshot(2, new WorldPoint(3203, 3428, 0)));
			walker.publishGameTick(snapshot(3, new WorldPoint(3205, 3428, 0)));

			assertTrue(input.targets.size() >= 3);
			int beforeArrival = input.targets.size();
			walker.publishGameTick(snapshot(4, destination));
			assertEquals("arrived", completion.get(3, TimeUnit.SECONDS).get("status"));
			assertEquals(beforeArrival, input.targets.size());
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void hazardousFinalClicksAlternateOnlyInsideTheArrivalRadius() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		GenericClientWalker walker = GenericClientTestSupport.walker(input, new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(), message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			WorldPoint destination = new WorldPoint(3210, 3428, 0);
			walker.publishGameTick(openSceneSnapshot(0, start));
			walker.walkTo(new GenericClientWalkRequest(destination, 2, 60, hazardousContext(),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			waitForFirstClick(walker, input, start);
			walker.publishGameTick(openSceneSnapshot(2, start));
			assertEquals(2, input.targets.size());
			assertNotEquals(input.targets.get(0), input.targets.get(1));
			assertTrue(distance(input.targets.get(0), destination) <= 2);
			assertTrue(distance(input.targets.get(1), destination) <= 2);
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void emergencyPauseKeepsTheWalkAliveAndResumesInput() throws Exception
	{
		DeferredWalkInput input = new DeferredWalkInput();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			CompletableFuture<Map<String, Object>> completion = walker.walkTo(new GenericClientWalkRequest(new WorldPoint(3230, 3428, 0), 0, 60, context(false),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (input.calls == 0 && System.nanoTime() < deadline)
			{
				walker.publishGameTick(snapshot(1, start));
				Thread.sleep(10L);
			}
			assertEquals(1, input.calls);

			walker.pauseActiveInput("emergency_consumable");
			assertEquals(1, input.cancellations);
			input.completeFirstAsCancelled();
			walker.publishGameTick(snapshot(2, start));
			assertEquals(1, input.calls);
			assertFalse(completion.isDone());

			walker.resumeActiveInput("emergency_consumable");
			for (long tick = 3; tick <= 6 && input.calls < 2; tick++)
			{
				walker.publishGameTick(snapshot(tick, start));
			}
			assertEquals(2, input.calls);
			assertFalse(completion.isDone());
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void rejoinsTheExistingRouteWhenAnAcceptedClickMovesOffRoute() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			reports::add);
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			CompletableFuture<Map<String, Object>> completion = walker.walkTo(new GenericClientWalkRequest(new WorldPoint(3230, 3428, 0), 0, 60, context(false),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			waitForFirstClick(walker, input, start);

			WorldPoint displaced = new WorldPoint(3203, 3433, 0);
			walker.publishGameTick(snapshot(2, displaced));
			waitForClickCount(walker, input, displaced, 2, 3);
			walker.cancelActive("test_finished");
			Map<String, Object> receipt = completion.get(3, TimeUnit.SECONDS);
			assertEquals(1, receipt.get("full_plans"));
			assertEquals(1, receipt.get("local_rejoins"));
		}
		finally
		{
			walker.close();
		}
	}


	@Test
	public void backsOffAfterTheFarthestAcceptedClickProducesNoMovement() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput(Integer.MAX_VALUE);
		List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			reports::add);
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			walker.walkTo(new GenericClientWalkRequest(new WorldPoint(3230, 3428, 0), 0, 60, context(false),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			waitForFirstClick(walker, input, start);
			int firstDistance = distance(start, input.targets.get(0));

			for (long tick = 2; tick <= 8; tick++)
			{
				walker.publishGameTick(snapshot(tick, start));
			}
			waitForClickCount(walker, input, start, 3, 10);

			int recoveryDistance = distance(start, input.targets.get(2));
			assertTrue(recoveryDistance <= firstDistance - 3);
			assertTrue(reports.stream().anyMatch(message -> message.contains("WALK_PATH_RETRY")));
			assertEquals(1, reports.stream()
				.filter(message -> message.contains("WALK_PLANNING"))
				.count());
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void replansAroundANpcOccupyingTheNextRouteTile() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			reports::add);
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			WorldPoint blocked = new WorldPoint(3203, 3428, 0);
			WorldPoint destination = new WorldPoint(3207, 3428, 0);
			List<GenericClientWorldSnapshot.NpcSnapshot> npcs = Collections.singletonList(
				npc(1, blocked));
			walker.publishGameTick(snapshot(0, start));
			walker.walkTo(new GenericClientWalkRequest(destination, 0, 60, hazardousContext(),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			waitForFirstClick(walker, input, start);

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			long tick = 2;
			for (; tick <= 20 && reports.stream().noneMatch(message -> message.contains("WALK_NPC_BLOCK_REPLAN")); tick++)
			{
				walker.publishGameTick(snapshot(tick, start, npcs));
				Thread.sleep(10L);
			}

			assertTrue(reports.stream().anyMatch(
				message -> message.contains("WALK_NPC_BLOCK_REPLAN") &&
					message.contains(blocked.toString())));
			int beforeReplanClick = input.candidateBatches.size();
			while (input.candidateBatches.size() == beforeReplanClick && System.nanoTime() < deadline)
			{
				walker.publishGameTick(snapshot(tick++, start, npcs));
				Thread.sleep(10L);
			}
			assertTrue(input.candidateBatches.size() > beforeReplanClick);
			for (WorldPoint candidate : input.candidateBatches.get(beforeReplanClick))
			{
				assertNotEquals(blocked, candidate);
			}
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void replansAroundANpcFurtherAlongTheAcceptedLeg() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			reports::add);
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			WorldPoint blocked = new WorldPoint(3205, 3428, 0);
			WorldPoint destination = new WorldPoint(3207, 3428, 0);
			List<GenericClientWorldSnapshot.NpcSnapshot> npcs = Collections.singletonList(
				npc(1, blocked));
			walker.publishGameTick(snapshot(0, start));
			walker.walkTo(new GenericClientWalkRequest(destination, 0, 60, context(false),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			waitForFirstClick(walker, input, start);

			long tick = 2;
			for (; tick <= 20 && reports.stream().noneMatch(message -> message.contains("WALK_NPC_BLOCK_REPLAN")); tick++)
			{
				walker.publishGameTick(snapshot(tick, start, npcs));
				Thread.sleep(10L);
			}

			assertTrue(reports.stream().anyMatch(message ->
				message.contains("WALK_NPC_BLOCK_REPLAN") &&
					message.contains(blocked.toString())));
			int beforeReplanClick = input.candidateBatches.size();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (input.candidateBatches.size() == beforeReplanClick && System.nanoTime() < deadline)
			{
				walker.publishGameTick(snapshot(tick++, start, npcs));
				Thread.sleep(10L);
			}
			assertTrue(input.candidateBatches.size() > beforeReplanClick);
			assertEquals(1, input.candidateBatches.get(beforeReplanClick).size());
			for (WorldPoint candidate : input.candidateBatches.get(beforeReplanClick))
			{
				assertNotEquals(blocked, candidate);
			}
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void waitsForNpcBodyBlockWithoutConsumingTheWalkTimeout() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			reports::add);
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			WorldPoint destination = new WorldPoint(3204, 3428, 0);
			List<GenericClientWorldSnapshot.NpcSnapshot> blockers = new ArrayList<>();
			int index = 1;
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					if (dx != 0 || dy != 0)
					{
						blockers.add(npc(index++, new WorldPoint(
							start.getX() + dx, start.getY() + dy, start.getPlane())));
					}
				}
			}
			walker.publishGameTick(snapshot(0, start));
			CompletableFuture<Map<String, Object>> completion = walker.walkTo(new GenericClientWalkRequest(destination, 0, 20, hazardousContext(),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			waitForFirstClick(walker, input, start);

			for (long tick = 2; tick <= 50; tick++)
			{
				walker.publishGameTick(snapshot(tick, start, blockers));
				Thread.sleep(5L);
			}

			assertFalse(completion.isDone());
			assertTrue(reports.stream().anyMatch(
				message -> message.contains("WALK_NPC_BLOCK_WAIT")));
			int clicksWhileBlocked = input.targets.size();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			for (long tick = 51; input.targets.size() == clicksWhileBlocked &&
				System.nanoTime() < deadline; tick++)
			{
				walker.publishGameTick(snapshot(tick, start));
				Thread.sleep(10L);
			}
			assertTrue(input.targets.size() > clicksWhileBlocked);
			walker.publishGameTick(snapshot(60, destination));
			assertEquals("arrived", completion.get(3, TimeUnit.SECONDS).get("status"));
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void enablesRunBeforeTheFirstRouteClickByDefault() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		AtomicInteger runToggles = new AtomicInteger();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			(enabled, breaks) ->
			{
				assertTrue(enabled);
				runToggles.incrementAndGet();
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "complete");
				receipt.put("result", "run_enabled");
				receipt.put("click_count", 1L);
				return CompletableFuture.completedFuture(receipt);
			},
			(reason, owner) -> { },
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			walker.publishGameTick(runSnapshot(0, start, 10_000, false));
			walker.walkTo(new GenericClientWalkRequest(new WorldPoint(3210, 3428, 0), 0, 60, context(false),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			long tick = 1;
			while (runToggles.get() == 0 && System.nanoTime() < deadline)
			{
				walker.publishGameTick(runSnapshot(tick++, start, 10_000, false));
				Thread.sleep(10L);
			}
			assertEquals(1, runToggles.get());
			while (input.targets.isEmpty() && System.nanoTime() < deadline)
			{
				walker.publishGameTick(runSnapshot(tick++, start, 10_000, true));
				Thread.sleep(10L);
			}
			assertEquals(1, runToggles.get());
			assertEquals(1, input.targets.size());
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void interruptibleWalkYieldsAndCancelsItsClickWhenDialogueOpens() throws Exception
	{
		DeferredWalkInput input = new DeferredWalkInput();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			WorldPoint destination = new WorldPoint(3210, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			CompletableFuture<Map<String, Object>> completion = walker.walkTo(new GenericClientWalkRequest(destination, 0, 60, context(false),
				true, Collections.emptyList(), GenericClientWalkInterrupts.parse(java.util.Map.of("dialogue", true)), Collections.emptyList(), null));

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			long tick = 1;
			while (input.calls == 0 && System.nanoTime() < deadline)
			{
				walker.publishGameTick(snapshot(tick++, start));
				Thread.sleep(10L);
			}
			assertEquals(1, input.calls);

			walker.publishGameTick(dialogueSnapshot(tick, start));
			Map<String, Object> receipt = completion.get(3, TimeUnit.SECONDS);
			assertEquals("interrupted", receipt.get("status"));
			assertEquals("dialogue", receipt.get("reason"));
			Map<?, ?> dialogue = (Map<?, ?>) receipt.get("dialogue");
			assertEquals("continue", dialogue.get("type"));
			assertEquals("The monkey in your backpack...", dialogue.get("speaker"));
			assertEquals(1, input.cancellations);
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void conserveModeDoesNotToggleRun() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		AtomicInteger runToggles = new AtomicInteger();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			(enabled, breaks) ->
			{
				runToggles.incrementAndGet();
				return CompletableFuture.completedFuture(Collections.emptyMap());
			},
			(reason, owner) -> { },
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			walker.publishGameTick(runSnapshot(0, start, 10_000, false));
			walker.walkTo(new GenericClientWalkRequest(new WorldPoint(3210, 3428, 0), 0, 60, context(false),
				false, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			waitForFirstClick(walker, input, start);

			assertEquals(0, runToggles.get());
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void conserveModeDisablesRunBeforeTheFirstRouteClick() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		AtomicInteger runToggles = new AtomicInteger();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			(enabled, breaks) ->
			{
				assertFalse(enabled);
				runToggles.incrementAndGet();
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "complete");
				receipt.put("result", "run_disabled");
				receipt.put("click_count", 1L);
				return CompletableFuture.completedFuture(receipt);
			},
			(reason, owner) -> { },
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			walker.publishGameTick(runSnapshot(0, start, 10_000, true));
			walker.walkTo(new GenericClientWalkRequest(new WorldPoint(3210, 3428, 0), 0, 60, context(false),
				false, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			long tick = 1;
			while (runToggles.get() == 0 && System.nanoTime() < deadline)
			{
				walker.publishGameTick(runSnapshot(tick++, start, 10_000, true));
				Thread.sleep(10L);
			}
			assertEquals(1, runToggles.get());
			while (input.targets.isEmpty() && System.nanoTime() < deadline)
			{
				walker.publishGameTick(runSnapshot(tick++, start, 10_000, false));
				Thread.sleep(10L);
			}
			assertEquals(1, runToggles.get());
			assertEquals(1, input.targets.size());
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void keepsOneGlobalPlanForALongRoute() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput(Integer.MAX_VALUE);
		List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			reports::add);
		try
		{
			WorldPoint start = new WorldPoint(3009, 3197, 0);
			WorldPoint destination = new WorldPoint(3165, 3491, 0);
			walker.publishGameTick(openSceneSnapshot(0, start));
			walker.walkTo(new GenericClientWalkRequest(destination, 8, 600, context(false),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			waitForFirstClick(walker, input, start);
			assertTrue(input.candidateBatches.get(0).size() > 49);
			assertTrue(distance(input.candidateBatches.get(0).get(0), destination) <= 8);
			assertEquals(1, reports.stream()
				.filter(message -> message.contains("WALK_PLANNING"))
				.count());
			assertFalse(reports.stream().anyMatch(
				message -> message.contains("segment_handoff")));
		}
		finally
		{
			walker.close();
		}
	}

}
