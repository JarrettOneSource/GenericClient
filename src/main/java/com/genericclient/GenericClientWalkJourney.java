package com.genericclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.runelite.api.coords.WorldPoint;

/** Progress and receipts for one journey. Mutable state is confined to the walker's monitor. */
final class GenericClientWalkJourney
{
	private final Consumer<String> reporter;
	private static final int MAX_CLICK_FAILURES = 5;
	private static final int OBSTACLE_SCAN_TILES = 12;
	private static final int HAZARDOUS_CLICK_COOLDOWN_TICKS = 1;
	private static final int CLICK_COOLDOWN_TICKS = 2;
	private static final int OFF_ROUTE_RADIUS = 3;
	private static final int RECOVERY_PLAN_TILES = 16;
	private static final int MAXIMUM_RECOVERY_REPLANS = 32;
	private static final int MINIMUM_RECOVERY_REPLANS = 6;
	final GenericClientWalkRequest request;
	final String journey;
	final String account;
	int nextVia;
	int planViaStart;
	List<Integer> viaIndices = Collections.emptyList();
	int failedSegment;
	final WorldPoint destination;
	final int within;
	final int timeoutTicks;
	GenericClientActivityContext activityContext;
	GenericClientActivityContext clickContext;
	final boolean useRun;
	Object interruptDetail;
	final GenericClientWalker.ClickBoundary clickBoundary;
	int inputRevision;
	final List<Map<String, Object>> clickReceipts = new ArrayList<>();
	final Set<WorldPoint> avoidTiles;
	final int maximumRecoveryPlans;
	final long startedAtTick;
	long lastObservedTick;
	final CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
	WorldPoint lastPlayer;
	WorldPoint lastAcceptedTarget;
	long lastMovedTick;
	long nextClickTick;
	long clickStartedTick;
	long pausedInteractionTicks;
	boolean planning;
	boolean clickInFlight;
	boolean runInFlight;
	boolean inputPaused;
	long inputPausedAtTick;
	boolean runToggleArmed = true;
	int planRevision;
	int plans;
	int localRejoins;
	int fullPlans;
	int startRejoins;
	double lastPlanMillis;
	int recoveryPlans;
	int pathRetries;
	int samePathStallRetries;
	int clicks;
	int liveBlockReplans;
	int npcBlockReplans;
	long npcBlockedSinceTick = -1;
	GenericClientEdgeMemory.View edgeMemory;
	final Map<Set<WorldPoint>, Map<String, Object>> edgeReceipts = new LinkedHashMap<>();
	final GenericClientWalkObstacles obstacles;
	final GenericClientWalkTransitions transitions;
	int runToggles;
	int consecutiveClickFailures;
	int pathIndex;
	int clickTargetIndex = -1;
	int pathTiles;
	int expandedNodes;
	int maximumLegTiles = Integer.MAX_VALUE;
	List<WorldPoint> path;
	Map<Integer, GenericClientTransport> transports = Map.of();
	WorldPoint clickTarget;
	boolean targetReached;
	long runStartedTick;

	InputWindow inputWindow()
	{
		boolean busy = planning || clickInFlight || obstacles.inFlight || runInFlight || inputPaused ||
			obstacles.pending != null || transitions.pending != null;
		WorldPoint waypoint = nextVia < request.via.size() ? request.via.get(nextVia) : destination;
		return new InputWindow(request.activityContext.inputTicket(), busy ? 0 : nextClickTick, waypoint);
	}

	static final class InputWindow
	{
		static final InputWindow NONE = new InputWindow(null, 0, null);
		final GenericClientActionBoundary.Ticket owner;
		final long nextTick;
		final WorldPoint waypoint;

		private InputWindow(GenericClientActionBoundary.Ticket owner, long nextTick, WorldPoint waypoint)
		{
			this.owner = owner;
			this.nextTick = nextTick;
			this.waypoint = waypoint;
		}
	}

	GenericClientWalkJourney(GenericClientWalkRequest request, long startedAtTick, WorldPoint start,
		GenericClientWalker.ClickBoundary clickBoundary, int nextVia, String journey, String account, GenericClientEdgeMemory memory, Consumer<String> reporter)
	{
		this.reporter = reporter;
		this.obstacles = new GenericClientWalkObstacles(this, memory, reporter);
		this.transitions = new GenericClientWalkTransitions(this);
		this.edgeMemory = memory.capture();
		this.request = request;
		this.destination = request.destination;
		this.within = request.within;
		this.timeoutTicks = request.timeoutTicks;
		this.activityContext = request.activityContext.openInputScope();
		this.useRun = request.useRun;
		this.clickBoundary = clickBoundary;
		this.avoidTiles = Collections.unmodifiableSet(new LinkedHashSet<>(request.avoidTiles));
		this.maximumRecoveryPlans = recoveryPlanLimit(start, destination);
		this.startedAtTick = startedAtTick;
		this.lastObservedTick = startedAtTick;
		this.lastPlayer = start;
		this.lastMovedTick = startedAtTick;
		this.nextVia = nextVia;
		this.journey = journey;
		this.account = account;
	}


