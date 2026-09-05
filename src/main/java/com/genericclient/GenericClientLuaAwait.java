package com.genericclient;

import static com.genericclient.GenericClientLuaScript.normalizeLuaValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/** Validated await payload and its coroutine-local parser; request identities survive REPL reuse. */
final class GenericClientLuaAwait
{
	private static final int DEFAULT_RANDOM_ACTION_TIMEOUT_TICKS = 8;
	private static final int DEFAULT_NPC_ACTION_TIMEOUT_TICKS = 20;
	private static final int DEFAULT_BANK_ACTION_TIMEOUT_TICKS = 200;
	private static final int DEFAULT_GE_ACTION_TIMEOUT_TICKS = 300;
	private static final int DEFAULT_WALK_ACTION_TIMEOUT_TICKS = 600;

	GenericClientWalkRequest walkRequest;
	volatile GenericClientActionBoundary.Ticket ticket = new GenericClientActionBoundary.Ticket();
	final Kind kind;
	final long requestId;
	final String actionType;
	final WorldPoint destination;
	final int within;
	final Integer targetId;
	final String targetName;
	final String targetAction;
	final GenericClientActivityContext activityContext;
	final String phaseName;
	final Map<String, Object> questAction;
	volatile int remainingTicks;
	boolean dispatched;

	GenericClientLuaAwait(
		Kind kind,
		long requestId,
		int remainingTicks,
		String actionType,
		WorldPoint destination,
		int within,
		Integer targetId,
		String targetName,
		String targetAction,
		GenericClientActivityContext activityContext,
		String phaseName,
		Map<String, Object> questAction)
	{
		this.kind = kind;
		this.requestId = requestId;
		this.remainingTicks = remainingTicks;
		this.actionType = actionType;
		this.destination = destination;
		this.within = within;
		this.targetId = targetId;
		this.targetName = targetName;
		this.targetAction = targetAction;
		this.activityContext = activityContext;
		this.phaseName = phaseName;
		this.questAction = questAction;
	}

	private static GenericClientLuaAwait gameTick(GenericClientActivityContext context)
	{
		return new GenericClientLuaAwait(Kind.GAME_TICK, 0, 0, null, null, 0, null, null, null,
			context, null, null);
	}

	private static GenericClientLuaAwait ticks(int ticks, GenericClientActivityContext context)
	{
		return new GenericClientLuaAwait(Kind.TICKS, 0, ticks, null, null, 0, null, null, null,
			context, null, null);
	}

	private static GenericClientLuaAwait randomAction(
		long requestId,
		int timeoutTicks,
		GenericClientActivityContext activityContext)
	{
		return new GenericClientLuaAwait(
			Kind.ACTION, requestId, timeoutTicks, "walk.random", null, 0,
			null, null, null, activityContext, null, null);
	}

	private static GenericClientLuaAwait walkAction(long requestId, GenericClientWalkRequest request)
	{
		GenericClientLuaAwait wait = new GenericClientLuaAwait(Kind.ACTION, requestId, request.timeoutTicks, "walk.to", request.destination,
			request.within, null, null, null, request.activityContext, null, null);
		wait.walkRequest = request;
		return wait;
	}

	private static GenericClientLuaAwait clickAction(
		long requestId,
		int timeoutTicks,
		WorldPoint destination,
		GenericClientActivityContext activityContext)
	{
		return new GenericClientLuaAwait(
			Kind.ACTION, requestId, timeoutTicks, "walk.click", destination, 0,
			null, null, null, activityContext, null, null);
	}

	private static GenericClientLuaAwait npcAction(
		long requestId,
		int timeoutTicks,
		Integer id,
		String name,
		String action,
		int within,
		GenericClientActivityContext activityContext)
	{
		return new GenericClientLuaAwait(
			Kind.ACTION, requestId, timeoutTicks, "npc.interact", null, within,
			id, name, action, activityContext, null, null);
	}

	private static GenericClientLuaAwait questAction(
		long requestId,
		int timeoutTicks,
		String type,
		Map<String, Object> action,
		GenericClientActivityContext activityContext)
	{
		return new GenericClientLuaAwait(
			Kind.ACTION, requestId, timeoutTicks, type, null, 0,
			null, null, null, activityContext, null, action);
	}

	private static GenericClientLuaAwait combatStyle(
		long requestId,
		int timeoutTicks,
		int style,
		GenericClientActivityContext activityContext)
	{
		return new GenericClientLuaAwait(
			Kind.ACTION, requestId, timeoutTicks, "combat.set_style", null, style,
			null, null, null, activityContext, null, null);
	}

