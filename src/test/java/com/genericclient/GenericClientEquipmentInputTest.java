package com.genericclient;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import net.runelite.api.MenuEntry;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

public class GenericClientEquipmentInputTest
{
	@Test
	public void matchesTheExactEquippedItemWidgetAndAction()
	{
		MenuEntry remove = entry(295, 25362449, "Remove");

		assertTrue(GenericClientEquipmentInput.matchesItem(remove, 295, 25362449, "Remove"));
		assertFalse(GenericClientEquipmentInput.matchesItem(remove, 295, 25362450, "Remove"));
		assertFalse(GenericClientEquipmentInput.matchesItem(remove, 296, 25362449, "Remove"));
		assertFalse(GenericClientEquipmentInput.matchesItem(remove, 295, 25362449, "Operate"));
	}

	@Test
	public void findsAnEquippedItemStoredOnTheSlotChild()
	{
		Widget child = widget(25362456, 0, 2560, null);
		Widget slot = widget(25362456, -1, -1, new Widget[]{child});

		assertSame(child, GenericClientEquipmentInput.findItemWidget(slot, 2560));
	}

	@Test
	public void matchesAWornItemMenuEntryWhoseItemIdLivesOnTheDynamicChild()
	{
		MenuEntry castleWars = entry(-1, 25362456, "Castle Wars");

		assertTrue(GenericClientEquipmentInput.matchesItem(
			castleWars, 2560, 25362456, "Castle Wars"));
	}

	private static MenuEntry entry(int itemId, int widgetId, String option)
	{
		Widget widget = (Widget) Proxy.newProxyInstance(
			Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class},
			(proxy, method, arguments) -> "getId".equals(method.getName())
				? widgetId
				: method.getReturnType().isPrimitive() ? 0 : null);
		return (MenuEntry) Proxy.newProxyInstance(
			MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class},
			(proxy, method, arguments) ->
			{
				switch (method.getName())
				{
					case "getItemId":
						return itemId;
					case "getWidget":
						return widget;
					case "getOption":
						return option;
					default:
						return method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
	}

	private static Widget widget(int widgetId, int index, int itemId, Widget[] children)
	{
		return (Widget) Proxy.newProxyInstance(
			Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class},
			(proxy, method, arguments) ->
			{
				switch (method.getName())
				{
					case "getId":
						return widgetId;
					case "getIndex":
						return index;
					case "getItemId":
						return itemId;
					case "getDynamicChildren":
						return children;
					default:
						return method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
	}
}
