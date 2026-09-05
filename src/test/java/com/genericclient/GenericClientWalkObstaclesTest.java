package com.genericclient;

import static com.genericclient.GenericClientWalkTestFixtures.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientWalkObstaclesTest
{
	@Test
	public void replansAroundANewLiveSolidWall() throws Exception
	{
		GenericClientCollisionMap collisionMap = GenericClientCollisionMap.loadBundled();
		WorldPoint start = new WorldPoint(3202, 3428, 0);
		WorldPoint destination = new WorldPoint(3230, 3428, 0);
		List<WorldPoint> route = new GenericClientPathfinder(collisionMap)
			.find(start, destination, 0)
			.getPath();
		int blockedIndex = firstCardinalEdge(route);
		WorldPoint beforeWall = route.get(blockedIndex - 1);
		WorldPoint wall = route.get(blockedIndex);
		List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			new FakeWalkInput(),
			new FakeObstacleInput(),
			collisionMap,
			reports::add);
		try
		{
			walker.publishGameTick(openSceneSnapshot(0, start));
			CompletableFuture<Map<String, Object>> completion =
				walker.walkTo(new GenericClientWalkRequest(destination, 0, 100, context(false),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (reports.stream().noneMatch(message -> message.contains("WALK_PLANNED")) &&
				System.nanoTime() < deadline)
			{
				Thread.sleep(10L);
			}

			walker.publishGameTick(solidWallSnapshot(1, start, beforeWall, wall));
			while (reports.stream()
				.filter(message -> message.contains("WALK_PLANNING"))
				.count() < 2 && System.nanoTime() < deadline)
			{
				Thread.sleep(10L);
			}

			assertFalse(completion.isDone());
			assertTrue(reports.stream().anyMatch(
				message -> message.contains("WALK_ROUTE_BLOCKED") &&
					message.contains(beforeWall.toString()) && message.contains(wall.toString())));
			assertTrue(reports.stream().anyMatch(
				message -> message.contains("reason=live_route_blocked") &&
					message.contains("blockedEdges=1")));
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void opensAClosedRouteDoorBeforeClickingBeyondIt() throws Exception
	{
		GenericClientCollisionMap collisionMap = GenericClientCollisionMap.loadBundled();
		WorldPoint start = new WorldPoint(3202, 3428, 0);
		WorldPoint destination = new WorldPoint(3230, 3428, 0);
		List<WorldPoint> route = new GenericClientPathfinder(collisionMap)
			.find(start, destination, 0)
			.getPath();
		int doorIndex = firstCardinalEdge(route);
		WorldPoint door = route.get(doorIndex);
		WorldPoint beforeDoor = route.get(doorIndex - 1);
		FakeWalkInput walkInput = new FakeWalkInput();
		RecordingObstacleInput obstacleInput = new RecordingObstacleInput();
		List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			walkInput,
			obstacleInput,
			collisionMap,
			reports::add);
		try
		{
			walker.publishGameTick(doorSnapshot(0, start, beforeDoor, door, true));
			CompletableFuture<Map<String, Object>> completion =
				walker.walkTo(new GenericClientWalkRequest(destination, 0, 100, context(false),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			long tick = 1;
			while (obstacleInput.interactions == 0 && System.nanoTime() < deadline)
			{
				walker.publishGameTick(doorSnapshot(tick++, start, beforeDoor, door, true));
				Thread.sleep(10L);
			}

			assertEquals(1, obstacleInput.interactions);
			assertEquals(2000, obstacleInput.objectId);
			assertEquals("Open", obstacleInput.action);
			assertEquals(door, obstacleInput.world);
			assertFalse(obstacleInput.breaksEnabled);
			assertTrue(walkInput.targets.isEmpty());

			walker.publishGameTick(doorSnapshot(tick++, start, beforeDoor, door, false));
			deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (walkInput.targets.isEmpty() && System.nanoTime() < deadline)
			{
				walker.publishGameTick(doorSnapshot(tick++, start, beforeDoor, door, false));
				Thread.sleep(10L);
			}
			assertFalse(walkInput.targets.isEmpty());

			walker.publishGameTick(doorSnapshot(tick, destination, beforeDoor, door, false));
			Map<String, Object> receipt = completion.get(3, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			assertEquals(1L, receipt.get("obstacle_interactions"));
			assertEquals(1L, receipt.get("obstacles_cleared"));
			Map<?, ?> outcome = (Map<?, ?>) ((List<?>) receipt.get("edge_memory")).get(0);
			assertEquals("cleared", outcome.get("status"));
			assertEquals(2000, outcome.get("object_id"));
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
	public void arrivalPastAnInteractedDoorRecordsTheClearedOutcome() throws Exception
	{
		GenericClientCollisionMap map = GenericClientCollisionMap.loadBundled();
		WorldPoint start = new WorldPoint(3202, 3428, 0);
		WorldPoint destination = new WorldPoint(3230, 3428, 0);
		List<WorldPoint> route = new GenericClientPathfinder(map).find(start, destination, 0).getPath();
		int index = firstCardinalEdge(route);
		WorldPoint before = route.get(index - 1);
		WorldPoint door = route.get(index);
		RecordingObstacleInput obstacles = new RecordingObstacleInput();
		try (GenericClientWalker walker = GenericClientTestSupport.walker(new FakeWalkInput(), obstacles, map, message -> { }))
		{
			walker.publishGameTick(doorSnapshot(0, start, before, door, true));
			CompletableFuture<Map<String, Object>> completion = walker.walkTo(new GenericClientWalkRequest(
				destination, 0, 100, context(false), true, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			long tick = 1;
			while (obstacles.interactions == 0 && System.nanoTime() < deadline)
			{
				walker.publishGameTick(doorSnapshot(tick++, start, before, door, true));
				Thread.sleep(10);
			}
			assertEquals(1, obstacles.interactions);
			walker.publishGameTick(openSceneSnapshot(tick, destination));
			Map<String, Object> receipt = completion.get(3, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			assertEquals(1L, receipt.get("obstacles_cleared"));
			assertEquals("cleared", ((Map<?, ?>) ((List<?>) receipt.get("edge_memory")).get(0)).get("status"));
		}
	}

	@Test
	public void opensAnAdjacentPairedGateAlongTheSameWall()
		throws Exception
	{
		GenericClientCollisionMap collisionMap = GenericClientCollisionMap.loadBundled();
		WorldPoint start = new WorldPoint(3202, 3428, 0);
		WorldPoint destination = new WorldPoint(3230, 3428, 0);
		List<WorldPoint> route = new GenericClientPathfinder(collisionMap)
			.find(start, destination, 0)
			.getPath();
		int gateIndex = firstCardinalEdge(route);
		WorldPoint afterGate = route.get(gateIndex);
		WorldPoint beforeGate = route.get(gateIndex - 1);
		boolean vertical = beforeGate.getX() == afterGate.getX();
		WorldPoint gate = vertical
			? new WorldPoint(afterGate.getX() + 1, afterGate.getY(), afterGate.getPlane())
			: new WorldPoint(afterGate.getX(), afterGate.getY() + 1, afterGate.getPlane());
		int gateOrientation = vertical
			? (afterGate.getY() > beforeGate.getY() ? 8 : 2)
			: (afterGate.getX() > beforeGate.getX() ? 1 : 4);
		RecordingObstacleInput obstacleInput = new RecordingObstacleInput();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			new FakeWalkInput(), obstacleInput, collisionMap, message -> { });
		try
		{
			walker.publishGameTick(pairedGateSnapshot(
				0, start, beforeGate, afterGate, gate, gateOrientation, true));
			CompletableFuture<Map<String, Object>> completion =
				walker.walkTo(new GenericClientWalkRequest(destination, 0, 100, context(false),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			long tick = 1;
			while (obstacleInput.interactions == 0 && System.nanoTime() < deadline)
			{
				walker.publishGameTick(pairedGateSnapshot(
					tick++, start, beforeGate, afterGate, gate, gateOrientation, true));
				Thread.sleep(10L);
			}

			assertEquals(1, obstacleInput.interactions);
			assertEquals(11767, obstacleInput.objectId);
			assertEquals("Open", obstacleInput.action);
			assertEquals(gate, obstacleInput.world);

			walker.publishGameTick(pairedGateSnapshot(
				tick++, start, beforeGate, afterGate, gate, gateOrientation, false));
			walker.publishGameTick(openSceneSnapshot(tick++, afterGate));
			walker.publishGameTick(openSceneSnapshot(tick, destination));

			Map<String, Object> receipt = completion.get(3, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			assertEquals(1L, receipt.get("obstacle_interactions"));
			assertEquals(1L, receipt.get("obstacles_cleared"));
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void rejectsTheArdougneGateForTheAdjacentPerpendicularFence()
	{
		WorldPoint from = new WorldPoint(2517, 3357, 0);
		WorldPoint to = new WorldPoint(2516, 3357, 0);
		WorldPoint gate = new WorldPoint(2518, 3356, 0);
		GenericClientSnapshot snapshot = pairedGateSnapshot(1, from, from, to, gate, 2, true);
		assertFalse(snapshot.canPlanMove(from.getX(), from.getY(), 0, -1, 0, false));
		assertFalse(snapshot.findRouteBlock(java.util.Arrays.asList(from, to), 0, 1).isTraversable());
	}

	@Test
	public void rejectsAPairedGateWithTheWrongOrientation()
	{
		WorldPoint from = new WorldPoint(3202, 3428, 0);
		WorldPoint to = new WorldPoint(3202, 3429, 0);
		WorldPoint gate = new WorldPoint(3203, 3429, 0);
		GenericClientSnapshot snapshot = pairedGateSnapshot(1, from, from, to, gate, 1, true);
		assertFalse(snapshot.canPlanMove(from.getX(), from.getY(), 0, 0, 1, false));
	}

	@Test
	public void replansAroundExplicitlyLockedDoorFeedback() throws Exception
	{
		GenericClientCollisionMap collisionMap = GenericClientCollisionMap.loadBundled();
		WorldPoint start = new WorldPoint(3202, 3428, 0);
		WorldPoint destination = new WorldPoint(3230, 3428, 0);
		List<WorldPoint> route = new GenericClientPathfinder(collisionMap)
			.find(start, destination, 0)
			.getPath();
		int doorIndex = firstCardinalEdge(route);
		WorldPoint door = route.get(doorIndex);
		WorldPoint beforeDoor = route.get(doorIndex - 1);
		RecordingObstacleInput obstacleInput = new RecordingObstacleInput();
		FakeWalkInput walkInput = new FakeWalkInput();
		List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			walkInput,
			obstacleInput,
			collisionMap,
			reports::add);
		try
		{
			walker.publishGameTick(doorSnapshot(0, start, beforeDoor, door, true));
			CompletableFuture<Map<String, Object>> completion =
				walker.walkTo(new GenericClientWalkRequest(destination, 0, 100, context(false),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			long tick = 1;
			while (obstacleInput.interactions == 0 && System.nanoTime() < deadline)
			{
				walker.publishGameTick(doorSnapshot(tick++, start, beforeDoor, door, true));
				Thread.sleep(10L);
			}
			assertEquals(1, obstacleInput.interactions);

			List<GenericClientGameMessageBuffer.Message> messages = Collections.singletonList(
				new GenericClientGameMessageBuffer.Message(
					tick, "gamemessage", "", "", "The door is securely locked."));
			walker.publishGameTick(doorSnapshot(
				tick, start, beforeDoor, door, true, messages));

			deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (walkInput.targets.isEmpty() && System.nanoTime() < deadline)
			{
				walker.publishGameTick(doorSnapshot(
					++tick, start, beforeDoor, door, true));
				Thread.sleep(10L);
			}
			assertFalse(completion.isDone());
			assertFalse(walkInput.targets.isEmpty());
			assertTrue(reports.stream().anyMatch(message ->
				message.contains("WALK_OBSTACLE_BLOCKED") && message.contains("reason=locked")));

			walker.publishGameTick(openSceneSnapshot(++tick, destination));
			Map<String, Object> receipt = completion.get(3, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			assertTrue(((Number) receipt.get("obstacle_interactions")).longValue() >= 1L);
			assertTrue(((Number) receipt.get("live_block_replans")).longValue() >= 1L);

			int previousPlans = (int) reports.stream().filter(message -> message.startsWith("WALK_PLANNING")).count();
			int previousInteractions = obstacleInput.interactions;
			walkInput.targets.clear();
			walker.publishGameTick(doorSnapshot(++tick, start, beforeDoor, door, true));
			walker.walkTo(new GenericClientWalkRequest(destination, 0, 100, context(false),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (walkInput.targets.isEmpty() && obstacleInput.interactions == previousInteractions && System.nanoTime() < deadline)
			{
				walker.publishGameTick(doorSnapshot(++tick, start, beforeDoor, door, true));
				Thread.sleep(10);
			}
			assertEquals("The second journey must remember the locked door", previousInteractions, obstacleInput.interactions);
			assertFalse(walkInput.targets.isEmpty());
			assertTrue(reports.stream().filter(message -> message.startsWith("WALK_PLANNING"))
				.skip(previousPlans).anyMatch(message -> message.contains("blockedEdges=" + ((List<?>) receipt.get("blocked_edges")).size())));

		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void replansAroundAnObstacleThatNeverOpens() throws Exception
	{
		GenericClientCollisionMap collisionMap = GenericClientCollisionMap.loadBundled();
		WorldPoint start = new WorldPoint(3202, 3428, 0);
		WorldPoint destination = new WorldPoint(3230, 3428, 0);
		List<WorldPoint> route = new GenericClientPathfinder(collisionMap)
			.find(start, destination, 0)
			.getPath();
		int doorIndex = firstCardinalEdge(route);
		WorldPoint door = route.get(doorIndex);
		WorldPoint beforeDoor = route.get(doorIndex - 1);
		RecordingObstacleInput obstacleInput = new RecordingObstacleInput();
		FakeWalkInput walkInput = new FakeWalkInput();
		List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
		GenericClientWalker walker = GenericClientTestSupport.walker(
			walkInput,
			obstacleInput,
			collisionMap,
			reports::add);
		try
		{
			walker.publishGameTick(doorSnapshot(0, start, beforeDoor, door, true));
			CompletableFuture<Map<String, Object>> completion =
				walker.walkTo(new GenericClientWalkRequest(destination, 0, 100, context(false),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			long tick = 1;
			while (reports.stream().noneMatch(message ->
				message.contains("WALK_OBSTACLE_BLOCKED") &&
					message.contains("reason=interaction_limit")) &&
				System.nanoTime() < deadline)
			{
				walker.publishGameTick(doorSnapshot(
					tick++, start, beforeDoor, door, true));
				Thread.sleep(10L);
			}

			assertEquals(3, obstacleInput.interactions);
			assertFalse(completion.isDone());
			while (walkInput.targets.isEmpty() && System.nanoTime() < deadline)
			{
				walker.publishGameTick(doorSnapshot(
					tick++, start, beforeDoor, door, true));
				Thread.sleep(10L);
			}
			assertFalse(walkInput.targets.isEmpty());

			walker.publishGameTick(openSceneSnapshot(tick, destination));
			Map<String, Object> receipt = completion.get(3, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			assertTrue(((Number) receipt.get("obstacle_interactions")).longValue() >= 3L);
			assertTrue(((Number) receipt.get("live_block_replans")).longValue() >= 1L);
		}
		finally
		{
			walker.close();
		}
	}

}