	String recordClick(GenericClientSnapshot snapshot, double reach, GenericClientInteractionResult result, Throwable error)
	{
		clickContext.cancelInput();
		clickInFlight = false;
		if (inputPaused)
		{
			return null;
		}
		long tick = snapshot == null ? lastObservedTick : snapshot.getGameTick();
		pausedInteractionTicks += Math.max(0L, tick - clickStartedTick);
		nextClickTick = tick + (result != null && result.isWalkExecuted() && !activityContext.refreshesWalkClicks()
			? clickBoundary.nextClickDelayTicks() : clickCooldownTicks());
		if (result != null)
		{
			Map<String, Object> clickReceipt = result.toReceipt();
			clickReceipt.put("requested_reach_fraction", reach);
			clickReceipts.add(clickReceipt);
		}
		if (result != null && result.isClickDispatched())
		{
			clicks++;
		}
		if (error == null && result != null && result.isWalkExecuted() && result.getTarget() != null)
		{
			consecutiveClickFailures = 0;
			clickTarget = result.getTarget();
			targetReached = false;
			lastAcceptedTarget = result.getTarget();
			clickTargetIndex = routeIndex(result.getTarget());
			WorldPoint from = snapshot == null ? null : snapshot.getPlayerWorldPoint();
			reporter.accept("WALK_CLICK plan=" + planRevision + " from=" + from +
				" target=" + clickTarget + " pathIndex=" + clickTargetIndex);
			return null;
		}

		consecutiveClickFailures++;
		clickTarget = null;
		clickTargetIndex = -1;
		String detail = error == null
			? result == null ? "null" : result.getDetail()
			: String.valueOf(error.getMessage());
		reporter.accept("WALK_CLICK_REJECTED failures=" +
			consecutiveClickFailures + " result=" + detail);
		return consecutiveClickFailures >= MAX_CLICK_FAILURES ? detail : null;
	}

	GenericClientActivityContext openClickScope()
	{
		clickContext = activityContext.openInputScope();
		return clickContext;
	}

	void pauseInput(long tick)
	{
		excludePendingInputTime(tick);
		inputPaused = true;
		inputRevision++;
		inputPausedAtTick = tick;
		activityContext.cancelInput();
	}

	void excludePendingInputTime(long tick)
	{
		excludePauseTime(tick);
		if (clickInFlight) pausedInteractionTicks += Math.max(0L, tick - clickStartedTick);
		if (runInFlight) pausedInteractionTicks += Math.max(0L, tick - runStartedTick);
		obstacles.excludeInputTime(tick);
		transitions.excludeInputTime(tick);
	}

	void resumeInput(GenericClientSnapshot snapshot)
	{
		long tick = snapshot == null ? inputPausedAtTick : snapshot.getGameTick();
		activityContext = request.activityContext.openInputScope();
		excludePauseTime(tick);
		inputPaused = false;
		lastPlayer = snapshot == null ? lastPlayer : snapshot.getPlayerWorldPoint();
		lastMovedTick = tick;
		nextClickTick = tick + 1;
		clickTarget = null;
		clickTargetIndex = -1;
	}

	private void excludePauseTime(long tick)
	{
		if (!inputPaused) return;
		long elapsed = Math.max(0L, tick - inputPausedAtTick);
		pausedInteractionTicks += elapsed;
		transitions.resume(elapsed);
		inputPausedAtTick = tick;
	}

	void updateMovement(WorldPoint player, long tick)
	{
		if (!player.equals(lastPlayer))
		{
			lastPlayer = player;
			lastMovedTick = tick;
			samePathStallRetries = 0;
		}
	}


	void observeViaProgress(WorldPoint player)
	{
		while (nextVia < request.via.size() && distance(player, request.via.get(nextVia)) <= 2)
		{
			nextVia++;
			reporter.accept("WALK_VIA_PASSED journey=" + journey + " passed=" + nextVia);
		}
	}


