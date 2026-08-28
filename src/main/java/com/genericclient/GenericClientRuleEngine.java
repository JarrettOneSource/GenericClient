package com.genericclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GenericClientRuleEngine
{
	Evaluation evaluate(
		GenericClientAutomationConfig config,
		GenericClientSchedule.Snapshot schedules,
		Map<String, Object> account,
		Map<String, Long> cooldowns,
		long nowEpochMillis)
	{
		List<RuleEvaluation> values = new ArrayList<>();
		int order = 0;
		for (GenericClientAutomationConfig.RuleSpec rule : config.getRules())
		{
			ConditionResult condition = evaluate(rule.getWhen(), schedules, account);
			long cooldownUntil = cooldowns.getOrDefault(rule.getId(), 0L);
			boolean coolingDown = condition.truth == Truth.TRUE && cooldownUntil > nowEpochMillis;
			String reason = coolingDown
				? "cooldown until " + java.time.Instant.ofEpochMilli(cooldownUntil)
				: condition.reason;
			values.add(new RuleEvaluation(rule, condition.truth, !coolingDown && condition.truth == Truth.TRUE,
				reason, cooldownUntil, order++));
		}
		List<RuleEvaluation> candidates = new ArrayList<>();
		for (RuleEvaluation value : values)
		{
			if (value.eligible)
			{
				candidates.add(value);
			}
		}
		candidates.sort(Comparator
			.comparingInt((RuleEvaluation value) -> value.rule.getPriority()).reversed()
			.thenComparingInt(value -> value.order));
		return new Evaluation(values, candidates.isEmpty() ? null : candidates.get(0));
	}

	private ConditionResult evaluate(
		GenericClientAutomationConfig.Condition condition,
		GenericClientSchedule.Snapshot schedules,
		Map<String, Object> account)
	{
		if (condition.getAll() != null)
		{
			ConditionResult unknown = null;
			for (GenericClientAutomationConfig.Condition child : condition.getAll())
			{
				ConditionResult result = evaluate(child, schedules, account);
				if (result.truth == Truth.FALSE)
				{
					return new ConditionResult(Truth.FALSE, result.reason);
				}
				if (result.truth == Truth.UNKNOWN && unknown == null)
				{
					unknown = result;
				}
			}
			return unknown == null
				? new ConditionResult(Truth.TRUE, "all conditions matched")
				: unknown;
		}
		if (condition.getAny() != null)
		{
			ConditionResult unknown = null;
			String falseReason = "no condition matched";
			for (GenericClientAutomationConfig.Condition child : condition.getAny())
			{
				ConditionResult result = evaluate(child, schedules, account);
				if (result.truth == Truth.TRUE)
				{
					return result;
				}
				if (result.truth == Truth.UNKNOWN && unknown == null)
				{
					unknown = result;
				}
				else if (result.truth == Truth.FALSE)
				{
					falseReason = result.reason;
				}
			}
			return unknown == null
				? new ConditionResult(Truth.FALSE, falseReason)
				: unknown;
		}
		if (condition.getNot() != null)
		{
			ConditionResult value = evaluate(condition.getNot(), schedules, account);
			if (value.truth == Truth.UNKNOWN)
			{
				return value;
			}
			return new ConditionResult(value.truth == Truth.TRUE ? Truth.FALSE : Truth.TRUE,
				"not: " + value.reason);
		}
		if (condition.getSchedule() != null)
		{
			GenericClientSchedule.State state = schedules.get(condition.getSchedule());
			if (state == null)
			{
				return new ConditionResult(Truth.UNKNOWN,
					"schedule " + condition.getSchedule() + " is unavailable");
			}
			return new ConditionResult(state.isActive() ? Truth.TRUE : Truth.FALSE,
				"schedule " + condition.getSchedule() + " is " +
					(state.isActive() ? "active" : "inactive"));
		}

		FactValue fact = resolveFact(condition.getFact(), account);
		if (!fact.known)
		{
			return new ConditionResult(Truth.UNKNOWN, fact.reason);
		}
		boolean matches = compare(fact.value, condition.getOperator(), condition.getExpected());
		return new ConditionResult(matches ? Truth.TRUE : Truth.FALSE,
			condition.getFact() + "=" + fact.value + " " + condition.getOperator() + " " +
				display(condition.getExpected()) + (matches ? " matched" : " did not match"));
	}

	private static FactValue resolveFact(String path, Map<String, Object> account)
	{
		if (account == null || account.isEmpty())
		{
			return FactValue.unknown("account snapshot is unavailable");
		}
		String rootName = path.substring(0, path.indexOf('.'));
		Object root = account.get(rootName);
		if (!(root instanceof Map))
		{
			return FactValue.unknown(rootName + " snapshot is unavailable");
		}
		Map<?, ?> rootMap = (Map<?, ?>) root;
		if (!Boolean.TRUE.equals(rootMap.get("available")))
		{
			return FactValue.unknown(rootName + " snapshot is unavailable");
		}
		if (path.startsWith("cash."))
		{
			String key = path.substring("cash.".length());
			if (("known_total_value".equals(key) || "bank_value".equals(key) ||
				"bank_coins".equals(key) || "bank_platinum_tokens".equals(key)) &&
				!Boolean.TRUE.equals(rootMap.get("bank_known")))
			{
				return FactValue.unknown("cash is incomplete because bank wealth is unknown");
			}
		}

		Object current = account;
		for (String part : path.split("\\."))
		{
			if (!(current instanceof Map))
			{
				return FactValue.unknown("fact is unavailable: " + path);
			}
			Map<?, ?> map = (Map<?, ?>) current;
			if (!map.containsKey(part) || map.get(part) == null)
			{
				return FactValue.unknown("fact is unavailable: " + path);
			}
			current = map.get(part);
		}
		if (!(current instanceof Number) && !(current instanceof Boolean) && !(current instanceof String))
		{
			return FactValue.unknown("fact is not comparable: " + path);
		}
		return FactValue.known(current);
	}

	private static boolean compare(Object actual, String operator, JsonElement expected)
	{
		if ("eq".equals(operator) || "ne".equals(operator))
		{
			boolean equal;
			if (actual instanceof Number && expected.getAsJsonPrimitive().isNumber())
			{
				equal = decimal(actual).compareTo(decimal(expected.getAsNumber())) == 0;
			}
			else if (actual instanceof Boolean && expected.getAsJsonPrimitive().isBoolean())
			{
				equal = actual.equals(expected.getAsBoolean());
			}
			else
			{
				equal = String.valueOf(actual).equals(expected.getAsString());
			}
			return "eq".equals(operator) ? equal : !equal;
		}
		if (!(actual instanceof Number))
		{
			return false;
		}
		int comparison = decimal(actual).compareTo(decimal(expected.getAsNumber()));
		switch (operator)
		{
			case "lt": return comparison < 0;
			case "lte": return comparison <= 0;
			case "gt": return comparison > 0;
			case "gte": return comparison >= 0;
			default: throw new IllegalArgumentException("Unknown comparison operator: " + operator);
		}
	}

	private static BigDecimal decimal(Object value)
	{
		return new BigDecimal(String.valueOf(value));
	}

	private static String display(JsonElement value)
	{
		JsonPrimitive primitive = value.getAsJsonPrimitive();
		return primitive.isString() ? primitive.getAsString() : primitive.toString();
	}

	enum Truth
	{
		TRUE,
		FALSE,
		UNKNOWN
	}

	static final class Evaluation
	{
		private final List<RuleEvaluation> rules;
		private final RuleEvaluation selected;

		private Evaluation(List<RuleEvaluation> rules, RuleEvaluation selected)
		{
			this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
			this.selected = selected;
		}

		RuleEvaluation getSelected()
		{
			return selected;
		}

		RuleEvaluation get(String id)
		{
			for (RuleEvaluation rule : rules)
			{
				if (rule.rule.getId().equals(id))
				{
					return rule;
				}
			}
			return null;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("selected_rule", selected == null ? null : selected.rule.getId());
			List<Map<String, Object>> values = new ArrayList<>();
			for (RuleEvaluation rule : rules)
			{
				values.add(rule.toMap());
			}
			result.put("rules", values);
			return result;
		}
	}

	static final class RuleEvaluation
	{
		private final GenericClientAutomationConfig.RuleSpec rule;
		private final Truth truth;
		private final boolean eligible;
		private final String reason;
		private final long cooldownUntil;
		private final int order;

		private RuleEvaluation(
			GenericClientAutomationConfig.RuleSpec rule,
			Truth truth,
			boolean eligible,
			String reason,
			long cooldownUntil,
			int order)
		{
			this.rule = rule;
			this.truth = truth;
			this.eligible = eligible;
			this.reason = reason;
			this.cooldownUntil = cooldownUntil;
			this.order = order;
		}

		GenericClientAutomationConfig.RuleSpec getRule()
		{
			return rule;
		}

		Truth getTruth()
		{
			return truth;
		}

		boolean isEligible()
		{
			return eligible;
		}

		String getReason()
		{
			return reason;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("id", rule.getId());
			result.put("priority", (long) rule.getPriority());
			result.put("truth", truth.name().toLowerCase());
			result.put("eligible", eligible);
			result.put("reason", reason);
			result.put("script", rule.getRun().getScript());
			result.put("cooldown_until", cooldownUntil <= 0L
				? null
				: java.time.Instant.ofEpochMilli(cooldownUntil).toString());
			return result;
		}
	}

	private static final class ConditionResult
	{
		private final Truth truth;
		private final String reason;

		private ConditionResult(Truth truth, String reason)
		{
			this.truth = truth;
			this.reason = reason;
		}
	}

	private static final class FactValue
	{
		private final boolean known;
		private final Object value;
		private final String reason;

		private FactValue(boolean known, Object value, String reason)
		{
			this.known = known;
			this.value = value;
			this.reason = reason;
		}

		private static FactValue known(Object value)
		{
			return new FactValue(true, value, null);
		}

		private static FactValue unknown(String reason)
		{
			return new FactValue(false, null, reason);
		}
	}
}
