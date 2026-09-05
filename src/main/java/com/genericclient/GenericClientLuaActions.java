package com.genericclient;

import static com.genericclient.GenericClientErrors.rootMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;
import net.runelite.api.coords.WorldPoint;

/** Semantic action submission with one behavior envelope and one completion ticket per await. */
final class GenericClientLuaActions
{
	private final GenericClientLuaHost host;
	private final GenericClientScriptCheckpointStore checkpointStore;
	private final WalkRandomAction walkRandomAction;
	private final WalkClickAction walkClickAction;
	private final WalkToAction walkToAction;
	private final NpcInteractAction npcInteractAction;
	private final CombatModeAction combatModeAction;
	private final QuestAction questAction;
	private final GenericClientActionBoundary actionBoundary;
	private final GenericClientBehaviorController behavior;

	GenericClientLuaActions(GenericClientLuaHost host, Path scriptsDirectory,
		WalkRandomAction walkRandomAction, WalkClickAction walkClickAction, WalkToAction walkToAction,
		NpcInteractAction npcInteractAction, CombatModeAction combatModeAction, QuestAction questAction,
		GenericClientBehaviorController behavior) throws IOException
	{
		this.host = host;
		this.checkpointStore = new GenericClientScriptCheckpointStore(scriptsDirectory.getParent().resolve("checkpoints"));
		this.walkRandomAction = walkRandomAction;
		this.walkClickAction = walkClickAction;
		this.walkToAction = walkToAction;
		this.npcInteractAction = npcInteractAction;
		this.combatModeAction = combatModeAction;
		this.questAction = questAction;
		this.behavior = behavior;
		this.actionBoundary = new GenericClientActionBoundary(behavior);
	}

	void submitWalkRandom(GenericClientLuaScript script, long requestId, GenericClientActivityContext context)
	{
		submitAction(script, requestId, "walk.random", context, input ->
			walkRandomAction.walk(input).thenApply(GenericClientInteractionResult::toReceipt));
	}

	void submitWalkClick(GenericClientLuaScript script, long requestId, WorldPoint destination,
		GenericClientActivityContext context)
	{
		submitAction(script, requestId, "walk.click", context, input ->
			walkClickAction.click(destination, input).thenApply(GenericClientInteractionResult::toReceipt));
	}

	void submitWalkTo(GenericClientLuaScript script, long requestId, GenericClientWalkRequest request)
	{
		GenericClientActivityContext context = request.activityContext;
		GenericClientWalker.ClickBoundary clicks = new GenericClientWalker.ClickBoundary()
		{
			@Override
			public CompletableFuture<GenericClientInteractionResult> execute(
				GenericClientActivityContext input,
				Supplier<CompletableFuture<GenericClientInteractionResult>> action)
			{
				java.util.concurrent.atomic.AtomicReference<GenericClientInteractionResult> raw =
					new java.util.concurrent.atomic.AtomicReference<>();
				return actionBoundary.execute(input.inputTicket(), input, () -> action.get().thenApply(value ->
				{
					raw.set(value);
					return value.toReceipt();
				}), true).thenApply(receipt ->
				{
					GenericClientInteractionResult value = raw.get();
					return value == null
						? new GenericClientInteractionResult(null, "WALK_TILE_CLICK_CANCELLED", false,
							Collections.emptyMap(), Collections.emptyMap())
						: value.withBehavior(receipt);
				});
			}

			@Override
			public int nextClickDelayTicks() { return behavior.nextWalkClickDelayTicks(); }

			@Override
			public double nextReachFraction() { return behavior.nextWalkReachFraction(); }
		};
		submitAction(script, requestId, "walk.to", context, input -> walkToAction.walkTo(request.withContext(input), clicks));
	}

	void submitNpcInteract(GenericClientLuaScript script, long requestId, Integer id, String name,
		String action, int within, GenericClientActivityContext context)
	{
		submitAction(script, requestId, "npc.interact", context, input ->
			npcInteractAction.interact(id, name, action, within, input));
	}

	void submitQuestAction(GenericClientLuaScript script, long requestId, String type,
		Map<String, Object> action, GenericClientActivityContext context)
	{
		if (GenericClientLuaIntent.isControl(type))
		{
			GenericClientActionBoundary.Ticket ticket = script.actionTicket(requestId);
			String name = (String) action.get("name");
			CompletableFuture<Map<String, Object>> result = "intent.begin".equals(type)
				? script.intents.begin(name, context.withResolver(behavior.policies), actionBoundary)
				: script.intents.end(name, Boolean.TRUE.equals(action.get("failed")));
			result.whenComplete((receipt, error) -> completeAction(script, requestId, ticket, receipt, error));
			return;
		}
		submitAction(script, requestId, type, context, input -> GenericClientScriptCheckpointStore.supports(type)
			? executeCheckpoint(script, type, action)
			: questAction.execute(type, action, input));
	}

