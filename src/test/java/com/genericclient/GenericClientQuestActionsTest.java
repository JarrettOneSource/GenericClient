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

	@Test
	public void parsesInventoryDialogueEmergencyEscape()
	{
		Map<String, Object> escape = new LinkedHashMap<>();
		escape.put("type", "inventory_dialogue");
		escape.put("item_id", 2564.0);
		escape.put("alternative_item_ids", List.of(2562.0, 2560.0));
		escape.put("action", "Rub");
		escape.put("choice", "Castle Wars Arena");
		escape.put("x", 2440.0);
		escape.put("y", 3089.0);
		escape.put("plane", 0.0);
		escape.put("within", 10.0);
		Map<String, Object> action = new LinkedHashMap<>();
		action.put("escape", escape);

		GenericClientEmergencyEscape parsed =
			GenericClientQuestActions.emergencyEscape(action);

		assertEquals(GenericClientEmergencyEscape.Type.INVENTORY_DIALOGUE,
			parsed.getType());
		assertEquals(2564, parsed.getItemId());
		assertEquals(List.of(2564, 2562, 2560), parsed.getItemIds());
		assertEquals(2562, parsed.resolveItemId(id -> id == 2562));
		assertEquals("Rub", parsed.getItemAction());
		assertEquals("Castle Wars Arena", parsed.getDialogueChoice());
		assertEquals(2440, parsed.getDestination().getX());
		assertEquals(10, parsed.getWithin());
	}

	@Test
	public void ownsOnlyProtectionPrayerThatTheScriptActuallyEnabled()
	{
		Map<String, Object> activated = prayerReceipt("set", "protect_from_melee");
		Map<String, Object> preexisting = prayerReceipt(
			"unchanged", "protect_from_magic");
		Map<String, Object> disabled = prayerReceipt(
			"unchanged", "protect_from_melee");

		String owned = GenericClientQuestActions.updateOwnedPrayer(
			"none", activated, true);
		assertEquals("protect_from_melee", owned);
		owned = GenericClientQuestActions.updateOwnedPrayer(owned, preexisting, true);
		assertEquals("protect_from_melee", owned);
		owned = GenericClientQuestActions.updateOwnedPrayer(owned, disabled, false);
		assertEquals("none", owned);
	}

	private static Map<String, Object> item(int id, int quantity)
	{
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", (double) id);
		item.put("quantity", (double) quantity);
		return item;
	}

	private static Map<String, Object> prayerReceipt(String status, String prayer)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", status);
		receipt.put("prayer", prayer);
		return receipt;
	}
}