	int nearestRouteIndex(WorldPoint player)
	{
		int from = Math.max(0, pathIndex - 3);
		int to = Math.min(path.size() - 1, pathIndex + 12);
		to = Math.min(to, transitions.nextIndex());
		int pendingVia = nextVia - planViaStart;
		if (pendingVia < viaIndices.size()) to = Math.min(to, viaIndices.get(pendingVia));
		if (pendingVia > 0) from = Math.max(from, viaIndices.get(pendingVia - 1));
		int bestIndex = -1;
		int bestDistance = Integer.MAX_VALUE;
		for (int index = from; index <= to; index++)
		{
			int candidateDistance = distance(player, path.get(index));
			if (candidateDistance < bestDistance ||
				(candidateDistance == bestDistance && index > bestIndex))
			{
				bestDistance = candidateDistance;
				bestIndex = index;
			}
		}
		return bestDistance <= OFF_ROUTE_RADIUS ? bestIndex : -1;
	}


	Set<WorldPoint> blockingNpcTiles(
		GenericClientSnapshot snapshot,
		int nearest)
	{
		if (snapshot == null || path == null || nearest < 0 ||
			nearest >= path.size() - 1)
		{
			return Collections.emptySet();
		}
		Set<WorldPoint> occupied = new LinkedHashSet<>();
		for (GenericClientNpcSnapshot npc : snapshot.getNpcs())
		{
			if (npc.isDead())
			{
				continue;
			}
			WorldPoint origin = npc.getWorldPoint();
			for (int dx = 0; dx < npc.getSize(); dx++)
			{
				for (int dy = 0; dy < npc.getSize(); dy++)
				{
					occupied.add(new WorldPoint(
						origin.getX() + dx,
						origin.getY() + dy,
						origin.getPlane()));
				}
			}
		}
		int scanEnd = clickTargetIndex > nearest
			? Math.min(clickTargetIndex, path.size() - 1)
			: Math.min(nearest + OBSTACLE_SCAN_TILES, path.size() - 1);
		for (int index = nearest + 1; index <= scanEnd; index++)
		{
			if (occupied.contains(path.get(index)))
			{
				return occupied;
			}
		}
		return Collections.emptySet();
	}


	void updateNpcBlockTime(long tick, boolean blocked)
	{
		if (blocked)
		{
			if (npcBlockedSinceTick < 0)
			{
				npcBlockedSinceTick = tick;
			}
			return;
		}
		if (npcBlockedSinceTick >= 0)
		{
			pausedInteractionTicks += tick - npcBlockedSinceTick;
			npcBlockedSinceTick = -1;
		}
	}


	long activeTicks(long tick)
	{
		long currentNpcBlock = npcBlockedSinceTick < 0
			? 0
			: tick - npcBlockedSinceTick;
		return Math.max(
			0,
			tick - startedAtTick - pausedInteractionTicks - currentNpcBlock);
	}


	int routeIndex(WorldPoint target)
	{
		if (nextVia == request.via.size() && request.isArrival(target)) return path.size() - 1;
		for (int index = path.size() - 1; index >= 0; index--)
		{
			if (target.equals(path.get(index)))
			{
				return index;
			}
		}
		throw new IllegalStateException("Selected walk target is not on the active route: " + target);
	}


