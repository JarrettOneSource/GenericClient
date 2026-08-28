package com.genericclient;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class GenericClientAutomationConfig
{
	static final String SCHEMA = "genericclient_automation.v1";
	private static final long MIN_RETRY_MILLIS = Duration.ofSeconds(1).toMillis();
	private static final long MAX_RETRY_MILLIS = Duration.ofDays(30).toMillis();
	private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
	private static final Pattern CLOCK_TIME = Pattern.compile("[0-9]{2}:[0-9]{2}");
	private static final Pattern FACT = Pattern.compile(
		"(?:skills\\.(?:available|total_level)|" +
		"skills\\.[a-z][a-z0-9_]*\\.(?:level|boosted_level|xp)|" +
		"cash\\.(?:available|bank_known|inventory_coins|inventory_platinum_tokens|" +
		"inventory_value|bank_coins|bank_platinum_tokens|bank_value|known_total_value|complete))");
	private static final Gson GSON = new Gson();

	private String schema;
	private String zone;
	private boolean enabled;
	private Map<String, ScheduleSpec> schedules;
	private List<RuleSpec> rules;

	private GenericClientAutomationConfig()
	{
	}

	static GenericClientAutomationConfig empty(String zone)
	{
		GenericClientAutomationConfig value = new GenericClientAutomationConfig();
		value.schema = SCHEMA;
		value.zone = ZoneId.of(zone).getId();
		value.enabled = false;
		value.schedules = Collections.emptyMap();
		value.rules = Collections.emptyList();
		value.validate();
		return value;
	}

	static GenericClientAutomationConfig fromMap(Map<String, Object> value)
	{
		if (value == null)
		{
			throw new IllegalArgumentException("Automation config is required");
		}
		GenericClientAutomationConfig config = GSON.fromJson(GSON.toJsonTree(value),
			GenericClientAutomationConfig.class);
		if (config == null)
		{
			throw new IllegalArgumentException("Automation config is empty");
		}
		config.validate();
		return config;
	}

	static GenericClientAutomationConfig fromJson(String value)
	{
		GenericClientAutomationConfig config = GSON.fromJson(value, GenericClientAutomationConfig.class);
		if (config == null)
		{
			throw new IllegalArgumentException("Automation config is empty");
		}
		config.validate();
		return config;
	}

	GenericClientAutomationConfig withEnabled(boolean enabled)
	{
		GenericClientAutomationConfig copy = fromJson(GSON.toJson(this));
		copy.enabled = enabled;
		copy.validate();
		return copy;
	}

	Map<String, Object> toMap()
	{
		@SuppressWarnings("unchecked")
		Map<String, Object> value = GSON.fromJson(GSON.toJson(this), LinkedHashMap.class);
		return value;
	}

	String toJson()
	{
		return GSON.toJson(this);
	}

	String getZone()
	{
		return zone;
	}

	ZoneId getZoneId()
	{
		return ZoneId.of(zone);
	}

	boolean isEnabled()
	{
		return enabled;
	}

	Map<String, ScheduleSpec> getSchedules()
	{
		return schedules;
	}

	List<RuleSpec> getRules()
	{
		return rules;
	}

	RuleSpec getRule(String id)
	{
		for (RuleSpec rule : rules)
		{
			if (rule.id.equals(id))
			{
				return rule;
			}
		}
		return null;
	}

	private void validate()
	{
		if (!SCHEMA.equals(schema))
		{
			throw new IllegalArgumentException("Unsupported automation schema: " + schema);
		}
		try
		{
			zone = ZoneId.of(requireText(zone, "Automation zone")).getId();
		}
		catch (java.time.DateTimeException exception)
		{
			throw new IllegalArgumentException("Invalid automation zone: " + zone, exception);
		}

		Map<String, ScheduleSpec> cleanSchedules = new LinkedHashMap<>();
		if (schedules != null)
		{
			for (Map.Entry<String, ScheduleSpec> entry : schedules.entrySet())
			{
				validateId(entry.getKey(), "Schedule id");
				if (entry.getValue() == null)
				{
					throw new IllegalArgumentException("Schedule is null: " + entry.getKey());
				}
				entry.getValue().validate(entry.getKey());
				cleanSchedules.put(entry.getKey(), entry.getValue());
			}
		}
		schedules = Collections.unmodifiableMap(cleanSchedules);

		List<RuleSpec> cleanRules = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		if (rules != null)
		{
			for (RuleSpec rule : rules)
			{
				if (rule == null)
				{
					throw new IllegalArgumentException("Automation rule cannot be null");
				}
				rule.validate(schedules, 0);
				if (!ids.add(rule.id))
				{
					throw new IllegalArgumentException("Duplicate automation rule id: " + rule.id);
				}
				cleanRules.add(rule);
			}
		}
		rules = Collections.unmodifiableList(cleanRules);
	}

	private static void validateId(String value, String label)
	{
		if (value == null || !ID.matcher(value).matches())
		{
			throw new IllegalArgumentException(
				label + " must use lowercase letters, numbers, hyphens, or underscores");
		}
	}

	private static String requireText(String value, String label)
	{
		if (value == null || value.trim().isEmpty())
		{
			throw new IllegalArgumentException(label + " is required");
		}
		return value.trim();
	}

	static final class ScheduleSpec
	{
		private List<String> days;
		private List<WindowSpec> windows;

		private void validate(String id)
		{
			if (days == null || days.isEmpty())
			{
				throw new IllegalArgumentException("Schedule " + id + " requires at least one day");
			}
			List<String> cleanDays = new ArrayList<>();
			Set<DayOfWeek> seen = new HashSet<>();
			for (String day : days)
			{
				try
				{
					DayOfWeek parsed = DayOfWeek.valueOf(requireText(day, "Schedule day").toUpperCase());
					if (!seen.add(parsed))
					{
						throw new IllegalArgumentException("Duplicate day in schedule " + id + ": " + day);
					}
					cleanDays.add(parsed.name());
				}
				catch (IllegalArgumentException exception)
				{
					throw new IllegalArgumentException("Invalid day in schedule " + id + ": " + day, exception);
				}
			}
			days = Collections.unmodifiableList(cleanDays);
			if (windows == null || windows.isEmpty())
			{
				throw new IllegalArgumentException("Schedule " + id + " requires at least one window");
			}
			List<WindowSpec> cleanWindows = new ArrayList<>();
			for (WindowSpec window : windows)
			{
				if (window == null)
				{
					throw new IllegalArgumentException("Schedule " + id + " contains a null window");
				}
				window.validate(id);
				cleanWindows.add(window);
			}
			windows = Collections.unmodifiableList(cleanWindows);
		}

		List<String> getDays()
		{
			return days;
		}

		List<WindowSpec> getWindows()
		{
			return windows;
		}
	}

	static final class WindowSpec
	{
		private String from;
		private String until;

		private void validate(String scheduleId)
		{
			from = validateTime(from, scheduleId, "from");
			until = validateTime(until, scheduleId, "until");
			if (from.equals(until))
			{
				throw new IllegalArgumentException(
					"Schedule " + scheduleId + " window cannot start and end at the same time");
			}
		}

		private static String validateTime(String value, String scheduleId, String field)
		{
			String clean = requireText(value, "Schedule " + field);
			if (!CLOCK_TIME.matcher(clean).matches())
			{
				throw new IllegalArgumentException(
					"Schedule " + scheduleId + " " + field + " must use HH:mm");
			}
			try
			{
				return LocalTime.parse(clean).toString();
			}
			catch (DateTimeParseException exception)
			{
				throw new IllegalArgumentException(
					"Invalid time in schedule " + scheduleId + ": " + clean, exception);
			}
		}

		LocalTime getFrom()
		{
			return LocalTime.parse(from);
		}

		LocalTime getUntil()
		{
			return LocalTime.parse(until);
		}
	}

	static final class RuleSpec
	{
		private String id;
		private int priority;
		private Condition when;
		private RunSpec run;
		@SerializedName(value = "retry_after", alternate = {"retryAfter"})
		private String retryAfter = "PT10M";

		private void validate(Map<String, ScheduleSpec> schedules, int depth)
		{
			validateId(id, "Rule id");
			if (when == null)
			{
				throw new IllegalArgumentException("Rule " + id + " requires a when condition");
			}
			when.validate(schedules, depth + 1);
			if (run == null)
			{
				throw new IllegalArgumentException("Rule " + id + " requires a run action");
			}
			run.validate(id);
			if (retryAfter == null || retryAfter.trim().isEmpty())
			{
				retryAfter = "PT10M";
			}
			try
			{
				long millis = Duration.parse(retryAfter).toMillis();
				if (millis < MIN_RETRY_MILLIS || millis > MAX_RETRY_MILLIS)
				{
					throw new IllegalArgumentException("retry_after is outside the supported range");
				}
			}
			catch (DateTimeParseException | ArithmeticException exception)
			{
				throw new IllegalArgumentException("Invalid retry_after for rule " + id, exception);
			}
		}

		String getId()
		{
			return id;
		}

		int getPriority()
		{
			return priority;
		}

		Condition getWhen()
		{
			return when;
		}

		RunSpec getRun()
		{
			return run;
		}

		long getRetryAfterMillis()
		{
			return Duration.parse(retryAfter).toMillis();
		}
	}

	static final class RunSpec
	{
		private String script;
		private Map<String, String> inputs;

		private void validate(String ruleId)
		{
			script = requireText(script, "Run script for rule " + ruleId);
			validateId(script, "Script id");
			Map<String, String> clean = new LinkedHashMap<>();
			if (inputs != null)
			{
				for (Map.Entry<String, String> entry : inputs.entrySet())
				{
					String key = requireText(entry.getKey(), "Script input id");
					String value = requireText(entry.getValue(), "Script input value");
					clean.put(key, value);
				}
			}
			inputs = Collections.unmodifiableMap(clean);
		}

		String getScript()
		{
			return script;
		}

		Map<String, Object> getInputs()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.putAll(inputs);
			return Collections.unmodifiableMap(value);
		}
	}

	static final class Condition
	{
		private List<Condition> all;
		private List<Condition> any;
		private Condition not;
		private String schedule;
		private String fact;
		private JsonElement eq;
		private JsonElement ne;
		private JsonElement lt;
		private JsonElement lte;
		private JsonElement gt;
		private JsonElement gte;

		private void validate(Map<String, ScheduleSpec> schedules, int depth)
		{
			if (depth > 16)
			{
				throw new IllegalArgumentException("Automation condition nesting exceeds 16 levels");
			}
			int forms = (all == null ? 0 : 1) + (any == null ? 0 : 1) + (not == null ? 0 : 1) +
				(schedule == null ? 0 : 1) + (fact == null ? 0 : 1);
			if (forms != 1)
			{
				throw new IllegalArgumentException("Each condition must contain exactly one condition form");
			}
			if (all != null)
			{
				all = validateChildren(all, schedules, depth, "all");
				return;
			}
			if (any != null)
			{
				any = validateChildren(any, schedules, depth, "any");
				return;
			}
			if (not != null)
			{
				not.validate(schedules, depth + 1);
				return;
			}
			if (schedule != null)
			{
				schedule = requireText(schedule, "Schedule condition");
				if (!schedules.containsKey(schedule))
				{
					throw new IllegalArgumentException("Unknown schedule in condition: " + schedule);
				}
				return;
			}

			fact = requireText(fact, "Fact condition");
			if (!FACT.matcher(fact).matches())
			{
				throw new IllegalArgumentException("Unsupported automation fact: " + fact);
			}
			int operators = count(eq) + count(ne) + count(lt) + count(lte) + count(gt) + count(gte);
			if (operators != 1)
			{
				throw new IllegalArgumentException(
					"Fact condition " + fact + " requires exactly one comparison operator");
			}
			JsonElement expected = expected();
			boolean booleanFact = "skills.available".equals(fact) ||
				"cash.available".equals(fact) || "cash.bank_known".equals(fact) ||
				"cash.complete".equals(fact);
			if (booleanFact && (eq == null && ne == null))
			{
				throw new IllegalArgumentException("Boolean fact " + fact + " supports only eq or ne");
			}
			if (booleanFact && (!expected.isJsonPrimitive() ||
				!expected.getAsJsonPrimitive().isBoolean()))
			{
				throw new IllegalArgumentException("Boolean comparison for " + fact + " requires true or false");
			}
			if (!booleanFact && (!expected.isJsonPrimitive() ||
				!expected.getAsJsonPrimitive().isNumber()))
			{
				throw new IllegalArgumentException("Numeric comparison for " + fact + " requires a number");
			}
		}

		private static List<Condition> validateChildren(
			List<Condition> source,
			Map<String, ScheduleSpec> schedules,
			int depth,
			String name)
		{
			if (source.isEmpty())
			{
				throw new IllegalArgumentException(name + " condition requires at least one child");
			}
			List<Condition> clean = new ArrayList<>();
			for (Condition child : source)
			{
				if (child == null)
				{
					throw new IllegalArgumentException(name + " condition contains a null child");
				}
				child.validate(schedules, depth + 1);
				clean.add(child);
			}
			return Collections.unmodifiableList(clean);
		}

		private static int count(JsonElement value)
		{
			return value == null ? 0 : 1;
		}

		private JsonElement expected()
		{
			if (eq != null) return eq;
			if (ne != null) return ne;
			if (lt != null) return lt;
			if (lte != null) return lte;
			if (gt != null) return gt;
			return gte;
		}

		List<Condition> getAll()
		{
			return all;
		}

		List<Condition> getAny()
		{
			return any;
		}

		Condition getNot()
		{
			return not;
		}

		String getSchedule()
		{
			return schedule;
		}

		String getFact()
		{
			return fact;
		}

		String getOperator()
		{
			if (eq != null) return "eq";
			if (ne != null) return "ne";
			if (lt != null) return "lt";
			if (lte != null) return "lte";
			if (gt != null) return "gt";
			return "gte";
		}

		JsonElement getExpected()
		{
			if (eq != null) return eq;
			if (ne != null) return ne;
			if (lt != null) return lt;
			if (lte != null) return lte;
			if (gt != null) return gt;
			return gte;
		}
	}
}
