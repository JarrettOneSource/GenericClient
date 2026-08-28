package com.genericclient;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

public class GenericClientInventoryInputTest
{
	@Test
	public void rejectsATabWhoseLayoutParentIsHidden()
	{
		Widget hiddenLayout = widget(true, null);
		Widget tab = widget(false, hiddenLayout);

		assertFalse(GenericClientInventoryInput.isVisible(tab));
		assertTrue(GenericClientInventoryInput.isVisible(widget(false, widget(false, null))));
	}

	@Test
	public void requiresTheExactItemToBeSelectedBeforeACompositeUse()
	{
		Widget cheese = itemWidget(1985);

		assertTrue(GenericClientInventoryInput.matchesSelectedItem(true, cheese, 1985));
		assertFalse(GenericClientInventoryInput.matchesSelectedItem(false, cheese, 1985));
		assertFalse(GenericClientInventoryInput.matchesSelectedItem(true, cheese, 2410));
	}

	private static Widget itemWidget(int itemId)
	{
		return (Widget) Proxy.newProxyInstance(
			Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class},
			(proxy, method, arguments) -> "getItemId".equals(method.getName())
				? itemId
				: method.getReturnType().isPrimitive() ? 0 : null);
	}

	private static Widget widget(boolean hidden, Widget parent)
	{
		return (Widget) Proxy.newProxyInstance(
			Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class},
			(proxy, method, arguments) ->
			{
				switch (method.getName())
				{
					case "isHidden":
					case "isSelfHidden":
						return hidden;
					case "getParent":
						return parent;
					default:
						return method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
	}
}
