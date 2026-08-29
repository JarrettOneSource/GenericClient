package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientPathfinderTest
{
	@Test
	public void guidesALongGlobalRouteThroughBoundedLocalSegments() throws Exception
	{
		GenericClientPathfinder pathfinder = new GenericClientPathfinder(
			GenericClientCollisionMap.loadBundled());
		WorldPoint current = new WorldPoint(3009, 3197, 0);
		WorldPoint destination = new WorldPoint(3165, 3491, 0);
		int segments = 0;

		while (distance(current, destination) > 8 && segments < 20)
		{
			GenericClientPathfinder.Result result = pathfinder.findSegment(
				current,
				destination,
				8,
				(x, y, plane, dx, dy, staticAllowed) -> staticAllowed,
				49);
			assertEquals(GenericClientPathfinder.Status.FOUND, result.getStatus());
			assertTrue(result.getPath().size() <= 49);
			current = result.getPath().get(result.getPath().size() - 1);
			segments++;
		}

		assertTrue(segments > 1);
		assertTrue(distance(current, destination) <= 8);
	}

	@Test
	public void crossesTheUnknownLiveSceneBorderUsingTheGlobalMap() throws Exception
	{
		GenericClientCollisionMap collisionMap = GenericClientCollisionMap.loadBundled();
		GenericClientPathfinder pathfinder = new GenericClientPathfinder(collisionMap);
		int[][] flags = new int[104][104];
		Arrays.fill(flags[0], 0xFFFFFF);
		Arrays.fill(flags[103], 0xFFFFFF);
		for (int x = 0; x < flags.length; x++)
		{
			flags[x][0] = 0xFFFFFF;
			flags[x][103] = 0xFFFFFF;
		}
		GenericClientSceneCollision live =
			new GenericClientSceneCollision(true, 3048, 3368, 0, flags);

		GenericClientPathfinder.Result result = pathfinder.findSegment(
			new WorldPoint(3090, 3402, 0),
			new WorldPoint(3070, 3359, 0),
			6,
			(x, y, plane, dx, dy, staticAllowed) ->
			{
				Boolean liveAllowed = live.canMove(
					new WorldPoint(x, y, plane),
					new WorldPoint(x + dx, y + dy, plane));
				return liveAllowed == null ? staticAllowed : liveAllowed;
			},
			49);

		assertEquals(GenericClientPathfinder.Status.FOUND, result.getStatus());
		assertTrue(result.getPath().size() <= 49);
	}

	@Test
	public void limitsALiveDetourToTheRequestedSegmentSize() throws Exception
	{
		GenericClientPathfinder pathfinder = new GenericClientPathfinder(
			GenericClientCollisionMap.loadBundled());
		WorldPoint start = new WorldPoint(3202, 3428, 0);
		WorldPoint destination = new WorldPoint(3230, 3428, 0);
		List<WorldPoint> waypoints = Arrays.asList(
			start,
			new WorldPoint(3202, 3480, 0),
			new WorldPoint(3230, 3480, 0),
			destination);
		Set<String> allowedEdges = new HashSet<>();
		for (int index = 1; index < waypoints.size(); index++)
		{
			GenericClientPathfinder.Result leg = pathfinder.find(
				waypoints.get(index - 1), waypoints.get(index), 0);
			assertEquals(GenericClientPathfinder.Status.FOUND, leg.getStatus());
			for (int step = 1; step < leg.getPath().size(); step++)
			{
				allowedEdges.add(edge(leg.getPath().get(step - 1), leg.getPath().get(step)));
			}
		}
		GenericClientPathfinder.EdgePolicy detour =
			(x, y, plane, dx, dy, staticAllowed) -> staticAllowed && allowedEdges.contains(edge(
				new WorldPoint(x, y, plane), new WorldPoint(x + dx, y + dy, plane)));
		GenericClientPathfinder.Result full = pathfinder.find(start, destination, 0, detour);
		assertEquals(GenericClientPathfinder.Status.FOUND, full.getStatus());
		assertTrue(full.getPath().size() > 49);

		GenericClientPathfinder.Result segment = pathfinder.findSegment(
			start, destination, 0, detour, 49);
		assertEquals(GenericClientPathfinder.Status.FOUND, segment.getStatus());
		assertEquals(49, segment.getPath().size());
	}

	@Test
	public void prefersLiveCollisionForANearbyStaticMapMismatch() throws Exception
	{
		GenericClientPathfinder pathfinder = new GenericClientPathfinder(
			GenericClientCollisionMap.loadBundled());
		WorldPoint start = new WorldPoint(2912, 3466, 0);
		WorldPoint destination = new WorldPoint(2912, 3468, 0);

		GenericClientPathfinder.Result result = pathfinder.findSegment(
			start,
			destination,
			0,
			(x, y, plane, dx, dy, staticAllowed) -> true,
			49);

		assertEquals(GenericClientPathfinder.Status.FOUND, result.getStatus());
		assertEquals(Arrays.asList(
			start,
			new WorldPoint(2912, 3467, 0),
			destination), result.getPath());
	}

	@Test
	public void routesFromFaladorWestBankToTheGrandExchange() throws Exception
	{
		GenericClientPathfinder pathfinder = new GenericClientPathfinder(
			GenericClientCollisionMap.loadBundled());

		GenericClientPathfinder.Result result = pathfinder.find(
			new WorldPoint(2945, 3376, 0),
			new WorldPoint(3165, 3491, 0),
			8);

		assertEquals(GenericClientPathfinder.Status.FOUND, result.getStatus());
	}

	private static String edge(WorldPoint first, WorldPoint second)
	{
		String left = first.getX() + "," + first.getY() + "," + first.getPlane();
		String right = second.getX() + "," + second.getY() + "," + second.getPlane();
		return left.compareTo(right) <= 0 ? left + ":" + right : right + ":" + left;
	}

	private static int distance(WorldPoint first, WorldPoint second)
	{
		return Math.max(
			Math.abs(first.getX() - second.getX()),
			Math.abs(first.getY() - second.getY()));
	}
}
