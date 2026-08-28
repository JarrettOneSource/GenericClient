package com.genericclient;

import static org.junit.Assert.assertSame;

import java.awt.Rectangle;
import java.lang.reflect.Proxy;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

public class GenericClientAutocastInputTest
{
	@Test
	public void prefersTheSpellIconOverATextOnlyDataChild()
	{
		Widget spriteMatch = widget("", "", 42, new String[]{"Autocast"}, null);
		Widget namedMatch = widget(
			"<col=00ff00>Water Strike</col>", "", -1, null, null);
		Widget root = widget("", "", -1, new Widget[]{spriteMatch, namedMatch});

		assertSame(spriteMatch,
			GenericClientAutocastInput.findSpellWidget(root, 42, "Water Strike"));
	}

	@Test
	public void fallsBackToTheSpellSpriteWhenTheAutocastChildHasNoText()
	{
		Widget spriteMatch = widget("", "", 42, null, null);
		Widget root = widget("", "", -1, new Widget[]{spriteMatch});

		assertSame(spriteMatch,
			GenericClientAutocastInput.findSpellWidget(root, 42, "Water Strike"));
	}

	private static Widget widget(String name, String text, int spriteId, Widget[] children)
	{
		return widget(name, text, spriteId, null, children);
	}

	private static Widget widget(
		String name,
		String text,
		int spriteId,
		String[] actions,
		Widget[] children)
	{
		return (Widget) Proxy.newProxyInstance(
			Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class},
			(proxy, method, arguments) ->
			{
				switch (method.getName())
				{
					case "getName":
						return name;
					case "getText":
						return text;
					case "getSpriteId":
						return spriteId;
					case "getActions":
						return actions;
					case "getBounds":
						return new Rectangle(10, 10, 20, 20);
					case "getDynamicChildren":
						return children;
					case "getStaticChildren":
					case "getNestedChildren":
						return null;
					case "isHidden":
					case "isSelfHidden":
						return false;
					default:
						return method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
	}
}