	private static GenericClientLuaAwait combatAutoRetaliate(
		long requestId,
		int timeoutTicks,
		boolean enabled,
		GenericClientActivityContext activityContext)
	{
		return new GenericClientLuaAwait(
			Kind.ACTION, requestId, timeoutTicks, "combat.set_auto_retaliate", null,
				enabled ? 1 : 0, null, null, null, activityContext, null, null);
	}

	private static GenericClientLuaAwait mouseOffscreen(long requestId, int timeoutTicks)
	{
		return new GenericClientLuaAwait(
			Kind.ACTION, requestId, timeoutTicks, "mouse.offscreen", null, 0,
				null, null, null, GenericClientActivityContext.none(), null, null);
	}

	private static GenericClientLuaAwait phase(
		long requestId,
		String name,
		GenericClientActivityContext activityContext)
	{
		return new GenericClientLuaAwait(Kind.PHASE, requestId, 0, null, null, 0,
			null, null, null, activityContext, name, null);
	}
	enum Kind
	{
		GAME_TICK,
		TICKS,
		ACTION,
		PHASE
	}


	static final class Parser
	{
		private static final Set<String> FIELDS = Set.of(
			"action", "ticks", "event", "phase", "timeout", "activity", "policy", "humanize");
		private long nextRequestId;
		GenericClientLuaAwait parse(Object yieldedValue, GenericClientActivityContext currentActivity, boolean operator)
		{
			Map<?, ?> request = awaitRequest(yieldedValue);
			Object action = request.get("action");
			GenericClientActivityContext context = activityContext(request,
				action instanceof Map ? (Map<?, ?>) action : Collections.emptyMap(), currentActivity, operator);

			Object ticks = request.get("ticks");
			if (ticks instanceof Number)
			{
				return parseTickWait((Number) ticks, context);
			}

			Object event = request.get("event");
			if (event instanceof String)
			{
				return parseEventWait((String) event, context);
			}

			if (action instanceof Map)
			{
				return parseActionWait(
					request, (Map<?, ?>) action, context);
			}

			Object phase = request.get("phase");
			if (phase instanceof String)
			{
				return parsePhaseWait((String) phase, context);
			}

			throw new IllegalArgumentException("Await request must contain ticks, event, action, or phase");
		}

		private static Map<?, ?> awaitRequest(Object yieldedValue)
		{
			if (!(yieldedValue instanceof Map))
			{
				throw new IllegalArgumentException("Script yielded an invalid await request");
			}
			Map<?, ?> envelope = (Map<?, ?>) yieldedValue;
			if (!"gc.await.v1".equals(envelope.get("protocol")) || !(envelope.get("request") instanceof Map))
			{
				throw new IllegalArgumentException("Script yielded an invalid await envelope");
			}
			Map<?, ?> request = (Map<?, ?>) envelope.get("request");
			for (Object field : request.keySet())
				if (!FIELDS.contains(field)) throw new IllegalArgumentException("Unknown await field: " + field);
			return request;
		}

		private static GenericClientLuaAwait parseTickWait(Number value, GenericClientActivityContext context)
		{
			int ticks = value.intValue();
			if (ticks < 1)
			{
				throw new IllegalArgumentException("Tick wait must be positive");
			}
			return GenericClientLuaAwait.ticks(ticks, context);
		}

		private static GenericClientLuaAwait parseEventWait(String event, GenericClientActivityContext context)
		{
			if (!"game.tick".equals(event))
			{
				throw new IllegalArgumentException("Unsupported event: " + event);
			}
			return GenericClientLuaAwait.gameTick(context);
		}

		private GenericClientLuaAwait parseActionWait(
			Map<?, ?> request,
			Map<?, ?> action,
			GenericClientActivityContext context)
		{
			Object typeValue = action.get("type");
			if (!(typeValue instanceof String))
			{
				throw new IllegalArgumentException("Action requires a type string");
			}
			String type = (String) typeValue;
			int timeout = actionTimeout(request, type);

			switch (type)
			{
				case "intent.begin":
				case "intent.end":
					String intentName = requiredText(action, "name", type);
					if (action.containsKey("failed") && !(action.get("failed") instanceof Boolean))
						throw new IllegalArgumentException("intent failed must be true or false");
					Map<String, Object> intent = new LinkedHashMap<>();
					intent.put("name", intentName);
					intent.put("failed", Boolean.TRUE.equals(action.get("failed")));
					return GenericClientLuaAwait.questAction(++nextRequestId, timeout, type, intent,
						context);
				case "walk.random":
					return GenericClientLuaAwait.randomAction(
						++nextRequestId,
						timeout,
						context);
				case "mouse.offscreen":
					return GenericClientLuaAwait.mouseOffscreen(++nextRequestId, timeout);
				case "walk.click":
					return GenericClientLuaAwait.clickAction(++nextRequestId, timeout, walkPoint(action.get("destination"), "destination"),
						context);
				case "walk.to":
					return parseWalkAction(action, timeout, context);
				case "npc.interact":
					return parseNpcAction(action, timeout, context);
				case "combat.set_style":
					return parseCombatStyle(action, timeout, context);
				case "combat.set_auto_retaliate":
					return parseAutoRetaliate(action, timeout, context);
				default:
					if (isQuestAction(type))
					{
						return GenericClientLuaAwait.questAction(
							++nextRequestId,
							timeout,
							type,
							copyAction(action),
							context);
					}
					throw new IllegalArgumentException("Unsupported action: " + type);
			}
		}

