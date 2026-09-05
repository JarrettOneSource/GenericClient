package com.genericclient;

import java.util.Locale;

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
