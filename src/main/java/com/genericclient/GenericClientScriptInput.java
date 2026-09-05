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
	private static GenericClientScriptInput from(com.genericclient.script.ScriptSettings.Input input)
	{
		List<Option> choices = new ArrayList<>();
		if (input.labels().length != 0 && input.labels().length != input.choices().length)
			throw new IllegalArgumentException("Choice labels must match choices for " + input.id());
		Set<String> values = new HashSet<>();
		for (int i = 0; i < input.choices().length; i++)
		{
			String value = input.choices()[i];
			if (value.isBlank() || !values.add(value)) throw new IllegalArgumentException("Invalid choice value for " + input.id());
			String label = input.labels().length == 0 ? value : input.labels()[i];
			if (label.isBlank()) throw new IllegalArgumentException("Choice label is required for " + input.id());
			choices.add(new Option(value,label));
		}
		if (choices.isEmpty() || findChoice(choices, input.defaultValue()) == null)
			throw new IllegalArgumentException("Invalid choices for " + input.id());
		return new GenericClientScriptInput(input.id(), input.label(), choices, input.defaultValue());
	}
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

	static List<GenericClientScriptInput> from(com.genericclient.script.ScriptSettings.Input[] definitions)
	{
		List<GenericClientScriptInput> inputs = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (com.genericclient.script.ScriptSettings.Input input : definitions)
		{
			if (!input.id().matches("[a-z][a-z0-9_]{0,31}"))
				throw new IllegalArgumentException("Script input id must be 1-32 lowercase letters, numbers, or underscores");
			if (!ids.add(input.id())) throw new IllegalArgumentException("Duplicate script input id: " + input.id());
			if (input.label().isBlank()) throw new IllegalArgumentException("Script input label is required");
			inputs.add(from(input));
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
