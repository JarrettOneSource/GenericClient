package com.genericclient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleSupplier;

final class GenericClientBehaviorState
{
	static final String SCHEMA = "genericclient_behavior_state.v3";
	private static final int MAX_PHASE_HISTORY = 256;

	private String schema = SCHEMA;
	private String profileId;
	private long totalActiveMillis;
	private long activeMillisSinceLongBreak;
	private double longHazardBudget;
	private double microPressure;
	private double microBudget;
	private String breakType = "none";
	private String longBreakMode = "none";
	private long breakEndEpochMillis;
	private long breakStartedEpochMillis;
	private String breakEndReason = "completed";
	private boolean longBreakDeferred;
	private long longDeferredUntilActiveMillis;
	private long microBreakCount;
	private long longBreakCount;
	private long cursorReleaseCount;
	private boolean suppressNextMicro;
	private long lastGlobalPhaseActiveMillis = Long.MIN_VALUE;
	private Map<String, Long> lastPhaseActiveMillis = new LinkedHashMap<>();
	private long savedAtEpochMillis;

	private GenericClientBehaviorState()
	{
	}

	GenericClientBehaviorState(String profileId, double longHazardBudget, double microBudget)
	{
		this.profileId = profileId;
		this.longHazardBudget = longHazardBudget;
		this.microBudget = microBudget;
	}

	double getMicroPressure() { return microPressure; }
	double getMicroBudget() { return microBudget; }
	void addMicroPressure(double pressure) { microPressure += pressure; }
	void resetMicroPressure(double nextBudget)
	{
		microPressure = 0.0;
		microBudget = nextBudget;
	}

	String getProfileId()
	{
		return profileId;
	}

	long getActiveMillisSinceLongBreak()
	{
		return activeMillisSinceLongBreak;
	}

	void addActiveMillis(long millis)
	{
		long positive = Math.max(0L, millis);
		totalActiveMillis = Math.addExact(totalActiveMillis, positive);
		activeMillisSinceLongBreak = Math.addExact(activeMillisSinceLongBreak, positive);
	}

	long getTotalActiveMillis()
	{
		return totalActiveMillis;
	}

	void resetLongClock(double nextBudget)
	{
		activeMillisSinceLongBreak = 0L;
		longHazardBudget = nextBudget;
		longBreakDeferred = false;
		longDeferredUntilActiveMillis = 0L;
	}

	void deferLongBreak(long refractoryMillis)
	{
		longBreakDeferred = true;
		longDeferredUntilActiveMillis = Math.addExact(totalActiveMillis, refractoryMillis);
	}

	boolean isLongBreakDeferred() { return longBreakDeferred; }
	long getLongDeferredUntilActiveMillis() { return longDeferredUntilActiveMillis; }
	long getBreakStartedEpochMillis() { return breakStartedEpochMillis; }
	String getBreakEndReason() { return breakEndReason; }
	void interruptBreak(String reason) { breakEndReason = reason; }

	double getLongHazardBudget()
	{
		return longHazardBudget;
	}

	String getBreakType()
	{
		return breakType;
	}

	String getLongBreakMode()
	{
		return longBreakMode;
	}

	long getBreakEndEpochMillis()
	{
		return breakEndEpochMillis;
	}

	void startBreak(String type, String mode, long startedEpochMillis, long endEpochMillis)
	{
		breakType = type;
		longBreakMode = mode;
		breakEndEpochMillis = endEpochMillis;
		breakStartedEpochMillis = startedEpochMillis;
		breakEndReason = "completed";
		if ("micro".equals(type))
		{
			microBreakCount++;
		}
		else if ("long".equals(type))
		{
			longBreakCount++;
		}
	}

	void clearBreak()
	{
		breakType = "none";
		longBreakMode = "none";
		breakEndEpochMillis = 0L;
		breakStartedEpochMillis = 0L;
		breakEndReason = "completed";
	}

	long getMicroBreakCount()
	{
		return microBreakCount;
	}

	long getLongBreakCount()
	{
		return longBreakCount;
	}

	void recordCursorRelease()
	{
		cursorReleaseCount++;
	}

	long getCursorReleaseCount()
	{
		return cursorReleaseCount;
	}

	void suppressNextMicro()
	{
		suppressNextMicro = true;
	}

	boolean consumeMicroSuppression()
	{
		boolean suppressed = suppressNextMicro;
		suppressNextMicro = false;
		return suppressed;
	}

	long getLastGlobalPhaseActiveMillis()
	{
		return lastGlobalPhaseActiveMillis;
	}

	Long getLastPhaseActiveMillis(String phase)
	{
		return lastPhaseActiveMillis.get(phase);
	}

