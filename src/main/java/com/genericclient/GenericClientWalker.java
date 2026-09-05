package com.genericclient;

import static com.genericclient.GenericClientWalkJourney.distance;
import static com.genericclient.GenericClientWalkJourney.immediateReceipt;
import com.genericclient.GenericClientWalkJourney.Continuation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import net.runelite.api.coords.WorldPoint;

final class GenericClientWalker implements AutoCloseable
{
	private static final int STALL_TICKS = 8;
	private static final int MAX_SAME_PATH_STALL_RETRIES = 2;
	private static final int OBSTACLE_SCAN_TILES = 12;
	private static final int MINIMUM_RUN_ENERGY = 1_000;

	private final WalkInput gameInput;
	private final ObstacleInput obstacleInput;
	private final RunInput runInput;
	private final GenericClientWalkTransitions.Input transitionInput;
	private final GenericClientPathfinder pathfinder;
	private final GenericClientCollisionMap collisionMap;
	private final GenericClientEdgeMemory edgeMemory;
	private final Consumer<String> reporter;
	private final ExecutorService plannerExecutor;

	private volatile GenericClientSnapshot latestSnapshot;
	private boolean closed;
	private GenericClientWalkJourney active;
	private final Map<String, Continuation> continuations = new java.util.LinkedHashMap<>();

	synchronized GenericClientWalkJourney.InputWindow inputWindow()
	{
		return active == null ? GenericClientWalkJourney.InputWindow.NONE : active.inputWindow();
	}

