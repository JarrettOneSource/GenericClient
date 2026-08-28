package com.genericclient;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GenericClientSchedule
{
	private static final int LOOKAHEAD_DAYS = 14;

	private final ZoneId zone;
	private final Map<String, Entry> entries;

	private GenericClientSchedule(ZoneId zone, Map<String, Entry> entries)
	{
		this.zone = zone;
		this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
	}

	static GenericClientSchedule compile(GenericClientAutomationConfig config)
	{
		Map<String, Entry> entries = new LinkedHashMap<>();
		for (Map.Entry<String, GenericClientAutomationConfig.ScheduleSpec> value :
			config.getSchedules().entrySet())
		{
			entries.put(value.getKey(), Entry.from(value.getValue()));
		}
		return new GenericClientSchedule(config.getZoneId(), entries);
	}

	Snapshot evaluate(Instant now)
	{
		Map<String, State> states = new LinkedHashMap<>();
		Instant next = null;
		for (Map.Entry<String, Entry> entry : entries.entrySet())
		{
			State state = entry.getValue().evaluate(now, zone);
			states.put(entry.getKey(), state);
			if (state.nextTransition != null && (next == null || state.nextTransition.isBefore(next)))
			{
				next = state.nextTransition;
			}
		}
		return new Snapshot(zone, states, next);
	}

	static final class Snapshot
	{
		private final ZoneId zone;
		private final Map<String, State> states;
		private final Instant nextTransition;

		private Snapshot(ZoneId zone, Map<String, State> states, Instant nextTransition)
		{
			this.zone = zone;
			this.states = Collections.unmodifiableMap(new LinkedHashMap<>(states));
			this.nextTransition = nextTransition;
		}

		State get(String id)
		{
			return states.get(id);
		}

		Instant getNextTransition()
		{
			return nextTransition;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("zone", zone.getId());
			result.put("next_transition", nextTransition == null ? null : nextTransition.toString());
			Map<String, Object> values = new LinkedHashMap<>();
			for (Map.Entry<String, State> entry : states.entrySet())
			{
				values.put(entry.getKey(), entry.getValue().toMap());
			}
			result.put("schedules", values);
			return result;
		}
	}

	static final class State
	{
		private final boolean active;
		private final Instant nextTransition;
		private final String reason;

		private State(boolean active, Instant nextTransition, String reason)
		{
			this.active = active;
			this.nextTransition = nextTransition;
			this.reason = reason;
		}

		boolean isActive()
		{
			return active;
		}

		Instant getNextTransition()
		{
			return nextTransition;
		}

		String getReason()
		{
			return reason;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("active", active);
			result.put("next_transition", nextTransition == null ? null : nextTransition.toString());
			result.put("reason", reason);
			return result;
		}
	}

	private static final class Entry
	{
		private final EnumSet<DayOfWeek> days;
		private final List<Window> windows;

		private Entry(EnumSet<DayOfWeek> days, List<Window> windows)
		{
			this.days = days.clone();
			this.windows = Collections.unmodifiableList(new ArrayList<>(windows));
		}

		private static Entry from(GenericClientAutomationConfig.ScheduleSpec spec)
		{
			EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
			for (String day : spec.getDays())
			{
				days.add(DayOfWeek.valueOf(day));
			}
			List<Window> windows = new ArrayList<>();
			for (GenericClientAutomationConfig.WindowSpec window : spec.getWindows())
			{
				windows.add(new Window(window.getFrom(), window.getUntil()));
			}
			return new Entry(days, windows);
		}

		private State evaluate(Instant now, ZoneId zone)
		{
			LocalDate localDate = now.atZone(zone).toLocalDate();
			List<Interval> intervals = new ArrayList<>();
			for (int offset = -1; offset <= LOOKAHEAD_DAYS; offset++)
			{
				LocalDate date = localDate.plusDays(offset);
				if (!days.contains(date.getDayOfWeek()))
				{
					continue;
				}
				for (Window window : windows)
				{
					intervals.add(window.interval(date, zone));
				}
			}
			List<Interval> merged = merge(intervals);
			for (Interval interval : merged)
			{
				if (!now.isBefore(interval.start) && now.isBefore(interval.end))
				{
					return new State(true, interval.end,
						"active until " + interval.end.atZone(zone));
				}
				if (interval.start.isAfter(now))
				{
					return new State(false, interval.start,
						"inactive until " + interval.start.atZone(zone));
				}
			}
			return new State(false, null, "inactive; no transition in lookahead");
		}

		private static List<Interval> merge(List<Interval> intervals)
		{
			intervals.sort(Comparator.comparing(value -> value.start));
			List<Interval> merged = new ArrayList<>();
			for (Interval interval : intervals)
			{
				if (merged.isEmpty())
				{
					merged.add(interval);
					continue;
				}
				Interval previous = merged.get(merged.size() - 1);
				if (!interval.start.isAfter(previous.end))
				{
					if (interval.end.isAfter(previous.end))
					{
						merged.set(merged.size() - 1, new Interval(previous.start, interval.end));
					}
				}
				else
				{
					merged.add(interval);
				}
			}
			return merged;
		}
	}

	private static final class Window
	{
		private final LocalTime from;
		private final LocalTime until;

		private Window(LocalTime from, LocalTime until)
		{
			this.from = from;
			this.until = until;
		}

		private Interval interval(LocalDate date, ZoneId zone)
		{
			ZonedDateTime start = ZonedDateTime.of(date, from, zone);
			LocalDate endDate = until.isAfter(from) ? date : date.plusDays(1);
			ZonedDateTime end = ZonedDateTime.of(endDate, until, zone);
			return new Interval(start.toInstant(), end.toInstant());
		}
	}

	private static final class Interval
	{
		private final Instant start;
		private final Instant end;

		private Interval(Instant start, Instant end)
		{
			this.start = start;
			this.end = end;
		}
	}
}
