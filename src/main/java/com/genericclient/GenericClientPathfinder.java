package com.genericclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

final class GenericClientPathfinder
{
	private static final int MAX_COORDINATE = 0x7FFF;
	private static final int MAX_EXPANDED_NODES = 250_000;
	private static final int[][] DIRECTIONS =
	{
		{-1, 0},
		{1, 0},
		{0, -1},
		{0, 1},
		{-1, -1},
		{1, -1},
		{-1, 1},
		{1, 1}
	};

	private final GenericClientCollisionMap collisionMap;

	GenericClientPathfinder(GenericClientCollisionMap collisionMap)
	{
		this.collisionMap = collisionMap;
	}

	Result find(WorldPoint start, WorldPoint destination, int within)
	{
		return find(start, destination, within, (x, y, plane, dx, dy, staticAllowed) -> staticAllowed);
	}

	Result find(
		WorldPoint start,
		WorldPoint destination,
		int within,
		EdgePolicy edgePolicy)
	{
		return find(start, destination, within, edgePolicy, MAX_EXPANDED_NODES);
	}

	private Result find(
		WorldPoint start,
		WorldPoint destination,
		int within,
		EdgePolicy edgePolicy,
		int maximumExpandedNodes)
	{
		if (start.getPlane() != destination.getPlane())
		{
			return Result.failed(Status.UNSUPPORTED_PLANE, 0);
		}
		if (within < 0)
		{
			throw new IllegalArgumentException("Arrival radius cannot be negative");
		}
		if (!validCoordinate(start.getX(), start.getY()) ||
			!validCoordinate(destination.getX(), destination.getY()))
		{
			return Result.failed(Status.UNREACHABLE, 0);
		}

		int startPacked = pack(start.getX(), start.getY(), start.getPlane());
		Map<Integer, Integer> costs = new HashMap<>();
		Map<Integer, Integer> parents = new HashMap<>();
		Set<Integer> closed = new HashSet<>();
		PriorityQueue<Node> open = new PriorityQueue<>(Comparator
			.comparingInt(Node::score)
			.thenComparingInt(Node::getHeuristic)
			.thenComparingLong(Node::getSequence));

		long sequence = 0;
		int initialHeuristic = heuristic(start.getX(), start.getY(), destination, within);
		costs.put(startPacked, 0);
		open.add(new Node(startPacked, 0, initialHeuristic, sequence++));

		int expanded = 0;
		while (!open.isEmpty())
		{
			Node current = open.poll();
			Integer bestCost = costs.get(current.position);
			if (bestCost == null || current.cost != bestCost || !closed.add(current.position))
			{
				continue;
			}

			int x = unpackX(current.position);
			int y = unpackY(current.position);
			int plane = unpackPlane(current.position);
			if (chebyshev(x, y, destination.getX(), destination.getY()) <= within)
			{
				return Result.found(reconstruct(current.position, startPacked, parents), expanded);
			}
			if (++expanded >= maximumExpandedNodes)
			{
				return Result.failed(Status.SEARCH_LIMIT, expanded);
			}

			for (int[] direction : DIRECTIONS)
			{
				int nextX = x + direction[0];
				int nextY = y + direction[1];
				boolean staticAllowed = collisionMap.canMove(
					x, y, plane, direction[0], direction[1]);
				if (!validCoordinate(nextX, nextY) ||
					!edgePolicy.canMove(
						x, y, plane, direction[0], direction[1], staticAllowed))
				{
					continue;
				}

				int next = pack(nextX, nextY, plane);
				if (closed.contains(next))
				{
					continue;
				}

				int stepCost = direction[0] == 0 || direction[1] == 0 ? 10 : 14;
				int nextCost = current.cost + stepCost;
				if (nextCost >= costs.getOrDefault(next, Integer.MAX_VALUE))
				{
					continue;
				}

				costs.put(next, nextCost);
				parents.put(next, current.position);
				int nextHeuristic = heuristic(nextX, nextY, destination, within);
				open.add(new Node(next, nextCost, nextHeuristic, sequence++));
			}
		}

		return Result.failed(Status.UNREACHABLE, expanded);
	}

