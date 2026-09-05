package com.genericclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.runelite.api.coords.WorldPoint;

final class GenericClientPathfinder
{
	private static final int MAX_COORDINATE = 0x7FFF;
	private static final int MAX_EXPANDED_NODES = 250_000;
	static final int DOOR_TRAVERSAL_COST = 80;
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
	private final int doorTraversalCost;

	GenericClientPathfinder(GenericClientCollisionMap collisionMap)
	{
		this(collisionMap, DOOR_TRAVERSAL_COST);
	}

	GenericClientPathfinder(
		GenericClientCollisionMap collisionMap,
		int doorTraversalCost)
	{
		this.collisionMap = collisionMap;
		if (doorTraversalCost < 0)
		{
			throw new IllegalArgumentException("Door traversal cost cannot be negative");
		}
		this.doorTraversalCost = doorTraversalCost;
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
		return find(start, destination, within, edgePolicy, List.of());
	}

	Result find(WorldPoint start, WorldPoint destination, int within,
		EdgePolicy edgePolicy, List<GenericClientTransport> transports)
	{
		return find(start, destination, within, edgePolicy, MAX_EXPANDED_NODES, List.of(),
			new GenericClientTransportGraph(transports));
	}

	private Result find(WorldPoint start, WorldPoint destination, int within,
		EdgePolicy edgePolicy, int maximumExpandedNodes, List<WorldPoint> arrivalTiles, GenericClientTransportGraph graph)
	{
		if (within < 0)
		{
			throw new IllegalArgumentException("Arrival radius cannot be negative");
		}
		if (!validCoordinate(start.getX(), start.getY()) ||
			!validCoordinate(destination.getX(), destination.getY()))
		{
			return Result.failed(Status.UNREACHABLE, 0);
		}
		GenericClientTransportGraph.Estimate estimate = graph.toward(destination, within);
		if (!estimate.connects(start)) return Result.failed(Status.UNSUPPORTED_PLANE, 0);

		return search(start, new Goal()
		{
			@Override public boolean reached(int x, int y, int plane)
			{
				return plane == destination.getPlane() && chebyshev(x, y, destination.getX(), destination.getY()) <= within &&
					(arrivalTiles.isEmpty() || arrivalTiles.contains(new WorldPoint(x, y, plane)));
			}
			@Override public int estimate(int x, int y, int plane) { return estimate.at(x, y, plane); }
		}, edgePolicy, maximumExpandedNodes, graph);
	}

	private Result search(WorldPoint start, Goal goal, EdgePolicy policy, int maximumExpandedNodes, GenericClientTransportGraph graph)
	{
		return new Search(start, goal, policy, maximumExpandedNodes, graph).run();
	}

	private final class Search
	{
		private final Goal goal;
		private final EdgePolicy edgePolicy;
		private final int maximumExpandedNodes;
		private final int startPacked;
		private final GenericClientTransportGraph graph;
		private final SearchNodes nodes = new SearchNodes();
		private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator
			.comparingInt(Node::score)
			.thenComparingInt(Node::getHeuristic)
			.thenComparingLong(Node::getSequence));
		private long sequence;
		private int expanded;

		private Search(
			WorldPoint start,
			Goal goal,
			EdgePolicy edgePolicy,
			int maximumExpandedNodes, GenericClientTransportGraph graph)
		{
			this.goal = goal;
			this.edgePolicy = edgePolicy;
			this.maximumExpandedNodes = maximumExpandedNodes;
			this.graph = graph;
			this.startPacked = pack(start.getX(), start.getY(), start.getPlane());
			nodes.improve(startPacked, 0, startPacked, null);
			open.add(new Node(
				startPacked,
				0,
				goal.estimate(start.getX(), start.getY(), start.getPlane()),
				sequence++));
		}

		private Result run()
		{
			while (!open.isEmpty())
			{
				Node current = open.poll();
				if (!nodes.close(current.position, current.cost))
				{
					continue;
				}

				int x = unpackX(current.position);
				int y = unpackY(current.position);
				int plane = unpackPlane(current.position);
				if (goal.reached(x, y, plane))
				{
					return reconstruct(current.position, startPacked, nodes, expanded);
				}
				if (++expanded >= maximumExpandedNodes)
				{
					return Result.failed(Status.SEARCH_LIMIT, expanded);
				}
				for (int[] direction : DIRECTIONS)
				{
					expand(current, x, y, plane, direction);
				}
				for (GenericClientTransport transport : graph.from(current.position))
				{
					WorldPoint next = transport.destination;
					add(current, next.getX(), next.getY(), next.getPlane(), transport.cost, transport);
				}
			}
			return Result.failed(Status.UNREACHABLE, expanded);
		}

