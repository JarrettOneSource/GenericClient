package com.genericclient;

import java.util.Map;
import java.util.function.Consumer;
import net.runelite.api.coords.WorldPoint;

/** Obstacle attempts and observed outcomes, confined to the owning walker's monitor. */
final class GenericClientWalkObstacles
{
	static final int INTERACT_DISTANCE = 3;
	private static final int SETTLE_TICKS = 8;
	private static final int MAX_ATTEMPTS = 3;
	private final GenericClientWalkJourney walk;
	private final GenericClientEdgeMemory memory;
	private final Consumer<String> reporter;
	GenericClientSnapshot.RouteBlock pending;
	boolean inFlight;
	private long attemptTick;
	private long retryTick;
	private int attempts;
	private int interactions;
	private int cleared;

	GenericClientWalkObstacles(GenericClientWalkJourney walk, GenericClientEdgeMemory memory, Consumer<String> reporter)
	{
		this.walk = walk;
		this.memory = memory;
		this.reporter = reporter;
	}

	Step advance(GenericClientSnapshot snapshot, WorldPoint player, long tick, int nearest)
	{
		if (inFlight) return Step.WAIT;
		if (pending == null) return Step.CONTINUE;
		if (observeClear(snapshot, nearest < 0 ? walk.pathIndex : nearest, tick)) return Step.WAIT;
		String lockedMessage = snapshot.lockedObstacleMessageSince(attemptTick);
		if (lockedMessage != null)
		{
			block(GenericClientEdgeMemory.Reason.LOCKED, lockedMessage);
			return Step.REPLAN;
		}
		if (tick < retryTick || tick - walk.lastMovedTick < SETTLE_TICKS) return Step.WAIT;
		if (attempts >= MAX_ATTEMPTS)
		{
			block(GenericClientEdgeMemory.Reason.INTERACTION_LIMIT, null);
			return Step.REPLAN;
		}
		prepare(tick, player);
		return Step.INTERACT;
	}

	boolean observeClear(GenericClientSnapshot snapshot, int progress, long tick)
	{
		if (pending == null || inFlight || !snapshot.routeBlockCleared(pending, progress)) return false;
		remember(pending, GenericClientEdgeMemory.Reason.CLEARED, null);
		cleared++;
		reporter.accept("WALK_OBSTACLE_CLEARED object=" + pending.getObjectId() +
			" action=" + pending.getAction() + " world=" + pending.getWorld());
		pending = null;
		attempts = 0;
		walk.lastMovedTick = tick;
		return true;
	}

	void recordSolid(GenericClientSnapshot.RouteBlock block, WorldPoint player)
	{
		remember(block, GenericClientEdgeMemory.Reason.SOLID, null);
		walk.liveBlockReplans++;
		reporter.accept("WALK_ROUTE_BLOCKED from=" + block.getFrom() + " to=" + block.getTo() +
			" player=" + player + " replan=" + walk.liveBlockReplans);
	}

	private void remember(GenericClientSnapshot.RouteBlock block, GenericClientEdgeMemory.Reason reason, String detail)
	{
		GenericClientEdgeMemory.Entry entry = memory.record(block, reason, detail);
		walk.edgeReceipts.put(entry.key(), entry.toMap());
	}

	void begin(GenericClientSnapshot.RouteBlock obstacle, long tick, WorldPoint player)
	{
		pending = obstacle;
		attempts = 0;
		prepare(tick, player);
	}

	private void prepare(long tick, WorldPoint player)
	{
		inFlight = true;
		attemptTick = tick;
		attempts++;
		reporter.accept("WALK_OBSTACLE_INTERACTION attempt=" + attempts +
			" object=" + pending.getObjectId() + " name=" + pending.getObjectName() +
			" action=" + pending.getAction() + " world=" + pending.getWorld() +
			" edge=" + pending.getFrom() + "->" + pending.getTo() + " player=" + player);
	}

	boolean complete(GenericClientSnapshot snapshot, Map<String, Object> receipt, Throwable error)
	{
		long tick = snapshot == null ? walk.lastObservedTick : snapshot.getGameTick();
		excludeInputTime(tick);
		inFlight = false;
		retryTick = tick + walk.clickCooldownTicks();
		String status = receipt == null ? null : String.valueOf(receipt.get("status"));
		String result = error == null
			? receipt == null ? "null" : String.valueOf(receipt.get("result"))
			: error.getMessage();
		if (error == null && "dispatched".equals(status))
		{
			interactions++;
			reporter.accept("WALK_OBSTACLE_DISPATCHED object=" + pending.getObjectId() +
				" action=" + pending.getAction() + " result=" + result);
			return false;
		}
		reporter.accept("WALK_OBSTACLE_REJECTED attempt=" + attempts +
			" object=" + pending.getObjectId() + " action=" + pending.getAction() + " result=" + result);
		if (attempts < MAX_ATTEMPTS) return false;
		block(GenericClientEdgeMemory.Reason.INTERACTION_FAILED, result);
		return true;
	}

	private void block(GenericClientEdgeMemory.Reason reason, String detail)
	{
		remember(pending, reason, detail);
		walk.liveBlockReplans++;
		reporter.accept("WALK_OBSTACLE_BLOCKED object=" + pending.getObjectId() +
			" action=" + pending.getAction() + " edge=" + pending.getFrom() +
			"->" + pending.getTo() + " reason=" + reason.name().toLowerCase(java.util.Locale.ROOT) +
			" replan=" + walk.liveBlockReplans);
		pending = null;
		attempts = 0;
	}

	void addReceipt(Map<String, Object> receipt)
	{
		receipt.put("obstacle_interactions", (long) interactions);
		receipt.put("obstacles_cleared", (long) cleared);
	}

	void excludeInputTime(long tick)
	{
		if (inFlight) walk.pausedInteractionTicks += Math.max(0L, tick - attemptTick);
	}

	enum Step { CONTINUE, WAIT, INTERACT, REPLAN }
}
