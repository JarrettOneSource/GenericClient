package com.genericclient;

import static org.junit.Assert.assertSame;

import java.awt.Rectangle;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

public class GenericClientPrayerInputTest
{
	@Test
	public void clicksTheActionChildThatOwnsTheMatchingPrayerIcon()
	{
		Widget action = widget(35454998, 0, -1, new String[]{"Activate"});
		Widget icon = widget(35454998, 1, 128, null);

		assertSame(action, GenericClientPrayerInput.findPrayerActionWidget(
			Arrays.asList(action, icon), 128, 148, "Activate"));
	}

	private static Widget widget(int id, int index, int spriteId, String[] actions)
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
					case "getSpriteId":
						return spriteId;
					case "getActions":
						return actions;
					case "getBounds":
						return new Rectangle(10, 10, 20, 20);
					case "isHidden":
					case "isSelfHidden":
						return false;
					default:
						return method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
	}
}