		private void expand(Node current, int x, int y, int plane, int[] direction)
		{
			int nextX = x + direction[0];
			int nextY = y + direction[1];
			boolean staticAllowed = collisionMap.canMove(x, y, plane, direction[0], direction[1]);
			if (!validCoordinate(nextX, nextY) || !edgePolicy.canMove(
				x, y, plane, direction[0], direction[1], staticAllowed))
			{
				return;
			}
			int stepCost = direction[0] == 0 || direction[1] == 0 ? 10 : 14;
			if (collisionMap.crossesDoor(x, y, plane, direction[0], direction[1])) stepCost += doorTraversalCost;
			add(current, nextX, nextY, plane, stepCost, null);
		}

		private void add(Node current, int nextX, int nextY, int plane, int stepCost, GenericClientTransport transport)
		{
			int next = pack(nextX, nextY, plane);
			int nextCost = current.cost + stepCost;
			if (!nodes.improve(next, nextCost, current.position, transport))
			{
				return;
			}
			open.add(new Node(
				next,
				nextCost,
				goal.estimate(nextX, nextY, plane),
				sequence++));
		}
	}

	Result findThrough(WorldPoint start, GenericClientWalkRequest request, int firstVia, EdgePolicy policy)
	{
		return findThrough(start, request, firstVia, policy, List.of());
	}

	Result findThrough(WorldPoint start, GenericClientWalkRequest request, int firstVia, EdgePolicy policy,
		List<GenericClientTransport> transports)
	{
		if (firstVia < 0 || firstVia > request.via.size()) throw new IllegalArgumentException("Invalid via progress");
		List<WorldPoint> path = new ArrayList<>();
		List<Integer> viaIndices = new ArrayList<>();
		Map<Integer, GenericClientTransport> chosen = new HashMap<>();
		GenericClientTransportGraph graph = new GenericClientTransportGraph(transports);
		WorldPoint cursor = start;
		int expanded = 0;
		for (int segment = firstVia; segment <= request.via.size(); segment++)
		{
			boolean finalSegment = segment == request.via.size();
			Result result = find(cursor, finalSegment ? request.destination : request.via.get(segment),
				finalSegment ? request.within : 2, policy, Math.max(1, MAX_EXPANDED_NODES - expanded),
				finalSegment ? request.arrivalTiles : List.of(), graph);
			expanded += result.expandedNodes;
			if (result.status != Status.FOUND)
				return new Result(result.status, Collections.emptyList(), expanded, Collections.emptyList(), segment + 1, Map.of());
			int offset = path.isEmpty() ? 0 : path.size() - 1;
			result.transports.forEach((index, transport) -> chosen.put(index + offset, transport));
			path.addAll(result.path.subList(path.isEmpty() ? 0 : 1, result.path.size()));
			cursor = result.path.get(result.path.size() - 1);
			if (!finalSegment) viaIndices.add(path.size() - 1);
		}
		return new Result(Status.FOUND, List.copyOf(path), expanded, List.copyOf(viaIndices), 0, Map.copyOf(chosen));
	}

	Result rejoin(WorldPoint start, List<WorldPoint> route, int fromIndex,
		List<Integer> remainingViaIndices, Map<Integer, GenericClientTransport> transports, EdgePolicy policy)
	{
		int end = remainingViaIndices.isEmpty() ? route.size() - 1 : remainingViaIndices.get(0);
		for (int origin : transports.keySet()) if (origin >= fromIndex) end = Math.min(end, origin);
		Map<Integer, Integer> goals = new HashMap<>();
		for (int index = Math.max(0, fromIndex); index <= end; index++)
		{
			WorldPoint point = route.get(index);
			if (point.getPlane() == start.getPlane() &&
				chebyshev(point.getX(), point.getY(), start.getX(), start.getY()) <= 32)
				goals.put(pack(point.getX(), point.getY(), point.getPlane()), index);
		}
		if (goals.isEmpty()) return Result.failed(Status.UNREACHABLE, 0);
		Result connector = search(start, new Goal()
		{
			@Override public boolean reached(int x, int y, int plane) { return goals.containsKey(pack(x, y, plane)); }
			@Override public int estimate(int x, int y, int plane) { return 0; }
		}, policy, 4096, new GenericClientTransportGraph(List.of()));
		if (connector.status != Status.FOUND) return connector;
		WorldPoint joined = connector.path.get(connector.path.size() - 1);
		int index = goals.get(pack(joined.getX(), joined.getY(), joined.getPlane()));
		List<WorldPoint> path = new ArrayList<>(connector.path);
		path.addAll(route.subList(index + 1, route.size()));
		List<Integer> via = new ArrayList<>();
		for (int marker : remainingViaIndices) via.add(connector.path.size() - 1 + marker - index);
		Map<Integer, GenericClientTransport> chosen = new HashMap<>();
		transports.forEach((origin, transport) -> {
			if (origin >= index) chosen.put(connector.path.size() - 1 + origin - index, transport);
		});
		return new Result(Status.FOUND, List.copyOf(path), connector.expandedNodes, List.copyOf(via), 0, Map.copyOf(chosen));
	}

	/** Primitive sparse storage covers the whole coordinate domain without clipping detours. */
	private static final class SearchNodes
	{
		private int[] positions = new int[128];
		private int[] costs = new int[128];
		private int[] parents = new int[128];
		private GenericClientTransport[] transports;
		private int size;

		// Zero is unused, cost + 1 is open, and the sign bit marks closed nodes.
		private boolean improve(int position, int cost, int parent, GenericClientTransport transport)
		{
			int index = index(position);
			int previous = costs[index];
			if (previous < 0 || previous > 0 && cost + 1 >= previous) return false;
			if (previous == 0 && (size + 1) * 4 >= positions.length * 3)
			{
				grow();
				index = index(position);
			}
			if (previous == 0) size++;
			positions[index] = position;
			costs[index] = cost + 1;
			parents[index] = parent;
			if (transport != null && transports == null) transports = new GenericClientTransport[positions.length];
			if (transports != null) transports[index] = transport;
			return true;
		}

		private boolean close(int position, int cost)
		{
			int index = index(position);
			if (costs[index] != cost + 1) return false;
			costs[index] |= Integer.MIN_VALUE;
			return true;
		}

		private int parent(int position)
		{
			int index = index(position);
			if (costs[index] == 0) throw new IllegalStateException("Path parent chain ended before the start tile");
			return parents[index];
		}

		private int index(int position)
		{
			int hash = position ^ (position >>> 16);
			hash *= 0x7feb352d;
			hash ^= hash >>> 15;
			hash *= 0x846ca68b;
			hash ^= hash >>> 16;
			int mask = positions.length - 1;
			int index = hash & mask;
			while (costs[index] != 0 && positions[index] != position) index = (index + 1) & mask;
			return index;
		}

		private void grow()
		{
			int[] oldPositions = positions;
			int[] oldCosts = costs;
			int[] oldParents = parents;
			GenericClientTransport[] oldTransports = transports;
			positions = new int[oldPositions.length * 2];
			costs = new int[positions.length];
			parents = new int[positions.length];
			if (oldTransports != null) transports = new GenericClientTransport[positions.length];
			for (int old = 0; old < oldPositions.length; old++)
			{
				if (oldCosts[old] == 0) continue;
				int index = index(oldPositions[old]);
				positions[index] = oldPositions[old];
				costs[index] = oldCosts[old];
				parents[index] = oldParents[old];
				if (oldTransports != null) transports[index] = oldTransports[old];
			}
		}
	}

	private interface Goal
	{
		boolean reached(int x, int y, int plane);
		int estimate(int x, int y, int plane);
	}

	@FunctionalInterface
	interface EdgePolicy
	{
		boolean canMove(int x, int y, int plane, int dx, int dy, boolean staticAllowed);
	}

	private static Result reconstruct(int goal, int start, SearchNodes nodes, int expanded)
	{
		List<WorldPoint> reversed = new ArrayList<>();
		Map<Integer, GenericClientTransport> chosen = new HashMap<>();
		Map<Integer, GenericClientTransport> reversedTransports = new HashMap<>();
		int current = goal;
		while (true)
		{
			reversed.add(new WorldPoint(unpackX(current), unpackY(current), unpackPlane(current)));
			if (current == start)
			{
				break;
			}
			GenericClientTransport transport = nodes.transports == null ? null : nodes.transports[nodes.index(current)];
			if (transport != null) reversedTransports.put(reversed.size(), transport);
			current = nodes.parent(current);
		}
		Collections.reverse(reversed);
		reversedTransports.forEach((index, transport) -> chosen.put(reversed.size() - index - 1, transport));
		return new Result(Status.FOUND, List.copyOf(reversed), expanded, List.of(), 0, Map.copyOf(chosen));
	}

	private static int chebyshev(int x1, int y1, int x2, int y2)
	{
		return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
	}

	private static boolean validCoordinate(int x, int y)
	{
		return x >= 0 && x <= MAX_COORDINATE && y >= 0 && y <= MAX_COORDINATE;
	}

	static int pack(int x, int y, int plane)
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
		private final List<Integer> viaIndices;
		private final int failedSegment;
		private final Map<Integer, GenericClientTransport> transports;

		private Result(Status status, List<WorldPoint> path, int expandedNodes)
		{
			this(status, path, expandedNodes, Collections.emptyList(), 0, Map.of());
		}

		private Result(Status status, List<WorldPoint> path, int expandedNodes,
			List<Integer> viaIndices, int failedSegment, Map<Integer, GenericClientTransport> transports)
		{
			this.status = status;
			this.path = path;
			this.expandedNodes = expandedNodes;
			this.viaIndices = viaIndices;
			this.failedSegment = failedSegment;
			this.transports = transports;
		}

		List<Integer> getViaIndices() { return viaIndices; }
		int getFailedSegment() { return failedSegment; }
		Map<Integer, GenericClientTransport> getTransports() { return transports; }

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

		Result withAdditionalExpandedNodes(int additional)
		{
			return new Result(status, path, expandedNodes + additional, viaIndices, failedSegment, transports);
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
