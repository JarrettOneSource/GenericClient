package com.genericclient;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
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

	@Test
	public void bankItemClickBoundsStopBeforeTheBottomControls()
	{
		Rectangle clipped = GenericClientBankInput.clipBankItemBounds(
			new Rectangle(350, 263, 48, 36),
			282,
			765,
			503);

		assertEquals(new Rectangle(350, 263, 48, 19), clipped);
	}

	@Test
	public void bankItemScrollMovesAClippedLastRowAboveTheControls()
	{
		assertEquals(18, GenericClientBankInput.scrollYForItem(
			new Rectangle(350, 263, 48, 36),
			new Rectangle(21, 41, 478, 244),
			0,
			40));
	}

	@Test
	public void bankItemScrollLeavesAVisibleRowAlone()
	{
		assertEquals(12, GenericClientBankInput.scrollYForItem(
			new Rectangle(350, 180, 48, 36),
			new Rectangle(21, 41, 478, 244),
			12,
			40));
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
