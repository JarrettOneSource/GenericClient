package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GenericClientQuestActionsTest
{
	@Test
	public void parsesJuniorFriendlyBankItemArray()
	{
		Map<String, Object> action = new LinkedHashMap<>();
		List<Map<String, Object>> items = new ArrayList<>();
		items.add(item(556, 300));
		items.add(item(558, 150));
		action.put("items", items);

		List<GenericClientBankInput.Requirement> requirements =
			GenericClientQuestActions.bankRequirements(action);

		assertEquals(2, requirements.size());
		assertEquals(556, requirements.get(0).getItemId());
		assertEquals(300, requirements.get(0).getQuantity());
		assertEquals(558, requirements.get(1).getItemId());
		assertEquals(150, requirements.get(1).getQuantity());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNonPositiveBankQuantity()
	{
		Map<String, Object> action = new LinkedHashMap<>();
		List<Map<String, Object>> items = new ArrayList<>();
		items.add(item(556, 0));
		action.put("items", items);

		GenericClientQuestActions.bankRequirements(action);
	}

	@Test
	public void acceptsLuaEmptyTableAsAnEmptyBankLoadout()
	{
		Map<String, Object> action = new LinkedHashMap<>();
		action.put("items", new LinkedHashMap<>());

		assertEquals(0, GenericClientQuestActions.bankRequirements(action).size());
	}

	private static Map<String, Object> item(int id, int quantity)
	{
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", (double) id);
		item.put("quantity", (double) quantity);
		return item;
	}
}
