package com.genericclient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
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
	private static final int MINIMUM_RECOVERY_REPLANS = 6;
	private static final int MAXIMUM_RECOVERY_REPLANS = 32;
	private static final int RECOVERY_PLAN_TILES = 16;
	private static final int MAX_CLICK_FAILURES = 5;
	private static final int OFF_ROUTE_RADIUS = 3;
	private static final int STALL_TICKS = 8;
	private static final int MAX_SAME_PATH_STALL_RETRIES = 2;
	private static final int CLICK_COOLDOWN_TICKS = 2;
	private static final int WAYPOINT_ADVANCE_RADIUS = 2;
	private static final int OBSTACLE_SCAN_TILES = 12;
	private static final int OBSTACLE_INTERACT_DISTANCE = 3;
	private static final int OBSTACLE_SETTLE_TICKS = 8;
	private static final int MAX_OBSTACLE_ATTEMPTS = 3;
	private static final int MINIMUM_RUN_ENERGY = 1_000;

	private final WalkInput gameInput;
	private final ObstacleInput obstacleInput;
	private final RunInput runInput;
	private final GenericClientPathfinder pathfinder;
	private final Consumer<String> reporter;
	private final ExecutorService plannerExecutor;

	private volatile GenericClientSnapshot latestSnapshot;
	private boolean closed;
	private ActiveWalk active;

	GenericClientWalker(
		GenericClientGameInput gameInput,
		GenericClientObjectInput objectInput,
		GenericClientRunInput runInput,
		GenericClientCollisionMap collisionMap,
		Consumer<String> reporter)
	{
		this(new WalkInput()
		{
			@Override
			public CompletableFuture<GenericClientInteractionResult> walkToFarthest(
				List<WorldPoint> candidates,
				boolean breaksEnabled)
			{
				return gameInput.walkToFarthest(candidates, breaksEnabled);
			}

			@Override
			public void cancelWalkToTile()
			{
				gameInput.cancelWalkToTile();
			}
		}, new ObstacleInput()
		{
			@Override
			public CompletableFuture<Map<String, Object>> interact(
				int objectId,
				String action,
				WorldPoint world,
				int within,
				boolean breaksEnabled)
			{
				return objectInput.interact(objectId, action, world, within, breaksEnabled);
			}

			@Override
			public void cancel()
			{
				objectInput.cancel("walker_cancelled");
			}
		}, runInput::setEnabled, runInput::cancel, collisionMap, reporter);
	}

	GenericClientWalker(
		WalkInput gameInput,
		ObstacleInput obstacleInput,
		GenericClientCollisionMap collisionMap,
		Consumer<String> reporter)
	{
		this(
			gameInput,
			obstacleInput,
			(enabled, breaksEnabled) -> CompletableFuture.completedFuture(runUnchanged()),
			reason -> { },
			collisionMap,
			reporter);
	}

	GenericClientWalker(
		WalkInput gameInput,
		ObstacleInput obstacleInput,
		java.util.function.BiFunction<Boolean, Boolean,
			CompletableFuture<Map<String, Object>>> runAction,
		Consumer<String> cancelRunAction,
		GenericClientCollisionMap collisionMap,
		Consumer<String> reporter)
	{
		this.gameInput = gameInput;
		this.obstacleInput = obstacleInput;
		this.runInput = new RunInput()
		{
			@Override
			public CompletableFuture<Map<String, Object>> setEnabled(
				boolean enabled,
				boolean breaksEnabled)
			{
				return runAction.apply(enabled, breaksEnabled);
			}

			@Override
			public void cancel(String reason)
			{
				cancelRunAction.accept(reason);
			}
		};
		this.pathfinder = new GenericClientPathfinder(collisionMap);
		this.reporter = reporter;
		this.plannerExecutor = Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "GenericClient-Pathfinder");
			thread.setDaemon(true);
			return thread;
		});
	}

	synchronized CompletableFuture<Map<String, Object>> walkTo(
		WorldPoint destination,
		int within,
		int timeoutTicks,
		boolean breaksEnabled)
	{
		return walkTo(destination, within, timeoutTicks, breaksEnabled, true);
	}

	synchronized CompletableFuture<Map<String, Object>> walkTo(
		WorldPoint destination,
		int within,
		int timeoutTicks,
		boolean breaksEnabled,
		boolean useRun)
	{
		if (destination == null)
		{
			throw new IllegalArgumentException("Walk destination cannot be null");
		}
		if (within < 0 || within > 10)
		{
			throw new IllegalArgumentException("Walk arrival radius must be between 0 and 10");
		}
		if (timeoutTicks < 1)
		{
			throw new IllegalArgumentException("Walk timeout must be positive");
		}

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
		if (start == null)
		{
			return CompletableFuture.completedFuture(
				immediateReceipt("unavailable", "player_snapshot_unavailable", destination, null, within));
		}
		if (start.getPlane() != destination.getPlane())
		{
			return CompletableFuture.completedFuture(
				immediateReceipt("unsupported_transition", "destination_is_on_another_plane",
					destination, start, within));
		}

		ActiveWalk walk = new ActiveWalk(
			destination, within, timeoutTicks, tick, start, breaksEnabled, useRun);
		active = walk;
		reporter.accept("WALK_REQUESTED start=" + start + " destination=" + destination +
			" within=" + within + " timeoutTicks=" + timeoutTicks);
		requestPlan(walk, start, "initial");
		return walk.completion;
	}

	void publishGameTick(GenericClientSnapshot snapshot)
	{
		latestSnapshot = snapshot;
		ActiveWalk clickWalk = null;
		List<WorldPoint> clickCandidates = null;
		ActiveWalk obstacleWalk = null;
		GenericClientSnapshot.RouteBlock obstacleBlock = null;

		synchronized (this)
		{
			ActiveWalk walk = active;
			if (walk == null)
			{
				return;
			}

			WorldPoint player = snapshot.getPlayerWorldPoint();
			long tick = snapshot.getGameTick();
			if (player == null)
			{
				finish(walk, "cancelled", "player_snapshot_unavailable", null, tick);
				return;
			}
			if (player.getPlane() != walk.destination.getPlane())
			{
				finish(walk, "unsupported_transition", "player_changed_plane", player, tick);
				return;
			}
			if (distance(player, walk.destination) <= walk.within)
			{
				if (walk.clickInFlight || walk.obstacleInFlight)
				{
					return;
				}
				finish(walk, "arrived", "arrival_radius", player, tick);
				return;
			}
			if (!walk.clickInFlight && !walk.obstacleInFlight &&
				tick - walk.startedAtTick - walk.pausedInteractionTicks >= walk.timeoutTicks)
			{
				finish(walk, "timed_out", "game_tick_timeout", player, tick);
				return;
			}

			if (!player.equals(walk.lastPlayer))
			{
				walk.lastPlayer = player;
				walk.lastMovedTick = tick;
				walk.samePathStallRetries = 0;
			}
			if (snapshot.getRunEnergy() < MINIMUM_RUN_ENERGY)
			{
				walk.runToggleArmed = true;
			}
			if (!walk.useRun && snapshot.isRunEnabled() && !walk.runInFlight)
			{
				walk.runInFlight = true;
				walk.runStartedTick = tick;
				dispatchRunToggle(walk, false);
				return;
			}
			if (walk.useRun && !snapshot.isRunEnabled() &&
				snapshot.getRunEnergy() >= MINIMUM_RUN_ENERGY &&
				walk.runToggleArmed && !walk.runInFlight)
			{
				walk.runToggleArmed = false;
				walk.runInFlight = true;
				walk.runStartedTick = tick;
				dispatchRunToggle(walk, true);
				return;
			}
			if (walk.runInFlight)
			{
				return;
			}
			if (walk.planning || walk.path == null)
			{
				return;
			}
			int nearest = nearestRouteIndex(walk, player);
			if (nearest >= 0 && nearest > walk.pathIndex)
			{
				walk.pathIndex = nearest;
				reporter.accept("WALK_PROGRESS plan=" + walk.planRevision + " tile=" + player +
					" pathIndex=" + nearest + " remaining=" + (walk.path.size() - 1 - nearest));
			}

			if (walk.obstacleInFlight)
			{
				return;
			}
			if (walk.pendingObstacle != null)
			{
				int progressIndex = nearest < 0 ? walk.pathIndex : nearest;
				if (snapshot.routeBlockCleared(walk.pendingObstacle, progressIndex))
				{
					walk.obstaclesCleared++;
					reporter.accept("WALK_OBSTACLE_CLEARED object=" +
						walk.pendingObstacle.getObjectId() + " action=" +
						walk.pendingObstacle.getAction() + " world=" +
						walk.pendingObstacle.getWorld());
					walk.pendingObstacle = null;
					walk.obstacleAttempts = 0;
					walk.lastMovedTick = tick;
					return;
				}
				String lockedMessage = snapshot.lockedObstacleMessageSince(walk.obstacleAttemptTick);
				if (lockedMessage != null)
				{
					reporter.accept("WALK_OBSTACLE_LOCKED object=" +
						walk.pendingObstacle.getObjectId() + " message=" + lockedMessage);
					finish(walk, "unreachable", "obstacle_locked", player, tick);
					return;
				}
				if (tick < walk.obstacleRetryTick ||
					tick - walk.lastMovedTick < OBSTACLE_SETTLE_TICKS)
				{
					return;
				}
				if (walk.obstacleAttempts >= MAX_OBSTACLE_ATTEMPTS)
				{
					finish(walk, "unreachable", "obstacle_interaction_limit", player, tick);
					return;
				}
				obstacleWalk = walk;
				obstacleBlock = walk.pendingObstacle;
				prepareObstacleDispatch(walk, obstacleBlock, tick, player);
			}

			if (obstacleWalk == null)
			{
				if (nearest < 0)
				{
					if (walk.clickTarget != null && tick - walk.lastMovedTick < STALL_TICKS)
					{
						return;
					}
					requestPlan(walk, player, "off_route");
					return;
				}
				if (walk.clickInFlight)
				{
					return;
				}
				if (walk.clickTarget != null)
				{
					int targetDistance = distance(player, walk.clickTarget);
					boolean finalWaypoint = walk.clickTargetIndex == walk.path.size() - 1;
					boolean passedTarget = !finalWaypoint && nearest > walk.clickTargetIndex;
					int advanceRadius = finalWaypoint ? 0 : WAYPOINT_ADVANCE_RADIUS;
					if (targetDistance > advanceRadius && !passedTarget)
					{
						if (tick - walk.lastMovedTick >= STALL_TICKS)
						{
							int attemptedLeg = Math.max(1, walk.clickTargetIndex - nearest);
							walk.maximumLegTiles = Math.min(
								walk.maximumLegTiles,
								Math.max(3, attemptedLeg - 3));
							reporter.accept("WALK_LEG_BACKOFF attempted=" + attemptedLeg +
								" maximum=" + walk.maximumLegTiles + " target=" + walk.clickTarget);
							walk.lastMovedTick = tick;
							if (walk.samePathStallRetries < MAX_SAME_PATH_STALL_RETRIES)
							{
								walk.samePathStallRetries++;
								walk.pathRetries++;
								walk.clickTarget = null;
								walk.clickTargetIndex = -1;
								walk.nextClickTick = tick + CLICK_COOLDOWN_TICKS;
								reporter.accept("WALK_PATH_RETRY plan=" + walk.planRevision +
									" attempt=" + walk.samePathStallRetries +
									" maximum=" + MAX_SAME_PATH_STALL_RETRIES);
								return;
							}
							walk.samePathStallRetries = 0;
							requestPlan(walk, player, "stalled");
						}
						return;
					}
					reporter.accept("WALK_WAYPOINT_REACHED plan=" + walk.planRevision +
						" tile=" + player + " target=" + walk.clickTarget +
						" distance=" + targetDistance + " passed=" + passedTarget);
					walk.clickTarget = null;
					walk.clickTargetIndex = -1;
					walk.lastMovedTick = tick;
					if (walk.maximumLegTiles != Integer.MAX_VALUE)
					{
						walk.maximumLegTiles++;
					}
				}
				else if (walk.clicks > 0 && tick - walk.lastMovedTick >= STALL_TICKS)
				{
					walk.lastMovedTick = tick;
					requestPlan(walk, player, "stalled_without_target");
					return;
				}
				if (tick < walk.nextClickTick)
				{
					return;
				}
				if (nearest >= walk.path.size() - 1)
				{
					requestPlan(walk, player, "path_exhausted_before_arrival");
					return;
				}

				int candidateEnd = walk.maximumLegTiles == Integer.MAX_VALUE
					? walk.path.size() - 1
					: Math.min(walk.path.size() - 1, nearest + walk.maximumLegTiles);
				GenericClientSnapshot.RouteBlock routeBlock = snapshot.findRouteBlock(
					walk.path,
					nearest,
					Math.min(candidateEnd, nearest + OBSTACLE_SCAN_TILES));
				if (routeBlock != null)
				{
					if (!routeBlock.isTraversable())
					{
						walk.blockedEdges.add(new BlockedEdge(
							routeBlock.getFrom(), routeBlock.getTo()));
						walk.liveBlockReplans++;
						reporter.accept("WALK_ROUTE_BLOCKED from=" + routeBlock.getFrom() +
							" to=" + routeBlock.getTo() + " player=" + player +
							" replan=" + walk.liveBlockReplans);
						requestPlan(walk, player, "live_route_blocked");
						return;
					}
					if (distance(player, routeBlock.getWorld()) <= OBSTACLE_INTERACT_DISTANCE)
					{
						walk.pendingObstacle = routeBlock;
						walk.obstacleAttempts = 0;
						obstacleWalk = walk;
						obstacleBlock = routeBlock;
						prepareObstacleDispatch(walk, routeBlock, tick, player);
					}
					else
					{
						candidateEnd = Math.min(candidateEnd, routeBlock.getPathIndex() - 1);
					}
				}

				if (obstacleWalk == null)
				{
					if (candidateEnd <= nearest)
					{
						finish(walk, "unreachable", "traversal_object_not_reachable", player, tick);
						return;
					}
					clickWalk = walk;
					clickCandidates = new ArrayList<>(candidateEnd - nearest);
					for (int index = candidateEnd; index > nearest; index--)
					{
						clickCandidates.add(walk.path.get(index));
					}
					walk.clickInFlight = true;
					walk.clickStartedTick = tick;
					reporter.accept("WALK_CLICK_REQUEST plan=" + walk.planRevision + " from=" + player +
						" candidates=" + clickCandidates.size() + " farthest=" + clickCandidates.get(0));
				}
			}
		}

		if (obstacleWalk != null)
		{
			dispatchObstacle(obstacleWalk, obstacleBlock);
		}
		else
		{
			dispatchClick(clickWalk, clickCandidates);
		}
	}

	private void dispatchRunToggle(ActiveWalk walk, boolean enabled)
	{
		runInput.setEnabled(enabled, walk.breaksEnabled).whenComplete((receipt, error) ->
		{
			synchronized (GenericClientWalker.this)
			{
				if (active != walk)
				{
					return;
				}
				walk.runInFlight = false;
				GenericClientSnapshot snapshot = latestSnapshot;
				long tick = snapshot == null ? walk.startedAtTick : snapshot.getGameTick();
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
		});
	}

	synchronized void cancelActive(String reason)
	{
		ActiveWalk walk = active;
		if (walk == null)
		{
			return;
		}
		GenericClientSnapshot snapshot = latestSnapshot;
		long tick = snapshot == null ? walk.startedAtTick : snapshot.getGameTick();
		WorldPoint reached = snapshot == null ? null : snapshot.getPlayerWorldPoint();
		finish(walk, "cancelled", reason, reached, tick);
	}

	private void prepareObstacleDispatch(
		ActiveWalk walk,
		GenericClientSnapshot.RouteBlock obstacle,
		long tick,
		WorldPoint player)
	{
		walk.obstacleInFlight = true;
		walk.clickStartedTick = tick;
		walk.obstacleAttemptTick = tick;
		walk.obstacleAttempts++;
		reporter.accept("WALK_OBSTACLE_INTERACTION attempt=" + walk.obstacleAttempts +
			" object=" + obstacle.getObjectId() + " name=" + obstacle.getObjectName() +
			" action=" + obstacle.getAction() + " world=" + obstacle.getWorld() +
			" edge=" + obstacle.getFrom() + "->" + obstacle.getTo() + " player=" + player);
	}

	private void dispatchObstacle(
		ActiveWalk walk,
		GenericClientSnapshot.RouteBlock obstacle)
	{
		obstacleInput.interact(
			obstacle.getObjectId(),
			obstacle.getAction(),
			obstacle.getWorld(),
			OBSTACLE_INTERACT_DISTANCE + 1,
			walk.breaksEnabled).whenComplete((receipt, error) ->
		{
			synchronized (GenericClientWalker.this)
			{
				if (active != walk)
				{
					return;
				}
				walk.obstacleInFlight = false;
				GenericClientSnapshot snapshot = latestSnapshot;
				long tick = snapshot == null ? walk.startedAtTick : snapshot.getGameTick();
				walk.pausedInteractionTicks += Math.max(0L, tick - walk.clickStartedTick);
				walk.obstacleRetryTick = tick + CLICK_COOLDOWN_TICKS;
				String status = receipt == null ? null : String.valueOf(receipt.get("status"));
				String result = error == null
					? receipt == null ? "null" : String.valueOf(receipt.get("result"))
					: error.getMessage();
				if (error == null && "dispatched".equals(status))
				{
					walk.obstacleInteractions++;
					reporter.accept("WALK_OBSTACLE_DISPATCHED object=" + obstacle.getObjectId() +
						" action=" + obstacle.getAction() + " result=" + result);
					return;
				}
				reporter.accept("WALK_OBSTACLE_REJECTED attempt=" + walk.obstacleAttempts +
					" object=" + obstacle.getObjectId() + " action=" + obstacle.getAction() +
					" result=" + result);
				if (walk.obstacleAttempts >= MAX_OBSTACLE_ATTEMPTS)
				{
					WorldPoint reached = snapshot == null ? null : snapshot.getPlayerWorldPoint();
					finish(walk, "unreachable", "obstacle_interaction_failed: " + result, reached, tick);
				}
			}
		});
	}

	private void dispatchClick(ActiveWalk walk, List<WorldPoint> candidates)
	{
		gameInput.walkToFarthest(candidates, walk.breaksEnabled).whenComplete((result, error) ->
		{
			synchronized (GenericClientWalker.this)
			{
				if (active != walk)
				{
					return;
				}

				walk.clickInFlight = false;
				GenericClientSnapshot snapshot = latestSnapshot;
				long tick = snapshot == null ? walk.startedAtTick : snapshot.getGameTick();
				walk.pausedInteractionTicks += Math.max(0L, tick - walk.clickStartedTick);
				walk.nextClickTick = tick + CLICK_COOLDOWN_TICKS;
				if (result != null && result.isClickDispatched())
				{
					walk.clicks++;
				}
				if (error == null && result != null && result.isWalkExecuted() && result.getTarget() != null)
				{
					walk.consecutiveClickFailures = 0;
					walk.clickTarget = result.getTarget();
					walk.clickTargetIndex = routeIndex(walk, result.getTarget());
					WorldPoint from = snapshot == null ? null : snapshot.getPlayerWorldPoint();
					reporter.accept("WALK_CLICK plan=" + walk.planRevision + " from=" + from +
						" target=" + walk.clickTarget + " pathIndex=" + walk.clickTargetIndex);
					return;
				}

				walk.consecutiveClickFailures++;
				walk.clickTarget = null;
				walk.clickTargetIndex = -1;
				String detail = error == null
					? result == null ? "null" : result.getDetail()
					: error.getMessage();
				reporter.accept("WALK_CLICK_REJECTED failures=" +
					walk.consecutiveClickFailures + " result=" + detail);
				if (walk.consecutiveClickFailures >= MAX_CLICK_FAILURES)
				{
					WorldPoint reached = snapshot == null ? null : snapshot.getPlayerWorldPoint();
					finish(walk, "click_failed", detail, reached, tick);
				}
			}
		});
	}

	private void requestPlan(ActiveWalk walk, WorldPoint start, String reason)
	{
		if (active != walk || walk.planning)
		{
			return;
		}
		boolean recoveryPlan = isRecoveryPlan(reason);
		if (recoveryPlan && walk.recoveryPlans >= walk.maximumRecoveryPlans)
		{
			GenericClientSnapshot snapshot = latestSnapshot;
			long tick = snapshot == null ? walk.startedAtTick : snapshot.getGameTick();
			finish(walk, "unreachable", "replan_limit", start, tick);
			return;
		}
		if (recoveryPlan)
		{
			walk.recoveryPlans++;
		}

		walk.planning = true;
		walk.clickTarget = null;
		walk.clickTargetIndex = -1;
		walk.plans++;
		int revision = ++walk.planRevision;
		GenericClientSnapshot planningSnapshot = latestSnapshot;
		Set<BlockedEdge> blockedEdges = new LinkedHashSet<>(walk.blockedEdges);
		reporter.accept("WALK_PLANNING plan=" + revision + " reason=" + reason +
			" start=" + start + " destination=" + walk.destination +
			" blockedEdges=" + blockedEdges.size());

		try
		{
			plannerExecutor.execute(() ->
			{
				GenericClientPathfinder.Result result;
				try
				{
					GenericClientPathfinder.EdgePolicy edgePolicy =
						(x, y, plane, dx, dy, staticAllowed) ->
						{
							BlockedEdge edge = new BlockedEdge(
								new WorldPoint(x, y, plane),
								new WorldPoint(x + dx, y + dy, plane));
							if (blockedEdges.contains(edge))
							{
								return false;
							}
							return planningSnapshot == null
								? staticAllowed
								: planningSnapshot.canPlanMove(
									x, y, plane, dx, dy, staticAllowed);
						};
					result = pathfinder.find(start, walk.destination, walk.within, edgePolicy);
				}
				catch (RuntimeException exception)
				{
					synchronized (GenericClientWalker.this)
					{
						if (active == walk && walk.planRevision == revision)
						{
							walk.planning = false;
							GenericClientSnapshot snapshot = latestSnapshot;
							long tick = snapshot == null ? walk.startedAtTick : snapshot.getGameTick();
							WorldPoint reached = snapshot == null ? start : snapshot.getPlayerWorldPoint();
							finish(walk, "unreachable", "planner_error: " + exception.getMessage(), reached, tick);
						}
					}
					return;
				}
				synchronized (GenericClientWalker.this)
				{
					if (active != walk || walk.planRevision != revision)
					{
						return;
					}
					walk.planning = false;
					walk.expandedNodes = result.getExpandedNodes();
					if (result.getStatus() != GenericClientPathfinder.Status.FOUND)
					{
						GenericClientSnapshot snapshot = latestSnapshot;
						long tick = snapshot == null ? walk.startedAtTick : snapshot.getGameTick();
						WorldPoint reached = snapshot == null ? start : snapshot.getPlayerWorldPoint();
						String status = result.getStatus() == GenericClientPathfinder.Status.UNSUPPORTED_PLANE
							? "unsupported_transition"
							: "unreachable";
						finish(walk, status, result.getStatus().name().toLowerCase(), reached, tick);
						return;
					}

					walk.path = result.getPath();
					walk.pathIndex = 0;
					walk.pathTiles = walk.path.size();
					GenericClientSnapshot currentSnapshot = latestSnapshot;
					long plannedAtTick = currentSnapshot == null
						? walk.lastMovedTick
						: currentSnapshot.getGameTick();
					walk.lastMovedTick = plannedAtTick;
					walk.nextClickTick = Math.max(walk.nextClickTick, plannedAtTick);
					reporter.accept("WALK_PLANNED plan=" + revision + " pathTiles=" +
						walk.pathTiles + " expandedNodes=" + walk.expandedNodes + " start=" + start +
						" reachedGoal=" + walk.path.get(walk.path.size() - 1));
				}
			});
		}
		catch (RejectedExecutionException exception)
		{
			walk.planning = false;
			GenericClientSnapshot snapshot = latestSnapshot;
			long tick = snapshot == null ? walk.startedAtTick : snapshot.getGameTick();
			finish(walk, "cancelled", "pathfinder_closed", start, tick);
		}
	}

	private static int nearestRouteIndex(ActiveWalk walk, WorldPoint player)
	{
		int from = Math.max(0, walk.pathIndex - 3);
		int to = Math.min(walk.path.size() - 1, walk.pathIndex + 12);
		int bestIndex = -1;
		int bestDistance = Integer.MAX_VALUE;
		for (int index = from; index <= to; index++)
		{
			int candidateDistance = distance(player, walk.path.get(index));
			if (candidateDistance < bestDistance ||
				(candidateDistance == bestDistance && index > bestIndex))
			{
				bestDistance = candidateDistance;
				bestIndex = index;
			}
		}
		return bestDistance <= OFF_ROUTE_RADIUS ? bestIndex : -1;
	}

	private static boolean isRecoveryPlan(String reason)
	{
		return "off_route".equals(reason) || "stalled".equals(reason) ||
			"stalled_without_target".equals(reason) || "live_route_blocked".equals(reason);
	}

	private static int routeIndex(ActiveWalk walk, WorldPoint target)
	{
		for (int index = walk.path.size() - 1; index >= 0; index--)
		{
			if (target.equals(walk.path.get(index)))
			{
				return index;
			}
		}
		throw new IllegalStateException("Selected walk target is not on the active route: " + target);
	}

	private void finish(
		ActiveWalk walk,
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
		if (walk.clickInFlight)
		{
			walk.clickInFlight = false;
			gameInput.cancelWalkToTile();
		}
		if (walk.obstacleInFlight)
		{
			walk.obstacleInFlight = false;
			obstacleInput.cancel();
		}
		if (walk.runInFlight)
		{
			walk.runInFlight = false;
			runInput.cancel("walker_finished");
		}
		Map<String, Object> receipt = receipt(walk, status, reason, reached, tick);
		reporter.accept("WALK_COMPLETED status=" + status + " reason=" + reason +
			" requested=" + walk.destination + " reached=" + reached + " ticks=" +
			receipt.get("game_ticks") + " plans=" + walk.plans + " clicks=" + walk.clicks);
		walk.completion.complete(receipt);
	}

	private static Map<String, Object> receipt(
		ActiveWalk walk,
		String status,
		String reason,
		WorldPoint reached,
		long tick)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", status);
		receipt.put("requested", worldMap(walk.destination));
		receipt.put("reached", worldMap(reached));
		receipt.put("within", (long) walk.within);
		receipt.put("game_ticks", Math.max(0, tick - walk.startedAtTick));
		receipt.put("active_game_ticks", Math.max(0, tick - walk.startedAtTick - walk.pausedInteractionTicks));
		receipt.put("plans", (long) walk.plans);
		receipt.put("recovery_plans", (long) walk.recoveryPlans);
		receipt.put("recovery_plan_limit", (long) walk.maximumRecoveryPlans);
		receipt.put("path_retries", (long) walk.pathRetries);
		receipt.put("clicks", (long) walk.clicks);
		receipt.put("obstacle_interactions", (long) walk.obstacleInteractions);
		receipt.put("obstacles_cleared", (long) walk.obstaclesCleared);
		receipt.put("live_block_replans", (long) walk.liveBlockReplans);
		List<Map<String, Object>> blockedEdges = new ArrayList<>();
		for (BlockedEdge edge : walk.blockedEdges)
		{
			blockedEdges.add(edge.toMap());
		}
		receipt.put("blocked_edges", blockedEdges);
		receipt.put("run_toggles", (long) walk.runToggles);
		receipt.put("path_tiles", (long) walk.pathTiles);
		receipt.put("expanded_nodes", (long) walk.expandedNodes);
		receipt.put("reason", reason);
		return receipt;
	}

	private static Map<String, Object> immediateReceipt(
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
		receipt.put("blocked_edges", new ArrayList<>());
		receipt.put("run_toggles", 0L);
		receipt.put("path_tiles", 0L);
		receipt.put("expanded_nodes", 0L);
		receipt.put("reason", reason);
		return receipt;
	}

	private static Map<String, Object> runUnchanged()
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "unchanged");
		receipt.put("result", "run_input_unavailable");
		receipt.put("click_count", 0L);
		return receipt;
	}

	private static Map<String, Object> worldMap(WorldPoint point)
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

	private static int distance(WorldPoint first, WorldPoint second)
	{
		if (first.getPlane() != second.getPlane())
		{
			return Integer.MAX_VALUE;
		}
		return Math.max(Math.abs(first.getX() - second.getX()),
			Math.abs(first.getY() - second.getY()));
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
			ActiveWalk walk = active;
			if (walk != null)
			{
				GenericClientSnapshot snapshot = latestSnapshot;
				long tick = snapshot == null ? walk.startedAtTick : snapshot.getGameTick();
				WorldPoint reached = snapshot == null ? null : snapshot.getPlayerWorldPoint();
				finish(walk, "cancelled", "walker_closed", reached, tick);
			}
		}
		plannerExecutor.shutdownNow();
	}

	interface WalkInput
	{
		CompletableFuture<GenericClientInteractionResult> walkToFarthest(
			List<WorldPoint> candidates,
			boolean breaksEnabled);

		void cancelWalkToTile();
	}

	interface ObstacleInput
	{
		CompletableFuture<Map<String, Object>> interact(
			int objectId,
			String action,
			WorldPoint world,
			int within,
			boolean breaksEnabled);

		void cancel();
	}

	interface RunInput
	{
		CompletableFuture<Map<String, Object>> setEnabled(
			boolean enabled,
			boolean breaksEnabled);

		void cancel(String reason);
	}

	private static final class ActiveWalk
	{
		private final WorldPoint destination;
		private final int within;
		private final int timeoutTicks;
		private final boolean breaksEnabled;
		private final boolean useRun;
		private final int maximumRecoveryPlans;
		private final long startedAtTick;
		private final CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
		private WorldPoint lastPlayer;
		private long lastMovedTick;
		private long nextClickTick;
		private long clickStartedTick;
		private long pausedInteractionTicks;
		private boolean planning;
		private boolean clickInFlight;
		private boolean obstacleInFlight;
		private boolean runInFlight;
		private boolean runToggleArmed = true;
		private int planRevision;
		private int plans;
		private int recoveryPlans;
		private int pathRetries;
		private int samePathStallRetries;
		private int clicks;
		private int obstacleAttempts;
		private int obstacleInteractions;
		private int obstaclesCleared;
		private int liveBlockReplans;
		private final Set<BlockedEdge> blockedEdges = new LinkedHashSet<>();
		private int runToggles;
		private int consecutiveClickFailures;
		private int pathIndex;
		private int clickTargetIndex = -1;
		private int pathTiles;
		private int expandedNodes;
		private int maximumLegTiles = Integer.MAX_VALUE;
		private List<WorldPoint> path;
		private WorldPoint clickTarget;
		private long obstacleRetryTick;
		private long obstacleAttemptTick;
		private long runStartedTick;
		private GenericClientSnapshot.RouteBlock pendingObstacle;

		private ActiveWalk(
			WorldPoint destination,
			int within,
			int timeoutTicks,
			long startedAtTick,
			WorldPoint start,
			boolean breaksEnabled,
			boolean useRun)
		{
			this.destination = destination;
			this.within = within;
			this.timeoutTicks = timeoutTicks;
			this.breaksEnabled = breaksEnabled;
			this.useRun = useRun;
			this.maximumRecoveryPlans = recoveryPlanLimit(start, destination);
			this.startedAtTick = startedAtTick;
			this.lastPlayer = start;
			this.lastMovedTick = startedAtTick;
		}
	}

	static int recoveryPlanLimit(WorldPoint start, WorldPoint destination)
	{
		int routeDistance = start == null || destination == null ? 0 : distance(start, destination);
		return Math.min(
			MAXIMUM_RECOVERY_REPLANS,
			MINIMUM_RECOVERY_REPLANS + routeDistance / RECOVERY_PLAN_TILES);
	}

	static final class BlockedEdge
	{
		private final WorldPoint first;
		private final WorldPoint second;

		BlockedEdge(WorldPoint first, WorldPoint second)
		{
			if (first == null || second == null)
			{
				throw new IllegalArgumentException("Blocked edge endpoints are required");
			}
			if (compare(first, second) <= 0)
			{
				this.first = first;
				this.second = second;
			}
			else
			{
				this.first = second;
				this.second = first;
			}
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("from", worldMap(first));
			value.put("to", worldMap(second));
			return value;
		}

		@Override
		public boolean equals(Object value)
		{
			if (this == value)
			{
				return true;
			}
			if (!(value instanceof BlockedEdge))
			{
				return false;
			}
			BlockedEdge other = (BlockedEdge) value;
			return first.equals(other.first) && second.equals(other.second);
		}

		@Override
		public int hashCode()
		{
			return 31 * first.hashCode() + second.hashCode();
		}

		private static int compare(WorldPoint left, WorldPoint right)
		{
			int plane = Integer.compare(left.getPlane(), right.getPlane());
			if (plane != 0)
			{
				return plane;
			}
			int x = Integer.compare(left.getX(), right.getX());
			return x == 0 ? Integer.compare(left.getY(), right.getY()) : x;
		}
	}
}
