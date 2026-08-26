package com.genericclient;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class GenericClientBehaviorOverrides
{
	static final String SCHEMA = "genericclient_behavior_overrides.v1";

	private String schema = SCHEMA;
	private double shortReleaseProbability;
	private double shortBodyMedianSeconds;
	private double shortTailProbability;
	private double longCadenceMinutes;
	private double longMedianMinutes;
	private double phaseShortChances;
	private GenericClientBehaviorProfile.LongBreakMode favoredLongBreakMode;
	private double oppositeLongBreakProbability;
	private GenericClientBehaviorProfile.Edge idleEdge;

	private GenericClientBehaviorOverrides()
	{
	}

	GenericClientBehaviorOverrides(
		double shortReleaseProbability,
		double shortBodyMedianSeconds,
		double shortTailProbability,
		double longCadenceMinutes,
		double longMedianMinutes,
		double phaseShortChances,
		GenericClientBehaviorProfile.LongBreakMode favoredLongBreakMode,
		double oppositeLongBreakProbability,
		GenericClientBehaviorProfile.Edge idleEdge)
	{
		this.shortReleaseProbability = shortReleaseProbability;
		this.shortBodyMedianSeconds = shortBodyMedianSeconds;
		this.shortTailProbability = shortTailProbability;
		this.longCadenceMinutes = longCadenceMinutes;
		this.longMedianMinutes = longMedianMinutes;
		this.phaseShortChances = phaseShortChances;
		this.favoredLongBreakMode = favoredLongBreakMode;
		this.oppositeLongBreakProbability = oppositeLongBreakProbability;
		this.idleEdge = idleEdge;
		validate();
	}

	static GenericClientBehaviorOverrides fromProfile(GenericClientBehaviorProfile profile)
	{
		return new GenericClientBehaviorOverrides(
			profile.getShortReleaseProbability(),
			profile.getShortBodyMedianSeconds(),
			profile.getShortTailProbability(),
			profile.getLongCadenceMinutes(),
			profile.getLongMedianMinutes(),
			profile.getPhaseShortChances(),
			profile.getFavoredLongBreakMode(),
			profile.getOppositeLongBreakProbability(),
			profile.getIdleEdge());
	}

	void validate()
	{
		if (!SCHEMA.equals(schema))
		{
			throw new IllegalArgumentException("Unsupported behavior override schema");
		}
		requireRange(shortReleaseProbability, 0.0, 1.0, "Micro chance");
		requireRange(shortBodyMedianSeconds, 1.0, 119.0, "Typical micro duration");
		requireRange(shortTailProbability, 0.0, 1.0, "Long micro chance");
		requireRange(longCadenceMinutes, 20.0, 1_440.0, "Long-break interval");
		requireRange(longMedianMinutes, 3.0, 60.0, "Typical long-break duration");
		requireRange(phaseShortChances, 1.0, 4.0, "Phase boost");
		requireRange(oppositeLongBreakProbability, 0.0, 0.5, "Long-style switch chance");
		if (favoredLongBreakMode == null)
		{
			throw new IllegalArgumentException("Preferred long-break style is required");
		}
		if (idleEdge == null)
		{
			throw new IllegalArgumentException("Idle edge is required");
		}
	}

	double getShortReleaseProbability()
	{
		return shortReleaseProbability;
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

	Map<String, Object> toMap()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("schema", schema);
		value.put("short_release_probability", shortReleaseProbability);
		value.put("short_body_median_seconds", shortBodyMedianSeconds);
		value.put("short_tail_probability", shortTailProbability);
		value.put("long_cadence_minutes", longCadenceMinutes);
		value.put("long_median_minutes", longMedianMinutes);
		value.put("phase_short_chances", phaseShortChances);
		value.put("favored_long_break_mode", favoredLongBreakMode.name().toLowerCase(Locale.ROOT));
		value.put("opposite_long_break_probability", oppositeLongBreakProbability);
		value.put("idle_edge", idleEdge.name().toLowerCase(Locale.ROOT));
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
