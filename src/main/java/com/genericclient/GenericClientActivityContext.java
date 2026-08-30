package com.genericclient;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Immutable behavior policy captured for one composite client interaction. */
final class GenericClientActivityContext
{
	private static final GenericClientActivityContext NONE =
		new GenericClientActivityContext(Activity.GENERAL, false);

	private final Activity activity;
	private final boolean discretionaryBehaviorEnabled;

	private GenericClientActivityContext(Activity activity, boolean discretionaryBehaviorEnabled)
	{
		if (activity == null)
		{
			throw new IllegalArgumentException("Activity is required");
		}
		this.activity = activity;
		this.discretionaryBehaviorEnabled = discretionaryBehaviorEnabled;
	}

	static GenericClientActivityContext of(Activity activity, boolean discretionaryBehaviorEnabled)
	{
		return new GenericClientActivityContext(activity, discretionaryBehaviorEnabled);
	}

	static GenericClientActivityContext general(boolean discretionaryBehaviorEnabled)
	{
		return discretionaryBehaviorEnabled ? of(Activity.GENERAL, true) : NONE;
	}

	static GenericClientActivityContext none()
	{
		return NONE;
	}

	Activity getActivity()
	{
		return activity;
	}

	boolean allowsBreaks()
	{
		return discretionaryBehaviorEnabled && activity.allowsBreaks;
	}

	boolean allowsCursorRelease()
	{
		return discretionaryBehaviorEnabled && activity.allowsCursorRelease;
	}

	boolean isDiscretionaryBehaviorEnabled()
	{
		return discretionaryBehaviorEnabled;
	}

	Map<String, Object> toMap()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("activity", activity.value);
		value.put("breaks", allowsBreaks());
		value.put("cursor_release", allowsCursorRelease());
		value.put("discretionary_behavior", discretionaryBehaviorEnabled);
		return value;
	}

	enum Activity
	{
		GENERAL("general", true, true),
		QUESTING("questing", true, true),
		DIALOGUE("dialogue", false, false),
		TRAVEL("travel", true, true),
		SKILLING("skilling", true, true),
		COMBAT("combat", false, false),
		BANKING("banking", false, false),
		TRADING("trading", false, false);

		private final String value;
		private final boolean allowsBreaks;
		private final boolean allowsCursorRelease;

		Activity(String value, boolean allowsBreaks, boolean allowsCursorRelease)
		{
			this.value = value;
			this.allowsBreaks = allowsBreaks;
			this.allowsCursorRelease = allowsCursorRelease;
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
				"Activity must be general, questing, dialogue, travel, skilling, combat, banking, or trading");
		}
	}
}
