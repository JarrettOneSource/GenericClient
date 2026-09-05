package com.genericclient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.coords.WorldPoint;

/** Interaction progress and observed landings, confined to the owning walker's monitor. */
final class GenericClientWalkTransitions
{
	private static final int TIMEOUT_TICKS = 60;
	private final GenericClientWalkJourney walk;
	final Set<String> blocked = new HashSet<>();
	final List<Map<String, Object>> receipts = new ArrayList<>();
	GenericClientTransport pending;
	boolean inFlight;
	private boolean failedInput;
	private boolean interruptedInput;
	private int nextStep;
	private GenericClientTransport.Step currentInput;
	private long beganTick;
	private long startedTick;
	private long inputTick;
	private long closedArrivalTick;
	private long activeTickLimit;
	private Map<String, Object> receipt;
	private List<Map<String, Object>> actions;

	GenericClientWalkTransitions(GenericClientWalkJourney walk) { this.walk = walk; }

	int nextIndex()
	{
		int next = walk.path == null ? -1 : walk.path.size();
		for (int index : walk.transports.keySet())
			if (index >= walk.pathIndex) next = Math.min(next, index);
		return next;
	}

	Step advance(GenericClientSnapshot snapshot, WorldPoint player, long tick)
	{
		if (interruptedInput) return Step.INTERRUPT;
		if (failedInput)
		{
			failedInput = false;
			return Step.FAILED;
		}
		if (inFlight) return Step.WAIT;
		if (pending == null && !beginNext(player, tick)) return Step.CONTINUE;
		if (requiresDialogueInterrupt(snapshot)) return Step.INTERRUPT;
		if (observeArrival(snapshot, player, tick)) return Step.ARRIVED;
		if (!pending.eligible(snapshot))
		{
			fail("requirements_changed", player, tick);
			return Step.FAILED;
		}
		if (walk.avoidTiles.contains(pending.origin) || walk.avoidTiles.contains(pending.destination))
		{
			fail("transport_avoided", player, tick);
			return Step.FAILED;
		}
		if (tick - beganTick >= activeTickLimit)
		{
			fail(currentInput == null ? "target_not_available" : "arrival_unverified", player, tick);
			return Step.FAILED;
		}
		if (nextStep == pending.steps.size() || tick <= inputTick || !pending.steps.get(nextStep).available(snapshot)) return Step.WAIT;
		currentInput = pending.steps.get(nextStep);
		inFlight = true;
		inputTick = tick;
		// Once issued, an interrupted input may still land. Observe it instead of submitting it twice.
		if (!(currentInput instanceof GenericClientTransport.ConversationStep)) nextStep++;
		return Step.INTERACT;
	}

	private boolean beginNext(WorldPoint player, long tick)
	{
		int index = nextIndex();
		GenericClientTransport next = walk.transports.get(index);
		if (next == null || !next.origin.equals(player)) return false;
		if (walk.nextVia < walk.request.via.size() &&
			index > walk.viaIndices.get(walk.nextVia - walk.planViaStart)) return false;
		begin(next, tick);
		return true;
	}

	boolean observeArrival(GenericClientSnapshot snapshot, WorldPoint player, long tick)
	{
		if (pending == null || inFlight || currentInput == null) return false;
		if (!pending.arrival.contains(player) || pending.conversation != null && snapshot.isDialogueOpen())
		{
			closedArrivalTick = -1;
			return false;
		}
		if (pending.conversation != null)
		{
			if (closedArrivalTick < 0) closedArrivalTick = tick;
			if (tick <= closedArrivalTick) return false;
		}
		end("arrived", null, player, tick);
		return true;
	}

