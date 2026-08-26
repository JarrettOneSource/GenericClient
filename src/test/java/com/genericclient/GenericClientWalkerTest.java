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
			CompletableFuture<?> completion = walker.walkTo(destination, 0, 60);

			waitForFirstClick(walker, input, start);
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
			walker.walkTo(destination, 0, 60);

			waitForFirstClick(walker, input, start);
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
		private final List<WorldPoint> targets = new ArrayList<>();

		@Override
		public CompletableFuture<String> walkToTile(WorldPoint target)
		{
			targets.add(target);
			return CompletableFuture.completedFuture("WALK_TILE_CLICK_EXECUTED test");
		}

		@Override
		public void cancelWalkToTile()
		{
		}
	}
}