	void recordPhase(String phase)
	{
		lastGlobalPhaseActiveMillis = totalActiveMillis;
		if (!lastPhaseActiveMillis.containsKey(phase) && lastPhaseActiveMillis.size() == MAX_PHASE_HISTORY)
		{
			String oldest = lastPhaseActiveMillis.keySet().iterator().next();
			lastPhaseActiveMillis.remove(oldest);
		}
		lastPhaseActiveMillis.put(phase, totalActiveMillis);
	}

	long getSavedAtEpochMillis()
	{
		return savedAtEpochMillis;
	}

	void markSaved(long epochMillis)
	{
		savedAtEpochMillis = epochMillis;
	}

	void migrate(DoubleSupplier initialMicroBudget)
	{
		if ("genericclient_behavior_state.v1".equals(schema))
		{
			schema = "genericclient_behavior_state.v2";
			breakStartedEpochMillis = "none".equals(breakType) ? 0L : Math.min(savedAtEpochMillis, breakEndEpochMillis);
			breakEndReason = "completed";
		}
		if ("genericclient_behavior_state.v2".equals(schema))
		{
			schema = SCHEMA;
			resetMicroPressure(initialMicroBudget.getAsDouble());
		}
	}

	void validate(String expectedProfileId)
	{
		validateIdentity(expectedProfileId);
		validateProgress();
		if (!Double.isFinite(microPressure) || microPressure < 0.0 ||
			!Double.isFinite(microBudget) || microBudget <= 0.0)
			throw new IllegalArgumentException("Behavior state has invalid micro-break pressure");
		validateBreak();
		validatePhaseHistory();
	}

	private void validateIdentity(String expectedProfileId)
	{
		if (!SCHEMA.equals(schema))
		{
			throw new IllegalArgumentException("Unsupported behavior state schema");
		}
		if (!expectedProfileId.equals(profileId))
		{
			throw new IllegalArgumentException("Behavior state belongs to another profile");
		}
	}

	private void validateProgress()
	{
		if (totalActiveMillis < 0L || activeMillisSinceLongBreak < 0L ||
			activeMillisSinceLongBreak > totalActiveMillis ||
			!Double.isFinite(longHazardBudget) || longHazardBudget <= 0.0 ||
			microBreakCount < 0L || longBreakCount < 0L || cursorReleaseCount < 0L ||
			longDeferredUntilActiveMillis < 0L || breakStartedEpochMillis < 0L ||
			breakEndReason == null || breakEndReason.isBlank())
		{
			throw new IllegalArgumentException("Behavior state has invalid long-break progress");
		}
	}

	private void validateBreak()
	{
		if (!("none".equals(breakType) || "micro".equals(breakType) || "long".equals(breakType)))
		{
			throw new IllegalArgumentException("Behavior state has an invalid break type");
		}
		if ("long".equals(breakType) &&
			!("afk".equals(longBreakMode) || "logout".equals(longBreakMode)))
		{
			throw new IllegalArgumentException("Long behavior state has an invalid mode");
		}
		if (!"long".equals(breakType) && !"none".equals(longBreakMode))
		{
			throw new IllegalArgumentException("Non-long behavior state cannot have a long-break mode");
		}
		if (!"none".equals(breakType) && breakEndEpochMillis <= 0L)
		{
			throw new IllegalArgumentException("Active behavior state has no break deadline");
		}
	}

	private void validatePhaseHistory()
	{
		if (lastPhaseActiveMillis == null)
		{
			lastPhaseActiveMillis = new LinkedHashMap<>();
		}
		for (Map.Entry<String, Long> entry : lastPhaseActiveMillis.entrySet())
		{
			if (entry.getKey() == null || entry.getKey().trim().isEmpty() ||
				entry.getValue() == null || entry.getValue() < 0L)
			{
				throw new IllegalArgumentException("Behavior state has invalid phase history");
			}
		}
	}

	Map<String, Object> toMap()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("schema", schema);
		value.put("profile_id", profileId);
		value.put("total_active_millis", totalActiveMillis);
		value.put("active_millis_since_long_break", activeMillisSinceLongBreak);
		value.put("long_hazard_budget", longHazardBudget);
		value.put("micro_pressure", microPressure);
		value.put("micro_budget", microBudget);
		value.put("break_type", breakType);
		value.put("long_break_mode", longBreakMode);
		value.put("break_end_epoch_millis", breakEndEpochMillis);
		value.put("break_started_epoch_millis", breakStartedEpochMillis);
		value.put("break_end_reason", breakEndReason);
		value.put("long_break_deferred", longBreakDeferred);
		value.put("long_deferred_until_active_millis", longDeferredUntilActiveMillis);
		value.put("micro_break_count", microBreakCount);
		value.put("long_break_count", longBreakCount);
		value.put("cursor_release_count", cursorReleaseCount);
		value.put("suppress_next_micro", suppressNextMicro);
		return value;
	}
}
