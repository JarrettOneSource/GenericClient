package com.genericclient;

import java.util.LinkedHashMap;
import java.util.Map;

final class GenericClientBehaviorState
{
	static final String SCHEMA = "genericclient_behavior_state.v1";
	private static final int MAX_PHASE_HISTORY = 256;

	private String schema = SCHEMA;
	private String profileId;
	private long totalActiveMillis;
	private long activeMillisSinceLongBreak;
	private double longHazardBudget;
	private String breakType = "none";
	private String longBreakMode = "none";
	private long breakEndEpochMillis;
	private long microBreakCount;
	private long longBreakCount;
	private boolean suppressNextMicro;
	private long lastGlobalPhaseActiveMillis = Long.MIN_VALUE;
	private Map<String, Long> lastPhaseActiveMillis = new LinkedHashMap<>();
	private long savedAtEpochMillis;

	private GenericClientBehaviorState()
	{
	}

	GenericClientBehaviorState(String profileId, double longHazardBudget)
	{
		this.profileId = profileId;
		this.longHazardBudget = longHazardBudget;
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
	}

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

	void startBreak(String type, String mode, long endEpochMillis)
	{
		breakType = type;
		longBreakMode = mode;
		breakEndEpochMillis = endEpochMillis;
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
	}

	long getMicroBreakCount()
	{
		return microBreakCount;
	}

	long getLongBreakCount()
	{
		return longBreakCount;
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

	void validate(String expectedProfileId)
	{
		if (!SCHEMA.equals(schema))
		{
			throw new IllegalArgumentException("Unsupported behavior state schema");
		}
		if (!expectedProfileId.equals(profileId))
		{
			throw new IllegalArgumentException("Behavior state belongs to another profile");
		}
		if (totalActiveMillis < 0L || activeMillisSinceLongBreak < 0L ||
			activeMillisSinceLongBreak > totalActiveMillis ||
			!Double.isFinite(longHazardBudget) || longHazardBudget <= 0.0)
		{
			throw new IllegalArgumentException("Behavior state has invalid long-break progress");
		}
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
		value.put("break_type", breakType);
		value.put("long_break_mode", longBreakMode);
		value.put("break_end_epoch_millis", breakEndEpochMillis);
		value.put("micro_break_count", microBreakCount);
		value.put("long_break_count", longBreakCount);
		value.put("suppress_next_micro", suppressNextMicro);
		return value;
	}
}