	Result findSegment(
		WorldPoint start,
		WorldPoint destination,
		int within,
		EdgePolicy edgePolicy,
		int maximumPathTiles)
	{
		if (maximumPathTiles < 2)
		{
			throw new IllegalArgumentException("Segment path limit must be at least two tiles");
		}
		if (chebyshev(
			start.getX(), start.getY(), destination.getX(), destination.getY()) < maximumPathTiles)
		{
			int localSearchLimit = maximumPathTiles * maximumPathTiles * DIRECTIONS.length;
			Result local = find(
				start, destination, within, edgePolicy, localSearchLimit);
			if (local.status == Status.FOUND)
			{
				return local.limitPathTiles(maximumPathTiles);
			}
		}
		Result global = find(start, destination, within, edgePolicy);
		if (global.status != Status.FOUND)
		{
			return global;
		}
		if (global.path.size() <= maximumPathTiles)
		{
			return find(start, destination, within, edgePolicy)
				.withAdditionalExpandedNodes(global.expandedNodes)
				.limitPathTiles(maximumPathTiles);
		}

		int expanded = global.expandedNodes;
		for (int guideIndex = maximumPathTiles - 1; guideIndex > 0;
			guideIndex = Math.max(0, guideIndex - 8))
		{
			Result local = find(start, global.path.get(guideIndex), 0, edgePolicy);
			expanded += local.expandedNodes;
			if (local.status == Status.FOUND)
			{
				return local.withExpandedNodes(expanded).limitPathTiles(maximumPathTiles);
			}
		}
		return Result.failed(Status.UNREACHABLE, expanded);
	}

	@FunctionalInterface
	interface EdgePolicy
	{
		boolean canMove(int x, int y, int plane, int dx, int dy, boolean staticAllowed);
	}

	private static List<WorldPoint> reconstruct(int goal, int start, Map<Integer, Integer> parents)
	{
		List<WorldPoint> reversed = new ArrayList<>();
		int current = goal;
		while (true)
		{
			reversed.add(new WorldPoint(unpackX(current), unpackY(current), unpackPlane(current)));
			if (current == start)
			{
				break;
			}
			Integer parent = parents.get(current);
			if (parent == null)
			{
				throw new IllegalStateException("Path parent chain ended before the start tile");
			}
			current = parent;
		}
		Collections.reverse(reversed);
		return Collections.unmodifiableList(reversed);
	}

	private static int heuristic(int x, int y, WorldPoint destination, int within)
	{
		int dx = Math.max(0, Math.abs(x - destination.getX()) - within);
		int dy = Math.max(0, Math.abs(y - destination.getY()) - within);
		int diagonal = Math.min(dx, dy);
		return diagonal * 14 + (Math.max(dx, dy) - diagonal) * 10;
	}

	private static int chebyshev(int x1, int y1, int x2, int y2)
	{
		return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
	}

	private static boolean validCoordinate(int x, int y)
	{
		return x >= 0 && x <= MAX_COORDINATE && y >= 0 && y <= MAX_COORDINATE;
	}

	private static int pack(int x, int y, int plane)
	{
		return (x & MAX_COORDINATE) | ((y & MAX_COORDINATE) << 15) | ((plane & 3) << 30);
	}

	private static int unpackX(int point)
	{
		return point & MAX_COORDINATE;
	}

	private static int unpackY(int point)
	{
		return (point >>> 15) & MAX_COORDINATE;
	}

	private static int unpackPlane(int point)
	{
		return point >>> 30;
	}

	enum Status
	{
		FOUND,
		UNREACHABLE,
		SEARCH_LIMIT,
		UNSUPPORTED_PLANE
	}

	static final class Result
	{
		private final Status status;
		private final List<WorldPoint> path;
		private final int expandedNodes;

		private Result(Status status, List<WorldPoint> path, int expandedNodes)
		{
			this.status = status;
			this.path = path;
			this.expandedNodes = expandedNodes;
		}

		private static Result found(List<WorldPoint> path, int expandedNodes)
		{
			return new Result(Status.FOUND, path, expandedNodes);
		}

		private static Result failed(Status status, int expandedNodes)
		{
			return new Result(status, Collections.emptyList(), expandedNodes);
		}

		Status getStatus()
		{
			return status;
		}

		List<WorldPoint> getPath()
		{
			return path;
		}

		int getExpandedNodes()
		{
			return expandedNodes;
		}

		private Result withAdditionalExpandedNodes(int additional)
		{
			return withExpandedNodes(expandedNodes + additional);
		}

		private Result withExpandedNodes(int expanded)
		{
			return new Result(status, path, expanded);
		}

		private Result limitPathTiles(int maximum)
		{
			if (status != Status.FOUND || path.size() <= maximum)
			{
				return this;
			}
			return new Result(
				status,
				Collections.unmodifiableList(new ArrayList<>(path.subList(0, maximum))),
				expandedNodes);
		}
	}

	private static final class Node
	{
		private final int position;
		private final int cost;
		private final int heuristic;
		private final long sequence;

		private Node(int position, int cost, int heuristic, long sequence)
		{
			this.position = position;
			this.cost = cost;
			this.heuristic = heuristic;
			this.sequence = sequence;
		}

		private int score()
		{
			return cost + heuristic;
		}

		private int getHeuristic()
		{
			return heuristic;
		}

		private long getSequence()
		{
			return sequence;
		}
	}
}
