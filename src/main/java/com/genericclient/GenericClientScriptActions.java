package com.genericclient;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.runelite.api.coords.WorldPoint;

final class GenericClientScriptActions
{
	private final WalkRandomAction random;
	private final WalkClickAction click;
	private final WalkToAction walk;
	private final NpcInteractAction npc;
	private final CombatModeAction combat;
	private final QuestAction operations;
	private final GenericClientBehaviorController behavior;
	private final GenericClientActionBoundary boundary;

	GenericClientScriptActions(WalkRandomAction random, WalkClickAction click, WalkToAction walk,
		NpcInteractAction npc, CombatModeAction combat, QuestAction operations,
		GenericClientBehaviorController behavior)
	{
		this.random = random;
		this.click = click;
		this.walk = walk;
		this.npc = npc;
		this.combat = combat;
		this.operations = operations;
		this.behavior = behavior;
		boundary = new GenericClientActionBoundary(behavior);
	}

	CompletableFuture<Map<String, Object>> execute(String type, Map<String, Object> arguments,
		GenericClientActivityContext context, GenericClientActionBoundary.Ticket ticket)
	{
		if (type.equals("walk.to") || type.equals("walk.step"))
			return dispatch(type, arguments, context.withTicket(ticket));
		boolean discretionary = !type.startsWith("safety.") && !type.equals("client.behaviors.configure")
			&& !type.equals("mouse.offscreen");
		GenericClientActivityContext policy = (discretionary ? context : GenericClientActivityContext.none()).withTicket(ticket);
		return boundary.execute(ticket, policy,
			() -> dispatch(type, arguments, context.withTicket(ticket)),
			discretionary);
	}

	private CompletableFuture<Map<String, Object>> dispatch(String type, Map<String, Object> args,
		GenericClientActivityContext context)
	{
		switch (type)
		{
			case "walk.random":
				return random.walk(context).thenApply(GenericClientInteractionResult::toReceipt);
			case "walk.click":
				return click.click(world(args), context).thenApply(GenericClientInteractionResult::toReceipt);
			case "walk.to":
			case "walk.step":
				Map<String, Object> journey = new java.util.LinkedHashMap<>(args);
				journey.keySet().removeAll(java.util.Set.of("timeout_ticks", "activity", "policy", "humanize"));
				return walk.walkTo(GenericClientWalkRequest.parse(journey, integer(args, "timeout_ticks", 600), context),
					clicks(type.equals("walk.step")));
			case "npc.interact":
				return npc.interact(args.containsKey("id") ? integer(args, "id", 0) : null,
					args.containsKey("index") ? integer(args, "index", 0) : null,
					args.containsKey("identity") ? ((Number)args.get("identity")).longValue() : null,
					(String) args.get("name"), (String) args.get("action"), integer(args, "within", 32), context);
			case "combat.set_style":
				return combat.setMode(integer(args, "style", 0), context);
			case "combat.set_auto_retaliate":
				return combat.setMode(Boolean.TRUE.equals(args.get("enabled")) ? 5 : 4, context);
			case "mouse.offscreen":
				return behavior.moveMouseOffscreen(context).thenApply(result -> Map.of("status", "moved", "result", result));
			default:
				return operations.execute(type, args, context);
		}
	}

	private GenericClientWalker.ClickBoundary clicks(boolean singleStep)
	{
		return new GenericClientWalker.ClickBoundary()
		{
			@Override
			public CompletableFuture<GenericClientInteractionResult> execute(
				GenericClientActivityContext input, Supplier<CompletableFuture<GenericClientInteractionResult>> action)
			{
				AtomicReference<GenericClientInteractionResult> observed = new AtomicReference<>();
				return boundary.execute(input.inputTicket(), input, () -> action.get().thenApply(result ->
				{
					observed.set(result);
					return result.toReceipt();
				}), true).thenApply(receipt ->
				{
					GenericClientInteractionResult result = observed.get();
					return result == null
						? new GenericClientInteractionResult(null, "WALK_TILE_CLICK_CANCELLED", false,
							Collections.emptyMap(), Collections.emptyMap())
						: result.withBehavior(receipt);
				});
			}

			@Override public int nextClickDelayTicks() { return behavior.nextWalkClickDelayTicks(); }
			@Override public double nextReachFraction() { return behavior.nextWalkReachFraction(); }
			@Override public boolean singleStep() { return singleStep; }
		};
	}

	private static int integer(Map<String, Object> value, String key, int defaultValue)
	{
		return ((Number) value.getOrDefault(key, defaultValue)).intValue();
	}

	private static WorldPoint world(Map<String, Object> value)
	{
		return new WorldPoint(((Number) value.get("x")).intValue(), ((Number) value.get("y")).intValue(),
			integer(value, "plane", 0));
	}

	@FunctionalInterface interface WalkRandomAction
	{
		CompletableFuture<GenericClientInteractionResult> walk(GenericClientActivityContext context);
	}
	@FunctionalInterface interface WalkClickAction
	{
		CompletableFuture<GenericClientInteractionResult> click(WorldPoint destination, GenericClientActivityContext context);
	}
	@FunctionalInterface interface WalkToAction
	{
		CompletableFuture<Map<String, Object>> walkTo(GenericClientWalkRequest request,
			GenericClientWalker.ClickBoundary boundary);
	}
	@FunctionalInterface interface NpcInteractAction
	{
		CompletableFuture<Map<String, Object>> interact(Integer id, Integer index, Long identity, String name, String action, int within,
			GenericClientActivityContext context);
	}
	@FunctionalInterface interface CombatModeAction
	{
		CompletableFuture<Map<String, Object>> setMode(int mode, GenericClientActivityContext context);
	}
	@FunctionalInterface interface QuestAction
	{
		CompletableFuture<Map<String, Object>> execute(String type, Map<String, Object> arguments,
			GenericClientActivityContext context);
	}
}