		private static int actionTimeout(Map<?, ?> request, String type)
		{
			int timeout = defaultActionTimeout(type);
			Object timeoutValue = request.get("timeout");
			if (timeoutValue instanceof Map)
			{
				Object gameTicks = ((Map<?, ?>) timeoutValue).get("game_ticks");
				if (gameTicks instanceof Number)
				{
					timeout = ((Number) gameTicks).intValue();
				}
			}
			if (timeout < 1)
			{
				throw new IllegalArgumentException("Action timeout must be positive");
			}
			return timeout;
		}

		private static int defaultActionTimeout(String type)
		{
			switch (type)
			{
				case "walk.to":
				case "walk.click":
					return DEFAULT_WALK_ACTION_TIMEOUT_TICKS;
				case "bank.loadout":
					return DEFAULT_BANK_ACTION_TIMEOUT_TICKS;
				case "ge.buy":
					return DEFAULT_GE_ACTION_TIMEOUT_TICKS;
				case "npc.interact":
				case "combat.set_style":
				case "combat.set_auto_retaliate":
					return DEFAULT_NPC_ACTION_TIMEOUT_TICKS;
				default:
					return isQuestAction(type)
						? DEFAULT_NPC_ACTION_TIMEOUT_TICKS
						: DEFAULT_RANDOM_ACTION_TIMEOUT_TICKS;
			}
		}

		private GenericClientLuaAwait parseWalkAction(
			Map<?, ?> action, int timeout, GenericClientActivityContext context)
		{
			return GenericClientLuaAwait.walkAction(++nextRequestId, GenericClientWalkRequest.parse(copyAction(action), timeout,
				context));
		}

		private GenericClientLuaAwait parseNpcAction(
			Map<?, ?> action,
			int timeout,
			GenericClientActivityContext context)
		{
			Integer id = optionalNonNegativeInt(action, "id", "npc.interact");
			String name = optionalText(action.get("name"));
			if (id == null && name == null)
			{
				throw new IllegalArgumentException("npc.interact requires id or name");
			}
			String option = requiredText(action, "action", "npc.interact");
			int within = action.get("within") instanceof Number
				? ((Number) action.get("within")).intValue()
				: 15;
			if (within < 1 || within > 32)
			{
				throw new IllegalArgumentException("npc.interact within must be between 1 and 32");
			}
			return GenericClientLuaAwait.npcAction(
				++nextRequestId,
				timeout,
				id,
				name,
				option,
				within,
				context);
		}

		private GenericClientLuaAwait parseCombatStyle(
			Map<?, ?> action,
			int timeout,
			GenericClientActivityContext context)
		{
			Object styleValue = action.get("style");
			if (!(styleValue instanceof Number))
			{
				throw new IllegalArgumentException("combat.set_style requires a numeric style");
			}
			int style = ((Number) styleValue).intValue();
			if (style < 0 || style > 3)
			{
				throw new IllegalArgumentException("combat.set_style style must be between 0 and 3");
			}
			return GenericClientLuaAwait.combatStyle(
				++nextRequestId,
				timeout,
				style,
				context);
		}

		private GenericClientLuaAwait parseAutoRetaliate(
			Map<?, ?> action,
			int timeout,
			GenericClientActivityContext context)
		{
			Object enabledValue = action.get("enabled");
			if (!(enabledValue instanceof Boolean))
			{
				throw new IllegalArgumentException(
					"combat.set_auto_retaliate requires enabled=true or enabled=false");
			}
			return GenericClientLuaAwait.combatAutoRetaliate(
				++nextRequestId,
				timeout,
				(Boolean) enabledValue,
				context);
		}

