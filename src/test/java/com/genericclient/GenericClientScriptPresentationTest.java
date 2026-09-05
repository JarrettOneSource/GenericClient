package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GenericClientScriptPresentationTest
{
	@Test
	public void parsesBoundedActionsAndOverlayRows()
	{
		List<GenericClientScriptAction> actions = GenericClientScriptAction.parse(Arrays.asList(
			action("refresh", "Refresh"),
			action("reset_count", "Reset count")));
		List<GenericClientOverlayRow> rows = GenericClientOverlayRow.parse(Arrays.asList(
			row("State", "Walking"),
			row("Count", 12.0),
			row("Rate", "1.2k XP/h"),
			row("ETA", "2m 10s")));

		assertEquals("refresh", actions.get(0).getId());
		assertEquals("Reset count", actions.get(1).getLabel());
		assertEquals("State", rows.get(0).getLabel());
		assertEquals("12", rows.get(1).getValue());
		assertEquals("2m 10s", rows.get(3).getValue());
	}

	@Test
	public void rejectsExcessOverlayRowsAndDuplicateActions()
	{
		assertFailure(() -> GenericClientOverlayRow.parse(Arrays.asList(
			row("One", 1), row("Two", 2), row("Three", 3), row("Four", 4), row("Five", 5))),
			"Script overlays may contain at most 4 rows");
		assertFailure(() -> GenericClientScriptAction.parse(Arrays.asList(
			action("refresh", "Refresh"), action("refresh", "Again"))),
			"Duplicate Lua script action id: refresh");
	}

	@Test
	public void overlayTextHasNoCharacterLimit()
	{
		String label = "A deliberately descriptive overlay label beyond sixteen characters";
		String value = "Recovered - awaiting control without crashing the automation script";
		List<GenericClientOverlayRow> rows = GenericClientOverlayRow.parse(
			Arrays.asList(row(label, value)));

		assertEquals(label, rows.get(0).getLabel());
		assertEquals(value, rows.get(0).getValue());
	}

	private static Map<String, Object> action(String id, String label)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("id", id);
		value.put("label", label);
		return value;
	}

	private static Map<String, Object> row(String label, Object content)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("label", label);
		value.put("value", content);
		return value;
	}

	private static void assertFailure(Runnable runnable, String message)
	{
		try
		{
			runnable.run();
			throw new AssertionError("Expected validation to fail");
		}
		catch (IllegalArgumentException expected)
		{
			assertEquals(message, expected.getMessage());
		}
	}
}
