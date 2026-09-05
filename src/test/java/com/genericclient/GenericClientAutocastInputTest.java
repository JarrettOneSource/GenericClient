package com.genericclient;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.lang.reflect.Proxy;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.SpriteID;
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
			GenericClientAutocastInput.findSpellWidget(root, 42, "Water Strike", 765, 503));
	}

	@Test
	public void fallsBackToTheSpellSpriteWhenTheAutocastChildHasNoText()
	{
		Widget spriteMatch = widget("", "", 42, null, null);
		Widget root = widget("", "", -1, new Widget[]{spriteMatch});

		assertSame(spriteMatch,
			GenericClientAutocastInput.findSpellWidget(root, 42, "Water Strike", 765, 503));
	}

	@Test
	public void ignoresMatchingDataChildrenOutsideTheVisibleAutocastPanel()
	{
		Widget offPanel = widget("", "", 42, new String[]{"Autocast"}, null,
			new Rectangle(0, 0, 20, 20));
		Widget visible = widget("", "", 42, new String[]{"Autocast"}, null,
			new Rectangle(650, 250, 30, 30));
		Widget root = widget("", "", -1, null, new Widget[]{offPanel, visible},
			new Rectangle(520, 210, 220, 200));

		assertSame(visible,
			GenericClientAutocastInput.findSpellWidget(root, 42, "Fire Bolt", 765, 503));
	}

	@Test
	public void verifiesTheRequestedAutocastSpellRatherThanAnyActiveSpell()
	{
		Widget earthBolt = widget("", "", SpriteID.Magicon.EARTH_BOLT, null, null);
		Widget fireBolt = widget("", "", SpriteID.Magicon.FIRE_BOLT, null, null);

		assertFalse(GenericClientAutocastInput.selectedSpellMatches(
			1, null, earthBolt, GenericClientSpellInput.Spell.FIRE_BOLT));
		assertTrue(GenericClientAutocastInput.selectedSpellMatches(
			1, null, fireBolt, GenericClientSpellInput.Spell.FIRE_BOLT));
	}

	@Test
	public void rejectsCombatWidgetsBeforeRuneLiteLaysThemOutOnCanvas()
	{
		Widget pending = widget("", "", -1, null, null,
			new Rectangle(-1, -1, 71, 50));
		Widget ready = widget("", "", -1, null, null,
			new Rectangle(646, 304, 71, 50));

		assertFalse(GenericClientAutocastInput.usableOnCanvas(pending, 765, 503));
		assertTrue(GenericClientAutocastInput.usableOnCanvas(ready, 765, 503));
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
		return widget(name, text, spriteId, actions, children, new Rectangle(10, 10, 20, 20));
	}

	private static Widget widget(
		String name,
		String text,
		int spriteId,
		String[] actions,
		Widget[] children,
		Rectangle bounds)
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
						return bounds;
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
