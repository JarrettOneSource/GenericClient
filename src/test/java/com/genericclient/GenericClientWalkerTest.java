package com.genericclient;

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
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientWalkerTest
{
	@Test
	public void scalesRecoveryBudgetForLongRoutesWithinABoundedMaximum()
	{
		assertEquals(6, GenericClientWalker.recoveryPlanLimit(
			new WorldPoint(3200, 3200, 0), new WorldPoint(3215, 3200, 0)));
		assertEquals(24, GenericClientWalker.recoveryPlanLimit(
			new WorldPoint(3000, 3200, 0), new WorldPoint(3300, 3200, 0)));
		assertEquals(32, GenericClientWalker.recoveryPlanLimit(
			new WorldPoint(1000, 1000, 0), new WorldPoint(3000, 3000, 0)));
	}

	@Test
	public void remembersBlockedEdgesIndependentOfTravelDirection()
	{
		WorldPoint west = new WorldPoint(3010, 3402, 0);
		WorldPoint east = new WorldPoint(3011, 3402, 0);

		assertEquals(
			new GenericClientWalker.BlockedEdge(west, east),
			new GenericClientWalker.BlockedEdge(east, west));
		assertNotEquals(
			new GenericClientWalker.BlockedEdge(west, east),
			new GenericClientWalker.BlockedEdge(west, new WorldPoint(3010, 3403, 0)));
	}

	@Test
	public void waitsUntilPlayerIsNearTheAcceptedWaypoint() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		GenericClientWalker walker = new GenericClientWalker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			WorldPoint destination = new WorldPoint(3230, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			CompletableFuture<?> completion = walker.walkTo(destination, 0, 60, context(false));

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

			WorldPoint nearTarget = twoTilesBefore(start, firstTarget);
			assertEquals(2, distance(nearTarget, firstTarget));
			walker.publishGameTick(snapshot(4, nearTarget));
			assertEquals(2, input.targets.size());
			assertEquals(Boolean.FALSE, input.breakPolicies.get(1));
		}
		finally
		{
			walker.close();
		}
	}

	@Test
	public void doesNotReclickTheFinalWaypointFromOneTileAway() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		GenericClientWalker walker = new GenericClientWalker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			WorldPoint destination = new WorldPoint(3207, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			walker.walkTo(destination, 0, 60, context(true));

			waitForFirstClick(walker, input, start);
			assertEquals(Boolean.TRUE, input.breakPolicies.get(0));
			assertEquals(destination, input.targets.get(0));
			WorldPoint oneTileAway = stepToward(
				new WorldPoint(destination.getX() - 2, destination.getY(), destination.getPlane()),
				destination);
			walker.publishGameTick(snapshot(2, oneTileAway));
			walker.publishGameTick(snapshot(3, oneTileAway));

			assertEquals(1, input.targets.size());
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
		GenericClientWalker walker = new GenericClientWalker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			CompletableFuture<Map<String, Object>> completion = walker.walkTo(
				new WorldPoint(3230, 3428, 0), 0, 60, context(false));
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
	public void doesNotReplanWhileAnAcceptedClickIsStillMovingOffRoute() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
		GenericClientWalker walker = new GenericClientWalker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			reports::add);
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			walker.walkTo(new WorldPoint(3230, 3428, 0), 0, 60, context(false));
			waitForFirstClick(walker, input, start);

			walker.publishGameTick(snapshot(2, new WorldPoint(3203, 3433, 0)));

			assertFalse(reports.stream().anyMatch(
				message -> message.contains("reason=off_route")));
			assertEquals(1, input.targets.size());
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
		GenericClientWalker walker = new GenericClientWalker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			reports::add);
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			walker.walkTo(new WorldPoint(3230, 3428, 0), 0, 60, context(false));
			waitForFirstClick(walker, input, start);
			int firstDistance = distance(start, input.targets.get(0));

			for (long tick = 2; tick <= 8; tick++)
			{
				walker.publishGameTick(snapshot(tick, start));
			}
			waitForClickCount(walker, input, start, 2, 10);

			int secondDistance = distance(start, input.targets.get(1));
			assertTrue(secondDistance <= firstDistance - 3);
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
		GenericClientWalker walker = new GenericClientWalker(
			new FakeWalkInput(),
			new FakeObstacleInput(),
			collisionMap,
			reports::add);
		try
		{
			walker.publishGameTick(openSceneSnapshot(0, start));
			CompletableFuture<Map<String, Object>> completion =
				walker.walkTo(destination, 0, 100, context(false));
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
		GenericClientWalker walker = new GenericClientWalker(
			walkInput,
			obstacleInput,
			collisionMap,
			reports::add);
		try
		{
			walker.publishGameTick(doorSnapshot(0, start, beforeDoor, door, true));
			CompletableFuture<Map<String, Object>> completion =
				walker.walkTo(destination, 0, 100, context(false));

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
	public void stopsAfterExplicitLockedDoorFeedback() throws Exception
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
		GenericClientWalker walker = new GenericClientWalker(
			new FakeWalkInput(),
			obstacleInput,
			collisionMap,
			message -> { });
		try
		{
			walker.publishGameTick(doorSnapshot(0, start, beforeDoor, door, true));
			CompletableFuture<Map<String, Object>> completion =
				walker.walkTo(destination, 0, 100, context(false));
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

			Map<String, Object> receipt = completion.get(3, TimeUnit.SECONDS);
			assertEquals("unreachable", receipt.get("status"));
			assertEquals("obstacle_locked", receipt.get("reason"));
			assertEquals(1L, receipt.get("obstacle_interactions"));
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
		GenericClientWalker walker = new GenericClientWalker(
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
			reason -> { },
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			walker.publishGameTick(runSnapshot(0, start, 10_000, false));
			walker.walkTo(new WorldPoint(3210, 3428, 0), 0, 60, context(false), true);
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
	public void conserveModeDoesNotToggleRun() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		AtomicInteger runToggles = new AtomicInteger();
		GenericClientWalker walker = new GenericClientWalker(
			input,
			new FakeObstacleInput(),
			(enabled, breaks) ->
			{
				runToggles.incrementAndGet();
				return CompletableFuture.completedFuture(Collections.emptyMap());
			},
			reason -> { },
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			walker.publishGameTick(runSnapshot(0, start, 10_000, false));
			walker.walkTo(new WorldPoint(3210, 3428, 0), 0, 60, context(false), false);
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
		GenericClientWalker walker = new GenericClientWalker(
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
			reason -> { },
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			walker.publishGameTick(runSnapshot(0, start, 10_000, true));
			walker.walkTo(new WorldPoint(3210, 3428, 0), 0, 60, context(false), false);
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
		GenericClientWalker walker = new GenericClientWalker(
			input,
			new FakeObstacleInput(),
			GenericClientCollisionMap.loadBundled(),
			reports::add);
		try
		{
			WorldPoint start = new WorldPoint(3009, 3197, 0);
			WorldPoint destination = new WorldPoint(3165, 3491, 0);
			walker.publishGameTick(openSceneSnapshot(0, start));
			walker.walkTo(destination, 8, 600, context(false));
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

	private static void waitForFirstClick(
		GenericClientWalker walker,
		FakeWalkInput input,
		WorldPoint player) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (input.targets.isEmpty() && System.nanoTime() < deadline)
		{
			walker.publishGameTick(snapshot(1, player));
			Thread.sleep(10L);
		}
		assertEquals(1, input.targets.size());
	}

	private static void waitForClickCount(
		GenericClientWalker walker,
		FakeWalkInput input,
		WorldPoint player,
		int count,
		long tick) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (input.targets.size() < count && System.nanoTime() < deadline)
		{
			walker.publishGameTick(snapshot(tick++, player));
			Thread.sleep(10L);
		}
		assertEquals(count, input.targets.size());
	}

	private static GenericClientSnapshot snapshot(long tick, WorldPoint player)
	{
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientSnapshot.PlayerSnapshot(
				"walker-test",
				player.getX(),
				player.getY(),
				player.getPlane(),
				0),
			Collections.emptyList());
	}

	private static GenericClientSnapshot runSnapshot(
		long tick,
		WorldPoint player,
		int runEnergy,
		boolean runEnabled)
	{
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientSnapshot.PlayerSnapshot(
				"walker-test",
				player.getX(),
				player.getY(),
				player.getPlane(),
				0,
				-1,
				null,
				10,
				10,
				runEnergy,
				runEnabled,
				null),
			Collections.emptyList());
	}

	private static GenericClientSnapshot openSceneSnapshot(long tick, WorldPoint player)
	{
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientSnapshot.PlayerSnapshot(
				"walker-test", player.getX(), player.getY(), player.getPlane(), 0),
			Collections.emptyList(),
			GenericClientAccountSnapshot.empty(),
			GenericClientQuestSnapshot.empty(),
			Collections.emptyList(),
			new GenericClientSceneCollision(
				true,
				player.getX() - 52,
				player.getY() - 52,
				player.getPlane(),
				new int[104][104]));
	}

	private static GenericClientSnapshot doorSnapshot(
		long tick,
		WorldPoint player,
		WorldPoint beforeDoor,
		WorldPoint door,
		boolean closed)
	{
		return doorSnapshot(
			tick, player, beforeDoor, door, closed, Collections.emptyList());
	}

	private static GenericClientSnapshot solidWallSnapshot(
		long tick,
		WorldPoint player,
		WorldPoint beforeWall,
		WorldPoint wall)
	{
		int baseX = Math.min(beforeWall.getX(), wall.getX()) - 10;
		int baseY = Math.min(beforeWall.getY(), wall.getY()) - 10;
		int[][] flags = new int[64][64];
		flags[wall.getX() - baseX][wall.getY() - baseY] = incomingWall(beforeWall, wall);
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientSnapshot.PlayerSnapshot(
				"walker-test", player.getX(), player.getY(), player.getPlane(), 0),
			Collections.emptyList(),
			GenericClientAccountSnapshot.empty(),
			GenericClientQuestSnapshot.empty(),
			Collections.emptyList(),
			new GenericClientSceneCollision(true, baseX, baseY, wall.getPlane(), flags));
	}

	private static GenericClientSnapshot doorSnapshot(
		long tick,
		WorldPoint player,
		WorldPoint beforeDoor,
		WorldPoint door,
		boolean closed,
		List<GenericClientGameMessageBuffer.Message> messages)
	{
		int baseX = Math.min(beforeDoor.getX(), door.getX()) - 10;
		int baseY = Math.min(beforeDoor.getY(), door.getY()) - 10;
		int[][] flags = new int[64][64];
		if (closed)
		{
			flags[door.getX() - baseX][door.getY() - baseY] = incomingWall(beforeDoor, door);
		}
		GenericClientQuestSnapshot quest = new GenericClientQuestSnapshot(
			true,
			new int[0],
			Collections.singletonList(new GenericClientQuestSnapshot.ObjectSnapshot(
				2000,
				"Test door",
				"wall",
				door.getX(),
				door.getY(),
				door.getPlane(),
				distance(player, door),
				Collections.singletonList(closed ? "Open" : "Close"))),
			GenericClientQuestSnapshot.DialogueSnapshot.closed());
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientSnapshot.PlayerSnapshot(
				"walker-test", player.getX(), player.getY(), player.getPlane(), 0),
			Collections.emptyList(),
			GenericClientAccountSnapshot.empty(),
			quest,
			messages,
			new GenericClientSceneCollision(true, baseX, baseY, door.getPlane(), flags));
	}

	private static int firstCardinalEdge(List<WorldPoint> route)
	{
		for (int index = 1; index < Math.min(route.size(), 12); index++)
		{
			WorldPoint before = route.get(index - 1);
			WorldPoint after = route.get(index);
			if (before.getX() == after.getX() ^ before.getY() == after.getY())
			{
				return index;
			}
		}
		throw new AssertionError("Test route has no cardinal edge");
	}

	private static int incomingWall(WorldPoint from, WorldPoint to)
	{
		if (to.getX() > from.getX())
		{
			return CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		}
		if (to.getX() < from.getX())
		{
			return CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		}
		if (to.getY() > from.getY())
		{
			return CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
		}
		return CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
	}

	private static WorldPoint stepToward(WorldPoint from, WorldPoint to)
	{
		return new WorldPoint(
			from.getX() + Integer.signum(to.getX() - from.getX()),
			from.getY() + Integer.signum(to.getY() - from.getY()),
			from.getPlane());
	}

	private static WorldPoint twoTilesBefore(WorldPoint from, WorldPoint to)
	{
		return new WorldPoint(
			to.getX() - Integer.signum(to.getX() - from.getX()) * 2,
			to.getY() - Integer.signum(to.getY() - from.getY()) * 2,
			to.getPlane());
	}

	private static int distance(WorldPoint first, WorldPoint second)
	{
		return Math.max(
			Math.abs(first.getX() - second.getX()),
			Math.abs(first.getY() - second.getY()));
	}

	private static GenericClientActivityContext context(boolean enabled)
	{
		return GenericClientActivityContext.of(
			GenericClientActivityContext.Activity.TRAVEL,
			enabled);
	}

	private static final class FakeWalkInput implements GenericClientWalker.WalkInput
	{
		private final int maximumProjectedTiles;
		private final List<WorldPoint> targets = new ArrayList<>();
		private final List<List<WorldPoint>> candidateBatches = new ArrayList<>();
		private final List<Boolean> breakPolicies = new ArrayList<>();

		private FakeWalkInput()
		{
			this(10);
		}

		private FakeWalkInput(int maximumProjectedTiles)
		{
			this.maximumProjectedTiles = maximumProjectedTiles;
		}

		@Override
		public CompletableFuture<GenericClientInteractionResult> walkToFarthest(
			List<WorldPoint> candidates,
			GenericClientActivityContext activityContext)
		{
			candidateBatches.add(new ArrayList<>(candidates));
			breakPolicies.add(activityContext.allowsBreaks());
			int projectedTiles = Math.min(maximumProjectedTiles, candidates.size());
			WorldPoint target = candidates.get(candidates.size() - projectedTiles);
			targets.add(target);
			return CompletableFuture.completedFuture(new GenericClientInteractionResult(
				target,
				"WALK_TILE_CLICK_EXECUTED test",
				true,
				Collections.emptyMap(),
				Collections.emptyMap()));
		}

		@Override
		public void cancelWalkToTile()
		{
		}
	}

	private static final class DeferredWalkInput implements GenericClientWalker.WalkInput
	{
		private final List<CompletableFuture<GenericClientInteractionResult>> requests =
			new ArrayList<>();
		private int calls;
		private int cancellations;

		@Override
		public CompletableFuture<GenericClientInteractionResult> walkToFarthest(
			List<WorldPoint> candidates,
			GenericClientActivityContext activityContext)
		{
			calls++;
			CompletableFuture<GenericClientInteractionResult> request = new CompletableFuture<>();
			requests.add(request);
			return request;
		}

		@Override
		public void cancelWalkToTile()
		{
			cancellations++;
		}

		private void completeFirstAsCancelled()
		{
			requests.get(0).complete(new GenericClientInteractionResult(
				null,
				"WALK_CLICK_FAILED reason=cancelled",
				false,
				Collections.emptyMap(),
				Collections.emptyMap()));
		}
	}

	private static final class FakeObstacleInput implements GenericClientWalker.ObstacleInput
	{
		@Override
		public CompletableFuture<Map<String, Object>> interact(
			int objectId,
			String action,
			WorldPoint world,
			int within,
			GenericClientActivityContext activityContext)
		{
			Map<String, Object> receipt = new LinkedHashMap<>();
			receipt.put("status", "dispatched");
			receipt.put("result", "menu_action_executed");
			return CompletableFuture.completedFuture(receipt);
		}

		@Override
		public void cancel()
		{
		}
	}

	private static final class RecordingObstacleInput implements GenericClientWalker.ObstacleInput
	{
		private int interactions;
		private int objectId;
		private String action;
		private WorldPoint world;
		private boolean breaksEnabled;

		@Override
		public CompletableFuture<Map<String, Object>> interact(
			int objectId,
			String action,
			WorldPoint world,
			int within,
			GenericClientActivityContext activityContext)
		{
			interactions++;
			this.objectId = objectId;
			this.action = action;
			this.world = world;
			this.breaksEnabled = activityContext.allowsBreaks();
			Map<String, Object> receipt = new LinkedHashMap<>();
			receipt.put("status", "dispatched");
			receipt.put("result", "menu_action_executed");
			return CompletableFuture.completedFuture(receipt);
		}

		@Override
		public void cancel()
		{
		}
	}
}
