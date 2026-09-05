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
	public void sparseSearchPreservesCoordinateZeroAndTheHighestPlane() throws Exception
	{
		GenericClientPathfinder pathfinder = new GenericClientPathfinder(GenericClientCollisionMap.loadBundled());
		for (int plane : new int[]{0, 3})
		{
			int x = plane == 0 ? 0 : 32767 - 512;
			int y = plane == 0 ? 0 : 32767;
			WorldPoint start = new WorldPoint(x, y, plane);
			WorldPoint destination = new WorldPoint(x + 512, y, plane);
			GenericClientPathfinder.Result result = pathfinder.find(start, destination, 0,
				(px, py, p, dx, dy, allowed) -> py + dy == y && px + dx >= x && px + dx <= x + 512);
			assertEquals(GenericClientPathfinder.Status.FOUND, result.getStatus());
			assertEquals(513, result.getPath().size());
			assertEquals(start, result.getPath().get(0));
			assertEquals(destination, result.getPath().get(512));
		}
	}

	@Test
	public void localRejoinRetainsTheSuffixAndCannotSkipAPendingVia() throws Exception
	{
		GenericClientPathfinder pathfinder = new GenericClientPathfinder(GenericClientCollisionMap.loadBundled());
		List<WorldPoint> route = new java.util.ArrayList<>();
		for (int x = 3200; x <= 3230; x++) route.add(new WorldPoint(x, 3428, 0));
		WorldPoint displaced = new WorldPoint(3218, 3430, 0);
		GenericClientPathfinder.Result result = pathfinder.rejoin(displaced, route, 3, List.of(10), java.util.Map.of(),
			(x, y, plane, dx, dy, allowed) -> true);
		assertEquals(GenericClientPathfinder.Status.FOUND, result.getStatus());
		assertEquals(displaced, result.getPath().get(0));
		int via = result.getViaIndices().get(0);
		assertEquals(route.get(10), result.getPath().get(via));
		assertEquals(route.subList(10, route.size()), result.getPath().subList(via, result.getPath().size()));
		assertTrue(result.getExpandedNodes() < 4096);
	}

	@Test
	public void localRejoinHasABoundedSearchAndReportsExhaustion() throws Exception
	{
		GenericClientPathfinder pathfinder = new GenericClientPathfinder(GenericClientCollisionMap.loadBundled());
		WorldPoint start = new WorldPoint(16000, 16000, 0);
		WorldPoint sealed = new WorldPoint(16005, 16000, 0);
		GenericClientPathfinder.Result result = pathfinder.rejoin(start, List.of(sealed), 0, List.of(), java.util.Map.of(),
			(x, y, plane, dx, dy, allowed) -> x + dx != sealed.getX() || y + dy != sealed.getY());
		assertEquals(GenericClientPathfinder.Status.SEARCH_LIMIT, result.getStatus());
		assertEquals(4096, result.getExpandedNodes());
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

		GenericClientPathfinder.Result result = pathfinder.find(
			new WorldPoint(3090, 3402, 0),
			new WorldPoint(3070, 3359, 0),
			6,
			(x, y, plane, dx, dy, staticAllowed) ->
			{
				Boolean liveAllowed = live.canMove(
					new WorldPoint(x, y, plane),
					new WorldPoint(x + dx, y + dy, plane));
				return liveAllowed == null ? staticAllowed : liveAllowed;
			});

		assertEquals(GenericClientPathfinder.Status.FOUND, result.getStatus());
	}

	@Test
	public void preservesALiveDetourBeyondTheFormerSegmentSize() throws Exception
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

	}

	@Test
	public void prefersLiveCollisionForANearbyStaticMapMismatch() throws Exception
	{
		GenericClientPathfinder pathfinder = new GenericClientPathfinder(
			GenericClientCollisionMap.loadBundled());
		WorldPoint start = new WorldPoint(2912, 3466, 0);
		WorldPoint destination = new WorldPoint(2912, 3468, 0);

		GenericClientPathfinder.Result result = pathfinder.find(
			start,
			destination,
			0,
			(x, y, plane, dx, dy, staticAllowed) -> true);

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

	@Test
	public void avoidsUsingMuseumDoorsAsOrdinaryWalkingTiles() throws Exception
	{
		GenericClientCollisionMap collisionMap = GenericClientCollisionMap.loadBundled();
		WorldPoint start = new WorldPoint(3248, 3448, 0);
		WorldPoint destination = new WorldPoint(3272, 3448, 0);

		GenericClientPathfinder.Result unweighted = new GenericClientPathfinder(
			collisionMap, 0).find(start, destination, 0);
		GenericClientPathfinder.Result weighted = new GenericClientPathfinder(
			collisionMap).find(start, destination, 0);

		assertEquals(GenericClientPathfinder.Status.FOUND, unweighted.getStatus());
		assertEquals(GenericClientPathfinder.Status.FOUND, weighted.getStatus());
		assertEquals(2, doorCrossings(collisionMap, unweighted.getPath()));
		assertEquals(0, doorCrossings(collisionMap, weighted.getPath()));
	}

	@Test
	public void keepsADoorReachableWhenItIsTheDestinationBoundary() throws Exception
	{
		GenericClientCollisionMap collisionMap = GenericClientCollisionMap.loadBundled();
		GenericClientPathfinder.Result result = new GenericClientPathfinder(collisionMap).find(
			new WorldPoint(3261, 3446, 0),
			new WorldPoint(3261, 3447, 0),
			0);

		assertEquals(GenericClientPathfinder.Status.FOUND, result.getStatus());
		assertEquals(2, result.getPath().size());
		assertEquals(1, doorCrossings(collisionMap, result.getPath()));
	}

	@Test
	public void routesFromEmirsArenaToTheGrandExchangeWithoutVarrockBuildingDetours()
		throws Exception
	{
		GenericClientCollisionMap collisionMap = GenericClientCollisionMap.loadBundled();
		GenericClientPathfinder.Result result = new GenericClientPathfinder(collisionMap).find(
			new WorldPoint(3315, 3235, 0),
			new WorldPoint(3165, 3491, 0),
			8);

		assertEquals(GenericClientPathfinder.Status.FOUND, result.getStatus());
		assertEquals(1, doorCrossings(collisionMap, result.getPath()));
		assertTrue(result.getPath().stream().noneMatch(point ->
			point.getX() >= 3248 && point.getX() <= 3272 &&
			point.getY() >= 3438 && point.getY() <= 3490));
	}

	private static int doorCrossings(
		GenericClientCollisionMap collisionMap,
		List<WorldPoint> path)
	{
		int crossings = 0;
		for (int index = 1; index < path.size(); index++)
		{
			WorldPoint previous = path.get(index - 1);
			WorldPoint current = path.get(index);
			if (collisionMap.crossesDoor(
				previous.getX(),
				previous.getY(),
				previous.getPlane(),
				current.getX() - previous.getX(),
				current.getY() - previous.getY()))
			{
				crossings++;
			}
		}
		return crossings;
	}

	private static String edge(WorldPoint first, WorldPoint second)
	{
		String left = first.getX() + "," + first.getY() + "," + first.getPlane();
		String right = second.getX() + "," + second.getY() + "," + second.getPlane();
		return left.compareTo(right) <= 0 ? left + ":" + right : right + ":" + left;
	}

}
