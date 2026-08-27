package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import org.junit.Test;

public class GenericClientNpcInputTest
{
	@Test
	public void findsTheRequestedNpcActionFromTheTopOfTheMenu()
	{
		MenuEntry talk = menuEntry(12, "Talk-to");
		MenuEntry bank = menuEntry(12, "Bank");
		MenuEntry otherBanker = menuEntry(13, "Bank");
		MenuEntry[] entries = {otherBanker, bank, talk};

		assertEquals(1, GenericClientNpcInput.findNpcEntryIndex(entries, 12, "bank"));
		assertEquals(2, GenericClientNpcInput.findNpcEntryIndex(entries, 12, "Talk-to"));
		assertEquals(-1, GenericClientNpcInput.findNpcEntryIndex(entries, 13, "Talk-to"));
	}

	@Test
	public void resolvesWorldViewNpcWhenItsMenuIdentifierUsesALocalIndex()
	{
		NPC npc = (NPC) Proxy.newProxyInstance(
			NPC.class.getClassLoader(),
			new Class<?>[]{NPC.class},
			(proxy, method, arguments) -> "getIndex".equals(method.getName()) ? 26_255 :
				(method.getReturnType().isPrimitive() ? 0 : null));
		MenuEntry entry = menuEntry(151, "Attack", npc);

		assertEquals(0, GenericClientNpcInput.findNpcEntryIndex(
			new MenuEntry[]{entry}, npc.getIndex(), "Attack"));
	}

	private static MenuEntry menuEntry(int identifier, String option)
	{
		return menuEntry(identifier, option, null);
	}

	private static MenuEntry menuEntry(int identifier, String option, NPC npc)
	{
		return (MenuEntry) Proxy.newProxyInstance(
			MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class},
			(proxy, method, arguments) ->
			{
				switch (method.getName())
				{
					case "getIdentifier":
						return identifier;
					case "getOption":
						return option;
					case "getNpc":
						return npc;
					default:
						return method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
	}
}
