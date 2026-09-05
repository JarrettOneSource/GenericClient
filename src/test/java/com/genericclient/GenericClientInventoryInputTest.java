package com.genericclient;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

public class GenericClientInventoryInputTest
{
	@Rule public TemporaryFolder folders = new TemporaryFolder();

	@Test
	public void dispatchesItemActionsAndSelectedSpellsWithTheSameExactInventoryIdentity() throws Exception
	{
		for (boolean spell : new boolean[]{false, true})
		{
			try (GenericClientNativeInputFixture scene = inventoryScene())
			{
				Widget item = scene.roots.get(InterfaceID.Inventory.ITEMS).getDynamicChildren()[0];
				String action = spell ? "Cast" : "Drop";
				scene.offerMenu(item.getBounds(), menuEntry(action, 440, 6, InterfaceID.Inventory.ITEMS));
				GenericClientNativeInputFixture.Element selected = new GenericClientNativeInputFixture.Element(14286874, -1, "");
				scene.selectedWidget = spell ? selected.widget : null;
				Map<String, Object> receipt = (spell
					? scene.inputs.inventoryInput.castSelectedSpellOnItem(440, 6, selected.id, "Superheat Item", GenericClientActivityContext.none())
					: scene.inputs.inventoryInput.interact(440, 6, action, GenericClientActivityContext.none())).get(3, TimeUnit.SECONDS);
				assertEquals("dispatched", receipt.get("status"));
				Map<?, ?> target = (Map<?, ?>) receipt.get("target");
				assertEquals(440L, target.get("id"));
				assertEquals(6L, target.get("slot"));
				assertEquals(3L, target.get("quantity"));
				assertEquals("Iron ore", target.get("name"));
				assertEquals(spell ? "Superheat Item" : null, target.get("spell"));
				assertEquals(1, scene.clicks.get());
				assertTrue(item.getBounds().contains(scene.pressed.get(0)));
			}
		}
	}

	@Test
	public void aDifferentSpellSelectedDuringMouseMovementCannotCastOnTheItem() throws Exception
	{
		try (GenericClientNativeInputFixture scene = inventoryScene())
		{
			Widget item = scene.roots.get(InterfaceID.Inventory.ITEMS).getDynamicChildren()[0];
			GenericClientNativeInputFixture.Element superheat = new GenericClientNativeInputFixture.Element(14286874, -1, "");
			GenericClientNativeInputFixture.Element alchemy = new GenericClientNativeInputFixture.Element(14286875, -1, "");
			scene.selectedWidget = superheat.widget;
			scene.onTarget = () -> scene.selectedWidget = alchemy.widget;
			scene.offerMenu(item.getBounds(), menuEntry("Cast", 440, 6, InterfaceID.Inventory.ITEMS));
			Map<String, Object> receipt = scene.inputs.inventoryInput.castSelectedSpellOnItem(
				440, 6, superheat.id, "Superheat Item", GenericClientActivityContext.none()).get(3, TimeUnit.SECONDS);
			assertEquals("rejected", receipt.get("status"));
			assertEquals("requested_spell_not_selected:Superheat Item", receipt.get("result"));
			assertEquals(0, scene.clicks.get());
		}
	}

	private GenericClientNativeInputFixture inventoryScene() throws Exception
	{
		GenericClientNativeInputFixture scene = new GenericClientNativeInputFixture(folders.newFolder().toPath());
		GenericClientNativeInputFixture.Element inventory = new GenericClientNativeInputFixture.Element(InterfaceID.Inventory.ITEMS, -1, "");
		GenericClientNativeInputFixture.Element item = new GenericClientNativeInputFixture.Element(inventory.id, 6, "");
		item.itemId = 440;
		item.itemQuantity = 3;
		inventory.children(item);
		scene.roots.put(inventory.id, inventory.widget);
		scene.items.put(440, (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
			new Class<?>[]{ItemComposition.class}, (proxy, method, arguments) -> {
				if (method.getName().equals("getName")) return "Iron ore";
				if (method.getName().equals("getInventoryActions")) return new String[]{"Drop"};
				throw new AssertionError("Unexpected item read: " + method.getName());
			}));
		return scene;
	}
	@Test
	public void rejectsATabWhoseLayoutParentIsHidden()
	{
		Widget hiddenLayout = widget(true, null);
		Widget tab = widget(false, hiddenLayout);

		assertFalse(GenericClientWidgets.isVisible(tab));
		assertTrue(GenericClientWidgets.isVisible(widget(false, widget(false, null))));
	}

	@Test
	public void requiresTheExactItemToBeSelectedBeforeACompositeUse()
	{
		Widget cheese = itemWidget(1985);

		assertTrue(GenericClientInventoryInput.matchesSelectedItem(true, cheese, 1985));
		assertFalse(GenericClientInventoryInput.matchesSelectedItem(false, cheese, 1985));
		assertFalse(GenericClientInventoryInput.matchesSelectedItem(true, cheese, 2410));
	}

	@Test
	public void matchesASelectedSpellOnlyToTheExactInventorySlot()
	{
		MenuEntry exact = menuEntry("Cast", 440, 6, 9764864);
		MenuEntry wrongSlot = menuEntry("Cast", 440, 7, 9764864);
		MenuEntry ordinaryItemAction = menuEntry("Use", 440, 6, 9764864);

		assertTrue(GenericClientInventoryInput.matchesItem(
			exact, 440, 6, 9764864, "Cast"));
		assertFalse(GenericClientInventoryInput.matchesItem(
			wrongSlot, 440, 6, 9764864, "Cast"));
		assertFalse(GenericClientInventoryInput.matchesItem(
			ordinaryItemAction, 440, 6, 9764864, "Cast"));
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

	private static MenuEntry menuEntry(String option, int itemId, int slot, int widgetId)
	{
		return (MenuEntry) Proxy.newProxyInstance(
			MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class},
			(proxy, method, arguments) ->
			{
				switch (method.getName())
				{
					case "getOption":
						return option;
					case "getTarget": return "Iron ore";
					case "getType": return MenuAction.CC_OP;
					case "getItemId":
						return itemId;
					case "getParam0":
						return slot;
					case "getParam1":
						return widgetId;
					default:
						return method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
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
