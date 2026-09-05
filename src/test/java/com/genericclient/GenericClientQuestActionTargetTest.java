package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Point;
import java.lang.reflect.Proxy;
import java.util.Collections;
import net.runelite.api.MenuEntry;
import net.runelite.api.MenuAction;
import org.junit.Test;

public class GenericClientQuestActionTargetTest
{
	@Test
	public void matchingNumbersCannotTurnAnActorOrGroundItemIntoAnObject()
	{
		MenuEntry actor = menuEntry(MenuAction.NPC_FIRST_OPTION, 2861, 42, 61, -1, -1, "Open");
		MenuEntry ground = menuEntry(MenuAction.GROUND_ITEM_THIRD_OPTION, 2861, 42, 61, -1, -1, "Take");
		MenuEntry object = menuEntry(MenuAction.GAME_OBJECT_FIRST_OPTION, 2861, 42, 61, -1, -1, "Take");
		assertFalse(GenericClientObjectInput.matchesObject(actor,object(2861,42,61,-1)));
		assertFalse(GenericClientObjectInput.matchesObject(ground,object(2861,42,61,-1)));
		assertFalse(GenericClientGroundItemInput.matchesGroundItem(object,2861,42,61,-1));
	}

	@Test
	public void objectMatcherUsesTheResolvedObjectTileAndWorldView()
	{
		MenuEntry exact = menuEntry(MenuAction.GAME_OBJECT_FIRST_OPTION, 2861, 42, 61, -1, -1, "Open");
		MenuEntry anotherObject = menuEntry(MenuAction.GAME_OBJECT_FIRST_OPTION, 2862, 42, 61, -1, -1, "Open");

		assertTrue(GenericClientObjectInput.matchesObject(exact, object(2861,42,61,-1)));
		assertFalse(GenericClientObjectInput.matchesObject(anotherObject, object(2861,42,61,-1)));
		assertFalse(GenericClientObjectInput.matchesObject(exact, object(2861,43,61,-1)));
		assertFalse(GenericClientObjectInput.matchesObject(exact, object(2861,42,61,1)));
	}

	@Test
	public void groundItemMatcherDoesNotConfuseSameIdOnAnotherTile()
	{
		MenuEntry exact = menuEntry(MenuAction.GROUND_ITEM_THIRD_OPTION, 2407, 51, 22, -1, -1, "Take");

		assertTrue(GenericClientGroundItemInput.matchesGroundItem(exact, 2407, 51, 22, -1));
		assertFalse(GenericClientGroundItemInput.matchesGroundItem(exact, 2407, 52, 22, -1));
	}

	@Test
	public void inventoryMatcherIncludesItemSlotWidgetAndExactAction()
	{
		MenuEntry exact = menuEntry(MenuAction.CC_OP, 1, 4, 9764864, -1, 1059, "Wear");

		assertTrue(GenericClientInventoryInput.matchesItem(exact, 1059, 4, 9764864, "wear"));
		assertFalse(GenericClientInventoryInput.matchesItem(exact, 1059, 5, 9764864, "Wear"));
		assertFalse(GenericClientInventoryInput.matchesItem(exact, 1059, 4, 9764864, "Use"));
	}

	@Test
	public void menuResolverDistinguishesDirectAndContextRows()
	{
		MenuEntry open = menuEntry(MenuAction.GAME_OBJECT_FIRST_OPTION, 2861, 42, 61, -1, -1, "Open");
		MenuEntry examine = menuEntry(MenuAction.EXAMINE_OBJECT, 2861, 42, 61, -1, -1, "Examine");
		GenericClientMenuInput.Target target = new GenericClientMenuInput.Target(
			new Point(100, 100),
			"Open",
			"door",
			Collections.emptyMap(),
			entry -> GenericClientObjectInput.matchesObject(entry, object(2861,42,61,-1)) &&
				"Open".equals(entry.getOption()));

		assertEquals(1, GenericClientMenuInput.findEntryIndex(
			new MenuEntry[]{examine, open}, target));
		assertEquals(0, GenericClientMenuInput.findEntryIndex(
			new MenuEntry[]{open, examine}, target));
	}

	@Test
	public void dynamicTargetRequiresTheMouseInsideItsLatestRegion()
	{
		GenericClientMenuInput.Target target = new GenericClientMenuInput.Target(
			new Point(110, 110),
			"Cast",
			"moving npc",
			Collections.emptyMap(),
			entry -> true,
			new java.awt.Rectangle(100, 100, 20, 30));

		assertTrue(target.isDynamic());
		assertTrue(target.acceptsMouse(110, 115));
		assertFalse(target.acceptsMouse(130, 115));
	}

	private static net.runelite.api.GameObject object(int id, int sceneX, int sceneY, int worldId)
	{
		net.runelite.api.WorldView world = (net.runelite.api.WorldView)Proxy.newProxyInstance(
			net.runelite.api.WorldView.class.getClassLoader(), new Class<?>[]{net.runelite.api.WorldView.class},
			(proxy,method,args) -> method.getName().equals("getId") ? worldId : null);
		return (net.runelite.api.GameObject)Proxy.newProxyInstance(net.runelite.api.GameObject.class.getClassLoader(),
			new Class<?>[]{net.runelite.api.GameObject.class},(proxy,method,args) ->
			{
				switch (method.getName())
				{
					case "getId": return id;
					case "getSceneMinLocation": return new net.runelite.api.Point(sceneX,sceneY);
					case "getWorldView": return world;
					default: return null;
				}
			});
	}

	private static MenuEntry menuEntry(
		MenuAction type,
		int identifier,
		int param0,
		int param1,
		int worldViewId,
		int itemId,
		String option)
	{
		return (MenuEntry) Proxy.newProxyInstance(
			MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class},
			(proxy, method, arguments) ->
			{
				switch (method.getName())
				{
					case "getType": return type;
					case "getIdentifier":
						return identifier;
					case "getParam0":
						return param0;
					case "getParam1":
						return param1;
					case "getWorldViewId":
						return worldViewId;
					case "getItemId":
						return itemId;
					case "getOption":
						return option;
					default:
						return method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
	}
}
