package com.genericclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GenericClientScriptInput
{
	private final String id;
	private final String label;
	private final List<Option> choices;
	private final String defaultValue;

	private GenericClientScriptInput(
		String id,
		String label,
		List<Option> choices,
		String defaultValue)
	{
		this.id = id;
		this.label = label;
		this.choices = Collections.unmodifiableList(new ArrayList<>(choices));
		this.defaultValue = defaultValue;
	}

	String getId()
	{
		return id;
	}

	String getLabel()
	{
		return label;
	}

	List<Option> getChoices()
	{
		return choices;
	}

	String getDefaultValue()
	{
		return defaultValue;
	}

	Map<String, Object> toMap()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("id", id);
		value.put("label", label);
		value.put("type", "choice");
		value.put("default", defaultValue);
		List<Map<String, Object>> optionValues = new ArrayList<>();
		for (Option option : choices)
		{
			optionValues.add(option.toMap());
		}
		value.put("choices", optionValues);
		return value;
	}

	static List<GenericClientScriptInput> parse(Object rawInputs)
	{
		if (rawInputs == null)
		{
			return Collections.emptyList();
		}
		if (rawInputs instanceof Map && ((Map<?, ?>) rawInputs).isEmpty())
		{
			return Collections.emptyList();
		}
		if (!(rawInputs instanceof List))
		{
			throw new IllegalArgumentException("Lua script inputs must be an array");
		}

		List<GenericClientScriptInput> inputs = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (Object rawInput : (List<?>) rawInputs)
		{
			if (!(rawInput instanceof Map))
			{
				throw new IllegalArgumentException("Each Lua script input must be a table");
			}
			Map<?, ?> input = (Map<?, ?>) rawInput;
			String id = requiredString(input, "id", "Script input id");
			if (!id.matches("[a-z][a-z0-9_]{0,31}"))
			{
				throw new IllegalArgumentException(
					"Script input id must be 1-32 lowercase letters, numbers, or underscores");
			}
			if (!ids.add(id))
			{
				throw new IllegalArgumentException("Duplicate Lua script input id: " + id);
			}
			String label = requiredString(input, "label", "Script input label");
			String type = requiredString(input, "type", "Script input type");
			if (!"choice".equals(type))
			{
				throw new IllegalArgumentException("Unsupported Lua script input type: " + type);
			}
			List<Option> choices = parseChoices(input.get("choices"), id);
			String defaultValue = input.get("default") instanceof String
				? (String) input.get("default")
				: choices.get(0).value;
			if (findChoice(choices, defaultValue) == null)
			{
				throw new IllegalArgumentException(
					"Default value for script input " + id + " is not one of its choices");
			}
			inputs.add(new GenericClientScriptInput(id, label, choices, defaultValue));
		}
		return Collections.unmodifiableList(inputs);
	}

	static Map<String, Object> resolve(
		List<GenericClientScriptInput> inputs,
		Map<String, Object> supplied)
	{
		Map<String, Object> values = supplied == null
			? Collections.emptyMap()
			: supplied;
		Set<String> expected = new HashSet<>();
		Map<String, Object> resolved = new LinkedHashMap<>();
		for (GenericClientScriptInput input : inputs)
		{
			expected.add(input.id);
			Object raw = values.containsKey(input.id) ? values.get(input.id) : input.defaultValue;
			if (!(raw instanceof String) || findChoice(input.choices, (String) raw) == null)
			{
				throw new IllegalArgumentException(
					"Invalid value for script input " + input.id + ": " + raw);
			}
			resolved.put(input.id, raw);
		}
		for (String key : values.keySet())
		{
			if (!expected.contains(key))
			{
				throw new IllegalArgumentException("Unknown script input: " + key);
			}
		}
		return Collections.unmodifiableMap(resolved);
	}

	private static List<Option> parseChoices(Object rawChoices, String inputId)
	{
		if (!(rawChoices instanceof List) || ((List<?>) rawChoices).isEmpty())
		{
			throw new IllegalArgumentException("Choice input " + inputId + " requires at least one choice");
		}
		List<Option> choices = new ArrayList<>();
		Set<String> values = new HashSet<>();
		for (Object rawChoice : (List<?>) rawChoices)
		{
			if (!(rawChoice instanceof Map))
			{
				throw new IllegalArgumentException("Choices for script input " + inputId + " must be tables");
			}
			Map<?, ?> choice = (Map<?, ?>) rawChoice;
			String value = requiredString(choice, "value", "Choice value");
			String label = requiredString(choice, "label", "Choice label");
			if (!values.add(value))
			{
				throw new IllegalArgumentException(
					"Duplicate choice value for script input " + inputId + ": " + value);
			}
			choices.add(new Option(value, label));
		}
		return choices;
	}

	private static Option findChoice(List<Option> choices, String value)
	{
		for (Option choice : choices)
		{
			if (choice.value.equals(value))
			{
				return choice;
			}
		}
		return null;
	}

	private static String requiredString(Map<?, ?> value, String key, String label)
	{
		Object raw = value.get(key);
		if (!(raw instanceof String) || ((String) raw).trim().isEmpty())
		{
			throw new IllegalArgumentException(label + " is required");
		}
		return ((String) raw).trim();
	}

	static final class Option
	{
		private final String value;
		private final String label;

		private Option(String value, String label)
		{
			this.value = value;
			this.label = label;
		}

		String getValue()
		{
			return value;
		}

		String getLabel()
		{
			return label;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("value", value);
			result.put("label", label);
			return result;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}
}
