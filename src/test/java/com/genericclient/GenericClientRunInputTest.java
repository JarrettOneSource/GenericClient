package com.genericclient;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

public class GenericClientRunInputTest
{
	@Test
	public void matchesOnlyTheRunWidgetAction()
	{
		Widget runButton = widget(100, 2);
		MenuEntry correct = entry(MenuAction.CC_OP, runButton, 0, 0);
		MenuEntry wrongWidget = entry(MenuAction.CC_OP, widget(101, 2), 0, 0);
		MenuEntry wrongAction = entry(MenuAction.CANCEL, runButton, 0, 0);

		assertTrue(GenericClientRunInput.isWidgetAction(correct));
		assertTrue(GenericClientRunInput.matchesWidget(correct, runButton));
		assertFalse(GenericClientRunInput.matchesWidget(wrongWidget, runButton));
		assertFalse(GenericClientRunInput.isWidgetAction(wrongAction));
	}

	@Test
	public void matchesMenuEntriesWithoutAnAttachedWidgetByPackedId()
	{
		Widget runButton = widget(100, 2);
		MenuEntry entry = entry(MenuAction.CC_OP, null, 0, 100);

		assertTrue(GenericClientRunInput.matchesWidget(entry, runButton));
	}

	private static Widget widget(int id, int index)
	{
		return (Widget) Proxy.newProxyInstance(
			Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class},
			(proxy, method, arguments) ->
			{
				switch (method.getName())
				{
					case "getId":
						return id;
					case "getIndex":
						return index;
					default:
						return method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
	}

	private static MenuEntry entry(
		MenuAction action,
		Widget widget,
		int param0,
		int param1)
	{
		return (MenuEntry) Proxy.newProxyInstance(
			MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class},
			(proxy, method, arguments) ->
			{
				switch (method.getName())
				{
					case "getType":
						return action;
					case "getWidget":
						return widget;
					case "getParam0":
						return param0;
					case "getParam1":
						return param1;
					default:
						return method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
	}
}