	private void submitAction(GenericClientLuaScript script, long requestId, String type,
		GenericClientActivityContext context, Function<GenericClientActivityContext, CompletableFuture<Map<String, Object>>> action)
	{
		GenericClientActionBoundary.Ticket ticket = script.actionTicket(requestId);
		GenericClientActivityContext inputContext = script.intents.context(context.withResolver(behavior.policies)).withTicket(ticket);
		boolean discretionary = !GenericClientScriptCheckpointStore.supports(type) &&
			!type.startsWith("safety.") && !"client.behaviors.configure".equals(type) &&
			!"mouse.offscreen".equals(type);
		actionBoundary.execute(ticket, discretionary ? inputContext : GenericClientActivityContext.none().withTicket(ticket),
			() -> action.apply(inputContext), discretionary && !"walk.to".equals(type)).handle((receipt, error) ->
		{
			if (error == null) return receipt;
			Map<String, Object> rejected = new LinkedHashMap<>();
			rejected.put("status", "rejected");
			rejected.put("walk.to".equals(type) || "mouse.offscreen".equals(type) ? "reason" : "result", rootMessage(error));
			return rejected;
		}).whenComplete((receipt, error) -> completeAction(script, requestId, ticket, receipt, error));
	}

	private CompletableFuture<Map<String, Object>> executeCheckpoint(
		GenericClientLuaScript script,
		String type,
		Map<String, Object> action)
	{
		GenericClientSnapshot snapshot = host.currentSnapshot;
		if (snapshot == null || snapshot.getPlayer() == null)
		{
			throw new IllegalStateException("Checkpoint requires a logged-in player");
		}
		String account = snapshot.getPlayer().getName();
		String scriptId = script.getName();
		return CompletableFuture.supplyAsync(() ->
		{
			try
			{
				return checkpointStore.execute(account, scriptId, type, action);
			}
			catch (IOException exception)
			{
				throw new CompletionException(exception);
			}
		});
	}

	void submitCombatSetStyle(GenericClientLuaScript script, long requestId, int styleIndex,
		GenericClientActivityContext context)
	{
		submitAction(script, requestId, "combat.set_style", context, input ->
			combatModeAction.setMode(styleIndex, input));
	}

	void submitCombatSetAutoRetaliate(GenericClientLuaScript script, long requestId, boolean enabled,
		GenericClientActivityContext context)
	{
		submitAction(script, requestId, "combat.set_auto_retaliate", context, input ->
			combatModeAction.setMode(enabled ? 5 : 4, input));
	}

