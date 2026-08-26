package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientWalkerTest
{
	@Test
	public void waitsUntilPlayerIsNearTheAcceptedWaypoint() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput();
		GenericClientWalker walker = new GenericClientWalker(
			input,
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			WorldPoint destination = new WorldPoint(3230, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			CompletableFuture<?> completion = walker.walkTo(destination, 0, 60, false);

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
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			WorldPoint destination = new WorldPoint(3207, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			walker.walkTo(destination, 0, 60, true);

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
	public void backsOffAfterTheFarthestAcceptedClickProducesNoMovement() throws Exception
	{
		FakeWalkInput input = new FakeWalkInput(Integer.MAX_VALUE);
		GenericClientWalker walker = new GenericClientWalker(
			input,
			GenericClientCollisionMap.loadBundled(),
			message -> { });
		try
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			walker.publishGameTick(snapshot(0, start));
			walker.walkTo(new WorldPoint(3230, 3428, 0), 0, 60, false);
			waitForFirstClick(walker, input, start);
			int firstDistance = distance(start, input.targets.get(0));

			for (long tick = 2; tick <= 8; tick++)
			{
				walker.publishGameTick(snapshot(tick, start));
			}
			waitForClickCount(walker, input, start, 2, 9);

			int secondDistance = distance(start, input.targets.get(1));
			assertTrue(secondDistance <= firstDistance - 3);
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
			walker.publishGameTick(snapshot(tick, player));
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
			boolean breaksEnabled)
		{
			candidateBatches.add(new ArrayList<>(candidates));
			breakPolicies.add(breaksEnabled);
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
}
