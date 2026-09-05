package com.genericclient;

import static org.junit.Assert.assertEquals;
import static com.genericclient.GenericClientTestSupport.transport;
import static org.junit.Assert.assertNotEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientTransportOptimalityTest
{
	private static final int NODES = 32;
	private static final int INFINITE = 1_000_000;

	@Test
	public void agreesWithExhaustiveShortestCostsOnDirectedTwoPlaneGraphs() throws Exception
	{
		GenericClientPathfinder planner = new GenericClientPathfinder(GenericClientCollisionMap.loadBundled());
		for (int seed = 0; seed < 40; seed++)
		{
			Random random = new Random(seed);
			int[][] walking = walkingEdges(random);
			List<GenericClientTransport> transports = new ArrayList<>();
			int[][] expected = new int[NODES][];
			for (int i = 0; i < NODES; i++) expected[i] = walking[i].clone();
			for (int i = 0; i < 12; i++)
			{
				int from = random.nextInt(NODES);
				int to = (from + 1 + random.nextInt(NODES - 1)) % NODES;
				int cost = 1 + random.nextInt(90);
				transports.add(transport("edge_" + i, point(from), point(to), cost));
				expected[from][to] = Math.min(expected[from][to], cost);
			}
			shortestCosts(expected);
			for (int goal : new int[]{15, 31})
			{
				GenericClientPathfinder.Result route = planner.find(point(0), point(goal), 0,
					(x, y, plane, dx, dy, allowed) -> allowed(walking, x, y, plane, dx, dy), transports);
				if (expected[0][goal] == INFINITE) assertNotEquals(GenericClientPathfinder.Status.FOUND, route.getStatus());
				else
				{
					assertEquals("seed " + seed, GenericClientPathfinder.Status.FOUND, route.getStatus());
					assertEquals("seed " + seed, expected[0][goal], routeCost(route, walking));
				}
			}
		}
	}

	private static int[][] walkingEdges(Random random)
	{
		int[][] edges = new int[NODES][NODES];
		for (int from = 0; from < NODES; from++)
		{
			for (int to = 0; to < NODES; to++)
			{
				WorldPoint start = point(from);
				WorldPoint end = point(to);
				int dx = Math.abs(start.getX() - end.getX());
				int dy = Math.abs(start.getY() - end.getY());
				int cost = dx == 0 || dy == 0 ? 10 : 14;
				edges[from][to] = start.getPlane() == end.getPlane() && dx <= 1 && dy <= 1 && random.nextBoolean() ? cost : INFINITE;
			}
			edges[from][from] = 0;
		}
		return edges;
	}

	private static boolean allowed(int[][] walking, int x, int y, int plane, int dx, int dy)
	{
		if (x + dx < 100 || x + dx > 103 || y + dy < 100 || y + dy > 103) return false;
		return walking[index(new WorldPoint(x, y, plane))][index(new WorldPoint(x + dx, y + dy, plane))] < INFINITE;
	}

	private static void shortestCosts(int[][] costs)
	{
		for (int via = 0; via < NODES; via++)
			for (int from = 0; from < NODES; from++)
				for (int to = 0; to < NODES; to++)
					costs[from][to] = Math.min(costs[from][to], costs[from][via] + costs[via][to]);
	}

	private static int routeCost(GenericClientPathfinder.Result route, int[][] walking)
	{
		int cost = 0;
		for (int i = 0; i < route.getPath().size() - 1; i++)
		{
			GenericClientTransport transport = route.getTransports().get(i);
			cost += transport == null ? walking[index(route.getPath().get(i))][index(route.getPath().get(i + 1))] : transport.cost;
		}
		return cost;
	}

	private static WorldPoint point(int node) { return new WorldPoint(100 + node % 4, 100 + node / 4 % 4, node / 16); }
	private static int index(WorldPoint point) { return point.getPlane() * 16 + (point.getY() - 100) * 4 + point.getX() - 100; }
}
