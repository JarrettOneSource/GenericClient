package com.genericclient;

import com.google.gson.annotations.SerializedName;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class GenericClientBehaviorOverrides
{
	static final String SCHEMA = "genericclient_behavior_overrides.v1";

	private String schema = SCHEMA;
	@SerializedName(
		value = "micro_break_probability",
		alternate = {"shortReleaseProbability", "short_release_probability"})
	private double microBreakProbability;
	@SerializedName("cursor_release_probability")
	private Double cursorReleaseProbability;
	private double shortBodyMedianSeconds;
	private double shortTailProbability;
	private double longCadenceMinutes;
	private double longMedianMinutes;
	private double phaseShortChances;
	private GenericClientBehaviorProfile.LongBreakMode favoredLongBreakMode;
	private double oppositeLongBreakProbability;
	private GenericClientBehaviorProfile.Edge idleEdge;
	private int mouseMoveDurationMillis;
	private int typingWordsPerMinute;
	@SerializedName("dialogue_reading_percent")
	private Integer dialogueReadingPercent;

	private GenericClientBehaviorOverrides()
	{
	}

	GenericClientBehaviorOverrides(
		double microBreakProbability,
		double cursorReleaseProbability,
		double shortBodyMedianSeconds,
		double shortTailProbability,
		double longCadenceMinutes,
		double longMedianMinutes,
		double phaseShortChances,
		GenericClientBehaviorProfile.LongBreakMode favoredLongBreakMode,
		double oppositeLongBreakProbability,
		GenericClientBehaviorProfile.Edge idleEdge,
		int mouseMoveDurationMillis,
		int typingWordsPerMinute,
		int dialogueReadingPercent)
	{
		this.microBreakProbability = microBreakProbability;
		this.cursorReleaseProbability = cursorReleaseProbability;
		this.shortBodyMedianSeconds = shortBodyMedianSeconds;
		this.shortTailProbability = shortTailProbability;
		this.longCadenceMinutes = longCadenceMinutes;
		this.longMedianMinutes = longMedianMinutes;
		this.phaseShortChances = phaseShortChances;
		this.favoredLongBreakMode = favoredLongBreakMode;
		this.oppositeLongBreakProbability = oppositeLongBreakProbability;
		this.idleEdge = idleEdge;
		this.mouseMoveDurationMillis = mouseMoveDurationMillis;
		this.typingWordsPerMinute = typingWordsPerMinute;
		this.dialogueReadingPercent = dialogueReadingPercent;
		validate();
	}

	static GenericClientBehaviorOverrides fromProfile(GenericClientBehaviorProfile profile)
	{
		return new GenericClientBehaviorOverrides(
			profile.getMicroBreakProbability(),
			profile.getCursorReleaseProbability(),
			profile.getShortBodyMedianSeconds(),
			profile.getShortTailProbability(),
			profile.getLongCadenceMinutes(),
			profile.getLongMedianMinutes(),
			profile.getPhaseShortChances(),
			profile.getFavoredLongBreakMode(),
			profile.getOppositeLongBreakProbability(),
			profile.getIdleEdge(),
			profile.getMouseMoveDurationMillis(),
			profile.getTypingWordsPerMinute(),
			profile.getDialogueReadingPercent());
	}

	void validate()
	{
		if (!SCHEMA.equals(schema))
		{
			throw new IllegalArgumentException("Unsupported behavior override schema");
		}
		requireRange(microBreakProbability, 0.0, 1.0, "Micro chance");
		if (cursorReleaseProbability != null)
		{
			requireRange(cursorReleaseProbability, 0.0, 1.0, "Cursor release chance");
		}
		requireRange(shortBodyMedianSeconds, 1.0, 119.0, "Typical micro duration");
		requireRange(shortTailProbability, 0.0, 1.0, "Long micro chance");
		requireRange(longCadenceMinutes, 20.0, 1_440.0, "Long-break interval");
		requireRange(longMedianMinutes, 3.0, 60.0, "Typical long-break duration");
		requireRange(phaseShortChances, 1.0, 4.0, "Phase boost");
		requireRange(oppositeLongBreakProbability, 0.0, 0.5, "Long-style switch chance");
		if (mouseMoveDurationMillis != 0 &&
			(mouseMoveDurationMillis < GenericClientBehaviorProfile.MOUSE_MOVE_DURATION_MIN_MILLIS ||
				mouseMoveDurationMillis > GenericClientBehaviorProfile.MOUSE_MOVE_DURATION_MAX_MILLIS))
		{
			throw new IllegalArgumentException("Mouse move duration must be between " +
				GenericClientBehaviorProfile.MOUSE_MOVE_DURATION_MIN_MILLIS + " and " +
				GenericClientBehaviorProfile.MOUSE_MOVE_DURATION_MAX_MILLIS);
		}
		if (typingWordsPerMinute != 0 &&
			(typingWordsPerMinute < GenericClientBehaviorProfile.TYPING_WORDS_PER_MINUTE_MIN ||
				typingWordsPerMinute > GenericClientBehaviorProfile.TYPING_WORDS_PER_MINUTE_MAX))
		{
			throw new IllegalArgumentException("Typing speed must be between " +
				GenericClientBehaviorProfile.TYPING_WORDS_PER_MINUTE_MIN + " and " +
				GenericClientBehaviorProfile.TYPING_WORDS_PER_MINUTE_MAX + " WPM");
		}
		if (dialogueReadingPercent != null &&
			(dialogueReadingPercent < GenericClientBehaviorProfile.DIALOGUE_READING_PERCENT_MIN ||
				dialogueReadingPercent > GenericClientBehaviorProfile.DIALOGUE_READING_PERCENT_MAX))
		{
			throw new IllegalArgumentException("Dialogue reading must be between " +
				GenericClientBehaviorProfile.DIALOGUE_READING_PERCENT_MIN + " and " +
				GenericClientBehaviorProfile.DIALOGUE_READING_PERCENT_MAX + " percent");
		}
		if (favoredLongBreakMode == null)
		{
			throw new IllegalArgumentException("Preferred long-break style is required");
		}
		if (idleEdge == null)
		{
			throw new IllegalArgumentException("Idle edge is required");
		}
	}

	double getMicroBreakProbability()
	{
		return microBreakProbability;
	}

	double getCursorReleaseProbability()
	{
		return cursorReleaseProbability == null
			? microBreakProbability
			: cursorReleaseProbability;
	}

	double getShortBodyMedianSeconds()
	{
		return shortBodyMedianSeconds;
	}

	double getShortTailProbability()
	{
		return shortTailProbability;
	}

	double getLongCadenceMinutes()
	{
		return longCadenceMinutes;
	}

	double getLongMedianMinutes()
	{
		return longMedianMinutes;
	}

	double getPhaseShortChances()
	{
		return phaseShortChances;
	}

	GenericClientBehaviorProfile.LongBreakMode getFavoredLongBreakMode()
	{
		return favoredLongBreakMode;
	}

	double getOppositeLongBreakProbability()
	{
		return oppositeLongBreakProbability;
	}

	GenericClientBehaviorProfile.Edge getIdleEdge()
	{
		return idleEdge;
	}

	int getMouseMoveDurationMillis()
	{
		return mouseMoveDurationMillis;
	}

	int getTypingWordsPerMinute()
	{
		return typingWordsPerMinute;
	}

	Integer getDialogueReadingPercent()
	{
		return dialogueReadingPercent;
	}

	Map<String, Object> toMap()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("schema", schema);
		value.put("micro_break_probability", microBreakProbability);
		value.put("cursor_release_probability", getCursorReleaseProbability());
		value.put("short_body_median_seconds", shortBodyMedianSeconds);
		value.put("short_tail_probability", shortTailProbability);
		value.put("long_cadence_minutes", longCadenceMinutes);
		value.put("long_median_minutes", longMedianMinutes);
		value.put("phase_short_chances", phaseShortChances);
		value.put("favored_long_break_mode", favoredLongBreakMode.name().toLowerCase(Locale.ROOT));
		value.put("opposite_long_break_probability", oppositeLongBreakProbability);
		value.put("idle_edge", idleEdge.name().toLowerCase(Locale.ROOT));
		value.put("mouse_move_duration_millis", (long) mouseMoveDurationMillis);
		value.put("typing_words_per_minute", (long) typingWordsPerMinute);
		if (dialogueReadingPercent != null)
		{
			value.put("dialogue_reading_percent", (long) dialogueReadingPercent);
		}
		return value;
	}

	private static void requireRange(double value, double minimum, double maximum, String label)
	{
		if (!Double.isFinite(value) || value < minimum || value > maximum)
		{
			throw new IllegalArgumentException(label + " must be between " + minimum + " and " + maximum);
		}
	}
}