	private void begin(GenericClientTransport transport, long tick)
	{
		pending = transport;
		nextStep = 0;
		currentInput = null;
		inputTick = tick - 1;
		closedArrivalTick = -1;
		activeTickLimit = TIMEOUT_TICKS;
		beganTick = tick;
		startedTick = tick;
		actions = new ArrayList<>();
		receipt = new LinkedHashMap<>();
		receipt.put("id", transport.id);
		receipt.put("origin", GenericClientWalkJourney.worldMap(transport.origin));
		receipt.put("destination", GenericClientWalkJourney.worldMap(transport.destination));
		receipt.put("status", "waiting");
		receipt.put("actions", actions);
		receipts.add(receipt);
	}

	GenericClientTransport.Step action() { return currentInput; }

	boolean ownsDialogue(GenericClientSnapshot snapshot)
	{
		return pending != null && currentInput != null && pending.conversation != null && pending.conversation.available(snapshot);
	}

	boolean requiresDialogueInterrupt(GenericClientSnapshot snapshot)
	{
		return pending != null && pending.conversation != null && snapshot.isDialogueOpen() && !ownsDialogue(snapshot);
	}

	void complete(Map<String, Object> result, Throwable error, WorldPoint player, long tick)
	{
		excludeInputTime(tick);
		inFlight = false;
		inputTick = tick;
		if (result != null) actions.add(result);
		if (error == null && result != null && "dispatched".equals(result.get("status")))
			return;
		if (currentInput instanceof GenericClientTransport.ConversationStep && result != null &&
			"dialogue_changed".equals(result.get("result")))
		{
			interruptedInput = true;
			return;
		}
		fail(error == null ? result == null ? "missing_receipt" : String.valueOf(result.get("result")) : error.getMessage(),
			player, tick);
		failedInput = true;
	}

	private void fail(String reason, WorldPoint player, long tick)
	{
		blocked.add(pending.id);
		end("failed", reason, player, tick);
	}

	private void end(String status, String reason, WorldPoint player, long tick)
	{
		receipt.put("status", status);
		if (reason != null) receipt.put("reason", reason);
		receipt.put("reached", GenericClientWalkJourney.worldMap(player));
		receipt.put("game_ticks", Math.max(0L, tick - startedTick));
		receipt.put("active_game_ticks", Math.max(0L, tick - beganTick));
		pending = null;
		walk.lastMovedTick = tick;
	}

	void finish(String status, WorldPoint player, long tick)
	{
		if (pending != null) end("arrived".equals(status) ? "cancelled" : status, "journey_finished", player, tick);
	}

	void resume(long elapsed) { beganTick += elapsed; closedArrivalTick = -1; }

	void restore(ResumeState state, long tick)
	{
		blocked.addAll(state.blocked);
		if (state.transport == null) return;
		begin(state.transport, tick);
		nextStep = state.nextStep;
		currentInput = state.currentInput;
		activeTickLimit = state.remainingTicks;
		receipt.put("resumed", true);
	}

	static final class ResumeState
	{
		private final GenericClientTransport transport;
		private final GenericClientTransport.Step currentInput;
		private final int nextStep;
		private final long remainingTicks;
		private final Set<String> blocked;

		ResumeState(GenericClientWalkTransitions transitions, long tick)
		{
			transport = transitions.currentInput == null ? null : transitions.pending;
			currentInput = transitions.currentInput;
			nextStep = transitions.nextStep;
			remainingTicks = Math.max(0L, transitions.activeTickLimit - Math.max(0L, tick - transitions.beganTick));
			blocked = Set.copyOf(transitions.blocked);
		}
	}

	void excludeInputTime(long tick)
	{
		if (!inFlight) return;
		long elapsed = Math.max(0L, tick - inputTick);
		walk.pausedInteractionTicks += elapsed;
		beganTick += elapsed;
	}

	enum Step { CONTINUE, WAIT, INTERACT, ARRIVED, FAILED, INTERRUPT }

	@FunctionalInterface
	interface Input
	{
		CompletableFuture<Map<String, Object>> execute(GenericClientTransport.Step step,
			GenericClientSnapshot snapshot, GenericClientActivityContext context);
	}
}