	void submitMouseOffscreen(GenericClientLuaScript script, long requestId)
	{
		submitAction(script, requestId, "mouse.offscreen", GenericClientActivityContext.none(), input ->
			behavior.moveMouseOffscreen(input).thenApply(value ->
			{
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "moved");
				receipt.put("result", value);
				return receipt;
			}));
	}

	void submitPhase(
		GenericClientLuaScript script,
		long requestId,
		String phase,
		GenericClientActivityContext activityContext)
	{
		behavior.enterPhase(phase, script.intents.context(activityContext.withResolver(behavior.policies))).whenComplete((receipt, error) ->
			completePhase(script, requestId, phase, receipt, error));
	}

	@FunctionalInterface
	interface WalkRandomAction
	{
		CompletableFuture<GenericClientInteractionResult> walk(GenericClientActivityContext activityContext);
	}


	@FunctionalInterface
	interface WalkClickAction
	{
		CompletableFuture<GenericClientInteractionResult> click(
			WorldPoint destination,
			GenericClientActivityContext activityContext);
	}


	@FunctionalInterface
	interface WalkToAction
	{
		CompletableFuture<Map<String, Object>> walkTo(GenericClientWalkRequest request,
			GenericClientWalker.ClickBoundary clickBoundary);
	}


	@FunctionalInterface
	interface NpcInteractAction
	{
		CompletableFuture<Map<String, Object>> interact(
			Integer id,
			String name,
			String action,
			int within,
			GenericClientActivityContext activityContext);
	}


	@FunctionalInterface
	interface CombatModeAction
	{
		CompletableFuture<Map<String, Object>> setMode(int mode, GenericClientActivityContext activityContext);
	}


	@FunctionalInterface
	interface QuestAction
	{
		CompletableFuture<Map<String, Object>> execute(
			String type,
			Map<String, Object> action,
			GenericClientActivityContext activityContext);
	}

	private volatile boolean emergencyPaused;
	private EmergencyPause emergencyPause;

	boolean isPaused() { return emergencyPaused; }
	void resetPauseState()
	{
		emergencyPaused = false;
		emergencyPause = null;
	}

	CompletableFuture<String> pauseForEmergency(String reason)
	{
		CompletableFuture<String> completion = new CompletableFuture<>();
		host.scheduler.execute(() ->
		{
			if (emergencyPause == null)
			{
				GenericClientLuaScript current = host.session;
				Long requestId = current == null ? null : current.pendingActionRequestId();
				emergencyPause = new EmergencyPause(current, requestId);
			}
			emergencyPaused = true;
			if (host.session != null) host.session.suspendActionInput(true);
			String result = "LUA_EMERGENCY_PAUSED reason=" + reason;
			host.publishStatus(result);
			completion.complete(result);
		});
		return completion;
	}

	CompletableFuture<String> resumeAfterEmergency(String reason)
	{
		CompletableFuture<String> completion = new CompletableFuture<>();
		host.scheduler.execute(() ->
		{
			EmergencyPause paused = emergencyPause;
			emergencyPause = null;
			emergencyPaused = false;
			String result = "LUA_EMERGENCY_RESUMED reason=" + reason;
			if (paused != null && paused.script != null) paused.script.suspendActionInput(false);
			if (paused != null && paused.completionObserved &&
				paused.script != null && host.canAdvance(paused.script))
			{
				if (paused.error != null || rejected(paused.receipt))
				{
					boolean retried = paused.script.retryAction(
						paused.requestId, host.currentSnapshot);
					result += " retried=" + retried;
				}
				else
				{
					applyActionCompletion(
						paused.script,
						paused.requestId,
						paused.receipt,
						paused.error);
					result += " completed=true";
				}
			}
			host.publishStatus(result);
			completion.complete(result);
		});
		return completion;
	}

	void completeAction(
		GenericClientLuaScript script,
		long requestId,
		GenericClientActionBoundary.Ticket ticket,
		Map<String, Object> receipt,
		Throwable error)
	{
		if (host.closed)
		{
			return;
		}
		try
		{
			host.scheduler.execute(() ->
				{
					if (!host.canAdvance(script) || !script.isCurrentAction(requestId, ticket))
					{
						return;
					}
					EmergencyPause paused = emergencyPause;
					if (emergencyPaused && paused != null && paused.matches(script, requestId))
					{
						paused.observe(receipt, error);
						host.publishStatus("LUA_ACTION_SUSPENDED_FOR_EMERGENCY request=" + requestId);
						return;
					}
					applyActionCompletion(script, requestId, receipt, error);
			});
		}
		catch (java.util.concurrent.RejectedExecutionException ignored)
		{
			// The host completed shutdown between the host.closed check and queue submission.
		}
	}

	private void applyActionCompletion(
		GenericClientLuaScript script,
		long requestId,
		Map<String, Object> receipt,
		Throwable error)
	{
		Map<String, Object> result = receipt;
		if (error != null)
		{
			result = new LinkedHashMap<>();
			result.put("status", "rejected");
			result.put("reason", rootMessage(error));
		}
		script.completeAction(requestId, result, host.currentSnapshot);
		host.reconcileScript(script);
	}

	private static boolean rejected(Map<String, Object> receipt)
	{
		return receipt == null || "rejected".equals(String.valueOf(receipt.get("status")));
	}

	void completePhase(
		GenericClientLuaScript script,
		long requestId,
		String phase,
		Map<String, Object> receipt,
		Throwable error)
	{
		if (host.closed)
		{
			return;
		}
		try
		{
				host.scheduler.execute(() ->
				{
					if (!host.canAdvance(script))
					{
						return;
					}
					Map<String, Object> result = receipt == null
					? new LinkedHashMap<>()
					: new LinkedHashMap<>(receipt);
				result.put("phase", phase);
				if (error != null)
				{
					result.put("status", "rejected");
					result.put("reason", rootMessage(error));
				}
				script.completePhase(requestId, result, host.currentSnapshot);
				host.reconcileScript(script);
			});
		}
		catch (java.util.concurrent.RejectedExecutionException ignored)
		{
			// The host completed shutdown between the host.closed check and queue submission.
		}
	}

	private static final class EmergencyPause
	{
		private final GenericClientLuaScript script;
		private final Long requestId;
		private Map<String, Object> receipt;
		private Throwable error;
		private boolean completionObserved;

		private EmergencyPause(GenericClientLuaScript script, Long requestId)
		{
			this.script = script;
			this.requestId = requestId;
		}

		private boolean matches(GenericClientLuaScript candidate, long candidateRequestId)
		{
			return script == candidate && requestId != null && requestId == candidateRequestId;
		}

		private void observe(Map<String, Object> value, Throwable failure)
		{
			receipt = value;
			error = failure;
			completionObserved = true;
		}
	}
}
