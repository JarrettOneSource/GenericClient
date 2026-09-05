package com.genericclient;

import static org.junit.Assert.*;
import static com.genericclient.GenericClientWalkObstacles.Step.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientWalkObstaclesStateTest
{
	@Rule public TemporaryFolder folders = new TemporaryFolder();

	@Test
	public void waitsForNativeCompletionAndTheMovementSettleBoundary() throws Exception
	{
		Fixture fixture = new Fixture();
		fixture.begin();
		assertEquals(WAIT, fixture.advance(11));
		assertFalse(fixture.obstacles.observeClear(fixture.snapshot(11), 1, 11));
		assertFalse(fixture.obstacles.complete(fixture.snapshot(13), Map.of("status", "dispatched", "result", "clicked"), null));
		assertEquals(WAIT, fixture.advance(17));
		assertEquals(INTERACT, fixture.advance(18));
		assertEquals(5L, fixture.walk.receipt("active", "test", fixture.from, 18).get("active_game_ticks"));
		assertFalse(fixture.obstacles.complete(fixture.snapshot(21), Map.of("status", "dispatched", "result", "clicked"), null));
		Map<String, Object> receipt = fixture.walk.receipt("active", "test", fixture.from, 21);
		assertEquals(5L, receipt.get("active_game_ticks"));
		assertEquals(2L, receipt.get("obstacle_interactions"));
		assertEquals(2, fixture.count("WALK_OBSTACLE_INTERACTION"));
		assertEquals(2, fixture.count("WALK_OBSTACLE_DISPATCHED"));
	}

	@Test
	public void aSlowNativeCompletionStillWaitsForItsRetryCooldown() throws Exception
	{
		Fixture fixture = new Fixture();
		fixture.begin();
		assertFalse(fixture.obstacles.complete(fixture.snapshot(30), Map.of("status", "dispatched"), null));
		assertEquals(WAIT, fixture.advance(31));
		assertEquals(INTERACT, fixture.advance(32));
		assertEquals(2L, fixture.walk.receipt("active", "test", fixture.from, 32).get("active_game_ticks"));
	}

	@Test
	public void threeRejectedNativeAttemptsBecomeALearnedFailure() throws Exception
	{
		Fixture fixture = new Fixture();
		fixture.begin();
		assertFalse(fixture.obstacles.complete(fixture.snapshot(11), null, new IOException("menu_closed")));
		assertEquals(INTERACT, fixture.advance(18));
		assertFalse(fixture.obstacles.complete(fixture.snapshot(19), Map.of("status", "rejected", "result", "not_found"), null));
		assertEquals(INTERACT, fixture.advance(21));
		assertTrue(fixture.obstacles.complete(fixture.snapshot(22), Map.of("status", "rejected", "result", "out_of_reach"), null));
		assertEquals(CONTINUE, fixture.advance(23));
		assertTrue(fixture.memory.capture().blocks(fixture.from, fixture.to));
		Map<String, Object> outcome = fixture.memory.capture().entries.get(0).toMap();
		assertEquals("interaction_failed", outcome.get("reason"));
		assertEquals("out_of_reach", outcome.get("detail"));
		Map<String, Object> receipt = fixture.walk.receipt("unreachable", "test", fixture.from, 23);
		assertEquals(0L, receipt.get("obstacle_interactions"));
		assertEquals(1L, receipt.get("live_block_replans"));
		assertEquals(3, fixture.count("WALK_OBSTACLE_REJECTED"));
		assertTrue(fixture.reports.stream().anyMatch(message -> message.endsWith("result=menu_closed")));
	}

	@Test
	public void missingFramesAndNativeReceiptsDoNotInventInteractionTime() throws Exception
	{
		Fixture fixture = new Fixture();
		fixture.begin();
		assertFalse(fixture.obstacles.complete(null, null, null));
		assertEquals(5L, fixture.walk.receipt("active", "test", fixture.from, 15).get("active_game_ticks"));
		assertTrue(fixture.reports.stream().anyMatch(message -> message.endsWith("result=null")));
	}

	@Test
	public void zeroIsAValidCurrentRouteIndexAfterBacktracking() throws Exception
	{
		Fixture fixture = new Fixture();
		fixture.begin();
		assertFalse(fixture.obstacles.complete(fixture.snapshot(11), Map.of("status", "dispatched"), null));
		fixture.walk.pathIndex = 1;
		assertEquals(WAIT, fixture.advance(12));
		assertTrue(fixture.memory.capture().entries.isEmpty());
		assertEquals(WAIT, fixture.obstacles.advance(fixture.snapshot(13), fixture.from, 13, -1));
		assertEquals(CONTINUE, fixture.advance(14));
		assertEquals(1L, fixture.walk.receipt("active", "test", fixture.to, 14).get("obstacles_cleared"));
		assertFalse(fixture.obstacles.observeClear(fixture.snapshot(15), 1, 15));
		assertEquals(1, fixture.count("WALK_OBSTACLE_CLEARED"));
	}

	@Test
	public void anObservedCrossingTakesPriorityOverOlderLockedFeedback() throws Exception
	{
		Fixture fixture = new Fixture();
		fixture.begin();
		assertFalse(fixture.obstacles.complete(fixture.snapshot(11), Map.of("status", "dispatched"), null));
		GenericClientSnapshot crossed = GenericClientWalkTestFixtures.doorSnapshot(13, fixture.to, fixture.from, fixture.to, false,
			List.of(new GenericClientGameMessageBuffer.Message(12, "gamemessage", "", "", "The door is locked.")));
		assertEquals(WAIT, fixture.obstacles.advance(crossed, fixture.to, 13, 1));
		assertFalse(fixture.memory.capture().blocks(fixture.from, fixture.to));
		assertEquals("cleared", fixture.memory.capture().entries.get(0).toMap().get("status"));
	}

	@Test
	public void explicitLockedFeedbackReplansBeforeTheSettleDelay() throws Exception
	{
		Fixture fixture = new Fixture();
		fixture.begin();
		assertFalse(fixture.obstacles.complete(fixture.snapshot(11), Map.of("status", "dispatched"), null));
		GenericClientSnapshot locked = GenericClientWalkTestFixtures.doorSnapshot(12, fixture.from, fixture.from, fixture.to, true,
			List.of(new GenericClientGameMessageBuffer.Message(12, "spam", "", "", "The door is locked.")));
		assertEquals(REPLAN, fixture.obstacles.advance(locked, fixture.from, 12, 0));
		assertEquals("locked", fixture.memory.capture().entries.get(0).toMap().get("reason"));
		assertEquals("The door is locked.", fixture.memory.capture().entries.get(0).toMap().get("detail"));
	}

	@Test
	public void recordsASolidEdgeOnceInTheReplanReceipt() throws Exception
	{
		Fixture fixture = new Fixture();
		GenericClientSnapshot.RouteBlock wall = GenericClientWalkTestFixtures.solidWallSnapshot(
			10, fixture.from, fixture.from, fixture.to).findRouteBlock(List.of(fixture.from, fixture.to), 0, 1);
		fixture.obstacles.recordSolid(wall, fixture.from);
		assertEquals(1L, fixture.walk.receipt("active", "test", fixture.from, 10).get("live_block_replans"));
		assertTrue(fixture.memory.capture().blocks(fixture.to, fixture.from));
		assertFalse(fixture.memory.capture().entries.get(0).toMap().containsKey("object_id"));
		assertEquals(1, fixture.count("WALK_ROUTE_BLOCKED"));
	}

	private final class Fixture
	{
		final WorldPoint from = new WorldPoint(3202, 3428, 0);
		final WorldPoint to = new WorldPoint(3203, 3428, 0);
		final List<String> reports = new ArrayList<>();
		final GenericClientEdgeMemory memory;
		final GenericClientWalkJourney walk;
		final GenericClientWalkObstacles obstacles;

		Fixture() throws IOException
		{
			memory = new GenericClientEdgeMemory(folders.newFolder().toPath(), () -> 1_000_000, reports::add);
			memory.activateAccount(42);
			GenericClientWalkRequest request = new GenericClientWalkRequest(to, 0, 100, GenericClientActivityContext.none(),
				false, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null);
			walk = new GenericClientWalkJourney(request, 10, from, (input, action) -> action.get(),
				0, "journey", "account", memory, reports::add);
			walk.path = List.of(from, to);
			obstacles = walk.obstacles;
		}

		void begin()
		{
			obstacles.begin(snapshot(10).findRouteBlock(walk.path, 0, 1), 10, from);
		}

		GenericClientSnapshot snapshot(long tick)
		{
			return GenericClientWalkTestFixtures.doorSnapshot(tick, from, from, to, true);
		}

		GenericClientWalkObstacles.Step advance(long tick)
		{
			return obstacles.advance(snapshot(tick), from, tick, 0);
		}

		long count(String event)
		{
			return reports.stream().filter(message -> message.startsWith(event + " ")).count();
		}
	}
}
