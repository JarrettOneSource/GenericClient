package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GenericClientScriptInputTest
{
	@Test
	public void parsesChoicesAndResolvesDefaultsOrSelections()
	{
		List<GenericClientScriptInput> parsed = GenericClientScriptInput.from(
			Configured.class.getAnnotation(com.genericclient.script.ScriptSettings.class).inputs());

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
		List<GenericClientScriptInput> parsed = GenericClientScriptInput.from(
			Configured.class.getAnnotation(com.genericclient.script.ScriptSettings.class).inputs());

		assertFailure(parsed, Collections.singletonMap("destination", "falador"),
			"Invalid value for script input destination: falador");
		assertFailure(parsed, Collections.singletonMap("extra", "value"),
			"Unknown script input: extra");
	}

	@com.genericclient.script.ScriptSettings(id="test",inputs=@com.genericclient.script.ScriptSettings.Input(
		id="destination",label="Destination",choices={"grand_exchange","varrock"},labels={"Grand Exchange","Varrock"},defaultValue="varrock"))
	private static final class Configured {}

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