	WorldPoint alternateArrivalTarget(GenericClientCollisionMap collisionMap, GenericClientSnapshot snapshot)
	{
		WorldPoint end = path.get(path.size() - 1);
		if (within == 0 || !end.equals(lastAcceptedTarget)) return null;
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if (dx == 0 && dy == 0) continue;
				WorldPoint next = new WorldPoint(end.getX() + dx, end.getY() + dy, end.getPlane());
				if (!request.isArrival(next) || avoidTiles.contains(next) ||
					edgeMemory.blocks(end, next)) continue;
				boolean allowed = collisionMap.canMove(end.getX(), end.getY(), end.getPlane(), dx, dy);
				if (allowed && snapshot.canPlanMove(end.getX(), end.getY(), end.getPlane(), dx, dy, allowed) &&
					snapshot.findRouteBlock(java.util.Arrays.asList(end, next), 0, 1) == null)
				{
					return next;
				}
			}
		}
		return null;
	}


	int clickCooldownTicks()
	{
		return activityContext.refreshesWalkClicks()
			? HAZARDOUS_CLICK_COOLDOWN_TICKS
			: CLICK_COOLDOWN_TICKS;
	}


	Map<String, Object> receipt(
		String status,
		String reason,
		WorldPoint reached,
		long tick)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", status);
		receipt.put("journey", journey);
		if (interruptDetail != null) receipt.put("detail", interruptDetail);
		receipt.put("via_passed", nextVia);
		receipt.put("via_total", request.via.size());
		if (failedSegment > 0) receipt.put("segment", failedSegment);
		receipt.put("requested", worldMap(destination));
		receipt.put("reached", worldMap(reached));
		receipt.put("within", (long) within);
		receipt.put("arrival_tiles", request.arrivalTiles.stream().map(GenericClientWalkJourney::worldMap)
			.collect(java.util.stream.Collectors.toList()));
		receipt.put("game_ticks", Math.max(0, tick - startedAtTick));
		receipt.put("active_game_ticks", activeTicks(tick));
		receipt.put("plans", (long) plans);
		receipt.put("local_rejoins", localRejoins);
		receipt.put("full_plans", fullPlans);
		receipt.put("start_rejoins", startRejoins);
		receipt.put("last_plan_millis", lastPlanMillis);
		receipt.put("recovery_plans", (long) recoveryPlans);
		receipt.put("recovery_plan_limit", (long) maximumRecoveryPlans);
		receipt.put("path_retries", (long) pathRetries);
		receipt.put("clicks", (long) clicks);
		receipt.put("click_receipts", new ArrayList<>(clickReceipts));
		obstacles.addReceipt(receipt);
		receipt.put("transports", new ArrayList<>(transitions.receipts));
		receipt.put("live_block_replans", (long) liveBlockReplans);
		receipt.put("npc_block_replans", (long) npcBlockReplans);
		receipt.put("blocked_edges", edgeMemory.blockedReceipts());
		receipt.put("edge_memory", new ArrayList<>(edgeReceipts.values()));
		receipt.put("avoid_tiles", (long) avoidTiles.size());
		receipt.put("run_toggles", (long) runToggles);
		receipt.put("path_tiles", (long) pathTiles);
		receipt.put("expanded_nodes", (long) expandedNodes);
		receipt.put("reason", reason);
		return receipt;
	}


	static Map<String, Object> immediateReceipt(
		String status,
		String reason,
		WorldPoint requested,
		WorldPoint reached,
		int within)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", status);
		receipt.put("requested", worldMap(requested));
		receipt.put("reached", worldMap(reached));
		receipt.put("within", (long) within);
		receipt.put("game_ticks", 0L);
		receipt.put("active_game_ticks", 0L);
		receipt.put("plans", 0L);
		receipt.put("recovery_plans", 0L);
		receipt.put("recovery_plan_limit", 0L);
		receipt.put("path_retries", 0L);
		receipt.put("clicks", 0L);
		receipt.put("obstacle_interactions", 0L);
		receipt.put("obstacles_cleared", 0L);
		receipt.put("live_block_replans", 0L);
		receipt.put("npc_block_replans", 0L);
		receipt.put("blocked_edges", new ArrayList<>());
		receipt.put("edge_memory", new ArrayList<>());
		receipt.put("transports", new ArrayList<>());
		receipt.put("run_toggles", 0L);
		receipt.put("path_tiles", 0L);
		receipt.put("expanded_nodes", 0L);
		receipt.put("reason", reason);
		return receipt;
	}


	static Map<String, Object> worldMap(WorldPoint point)
	{
		if (point == null)
		{
			return null;
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("x", (long) point.getX());
		value.put("y", (long) point.getY());
		value.put("plane", (long) point.getPlane());
		return value;
	}


	static int distance(WorldPoint first, WorldPoint second)
	{
		if (first.getPlane() != second.getPlane())
		{
			return Integer.MAX_VALUE;
		}
		return Math.max(Math.abs(first.getX() - second.getX()),
			Math.abs(first.getY() - second.getY()));
	}


	static int recoveryPlanLimit(WorldPoint start, WorldPoint destination)
	{
		int routeDistance = start == null || destination == null ? 0 : distance(start, destination);
		return Math.min(
			MAXIMUM_RECOVERY_REPLANS,
			MINIMUM_RECOVERY_REPLANS + routeDistance / RECOVERY_PLAN_TILES);
	}


	static final class Continuation
	{
		final GenericClientWalkRequest request;
		final String account;
		final String journey;
		final int nextVia;
		final GenericClientWalkTransitions.ResumeState transport;

		Continuation(GenericClientWalkJourney walk, long tick)
		{
			request = walk.request;
			account = walk.account;
			journey = walk.journey;
			nextVia = walk.nextVia;
			transport = new GenericClientWalkTransitions.ResumeState(walk.transitions, tick);
		}
	}


	void installPlan(GenericClientWalkPlanner plan, long tick)
	{
		path = plan.result.getPath();
		transports = plan.result.getTransports();
		planViaStart = plan.plannedFirstVia;
		viaIndices = plan.result.getViaIndices();
		pathIndex = 0;
		pathTiles = path.size();
		lastMovedTick = tick;
		nextClickTick = tick;
		reporter.accept("WALK_PLANNED plan=" + plan.revision + " pathTiles=" + pathTiles +
			" expandedNodes=" + expandedNodes + " start=" + plan.start +
			" reachedGoal=" + path.get(path.size() - 1) +
			" kind=" + (plan.reused ? "rejoin" : "full") + " millis=" + lastPlanMillis);
	}

}
