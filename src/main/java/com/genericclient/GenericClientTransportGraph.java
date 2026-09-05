package com.genericclient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.runelite.api.coords.WorldPoint;

/** Captured transport connectivity and a collision-free lower bound for one search goal. */
final class GenericClientTransportGraph
{
	private static final int UNREACHABLE = Integer.MAX_VALUE / 4;
	private final List<GenericClientTransport> transports;
	private final int[] positions;
	private final List<List<GenericClientTransport>> origins = new ArrayList<>();

	GenericClientTransportGraph(List<GenericClientTransport> transports)
	{
		this.transports = List.copyOf(transports);
		Map<Integer, List<GenericClientTransport>> grouped = new HashMap<>();
		for (GenericClientTransport transport : transports)
		{
			WorldPoint origin = transport.origin;
			grouped.computeIfAbsent(GenericClientPathfinder.pack(origin.getX(), origin.getY(), origin.getPlane()),
				key -> new ArrayList<>()).add(transport);
		}
		positions = grouped.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
		for (int position : positions) origins.add(List.copyOf(grouped.get(position)));
	}

	List<GenericClientTransport> from(int position)
	{
		int index = Arrays.binarySearch(positions, position);
		return index < 0 ? List.of() : origins.get(index);
	}

	Estimate toward(WorldPoint destination, int within) { return new Estimate(destination, within); }

	final class Estimate
	{
		private final WorldPoint destination;
		private final int within;
		private final int[] remaining = new int[transports.size()];

		private Estimate(WorldPoint destination, int within)
		{
			this.destination = destination;
			this.within = within;
			PriorityQueue<Map.Entry<Integer, Integer>> frontier = new PriorityQueue<>(Comparator.comparingInt(Map.Entry::getValue));
			for (int i = 0; i < remaining.length; i++)
			{
				GenericClientTransport transport = transports.get(i);
				remaining[i] = add(transport.cost, walking(transport.destination, destination, within));
				if (remaining[i] != UNREACHABLE) frontier.add(Map.entry(i, remaining[i]));
			}
			while (!frontier.isEmpty())
			{
				Map.Entry<Integer, Integer> current = frontier.remove();
				if (current.getValue() != remaining[current.getKey()]) continue;
				WorldPoint origin = transports.get(current.getKey()).origin;
				for (int i = 0; i < remaining.length; i++)
				{
					GenericClientTransport predecessor = transports.get(i);
					int candidate = add(predecessor.cost, add(walking(predecessor.destination, origin, 0), current.getValue()));
					int previous = remaining[i];
					remaining[i] = Math.min(remaining[i], candidate);
					if (remaining[i] != previous) frontier.add(Map.entry(i, remaining[i]));
				}
			}
		}

		int at(int x, int y, int plane)
		{
			int best = walking(x, y, plane, destination, within);
			for (int i = 0; i < transports.size(); i++)
				best = Math.min(best, add(walking(x, y, plane, transports.get(i).origin, 0), remaining[i]));
			return best;
		}

		boolean connects(WorldPoint start) { return at(start.getX(), start.getY(), start.getPlane()) < UNREACHABLE; }
	}

	private static int walking(WorldPoint start, WorldPoint destination, int within)
	{
		return walking(start.getX(), start.getY(), start.getPlane(), destination, within);
	}

	private static int walking(int x, int y, int plane, WorldPoint destination, int within)
	{
		if (plane != destination.getPlane()) return UNREACHABLE;
		int dx = Math.max(0, Math.abs(x - destination.getX()) - within);
		int dy = Math.max(0, Math.abs(y - destination.getY()) - within);
		int diagonal = Math.min(dx, dy);
		return diagonal * 14 + (Math.max(dx, dy) - diagonal) * 10;
	}

	private static int add(int first, int second) { return (int) Math.min(UNREACHABLE, (long) first + second); }
}