		private GenericClientLuaAwait parsePhaseWait(
			String value, GenericClientActivityContext context)
		{
			String phase = value.trim();
			if (!phase.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"))
			{
				throw new IllegalArgumentException(
					"Phase name must be 1-64 letters, numbers, dots, underscores, or hyphens");
			}
			return GenericClientLuaAwait.phase(
				++nextRequestId,
				phase,
				context);
		}

		private static GenericClientActivityContext activityContext(
			Map<?, ?> request, Map<?, ?> action, GenericClientActivityContext declared, boolean operator)
		{
			GenericClientActivityContext context = declared;
			if (request.containsKey("activity"))
			{
				if (!(request.get("activity") instanceof String))
					throw new IllegalArgumentException("activity must be a string");
				context = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.fromName(
					(String) request.get("activity")));
			}
			if (context == null) context = GenericClientActivityContext.preset(actionActivity(action));
			context = context.withPolicy(request.get("policy"));
			if (request.containsKey("humanize") && !(request.get("humanize") instanceof Boolean))
				throw new IllegalArgumentException("humanize must be true or false");
			boolean humanize = request.containsKey("humanize") ? (Boolean) request.get("humanize") : !operator;
			return humanize ? context : context.plain();
		}

		private static GenericClientActivityContext.Activity actionActivity(Map<?, ?> action)
		{
			String type = String.valueOf(action.get("type"));
			String option = String.valueOf(action.get("action"));
			boolean npc = "npc.interact".equals(type);
			if ("bank.loadout".equals(type) || npc && "Bank".equalsIgnoreCase(option))
				return GenericClientActivityContext.Activity.BANKING;
			if ("ge.buy".equals(type) || npc && "Exchange".equalsIgnoreCase(option))
				return GenericClientActivityContext.Activity.TRADING;
			if (type.startsWith("combat.") || npc && "Attack".equalsIgnoreCase(option))
				return GenericClientActivityContext.Activity.COMBAT;
			if (type.startsWith("dialogue.") || npc && "Talk-to".equalsIgnoreCase(option))
				return GenericClientActivityContext.Activity.DIALOGUE;
			if (type.startsWith("walk.") || type.startsWith("travel."))
				return GenericClientActivityContext.Activity.TRAVEL;
			return GenericClientActivityContext.Activity.GENERAL;
		}

		private static WorldPoint walkPoint(Object value, String label)
		{
			return GenericClientWalkRequest.point(value, label);
		}

		private static boolean isQuestAction(String type)
		{
			return "object.interact".equals(type) ||
				"checkpoint.get".equals(type) ||
				"checkpoint.set".equals(type) ||
				"checkpoint.clear".equals(type) ||
				"item.interact".equals(type) ||
				"equipment.interact".equals(type) ||
				"item.use_on_object".equals(type) ||
				"item.use_on_npc".equals(type) ||
				"item.use_on_item".equals(type) ||
				"ground_item.take".equals(type) ||
				"dialogue.continue".equals(type) ||
				"dialogue.choose".equals(type) ||
				"bank.loadout".equals(type) ||
				"ge.buy".equals(type) ||
				"travel.home_teleport".equals(type) ||
				"combat.cast".equals(type) ||
				"spell.cast_on_item".equals(type) ||
				"combat.set_autocast".equals(type) ||
				"prayer.set".equals(type) ||
				"ui.close".equals(type) ||
				"ui.click".equals(type) ||
				"ui.key".equals(type) ||
				"world.select".equals(type) ||
				"consumable.cure_poison".equals(type) ||
				"client.behaviors.configure".equals(type) ||
				"safety.configure".equals(type) ||
				"safety.clear".equals(type) ||
				"safety.recover".equals(type) ||
				"safety.escape".equals(type);
		}

		private static Map<String, Object> copyAction(Map<?, ?> value)
		{
			Map<String, Object> result = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : value.entrySet())
			{
				if (!(entry.getKey() instanceof String))
				{
					throw new IllegalArgumentException("Action keys must be strings");
				}
				result.put((String) entry.getKey(), normalizeLuaValue(entry.getValue()));
			}
			return Collections.unmodifiableMap(result);
		}

		private static String requiredText(Map<?, ?> value, String key, String actionType)
		{
			Object raw = value.get(key);
			if (!(raw instanceof String) || ((String) raw).trim().isEmpty())
			{
				throw new IllegalArgumentException(actionType + " requires a non-empty " + key);
			}
			return ((String) raw).trim();
		}

		private static Integer optionalNonNegativeInt(Map<?, ?> value, String key, String actionType)
		{
			Object raw = value.get(key);
			if (raw == null)
			{
				return null;
			}
			if (!(raw instanceof Number))
			{
				throw new IllegalArgumentException(actionType + " " + key + " must be numeric");
			}
			int result = ((Number) raw).intValue();
			if (result < 0)
			{
				throw new IllegalArgumentException(actionType + " " + key + " cannot be negative");
			}
			return result;
		}

		private static String optionalText(Object raw)
		{
			if (raw == null)
			{
				return null;
			}
			if (!(raw instanceof String) || ((String) raw).trim().isEmpty())
			{
				throw new IllegalArgumentException("Optional text values must be non-empty strings");
			}
			return ((String) raw).trim();
		}

	}
}
