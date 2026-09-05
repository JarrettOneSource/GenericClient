package com.genericclient;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.runelite.api.coords.WorldPoint;

/** Captured graph inputs and search output for one asynchronous planning attempt. */
final class GenericClientWalkPlanner
{
	private final GenericClientPathfinder pathfinder;
	private final GenericClientWalkRequest request;
	private final Set<WorldPoint> avoidTiles;
	final WorldPoint start;
	final int revision;
	final int firstVia;
	private final List<WorldPoint> previousRoute;
	private final List<Integer> remainingVia;
	private final java.util.Map<Integer, GenericClientTransport> previousTransports;
	private final int joinFrom;
	private final boolean tryRejoin;
	final long queuedAt = System.nanoTime();
	private final GenericClientSnapshot planningSnapshot;
	final GenericClientEdgeMemory.View memory;
	final Set<WorldPoint> occupiedTiles;
	private final List<GenericClientTransport> transports;
	GenericClientPathfinder.Result result;
	boolean reused;
	boolean startRejoined;
	boolean stale;
	int plannedFirstVia;


	GenericClientWalkPlanner(GenericClientPathfinder pathfinder, GenericClientWalkJourney walk, WorldPoint start,
		String reason, Set<WorldPoint> occupied, GenericClientSnapshot snapshot, GenericClientEdgeMemory.View memory, Consumer<String> reporter)
	{
		this.pathfinder = pathfinder;
		request = walk.request;
		avoidTiles = walk.avoidTiles;
		planningSnapshot = snapshot;
		this.start = start;
		revision = ++walk.planRevision;
		firstVia = walk.nextVia;
		plannedFirstVia = firstVia;
		previousRoute = walk.path;
		previousTransports = walk.transports;
		remainingVia = previousRoute == null ? Collections.emptyList() :
			List.copyOf(walk.viaIndices.subList(firstVia - walk.planViaStart, walk.viaIndices.size()));
		joinFrom = previousTransports.containsKey(walk.pathIndex) ? walk.pathIndex : walk.pathIndex + 1;
		tryRejoin = previousRoute != null && ("off_route".equals(reason) || "stalled".equals(reason) ||
			"stalled_without_target".equals(reason) || "plan_start_moved".equals(reason));
		this.memory = memory;
		walk.edgeMemory = memory;
		for (GenericClientEdgeMemory.Entry entry : memory.entries) walk.edgeReceipts.put(entry.key(), entry.toMap());
		occupiedTiles = new LinkedHashSet<>(occupied);
		transports = GenericClientTransportCatalog.available(snapshot, avoidTiles, occupiedTiles, walk.transitions.blocked);
		reporter.accept("WALK_PLANNING plan=" + revision + " reason=" + reason + " start=" + start +
			" destination=" + walk.destination + " blockedEdges=" + memory.blockedEdges.size() +
			" npcBlockedTiles=" + occupiedTiles.size());
	}


	void calculate(Supplier<Frame> currentFrame)
	{
		search();
		alignStart(currentFrame.get());
	}

	private void search()
	{
		GenericClientPathfinder.EdgePolicy policy = edgePolicy(planningSnapshot);
		boolean eligibleSuffix = previousTransports.entrySet().stream().filter(entry -> entry.getKey() >= joinFrom)
			.allMatch(entry -> transports.contains(entry.getValue()));
		GenericClientPathfinder.Result local = tryRejoin && eligibleSuffix
			? pathfinder.rejoin(start, previousRoute, joinFrom, remainingVia, previousTransports, policy) : null;
		if (local != null && local.getStatus() == GenericClientPathfinder.Status.FOUND)
		{
			result = local;
			reused = true;
			return;
		}
		result = pathfinder.findThrough(start, request, firstVia, policy, transports);
		if (local != null) result = result.withAdditionalExpandedNodes(local.getExpandedNodes());
	}

	private void alignStart(Frame frame)
	{
		GenericClientSnapshot latest = frame.snapshot;
		WorldPoint current = latest == null ? null : latest.getPlayerWorldPoint();
		int observedVia = frame.nextVia;
		if (result.getStatus() != GenericClientPathfinder.Status.FOUND || current == null ||
			(current.equals(start) && observedVia == firstVia)) return;
		int skipped = observedVia - firstVia;
		int afterPassedVia = skipped == 0 ? 0 : result.getViaIndices().get(skipped - 1);
		GenericClientPathfinder.Result aligned = pathfinder.rejoin(current, result.getPath(), afterPassedVia,
			result.getViaIndices().subList(skipped, result.getViaIndices().size()), result.getTransports(),
			edgePolicy(latest));
		if (aligned.getStatus() != GenericClientPathfinder.Status.FOUND)
		{
			stale = true;
			return;
		}
		result = aligned.withAdditionalExpandedNodes(result.getExpandedNodes());
		startRejoined = true;
		plannedFirstVia = observedVia;
	}

	boolean fresh(WorldPoint current, int nextVia)
	{
		WorldPoint plannedStart = result.getStatus() == GenericClientPathfinder.Status.FOUND
			? result.getPath().get(0) : start;
		return !stale && plannedStart.equals(current) && nextVia == plannedFirstVia;
	}

	boolean transportsFresh(GenericClientSnapshot frame, GenericClientWalkJourney walk)
	{
		return frame != null && transports.equals(GenericClientTransportCatalog.available(frame, avoidTiles, occupiedTiles, walk.transitions.blocked));
	}

	private GenericClientPathfinder.EdgePolicy edgePolicy(GenericClientSnapshot snapshot)
	{
		return (x, y, plane, dx, dy, staticAllowed) ->
		{
			WorldPoint next = new WorldPoint(x + dx, y + dy, plane);
			if (avoidTiles.contains(next) || occupiedTiles.contains(next) ||
				memory.blocks(new WorldPoint(x, y, plane), next)) return false;
			return snapshot == null ? staticAllowed : snapshot.canPlanMove(x, y, plane, dx, dy, staticAllowed);
		};
	}


	static final class Frame
	{
		final GenericClientSnapshot snapshot;
		final int nextVia;

		Frame(GenericClientSnapshot snapshot, int nextVia)
		{
			this.snapshot = snapshot;
			this.nextVia = nextVia;
		}
	}
}
