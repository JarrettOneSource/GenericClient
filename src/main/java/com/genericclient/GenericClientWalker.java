package com.genericclient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import net.runelite.api.coords.WorldPoint;

final class GenericClientWalker implements AutoCloseable
{
	private static final int MAX_REPLANS = 6;
	private static final int MAX_CLICK_AHEAD = 5;
	private static final int MAX_CLICK_FAILURES = 5;
	private static final int OFF_ROUTE_RADIUS = 3;
	private static final int STALL_TICKS = 8;
	private static final int CLICK_COOLDOWN_TICKS = 2;

	private final GenericClientGameInput gameInput;
	private final GenericClientPathfinder pathfinder;
	private final Consumer<String> reporter;
	private final ExecutorService plannerExecutor;

	private volatile GenericClientSnapshot latestSnapshot;
	private boolean closed;
	private ActiveWalk active;

	GenericClientWalker(
		GenericClientGameInput gameInput,
		GenericClientCollisionMap collisionMap,
		Consumer<String> reporter)
	{
		this.gameInput = gameInput;
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
		int timeoutTicks)
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

		ActiveWalk walk = new ActiveWalk(destination, within, timeoutTicks, tick, start);
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
		WorldPoint clickTarget = null;

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
				finish(walk, "arrived", "arrival_radius", player, tick);
				return;
			}
			if (tick - walk.startedAtTick >= walk.timeoutTicks)
			{
				finish(walk, "timed_out", "game_tick_timeout", player, tick);
				return;
			}

			if (!player.equals(walk.lastPlayer))
			{
				walk.lastPlayer = player;
				walk.lastMovedTick = tick;
			}
			if (walk.planning || walk.path == null)
			{
				return;
			}

			int nearest = nearestRouteIndex(walk, player);
			if (nearest < 0)
			{
				requestPlan(walk, player, "off_route");
				return;
			}
			if (nearest > walk.pathIndex)
			{
				walk.pathIndex = nearest;
				reporter.accept("WALK_PROGRESS plan=" + walk.planRevision + " tile=" + player +
					" pathIndex=" + nearest + " remaining=" + (walk.path.size() - 1 - nearest));
			}

			if (walk.clicks > 0 && tick - walk.lastMovedTick >= STALL_TICKS)
			{
				walk.lastMovedTick = tick;
				requestPlan(walk, player, "stalled");
				return;
			}
			if (walk.clickInFlight || tick < walk.nextClickTick)
			{
				return;
			}

			int clickAhead = Math.max(1, MAX_CLICK_AHEAD - walk.consecutiveClickFailures);
			int clickIndex = Math.min(walk.path.size() - 1, nearest + clickAhead);
			if (clickIndex <= nearest)
			{
				requestPlan(walk, player, "path_exhausted_before_arrival");
				return;
			}

			clickWalk = walk;
			clickTarget = walk.path.get(clickIndex);
			walk.clickInFlight = true;
			walk.clicks++;
			reporter.accept("WALK_CLICK plan=" + walk.planRevision + " from=" + player +
				" target=" + clickTarget + " pathIndex=" + clickIndex);
		}

		dispatchClick(clickWalk, clickTarget);
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

	private void dispatchClick(ActiveWalk walk, WorldPoint target)
	{
		gameInput.walkToTile(target).whenComplete((result, error) ->
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
				walk.nextClickTick = tick + CLICK_COOLDOWN_TICKS;
				if (error == null && result != null && result.startsWith("WALK_TILE_CLICK_EXECUTED"))
				{
					walk.consecutiveClickFailures = 0;
					return;
				}

				walk.consecutiveClickFailures++;
				String detail = error == null ? String.valueOf(result) : error.getMessage();
				reporter.accept("WALK_CLICK_REJECTED target=" + target + " failures=" +
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
		if (walk.plans >= MAX_REPLANS + 1)
		{
			GenericClientSnapshot snapshot = latestSnapshot;
			long tick = snapshot == null ? walk.startedAtTick : snapshot.getGameTick();
			finish(walk, "unreachable", "replan_limit", start, tick);
			return;
		}

		walk.planning = true;
		walk.plans++;
		int revision = ++walk.planRevision;
		reporter.accept("WALK_PLANNING plan=" + revision + " reason=" + reason +
			" start=" + start + " destination=" + walk.destination);

		try
		{
			plannerExecutor.execute(() ->
			{
				GenericClientPathfinder.Result result;
				try
				{
					result = pathfinder.find(start, walk.destination, walk.within);
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
		receipt.put("plans", (long) walk.plans);
		receipt.put("clicks", (long) walk.clicks);
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
		receipt.put("plans", 0L);
		receipt.put("clicks", 0L);
		receipt.put("path_tiles", 0L);
		receipt.put("expanded_nodes", 0L);
		receipt.put("reason", reason);
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

	private static final class ActiveWalk
	{
		private final WorldPoint destination;
		private final int within;
		private final int timeoutTicks;
		private final long startedAtTick;
		private final CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
		private WorldPoint lastPlayer;
		private long lastMovedTick;
		private long nextClickTick;
		private boolean planning;
		private boolean clickInFlight;
		private int planRevision;
		private int plans;
		private int clicks;
		private int consecutiveClickFailures;
		private int pathIndex;
		private int pathTiles;
		private int expandedNodes;
		private List<WorldPoint> path;

		private ActiveWalk(
			WorldPoint destination,
			int within,
			int timeoutTicks,
			long startedAtTick,
			WorldPoint start)
		{
			this.destination = destination;
			this.within = within;
			this.timeoutTicks = timeoutTicks;
			this.startedAtTick = startedAtTick;
			this.lastPlayer = start;
			this.lastMovedTick = startedAtTick;
		}
	}
}
