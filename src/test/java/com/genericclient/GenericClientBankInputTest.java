package com.genericclient;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import net.runelite.api.MenuEntry;
import org.junit.Test;

public class GenericClientBankInputTest
{
	@Test
	public void bankItemMatcherIncludesItemSlotWidgetAndAction()
	{
		MenuEntry entry = menuEntry(556, 8, 786444, "Withdraw-X");

		assertTrue(GenericClientBankInput.matchesBankItem(
			entry, 556, 8, 786444, "withdraw-x"));
		assertFalse(GenericClientBankInput.matchesBankItem(
			entry, 556, 9, 786444, "Withdraw-X"));
		assertFalse(GenericClientBankInput.matchesBankItem(
			entry, 556, 8, 786444, "Withdraw-All"));
	}

	private static MenuEntry menuEntry(int itemId, int slot, int widgetId, String option)
	{
		return (MenuEntry) Proxy.newProxyInstance(
			MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class},
			(proxy, method, arguments) ->
			{
				switch (method.getName())
				{
					case "getItemId":
						return itemId;
					case "getParam0":
						return slot;
					case "getParam1":
						return widgetId;
					case "getOption":
						return option;
					default:
						return method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
	}
}
