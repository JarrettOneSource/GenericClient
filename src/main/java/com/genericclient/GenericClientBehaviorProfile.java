package com.genericclient;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class GenericClientBehaviorProfile
{
	static final String SCHEMA = "genericclient_behavior_profile.v1";
	static final double SHORT_DURATION_MIN_SECONDS = 1.0;
	static final double SHORT_DURATION_MAX_SECONDS = 120.0;
	static final double LONG_DURATION_MIN_MINUTES = 3.0;
	static final double LONG_DURATION_MAX_MINUTES = 60.0;
	static final int DEFAULT_MOUSE_MOVE_DURATION_MILLIS = 432;
	static final int MOUSE_MOVE_DURATION_MIN_MILLIS = 150;
	static final int MOUSE_MOVE_DURATION_MAX_MILLIS = 1_200;
	static final int DEFAULT_TYPING_WORDS_PER_MINUTE = 55;
	static final int TYPING_WORDS_PER_MINUTE_MIN = 20;
	static final int TYPING_WORDS_PER_MINUTE_MAX = 180;

	private static final String DOMAIN = "genericclient.behavior.v1";
	private static final double[] SHORT_QUANTILES = {0.0, 0.10, 0.25, 0.50, 0.75, 0.90, 1.0};
	private static final double[] SHORT_PROBABILITIES = {0.02, 0.05, 0.12, 0.35, 0.65, 0.85, 1.0};
	private static final double REFERENCE_ELIGIBLE_INTERACTIONS_PER_HOUR = 36.0;

	private final String id;
	private final double shortReleaseProbability;
	private final double shortBodyMedianSeconds;
	private final double shortTailProbability;
	private final double longCadenceMinutes;
	private final double longRefractoryMinutes;
	private final double longScaleMinutes;
	private final double longMedianMinutes;
	private final double phaseShortChances;
	private final double phaseLongBonusMaximum;
	private final LongBreakMode favoredLongBreakMode;
	private final double oppositeLongBreakProbability;
	private final Edge idleEdge;
	private final int mouseMoveDurationMillis;
	private final int typingWordsPerMinute;
	private final String title;
	private final String summary;
	private final double referenceDowntimePercent;
	private final boolean customized;

	private GenericClientBehaviorProfile(
		String id,
		double shortReleaseProbability,
		double shortBodyMedianSeconds,
		double shortTailProbability,
		double longCadenceMinutes,
		double longRefractoryMinutes,
		double longScaleMinutes,
		double longMedianMinutes,
		double phaseShortChances,
		double phaseLongBonusMaximum,
		LongBreakMode favoredLongBreakMode,
		double oppositeLongBreakProbability,
		Edge idleEdge,
		int mouseMoveDurationMillis,
		int typingWordsPerMinute,
		boolean customized)
	{
		this.id = id;
		this.shortReleaseProbability = shortReleaseProbability;
		this.shortBodyMedianSeconds = shortBodyMedianSeconds;
		this.shortTailProbability = shortTailProbability;
		this.longCadenceMinutes = longCadenceMinutes;
		this.longRefractoryMinutes = longRefractoryMinutes;
		this.longScaleMinutes = longScaleMinutes;
		this.longMedianMinutes = longMedianMinutes;
		this.phaseShortChances = phaseShortChances;
		this.phaseLongBonusMaximum = phaseLongBonusMaximum;
		this.favoredLongBreakMode = favoredLongBreakMode;
		this.oppositeLongBreakProbability = oppositeLongBreakProbability;
		this.idleEdge = idleEdge;
		this.mouseMoveDurationMillis = mouseMoveDurationMillis;
		this.typingWordsPerMinute = typingWordsPerMinute;
		this.customized = customized;
		this.referenceDowntimePercent = calculateReferenceDowntimePercent();
		this.title = buildTitle();
		this.summary = buildSummary();
	}

	static GenericClientBehaviorProfile fromAccountHash(long accountHash)
	{
		if (accountHash == -1L)
		{
			throw new IllegalArgumentException("Account hash is unavailable before login");
		}

		double styleZ = inverseNormal(unit(accountHash, "attention.style"));
		double shortQuantile = correlatedUnit(accountHash, "short.cadence", styleZ, 0.60);
		double shortDurationQuantile = correlatedUnit(accountHash, "short.duration", styleZ, 0.30);
		double longQuantile = correlatedUnit(accountHash, "long.cadence", styleZ, 0.40);
		double phaseQuantile = correlatedUnit(accountHash, "phase.sensitivity", styleZ, 0.50);
		double longDurationQuantile = unit(accountHash, "long.duration");
		double mouseDurationQuantile = correlatedUnit(accountHash, "mouse.duration", styleZ, 0.35);
		double typingQuantile = correlatedUnit(accountHash, "typing.wpm", styleZ, 0.20);

		double longCadence = 300.0 * Math.exp(-2.015 * longQuantile);
		double longRefractory = clamp(0.30 * longCadence, 10.0, 60.0);
		LongBreakMode favorite = unit(accountHash, "long.mode") < 0.5
			? LongBreakMode.AFK
			: LongBreakMode.LOGOUT;
		double oppositeUnit = unit(accountHash, "long.mode.opposite");

		return new GenericClientBehaviorProfile(
			profileId(accountHash),
			interpolate(shortQuantile, SHORT_QUANTILES, SHORT_PROBABILITIES),
			2.0 + 4.0 * shortDurationQuantile,
			0.01 + 0.03 * shortDurationQuantile,
			longCadence,
			longRefractory,
			(longCadence - longRefractory) / 0.886226925452758,
			7.0 + 15.0 * longDurationQuantile,
			1.0 + 3.0 * phaseQuantile,
			1.5 * phaseQuantile,
			favorite,
			0.02 + 0.13 * oppositeUnit * oppositeUnit,
			Edge.values()[(int) Math.floor(unit(accountHash, "idle.edge") * Edge.values().length)],
			roundToStep(300.0 + 350.0 * mouseDurationQuantile, 25),
			roundToStep(35.0 + 65.0 * typingQuantile, 5),
			false);
	}

	GenericClientBehaviorProfile withOverrides(GenericClientBehaviorOverrides overrides)
	{
		overrides.validate();
		double cadence = overrides.getLongCadenceMinutes();
		double refractory = clamp(0.30 * cadence, 10.0, 60.0);
		double phase = overrides.getPhaseShortChances();
		return new GenericClientBehaviorProfile(
			id,
			overrides.getShortReleaseProbability(),
			overrides.getShortBodyMedianSeconds(),
			overrides.getShortTailProbability(),
			cadence,
			refractory,
			(cadence - refractory) / 0.886226925452758,
			overrides.getLongMedianMinutes(),
			phase,
			(phase - 1.0) / 3.0 * 1.5,
			overrides.getFavoredLongBreakMode(),
			overrides.getOppositeLongBreakProbability(),
			overrides.getIdleEdge(),
			overrides.getMouseMoveDurationMillis() == 0
				? mouseMoveDurationMillis
				: overrides.getMouseMoveDurationMillis(),
			overrides.getTypingWordsPerMinute() == 0
				? typingWordsPerMinute
				: overrides.getTypingWordsPerMinute(),
			true);
	}

	String getId()
	{
		return id;
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

	double getLongRefractoryMinutes()
	{
		return longRefractoryMinutes;
	}

	double getLongScaleMinutes()
	{
		return longScaleMinutes;
	}

	double getLongMedianMinutes()
	{
		return longMedianMinutes;
	}

	double getPhaseShortChances()
	{
		return phaseShortChances;
	}

	double getPhaseLongBonusMaximum()
	{
		return phaseLongBonusMaximum;
	}

	LongBreakMode getFavoredLongBreakMode()
	{
		return favoredLongBreakMode;
	}

	double getOppositeLongBreakProbability()
	{
		return oppositeLongBreakProbability;
	}

	Edge getIdleEdge()
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

	String getTitle()
	{
		return title;
	}

	String getSummary()
	{
		return summary;
	}

	double getReferenceDowntimePercent()
	{
		return referenceDowntimePercent;
	}

	boolean isCustomized()
	{
		return customized;
	}

	double cumulativeLongHazard(double activeMinutes)
	{
		double elapsed = Math.max(0.0, activeMinutes - longRefractoryMinutes);
		double scaled = elapsed / longScaleMinutes;
		return scaled * scaled;
	}

	Map<String, Object> toMap()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("schema", SCHEMA);
		value.put("id", id);
		value.put("title", title);
		value.put("summary", summary);
		value.put("customized", customized);
		value.put("short_release_probability", shortReleaseProbability);
		value.put("short_body_median_seconds", shortBodyMedianSeconds);
		value.put("short_tail_probability", shortTailProbability);
		value.put("short_duration_bounds_seconds", bounds(1.0, 120.0));
		value.put("long_cadence_minutes", longCadenceMinutes);
		value.put("long_refractory_minutes", longRefractoryMinutes);
		value.put("long_median_minutes", longMedianMinutes);
		value.put("long_duration_bounds_minutes", bounds(3.0, 60.0));
		value.put("phase_short_chances", phaseShortChances);
		value.put("phase_long_bonus_maximum", phaseLongBonusMaximum);
		value.put("favored_long_break_mode", favoredLongBreakMode.name().toLowerCase(Locale.ROOT));
		value.put("opposite_long_break_probability", oppositeLongBreakProbability);
		value.put("idle_edge", idleEdge.name().toLowerCase(Locale.ROOT));
		value.put("mouse_move_duration_millis", (long) mouseMoveDurationMillis);
		value.put("typing_words_per_minute", (long) typingWordsPerMinute);
		value.put("reference_eligible_interactions_per_hour", REFERENCE_ELIGIBLE_INTERACTIONS_PER_HOUR);
		value.put("reference_forced_downtime_percent", referenceDowntimePercent);
		return value;
	}

	private String buildTitle()
	{
		String shortTitle;
		if (shortReleaseProbability < 0.15)
		{
			shortTitle = "Deep focus";
		}
		else if (shortReleaseProbability < 0.55)
		{
			shortTitle = "Even-keel pacing";
		}
		else
		{
			shortTitle = "Frequent multitasking";
		}

		String longTitle = longCadenceMinutes >= 160.0
			? "rare long breaks"
			: longCadenceMinutes >= 80.0 ? "regular long breaks" : "frequent long breaks";
		return shortTitle + "; " + longTitle;
	}

	private String buildSummary()
	{
		return String.format(Locale.ROOT,
			"Releases client focus after about %.0f%% of composite client interactions. " +
				"Typical forced pauses center near %.1f seconds, with a %.0f%% chance of a 12-120 second tail. " +
				"Long breaks average about %.0f active minutes apart and center near %.1f minutes; usually %s, " +
				"with the opposite choice about %.0f%% of the time. Major phases apply %.1f ordinary short-break chances. " +
				"Recorded mouse paths play over %d milliseconds and text entry averages %d WPM. " +
				"At %.0f eligible actions per hour, estimated forced downtime is %.0f%%.",
			shortReleaseProbability * 100.0,
			shortBodyMedianSeconds,
			shortTailProbability * 100.0,
			longCadenceMinutes,
			longMedianMinutes,
			favoredLongBreakMode == LongBreakMode.AFK ? "remains AFK" : "logs out",
			oppositeLongBreakProbability * 100.0,
			phaseShortChances,
			mouseMoveDurationMillis,
			typingWordsPerMinute,
			REFERENCE_ELIGIBLE_INTERACTIONS_PER_HOUR,
			referenceDowntimePercent);
	}

	private double calculateReferenceDowntimePercent()
	{
		double bodyMeanSeconds = 1.0 + (shortBodyMedianSeconds - 1.0) * Math.exp(0.125);
		double tailMeanSeconds = 108.0 / Math.log(10.0);
		double shortMeanSeconds = (1.0 - shortTailProbability) * bodyMeanSeconds +
			shortTailProbability * tailMeanSeconds;
		double shortMinutes = REFERENCE_ELIGIBLE_INTERACTIONS_PER_HOUR * shortReleaseProbability *
			shortMeanSeconds / 60.0;
		double longMeanMinutes = 3.0 + (longMedianMinutes - 3.0) * Math.exp(0.125);
		double longMinutes = 60.0 / longCadenceMinutes * longMeanMinutes;
		double forcedMinutes = shortMinutes + longMinutes;
		return 100.0 * forcedMinutes / (60.0 + forcedMinutes);
	}

	private static Map<String, Object> bounds(double minimum, double maximum)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("minimum", minimum);
		value.put("maximum", maximum);
		return value;
	}

	private static double correlatedUnit(long accountHash, String label, double styleZ, double loading)
	{
		double independentZ = inverseNormal(unit(accountHash, label));
		double correlatedZ = loading * styleZ + Math.sqrt(1.0 - loading * loading) * independentZ;
		return normalCdf(correlatedZ);
	}

	private static double interpolate(double value, double[] xs, double[] ys)
	{
		for (int index = 1; index < xs.length; index++)
		{
			if (value <= xs[index])
			{
				double fraction = (value - xs[index - 1]) / (xs[index] - xs[index - 1]);
				return ys[index - 1] + fraction * (ys[index] - ys[index - 1]);
			}
		}
		return ys[ys.length - 1];
	}

	private static int roundToStep(double value, int step)
	{
		return (int) Math.round(value / step) * step;
	}

	private static double unit(long accountHash, String label)
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(DOMAIN.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			digest.update(ByteBuffer.allocate(Long.BYTES).putLong(accountHash).array());
			digest.update((byte) 0);
			byte[] bytes = digest.digest(label.getBytes(StandardCharsets.UTF_8));
			long bits = ByteBuffer.wrap(bytes).getLong() >>> 11;
			return (bits + 0.5) / (double) (1L << 53);
		}
		catch (NoSuchAlgorithmException exception)
		{
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String profileId(long accountHash)
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(DOMAIN.getBytes(StandardCharsets.UTF_8));
			digest.update(ByteBuffer.allocate(Long.BYTES).putLong(accountHash).array());
			byte[] bytes = digest.digest("profile.id".getBytes(StandardCharsets.UTF_8));
			StringBuilder id = new StringBuilder(16);
			for (int index = 0; index < 8; index++)
			{
				id.append(String.format(Locale.ROOT, "%02x", bytes[index] & 0xFF));
			}
			return id.toString();
		}
		catch (NoSuchAlgorithmException exception)
		{
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static double inverseNormal(double probability)
	{
		if (probability <= 0.0 || probability >= 1.0)
		{
			throw new IllegalArgumentException("Normal probability must be between zero and one");
		}

		final double[] a =
		{
			-3.969683028665376e+01, 2.209460984245205e+02,
			-2.759285104469687e+02, 1.383577518672690e+02,
			-3.066479806614716e+01, 2.506628277459239e+00
		};
		final double[] b =
		{
			-5.447609879822406e+01, 1.615858368580409e+02,
			-1.556989798598866e+02, 6.680131188771972e+01,
			-1.328068155288572e+01
		};
		final double[] c =
		{
			-7.784894002430293e-03, -3.223964580411365e-01,
			-2.400758277161838e+00, -2.549732539343734e+00,
			4.374664141464968e+00, 2.938163982698783e+00
		};
		final double[] d =
		{
			7.784695709041462e-03, 3.224671290700398e-01,
			2.445134137142996e+00, 3.754408661907416e+00
		};
		if (probability < 0.02425)
		{
			double q = Math.sqrt(-2.0 * Math.log(probability));
			return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
				((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
		}
		if (probability > 0.97575)
		{
			double q = Math.sqrt(-2.0 * Math.log(1.0 - probability));
			return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
				((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
		}
		double q = probability - 0.5;
		double r = q * q;
		return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
			(((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0);
	}

	private static double normalCdf(double value)
	{
		double absolute = Math.abs(value);
		double t = 1.0 / (1.0 + 0.2316419 * absolute);
		double polynomial = t * (0.319381530 + t * (-0.356563782 +
			t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
		double tail = Math.exp(-0.5 * absolute * absolute) / Math.sqrt(2.0 * Math.PI) * polynomial;
		return value >= 0.0 ? 1.0 - tail : tail;
	}

	private static double clamp(double value, double minimum, double maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	enum Edge
	{
		LEFT,
		RIGHT,
		TOP,
		BOTTOM
	}

	enum LongBreakMode
	{
		AFK,
		LOGOUT
	}
}
