package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GenericClientScriptInputTest
{
	@Test
	public void parsesChoicesAndResolvesDefaultsOrSelections()
	{
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("id", "destination");
		input.put("label", "Destination");
		input.put("type", "choice");
		input.put("default", "varrock");
		input.put("choices", Arrays.asList(
			choice("grand_exchange", "Grand Exchange"),
			choice("varrock", "Varrock")));

		List<GenericClientScriptInput> parsed =
			GenericClientScriptInput.parse(Collections.singletonList(input));

		assertEquals(1, parsed.size());
		assertEquals("Destination", parsed.get(0).getLabel());
		assertEquals("varrock", parsed.get(0).getDefaultValue());
		assertEquals("varrock",
			GenericClientScriptInput.resolve(parsed, Collections.emptyMap()).get("destination"));
		assertEquals("grand_exchange",
			GenericClientScriptInput.resolve(
				parsed,
				Collections.singletonMap("destination", "grand_exchange")).get("destination"));
	}

	@Test
	public void rejectsUnknownInputsAndInvalidChoices()
	{
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("id", "destination");
		input.put("label", "Destination");
		input.put("type", "choice");
		input.put("choices", Collections.singletonList(choice("varrock", "Varrock")));
		List<GenericClientScriptInput> parsed =
			GenericClientScriptInput.parse(Collections.singletonList(input));

		assertFailure(parsed, Collections.singletonMap("destination", "falador"),
			"Invalid value for script input destination: falador");
		assertFailure(parsed, Collections.singletonMap("extra", "value"),
			"Unknown script input: extra");
	}

	private static Map<String, Object> choice(String value, String label)
	{
		Map<String, Object> choice = new LinkedHashMap<>();
		choice.put("value", value);
		choice.put("label", label);
		return choice;
	}

	private static void assertFailure(
		List<GenericClientScriptInput> inputs,
		Map<String, Object> values,
		String expectedMessage)
	{
		try
		{
			GenericClientScriptInput.resolve(inputs, values);
			throw new AssertionError("Expected script input validation to fail");
		}
		catch (IllegalArgumentException expected)
		{
			assertEquals(expectedMessage, expected.getMessage());
		}
	}
}
