package com.genericclient;

import java.util.Locale;
import java.util.Map;

/** Declared behavior and input authority captured for one semantic action. */
final class GenericClientActivityContext
{
	static final int PERFORMANCE_MOUSE_MOVE_DURATION_MILLIS = 180;
	private static final GenericClientActivityContext NONE = preset(Activity.GENERAL).plain();

	private final Activity activity;
	final GenericClientBehaviorPolicy declaredPolicy;
	final boolean humanize;
	final boolean intent;
	private final GenericClientActionBoundary.Ticket ticket;
	private final GenericClientPolicyResolver resolver;

	private GenericClientActivityContext(Activity activity, GenericClientBehaviorPolicy policy,
		boolean humanize, boolean intent, GenericClientActionBoundary.Ticket ticket, GenericClientPolicyResolver resolver)
	{
		this.activity = activity;
		this.declaredPolicy = policy;
		this.humanize = humanize;
		this.intent = intent;
		this.ticket = ticket;
		this.resolver = resolver;
	}

	static GenericClientActivityContext preset(Activity activity)
	{
		return new GenericClientActivityContext(activity, activity.policy, true, false, null, null);
	}

	GenericClientActivityContext withPolicy(Object overrides)
	{
		return new GenericClientActivityContext(activity, declaredPolicy.withOverrides(overrides), humanize, intent, ticket, resolver);
	}

	static GenericClientActivityContext forOperation(String type, Map<String, Object> options,
		GenericClientActivityContext declared, boolean operator)
	{
		if (options.containsKey("breaks")) throw new IllegalArgumentException("Unknown operation option: breaks");
		GenericClientActivityContext context = declared;
		if (options.containsKey("activity"))
		{
			if (!(options.get("activity") instanceof String))
				throw new IllegalArgumentException("activity must be a string");
			context = preset(Activity.fromName((String) options.get("activity")));
		}
		if (context == null) context = preset(actionActivity(type, options));
		context = context.withPolicy(options.get("policy"));
		return booleanOption(options, "humanize", !operator) ? context : context.plain();
	}

	private static boolean booleanOption(Map<String, Object> options, String key, boolean defaultValue)
	{
		if (!options.containsKey(key)) return defaultValue;
		if (!(options.get(key) instanceof Boolean)) throw new IllegalArgumentException(key + " must be true or false");
		return (Boolean) options.get(key);
	}

	private static Activity actionActivity(String type, Map<String, Object> options)
	{
		String action = type.equals("npc.interact") || type.equals("player.interact") ? String.valueOf(options.get("action")) : "";
		if (type.startsWith("bank.") || action.equalsIgnoreCase("Bank")) return Activity.BANKING;
		if (type.startsWith("ge.") || action.equalsIgnoreCase("Exchange") || action.equalsIgnoreCase("Trade with")) return Activity.TRADING;
		if (type.startsWith("combat.") || action.equalsIgnoreCase("Attack")) return Activity.COMBAT;
		if (type.startsWith("dialogue.") || action.equalsIgnoreCase("Talk-to")) return Activity.DIALOGUE;
		if (type.startsWith("walk.") || type.startsWith("travel.")) return Activity.TRAVEL;
		return Activity.GENERAL;
	}

	GenericClientActivityContext plain()
	{
		return new GenericClientActivityContext(activity, declaredPolicy, false, intent, ticket, resolver);
	}

	GenericClientActivityContext withTicket(GenericClientActionBoundary.Ticket ticket)
	{
		return new GenericClientActivityContext(activity, declaredPolicy, humanize, intent, ticket, resolver);
	}

	GenericClientActivityContext inIntent()
	{
		return new GenericClientActivityContext(activity, declaredPolicy, humanize, true, ticket, resolver);
	}

	GenericClientActivityContext withResolver(GenericClientPolicyResolver resolver)
	{
		return new GenericClientActivityContext(activity, declaredPolicy, humanize, intent, ticket, resolver);
	}

	GenericClientPolicyResolver.Resolution resolve()
	{
		return resolver == null ? GenericClientPolicyResolver.declared(this) : resolver.resolve(this);
	}

	GenericClientBehaviorPolicy policy()
	{
		return resolve().policy;
	}

	GenericClientActivityContext openInputScope()
	{
		return withTicket(ticket == null ? new GenericClientActionBoundary.Ticket() : ticket.child());
	}

	GenericClientActivityContext forkInputScope()
	{
		return withTicket(ticket == null ? new GenericClientActionBoundary.Ticket() : ticket.branch());
	}

	GenericClientActionBoundary.Ticket inputTicket() { return ticket; }

	void cancelInput()
	{
		if (ticket != null) ticket.cancel();
	}

	boolean isInputAllowed()
	{
		return ticket == null || ticket.isActive();
	}

	boolean applyIfCurrent(Runnable update)
	{
		if (ticket != null) return ticket.applyIfCurrent(update);
		update.run();
		return true;
	}

	boolean ownsSameInput(GenericClientActivityContext other)
	{
		return this == other || other != null && ticket != null && ticket == other.ticket;
	}

	static GenericClientActivityContext none() { return NONE; }
	Activity getActivity() { return activity; }
	boolean allowsBreaks() { return policy().breaks; }
	boolean allowsCursorRelease() { return policy().cursorRelease != GenericClientBehaviorPolicy.CursorRelease.NONE; }
	boolean refreshesWalkClicks() { return policy().walkRefresh; }

	int mouseMoveDurationMillis(int profileDurationMillis)
	{
		return policy().mouse == GenericClientBehaviorPolicy.Mouse.FAST
			? PERFORMANCE_MOUSE_MOVE_DURATION_MILLIS : profileDurationMillis;
	}

	enum Activity
	{
		GENERAL("general", GenericClientBehaviorPolicy.ROUTINE),
		QUESTING("questing", GenericClientBehaviorPolicy.ROUTINE),
		DIALOGUE("dialogue", GenericClientBehaviorPolicy.GUARDED),
		TRAVEL("travel", GenericClientBehaviorPolicy.TRAVEL),
		HAZARDOUS_TRAVEL("hazardous_travel", GenericClientBehaviorPolicy.HAZARDOUS_TRAVEL),
		SKILLING("skilling", GenericClientBehaviorPolicy.SKILLING),
		COMBAT("combat", GenericClientBehaviorPolicy.COMBAT),
		MANUAL("manual", GenericClientBehaviorPolicy.MANUAL),
		BANKING("banking", GenericClientBehaviorPolicy.GUARDED),
		TRADING("trading", GenericClientBehaviorPolicy.GUARDED);

		private final String value;
		private final GenericClientBehaviorPolicy policy;

		Activity(String value, GenericClientBehaviorPolicy policy)
		{
			this.value = value;
			this.policy = policy;
		}

		String getValue()
		{
			return value;
		}

		static Activity fromName(String value)
		{
			if (value != null)
			{
				String normalized = value.trim().toLowerCase(Locale.ROOT);
				for (Activity candidate : values())
				{
					if (candidate.value.equals(normalized))
					{
						return candidate;
					}
				}
			}
			throw new IllegalArgumentException(
				"Activity must be general, questing, dialogue, travel, hazardous_travel, skilling, combat, manual, banking, or trading");
		}
	}
}