	GenericClientWalker(WalkInput gameInput, ObstacleInput obstacleInput, RunInput runInput,
		GenericClientWalkTransitions.Input transitionInput, GenericClientCollisionMap collisionMap, GenericClientEdgeMemory edgeMemory, Consumer<String> reporter)
	{
		this(gameInput, obstacleInput, runInput, transitionInput, collisionMap, edgeMemory, reporter, Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "GenericClient-Pathfinder");
			thread.setDaemon(true);
			return thread;
		}));
	}

	GenericClientWalker(WalkInput gameInput, ObstacleInput obstacleInput, RunInput runInput,
		GenericClientWalkTransitions.Input transitionInput, GenericClientCollisionMap collisionMap, GenericClientEdgeMemory edgeMemory,
		Consumer<String> reporter, ExecutorService plannerExecutor)
	{
		this.gameInput = gameInput;
		this.obstacleInput = obstacleInput;
		this.runInput = runInput;
		this.transitionInput = transitionInput;
		this.collisionMap = collisionMap;
		this.edgeMemory = edgeMemory;
		this.pathfinder = new GenericClientPathfinder(collisionMap);
		this.reporter = reporter;
		this.plannerExecutor = plannerExecutor;
	}

	CompletableFuture<Map<String, Object>> walkTo(GenericClientWalkRequest request)
	{
		return walkTo(request, (input, action) -> action.get());
	}

	synchronized CompletableFuture<Map<String, Object>> walkTo(GenericClientWalkRequest request, ClickBoundary clickBoundary)
	{
		WorldPoint destination = request.destination;
		int within = request.within;
		GenericClientSnapshot snapshot = latestSnapshot;
		WorldPoint start = snapshot == null ? null : snapshot.getPlayerWorldPoint();
		long tick = snapshot == null ? 0 : snapshot.getGameTick();
		if (closed)
		{
			return CompletableFuture.completedFuture(
				immediateReceipt("cancelled", "walker_closed", destination, start, within));
		}
		if (active != null)
		{
			return CompletableFuture.completedFuture(
				immediateReceipt("busy", "another_walk_is_active", destination, start, within));
		}
		if (!edgeMemory.isAvailable())
			return CompletableFuture.completedFuture(immediateReceipt("unavailable", "navigation_account_unavailable", destination, start, within));
		if (start == null || snapshot.getPlayer().getName() == null)
		{
			return CompletableFuture.completedFuture(
				immediateReceipt("unavailable", "player_snapshot_unavailable", destination, null, within));
		}
		int nextVia = 0;
		String account = snapshot.getPlayer().getName();
		String journey = java.util.UUID.randomUUID().toString();
		Continuation continuation = null;
		if (request.resume != null)
		{
			continuation = continuations.get(request.resume);
			if (continuation == null || !java.util.Objects.equals(account, continuation.account) ||
				!request.sameJourney(continuation.request))
				return CompletableFuture.completedFuture(immediateReceipt("rejected", "invalid_resume", destination, start, within));
			nextVia = continuation.nextVia;
			journey = continuation.journey;
			continuations.remove(request.resume);
		}
		GenericClientWalkJourney walk = new GenericClientWalkJourney(request, tick, start, clickBoundary, nextVia, journey, account, edgeMemory, reporter);
		if (continuation != null) walk.transitions.restore(continuation.transport, tick);
		active = walk;
		reporter.accept("WALK_REQUESTED start=" + start + " destination=" + destination +
			" within=" + within + " timeoutTicks=" + request.timeoutTicks +
			" avoidTiles=" + walk.avoidTiles.size());
		if (!interruptWalk(walk, snapshot) && walk.transitions.pending == null) requestPlan(walk, start, "initial");
		return walk.completion;
	}

	synchronized void activateAccount(long accountHash) throws java.io.IOException
	{
		String profileId = GenericClientBehaviorProfile.fromAccountHash(accountHash).getId();
		if (!profileId.equals(edgeMemory.capture().profileId)) clearAccount();
		edgeMemory.activateAccount(accountHash);
	}

	synchronized void clearAccount()
	{
		cancelActive("account_changed");
		clearSnapshot();
		continuations.clear();
		edgeMemory.clearAccount();
	}

	synchronized void clearSnapshot()
	{
		latestSnapshot = null;
	}

	void publishGameTick(GenericClientSnapshot snapshot)
	{
		TickDecision decision;

		synchronized (this)
		{
			latestSnapshot = snapshot;
			edgeMemory.observeQuestState(snapshot.questStateKey());
			GenericClientWalkJourney walk = active;
			if (walk == null)
			{
				return;
			}

			WorldPoint player = snapshot.getPlayerWorldPoint();
			long tick = snapshot.getGameTick();
			walk.lastObservedTick = tick;
			if (consumeBeforeRoute(walk, snapshot, player, tick))
			{
				return;
			}

			GenericClientWalkTransitions.Step transition = walk.transitions.advance(snapshot, player, tick);
			if (transition == GenericClientWalkTransitions.Step.INTERRUPT)
			{
				finish(walk, "interrupted", "dialogue", player, tick);
				return;
			}
			if (transition == GenericClientWalkTransitions.Step.ARRIVED || transition == GenericClientWalkTransitions.Step.FAILED)
			{
				requestPlan(walk, player, transition == GenericClientWalkTransitions.Step.ARRIVED ? "transport_arrived" : "transport_failed");
				return;
			}
			if (transition == GenericClientWalkTransitions.Step.WAIT) return;
			decision = transition == GenericClientWalkTransitions.Step.INTERACT
				? TickDecision.transition(walk, walk.transitions.action(), snapshot) : advanceWalking(walk, snapshot, player, tick);
		}

		if (decision.transition != null)
		{
			dispatchTransition(decision);
		}
		else if (decision.obstacle != null)
		{
			dispatchObstacle(decision);
		}
		else if (decision.clickCandidates != null)
		{
			dispatchClick(decision);
		}
	}

	private TickDecision advanceWalking(GenericClientWalkJourney walk, GenericClientSnapshot snapshot, WorldPoint player, long tick)
	{
		if (walk.obstacles.pending == null && consumeRunState(walk, snapshot, tick)) return TickDecision.stop();
		int nearest = updateRouteProgress(walk, player);
		GenericClientWalkObstacles.Step obstacleStep = walk.obstacles.advance(snapshot, player, tick, nearest);
		if (obstacleStep == GenericClientWalkObstacles.Step.REPLAN)
		{
			requestPlan(walk, player, "live_route_blocked");
			return TickDecision.stop();
		}
		if (obstacleStep == GenericClientWalkObstacles.Step.WAIT) return TickDecision.stop();
		return obstacleStep == GenericClientWalkObstacles.Step.INTERACT
			? TickDecision.obstacle(walk, walk.obstacles.pending) : advanceRoute(walk, snapshot, player, tick, nearest);
	}

	private boolean consumeBeforeRoute(
		GenericClientWalkJourney walk,
		GenericClientSnapshot snapshot,
		WorldPoint player,
		long tick)
	{
		if (consumeTerminalState(walk, snapshot, player, tick))
		{
			return true;
		}
		walk.updateMovement(player, tick);
		if (walk.clickInFlight || walk.obstacles.inFlight || walk.transitions.inFlight || walk.runInFlight) return true;
		int nearest = walk.path == null ? -1 : walk.nearestRouteIndex(player);
		Set<WorldPoint> occupied = walk.blockingNpcTiles(snapshot, nearest);
		walk.updateNpcBlockTime(tick, !occupied.isEmpty());
		if (!walk.clickInFlight && !walk.obstacles.inFlight && !walk.transitions.inFlight &&
			walk.activeTicks(tick) >= walk.timeoutTicks && occupied.isEmpty())
		{
			finish(walk, "timed_out", "game_tick_timeout", player, tick);
			return true;
		}
		if (walk.transitions.pending != null) return false;
		if (walk.planning) return true;
		if (walk.path == null)
		{
			requestPlan(walk, player, "plan_start_moved");
			return true;
		}
		return false;
	}

	private boolean consumeTerminalState(GenericClientWalkJourney walk, GenericClientSnapshot snapshot, WorldPoint player, long tick)
	{
		if (player == null)
		{
			finish(walk, "cancelled", "player_snapshot_unavailable", null, tick);
			return true;
		}
		if (interruptWalk(walk, snapshot)) return true;
		if (walk.transitions.pending == null && player.getPlane() != walk.lastPlayer.getPlane())
		{
			finish(walk, "unsupported_transition", "player_changed_plane", player, tick);
			return true;
		}
		if (walk.inputPaused)
		{
			walk.lastPlayer = player;
			walk.lastMovedTick = tick;
			return true;
		}
		walk.observeViaProgress(player);
		if (walk.nextVia == walk.request.via.size() && walk.request.isArrival(player))
		{
			if (!walk.clickInFlight && !walk.obstacles.inFlight && !walk.transitions.inFlight)
			{
				walk.transitions.observeArrival(snapshot, player, tick);
				if (walk.transitions.pending != null && walk.transitions.pending.arrival.contains(player)) return false;
				walk.obstacles.observeClear(snapshot, walk.path == null ? 0 : walk.path.size() - 1, tick);
				finish(walk, "arrived", "arrival_radius", player, tick);
			}
			return true;
		}
		return false;
	}

	private boolean consumeRunState(
		GenericClientWalkJourney walk,
		GenericClientSnapshot snapshot,
		long tick)
	{
		if (snapshot.getRunEnergy() < MINIMUM_RUN_ENERGY)
		{
			walk.runToggleArmed = true;
		}
		if (!walk.useRun && snapshot.isRunEnabled() && !walk.runInFlight)
		{
			startRunToggle(walk, tick, false);
			return true;
		}
		if (walk.useRun && !snapshot.isRunEnabled() &&
			snapshot.getRunEnergy() >= MINIMUM_RUN_ENERGY &&
			walk.runToggleArmed && !walk.runInFlight)
		{
			walk.runToggleArmed = false;
			startRunToggle(walk, tick, true);
			return true;
		}
		return walk.runInFlight;
	}

	private void startRunToggle(GenericClientWalkJourney walk, long tick, boolean enabled)
	{
		walk.runInFlight = true;
		walk.runStartedTick = tick;
		dispatchRunToggle(walk, enabled);
	}

	private int updateRouteProgress(GenericClientWalkJourney walk, WorldPoint player)
	{
		int nearest = walk.nearestRouteIndex(player);
		if (nearest >= 0 && nearest > walk.pathIndex)
		{
			walk.pathIndex = nearest;
			reporter.accept("WALK_PROGRESS plan=" + walk.planRevision + " tile=" + player +
				" pathIndex=" + nearest + " remaining=" + (walk.path.size() - 1 - nearest));
		}
		return nearest;
	}

	private TickDecision advanceRoute(
		GenericClientWalkJourney walk,
		GenericClientSnapshot snapshot,
		WorldPoint player,
		long tick,
		int nearest)
	{
		if (walk.clickInFlight) return TickDecision.stop();
		if (nearest < 0)
		{
			requestPlan(walk, player, "off_route");
			return TickDecision.stop();
		}
		if (consumeClickTarget(walk, snapshot, player, tick, nearest)) return TickDecision.stop();
		if (tick < walk.nextClickTick)
		{
			return TickDecision.stop();
		}
		if (nearest >= walk.path.size() - 1)
		{
			requestPlan(walk, player, "path_exhausted_before_arrival");
			return TickDecision.stop();
		}
		return prepareRouteDispatch(walk, snapshot, player, tick, nearest);
	}

	private boolean consumeClickTarget(GenericClientWalkJourney walk, GenericClientSnapshot snapshot, WorldPoint player, long tick, int nearest)
	{
		boolean reached = walk.clickTarget != null && (distance(player, walk.clickTarget) == 0 ||
			walk.clickTargetIndex >= 0 && nearest > walk.clickTargetIndex);
		if (reached)
		{
			walk.lastMovedTick = tick;
			if (!walk.targetReached && walk.maximumLegTiles != Integer.MAX_VALUE) walk.maximumLegTiles++;
			walk.targetReached = true;
		}
		else if (walk.clicks > 0 && tick - walk.lastMovedTick >= STALL_TICKS)
		{
			Set<WorldPoint> occupied = walk.blockingNpcTiles(snapshot, nearest);
			if (!occupied.isEmpty())
			{
				walk.npcBlockReplans++;
				walk.samePathStallRetries = 0;
				walk.lastMovedTick = tick;
				reporter.accept("WALK_NPC_BLOCK_REPLAN plan=" + walk.planRevision + " player=" + player + " occupiedTiles=" + occupied);
				requestPlan(walk, player, "npc_body_blocked", occupied);
			}
			else if (walk.clickTarget == null)
			{
				walk.lastMovedTick = tick;
				requestPlan(walk, player, "stalled_without_target");
			}
			else handleStalledTarget(walk, player, tick, nearest);
			return true;
		}
		if (tick < walk.nextClickTick) return true;
		if (walk.clickTarget != null)
		{
			reporter.accept("WALK_CADENCE_CLICK plan=" + walk.planRevision + " player=" + player + " previousTarget=" + walk.clickTarget);
			walk.clickTarget = null;
			walk.clickTargetIndex = -1;
		}
		return false;
	}

	private void handleStalledTarget(
		GenericClientWalkJourney walk,
		WorldPoint player,
		long tick,
		int nearest)
	{
		int attemptedLeg = Math.max(1, walk.clickTargetIndex - nearest);
		walk.maximumLegTiles = Math.min(walk.maximumLegTiles, Math.max(3, attemptedLeg - 3));
		reporter.accept("WALK_LEG_BACKOFF attempted=" + attemptedLeg +
			" maximum=" + walk.maximumLegTiles + " target=" + walk.clickTarget);
		walk.lastMovedTick = tick;
		if (walk.samePathStallRetries < MAX_SAME_PATH_STALL_RETRIES)
		{
			walk.samePathStallRetries++;
			walk.pathRetries++;
			walk.clickTarget = null;
			walk.clickTargetIndex = -1;
			walk.nextClickTick = tick + walk.clickCooldownTicks();
			reporter.accept("WALK_PATH_RETRY plan=" + walk.planRevision +
				" attempt=" + walk.samePathStallRetries +
				" maximum=" + MAX_SAME_PATH_STALL_RETRIES);
			return;
		}
		walk.samePathStallRetries = 0;
		requestPlan(walk, player, "stalled");
	}

	private TickDecision prepareRouteDispatch(
		GenericClientWalkJourney walk,
		GenericClientSnapshot snapshot,
		WorldPoint player,
		long tick,
		int nearest)
	{
		int candidateEnd = walk.maximumLegTiles == Integer.MAX_VALUE
			? walk.path.size() - 1
			: Math.min(walk.path.size() - 1, nearest + walk.maximumLegTiles);
		candidateEnd = Math.min(candidateEnd, walk.transitions.nextIndex());
		if (walk.nextVia < walk.request.via.size())
		{
			candidateEnd = Math.min(candidateEnd, walk.viaIndices.get(walk.nextVia - walk.planViaStart));
			if (candidateEnd <= nearest)
			{
				requestPlan(walk, player, "via_not_reached");
				return TickDecision.stop();
			}
		}
		GenericClientSnapshot.RouteBlock routeBlock = snapshot.findRouteBlock(
			walk.path,
			nearest,
			Math.min(candidateEnd, nearest + OBSTACLE_SCAN_TILES));
		if (routeBlock != null)
		{
			if (!routeBlock.isTraversable())
			{
				walk.obstacles.recordSolid(routeBlock, player);
				requestPlan(walk, player, "live_route_blocked");
				return TickDecision.stop();
			}
			if (distance(player, routeBlock.getWorld()) <= GenericClientWalkObstacles.INTERACT_DISTANCE)
			{
				walk.obstacles.begin(routeBlock, tick, player);
				return TickDecision.obstacle(walk, routeBlock);
			}
			candidateEnd = Math.min(candidateEnd, routeBlock.getPathIndex() - 1);
		}

		if (candidateEnd <= nearest)
		{
			finish(walk, "unreachable", "traversal_object_not_reachable", player, tick);
			return TickDecision.stop();
		}
		List<WorldPoint> candidates = new ArrayList<>(candidateEnd - nearest);
		for (int index = candidateEnd; index > nearest; index--)
		{
			candidates.add(walk.path.get(index));
		}
		if (walk.activityContext.refreshesWalkClicks() && walk.nextVia == walk.request.via.size() && candidateEnd == walk.path.size() - 1)
		{
			WorldPoint alternate = walk.alternateArrivalTarget(collisionMap, snapshot);
			if (alternate != null) candidates.add(0, alternate);
		}
		walk.clickInFlight = true;
		walk.clickStartedTick = tick;
		reporter.accept("WALK_CLICK_REQUEST plan=" + walk.planRevision + " from=" + player +
			" candidates=" + candidates.size() + " farthest=" + candidates.get(0));
		return TickDecision.click(walk, candidates);
	}

	private boolean interruptWalk(GenericClientWalkJourney walk, GenericClientSnapshot snapshot)
	{
		GenericClientWalkInterrupts.Match match = walk.request.interrupts.evaluate(snapshot, walk.transitions.ownsDialogue(snapshot));
		if (match == null && walk.transitions.requiresDialogueInterrupt(snapshot))
			match = new GenericClientWalkInterrupts.Match("interrupted", "dialogue", "transport_foreign_dialogue");
		if (match == null) return false;
		walk.observeViaProgress(snapshot.getPlayerWorldPoint());
		walk.interruptDetail = match.detail;
		finish(walk, match.status, match.reason, snapshot.getPlayerWorldPoint(), snapshot.getGameTick());
		return true;
	}

	private void dispatchRunToggle(GenericClientWalkJourney walk, boolean enabled)
	{
		int inputRevision = walk.inputRevision;
		runInput.setEnabled(enabled, walk.activityContext).whenComplete((receipt, error) -> completeRunToggle(walk, inputRevision, receipt, error));
	}

	private void completeRunToggle(GenericClientWalkJourney walk, int inputRevision, Map<String, Object> receipt, Throwable error)
	{
		synchronized (GenericClientWalker.this)
		{
			if (active != walk || walk.inputRevision != inputRevision)
			{
				return;
			}
			walk.runInFlight = false;
			if (walk.inputPaused)
			{
				return;
			}
			GenericClientSnapshot snapshot = latestSnapshot;
			long tick = snapshot == null ? walk.lastObservedTick : snapshot.getGameTick();
			walk.pausedInteractionTicks += Math.max(0L, tick - walk.runStartedTick);
			String status = receipt == null ? null : String.valueOf(receipt.get("status"));
			String result = error == null
				? receipt == null ? "null" : String.valueOf(receipt.get("result"))
				: error.getMessage();
			if ("complete".equals(status) || "unchanged".equals(status))
			{
				Object clicks = receipt.get("click_count");
				if (clicks instanceof Number && ((Number) clicks).longValue() > 0L)
				{
					walk.runToggles++;
				}
				reporter.accept("WALK_RUN_READY result=" + result +
					" energy=" + (snapshot == null ? 0 : snapshot.getRunEnergy()));
			}
			else
			{
				reporter.accept("WALK_RUN_REJECTED result=" + result);
			}
		}
	}
	synchronized void cancelActive(String reason)
	{
		GenericClientWalkJourney walk = active;
		if (walk == null)
		{
			return;
		}
		GenericClientSnapshot snapshot = latestSnapshot;
		long tick = snapshot == null ? walk.lastObservedTick : snapshot.getGameTick();
		WorldPoint reached = snapshot == null ? null : snapshot.getPlayerWorldPoint();
		finish(walk, "cancelled", reason, reached, tick);
	}

	synchronized void pauseActiveInput(String reason)
	{
		GenericClientWalkJourney walk = active;
		if (walk == null || walk.inputPaused)
		{
			return;
		}
		GenericClientSnapshot snapshot = latestSnapshot;
		long tick = snapshot == null ? walk.lastObservedTick : snapshot.getGameTick();
		walk.pauseInput(tick);
		if (walk.clickInFlight)
		{
			walk.clickInFlight = false;
			gameInput.cancelWalkToTile(walk.clickContext);
		}
		if (walk.obstacles.inFlight || walk.transitions.inFlight)
		{
			walk.obstacles.inFlight = false;
			walk.transitions.inFlight = false;
			obstacleInput.cancel("walker_cancelled", walk.activityContext);
		}
		if (walk.runInFlight)
		{
			walk.runInFlight = false;
			runInput.cancel(reason, walk.activityContext);
		}
		reporter.accept("WALK_INPUT_PAUSED reason=" + reason);
	}

	synchronized void resumeActiveInput(String reason)
	{
		GenericClientWalkJourney walk = active;
		if (walk == null || !walk.inputPaused)
		{
			return;
		}
		GenericClientSnapshot snapshot = latestSnapshot;
		walk.resumeInput(snapshot);
		reporter.accept("WALK_INPUT_RESUMED reason=" + reason);
	}

	private void dispatchObstacle(TickDecision decision)
	{
		GenericClientSnapshot.RouteBlock obstacle = decision.obstacle;
		obstacleInput.interact(
			obstacle.getObjectId(),
			obstacle.getAction(),
			obstacle.getWorld(),
			GenericClientWalkObstacles.INTERACT_DISTANCE + 1,
			decision.context).whenComplete((receipt, error) -> completeObstacle(decision.walk, decision.revision, receipt, error));
	}

	private void completeObstacle(GenericClientWalkJourney walk, int inputRevision,
		Map<String, Object> receipt, Throwable error)
	{
		synchronized (this)
		{
			if (active != walk || walk.inputRevision != inputRevision || walk.inputPaused) return;
			GenericClientSnapshot snapshot = latestSnapshot;
			if (walk.obstacles.complete(snapshot, receipt, error))
			{
				WorldPoint player = snapshot == null ? null : snapshot.getPlayerWorldPoint();
				if (player != null) requestPlan(walk, player, "live_route_blocked");
				else finish(walk, "unavailable", "player_snapshot_unavailable", null, walk.startedAtTick);
			}
		}
	}

	private void dispatchClick(TickDecision decision)
	{
		GenericClientWalkJourney walk = decision.walk;
		double reach = decision.context.refreshesWalkClicks() ? 1.0 : walk.clickBoundary.nextReachFraction();
		walk.clickBoundary.execute(decision.context, () -> gameInput.walkToFarthest(decision.clickCandidates, decision.context, reach))
			.whenComplete((result, error) -> completeClick(walk, decision.revision, reach, result, error));
	}

	private void dispatchTransition(TickDecision decision)
	{
		GenericClientWalkJourney walk = decision.walk;
		transitionInput.execute(decision.transition, decision.snapshot, decision.context).whenComplete((receipt, error) -> {
			synchronized (this)
			{
				if (active != walk || decision.revision != walk.inputRevision) return;
				walk.transitions.complete(receipt, error, walk.lastPlayer, walk.lastObservedTick);
			}
		});
	}

	private void completeClick(GenericClientWalkJourney walk, int inputRevision, double reach,
		GenericClientInteractionResult result, Throwable error)
	{
		synchronized (this)
		{
			if (active != walk || walk.inputRevision != inputRevision) return;
			GenericClientSnapshot snapshot = latestSnapshot;
			String failure = walk.recordClick(snapshot, reach, result, error);
			if (failure != null)
			{
				WorldPoint reached = snapshot == null ? null : snapshot.getPlayerWorldPoint();
				long tick = snapshot == null ? walk.lastObservedTick : snapshot.getGameTick();
				finish(walk, "click_failed", failure, reached, tick);
			}
		}
	}

	private void requestPlan(GenericClientWalkJourney walk, WorldPoint start, String reason)
	{
		requestPlan(walk, start, reason, Collections.emptySet());
	}

	private void requestPlan(
		GenericClientWalkJourney walk,
		WorldPoint start,
		String reason,
		Set<WorldPoint> npcBlockedTiles)
	{
		if (active != walk || walk.planning)
		{
			return;
		}
		boolean recoveryPlan = isRecoveryPlan(reason);
		if (recoveryPlan && walk.recoveryPlans >= walk.maximumRecoveryPlans)
		{
			GenericClientSnapshot snapshot = latestSnapshot;
			long tick = snapshot == null ? walk.lastObservedTick : snapshot.getGameTick();
			finish(walk, "unreachable", "replan_limit", start, tick);
			return;
		}
		if (recoveryPlan)
		{
			walk.recoveryPlans++;
		}
		if ("npc_body_blocked".equals(reason))
		{
			walk.maximumLegTiles = 1;
		}

		walk.planning = true;
		walk.clickTarget = null;
		walk.clickTargetIndex = -1;
		walk.plans++;
		GenericClientWalkPlanner attempt = new GenericClientWalkPlanner(
			pathfinder, walk, start, reason, npcBlockedTiles, latestSnapshot, edgeMemory.capture(), reporter);
		try
		{
			plannerExecutor.execute(() -> calculatePlan(walk, attempt));
		}
		catch (RejectedExecutionException exception)
		{
			walk.planning = false;
			GenericClientSnapshot snapshot = latestSnapshot;
			long tick = snapshot == null ? walk.lastObservedTick : snapshot.getGameTick();
			finish(walk, "cancelled", "pathfinder_closed", start, tick);
		}
	}

	private void calculatePlan(GenericClientWalkJourney walk, GenericClientWalkPlanner plan)
	{
		try
		{
			plan.calculate(() -> {
				synchronized (this) { return new GenericClientWalkPlanner.Frame(latestSnapshot, walk.nextVia); }
			});
		}
		catch (RuntimeException exception)
		{
			failPlan(walk, plan, exception);
			return;
		}
		completePlan(walk, plan);
	}

	private void failPlan(GenericClientWalkJourney walk, GenericClientWalkPlanner plan, RuntimeException exception)
	{
		synchronized (GenericClientWalker.this)
		{
			if (active != walk || walk.planRevision != plan.revision) return;
			walk.planning = false;
			GenericClientSnapshot snapshot = latestSnapshot;
			long tick = snapshot == null ? walk.lastObservedTick : snapshot.getGameTick();
			WorldPoint reached = snapshot == null ? plan.start : snapshot.getPlayerWorldPoint();
			finish(walk, "unreachable", "planner_error: " + exception.getMessage(), reached, tick);
		}
	}

	private void completePlan(GenericClientWalkJourney walk, GenericClientWalkPlanner plan)
	{
		synchronized (GenericClientWalker.this)
		{
			if (active != walk || walk.planRevision != plan.revision) return;
			GenericClientSnapshot frame = latestSnapshot;
			WorldPoint current = frame == null ? null : frame.getPlayerWorldPoint();
			if (current != null && interruptWalk(walk, frame)) return;
			walk.planning = false;
			if (plan.reused) walk.localRejoins++; else walk.fullPlans++;
			if (plan.startRejoined) walk.startRejoins++;
			walk.lastPlanMillis = (System.nanoTime() - plan.queuedAt) / 1_000_000.0;
			if (!plan.fresh(current, walk.nextVia) || plan.memory != edgeMemory.capture() || !plan.transportsFresh(frame, walk))
			{
				if (current != null)
				{
					reporter.accept("WALK_PLAN_START_MOVED from=" + plan.start + " current=" + current);
					requestPlan(walk, current, "plan_start_moved");
				}
				return;
			}
			walk.expandedNodes = plan.result.getExpandedNodes();
			walk.failedSegment = plan.result.getFailedSegment();
			if (plan.result.getStatus() == GenericClientPathfinder.Status.FOUND) walk.installPlan(plan, frame.getGameTick());
			else finishFailedPlan(walk, plan, current, frame.getGameTick());
		}
	}

	private void finishFailedPlan(GenericClientWalkJourney walk, GenericClientWalkPlanner plan, WorldPoint current, long tick)
	{
		if (!plan.occupiedTiles.isEmpty() && plan.result.getStatus() == GenericClientPathfinder.Status.UNREACHABLE)
		{
			walk.lastMovedTick = tick;
			walk.nextClickTick = tick + walk.clickCooldownTicks();
			reporter.accept("WALK_NPC_BLOCK_WAIT plan=" + plan.revision + " player=" + current + " occupiedTiles=" + plan.occupiedTiles.size());
			return;
		}
		String status = plan.result.getStatus() == GenericClientPathfinder.Status.UNSUPPORTED_PLANE
			? "unsupported_transition" : plan.result.getStatus() == GenericClientPathfinder.Status.SEARCH_LIMIT ? "search_limit" : "unreachable";
		finish(walk, status, plan.result.getStatus().name().toLowerCase(), current, tick);
	}
	private static boolean isRecoveryPlan(String reason)
	{
		return "off_route".equals(reason) || "stalled".equals(reason) || "transport_failed".equals(reason) ||
			"stalled_without_target".equals(reason) || "live_route_blocked".equals(reason) ||
			"plan_start_moved".equals(reason) || "via_not_reached".equals(reason) || "path_exhausted_before_arrival".equals(reason);
	}

	private void finish(
		GenericClientWalkJourney walk,
		String status,
		String reason,
		WorldPoint reached,
		long tick)
	{
		if (active != walk)
		{
			return;
		}
		active = null;
		walk.excludePendingInputTime(tick);
		walk.activityContext.cancelInput();
		if (walk.clickInFlight)
		{
			walk.clickInFlight = false;
			gameInput.cancelWalkToTile(walk.clickContext);
		}
		if (walk.obstacles.inFlight || walk.transitions.inFlight)
		{
			walk.obstacles.inFlight = false;
			walk.transitions.inFlight = false;
			obstacleInput.cancel("walker_cancelled", walk.activityContext);
		}
		if (walk.runInFlight)
		{
			walk.runInFlight = false;
			runInput.cancel("walker_finished", walk.activityContext);
		}
		Continuation continuation = "interrupted".equals(status) || "unavailable".equals(status) ? new Continuation(walk, tick) : null;
		walk.transitions.finish(status, reached, tick);
		Map<String, Object> receipt = walk.receipt(status, reason, reached, tick);
		if (continuation != null)
		{
			String token = java.util.UUID.randomUUID().toString();
			if (continuations.size() == 64) continuations.remove(continuations.keySet().iterator().next());
			continuations.put(token, continuation);
			receipt.put("continuation", token);
		}
		if ("dialogue".equals(reason) && latestSnapshot != null)
		{
			receipt.put("dialogue", latestSnapshot.read("dialogue", Collections.emptyMap()));
		}
		reporter.accept("WALK_COMPLETED status=" + status + " reason=" + reason +
			" requested=" + walk.destination + " reached=" + reached + " ticks=" +
			receipt.get("game_ticks") + " plans=" + walk.plans + " clicks=" + walk.clicks);
		walk.completion.complete(receipt);
	}

	@Override
	public void close()
	{
		synchronized (this)
		{
			if (closed)
			{
				return;
			}
			closed = true;
			GenericClientWalkJourney walk = active;
			if (walk != null)
			{
				GenericClientSnapshot snapshot = latestSnapshot;
				long tick = snapshot == null ? walk.lastObservedTick : snapshot.getGameTick();
				WorldPoint reached = snapshot == null ? null : snapshot.getPlayerWorldPoint();
				finish(walk, "cancelled", "walker_closed", reached, tick);
			}
		}
		plannerExecutor.shutdownNow();
	}

	interface ClickBoundary
	{
		CompletableFuture<GenericClientInteractionResult> execute(
			GenericClientActivityContext input,
			java.util.function.Supplier<CompletableFuture<GenericClientInteractionResult>> action);

		default int nextClickDelayTicks() { return 6; }
		default double nextReachFraction() { return 1.0; }
	}

	interface WalkInput
	{
		CompletableFuture<GenericClientInteractionResult> walkToFarthest(
			List<WorldPoint> candidates,
			GenericClientActivityContext activityContext, double reachFraction);

		void cancelWalkToTile(GenericClientActivityContext owner);
	}

	interface ObstacleInput
	{
		CompletableFuture<Map<String, Object>> interact(
			int objectId,
			String action,
			WorldPoint world,
			int within,
			GenericClientActivityContext activityContext);

		void cancel(String reason, GenericClientActivityContext owner);
	}

	interface RunInput
	{
		CompletableFuture<Map<String, Object>> setEnabled(
			boolean enabled,
			GenericClientActivityContext activityContext);

		void cancel(String reason, GenericClientActivityContext owner);
	}

	private static final class TickDecision
	{
		private static final TickDecision STOP = new TickDecision(null, null, null, null, null);

		private final GenericClientWalkJourney walk;
		private final GenericClientSnapshot.RouteBlock obstacle;
		private final List<WorldPoint> clickCandidates;
		private final GenericClientTransport.Step transition;
		private final GenericClientSnapshot snapshot;
		private final GenericClientActivityContext context;
		private final int revision;

		private TickDecision(
			GenericClientWalkJourney walk,
			GenericClientSnapshot.RouteBlock obstacle,
			List<WorldPoint> clickCandidates, GenericClientTransport.Step transition, GenericClientSnapshot snapshot)
		{
			this.walk = walk;
			this.obstacle = obstacle;
			this.clickCandidates = clickCandidates;
			this.transition = transition;
			this.snapshot = snapshot;
			this.context = walk == null ? null : clickCandidates == null ? walk.activityContext : walk.openClickScope();
			this.revision = walk == null ? 0 : walk.inputRevision;
		}

		private static TickDecision stop()
		{
			return STOP;
		}

		private static TickDecision obstacle(
			GenericClientWalkJourney walk,
			GenericClientSnapshot.RouteBlock obstacle)
		{
			return new TickDecision(walk, obstacle, null, null, null);
		}

		private static TickDecision click(GenericClientWalkJourney walk, List<WorldPoint> candidates)
		{
			return new TickDecision(walk, null, candidates, null, null);
		}

		private static TickDecision transition(GenericClientWalkJourney walk, GenericClientTransport.Step step, GenericClientSnapshot frame)
		{
			return new TickDecision(walk, null, null, step, frame);
		}
	}

}
