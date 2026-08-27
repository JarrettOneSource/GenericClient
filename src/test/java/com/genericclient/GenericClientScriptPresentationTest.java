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
			row("Count", 12.0)));

		assertEquals("refresh", actions.get(0).getId());
		assertEquals("Reset count", actions.get(1).getLabel());
		assertEquals("State", rows.get(0).getLabel());
		assertEquals("12", rows.get(1).getValue());
	}

	@Test
	public void rejectsExcessOverlayRowsAndDuplicateActions()
	{
		assertFailure(() -> GenericClientOverlayRow.parse(Arrays.asList(
			row("One", 1), row("Two", 2), row("Three", 3), row("Four", 4))),
			"Script overlays may contain at most 3 rows");
		assertFailure(() -> GenericClientScriptAction.parse(Arrays.asList(
			action("refresh", "Refresh"), action("refresh", "Again"))),
			"Duplicate Lua script action id: refresh");
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
