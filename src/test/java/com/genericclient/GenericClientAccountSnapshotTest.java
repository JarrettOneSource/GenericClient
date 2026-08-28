package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import org.junit.Test;

public class GenericClientAccountSnapshotTest
{
	@Test
	public void modelsMissingLoggedInInventoryContainersAsKnownEmpty()
	{
		// The game does not always allocate item containers until their first item exists.
		Client client = (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			(proxy, method, arguments) -> method.getReturnType().isPrimitive() ? 0 : null);
		GenericClientAccountSnapshot.ContainerSnapshot inventory =
			GenericClientAccountSnapshot.captureContainer(
				client, net.runelite.api.gameval.InventoryID.INV, false);
		GenericClientAccountSnapshot.ContainerSnapshot equipment =
			GenericClientAccountSnapshot.captureContainer(
				client, net.runelite.api.gameval.InventoryID.WORN, true);

		assertEquals(true, inventory.toMap().get("available"));
		assertEquals(28L, inventory.toMap().get("slot_count"));
		assertEquals(0L, inventory.toMap().get("occupied_slots"));
		assertEquals(true, equipment.toMap().get("available"));
		assertEquals(14L, equipment.toMap().get("slot_count"));
	}

	@Test
	public void keepsCanonicalInventoryCapacityWhenClientArrayIsTrimmed()
	{
		ItemContainer container = (ItemContainer) Proxy.newProxyInstance(
			ItemContainer.class.getClassLoader(),
			new Class<?>[]{ItemContainer.class},
			(proxy, method, arguments) -> "getItems".equals(method.getName())
				? new Item[]{new Item(556, 1)}
				: method.getReturnType().isPrimitive() ? 0 : null);
		Client client = (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			(proxy, method, arguments) -> "getItemContainer".equals(method.getName())
				? container
				: method.getReturnType().isPrimitive() ? 0 : null);

		GenericClientAccountSnapshot.ContainerSnapshot inventory =
			GenericClientAccountSnapshot.captureContainer(
				client, net.runelite.api.gameval.InventoryID.INV, false);

		assertEquals(28L, inventory.toMap().get("slot_count"));
		assertEquals(1L, inventory.toMap().get("occupied_slots"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void exposesInventoryAndEquipmentAsNamedSlotItems()
	{
		GenericClientAccountSnapshot.ItemSnapshot coins = new GenericClientAccountSnapshot.ItemSnapshot(
			3, null, 995, 125_000, "Coins", true, true, true, Arrays.asList("Use", "Drop"));
		GenericClientAccountSnapshot.ItemSnapshot weapon = new GenericClientAccountSnapshot.ItemSnapshot(
			3, "weapon", 1205, 1, "Bronze dagger", false, true, true, Collections.singletonList("Remove"));
		GenericClientAccountSnapshot snapshot = new GenericClientAccountSnapshot(
			true,
			36,
			Collections.emptyList(),
			new GenericClientAccountSnapshot.ContainerSnapshot(true, 28, Collections.singletonList(coins)),
			new GenericClientAccountSnapshot.ContainerSnapshot(true, 14, Collections.singletonList(weapon)));

		Map<String, Object> inventory = (Map<String, Object>) snapshot.read("inventory");
		Map<String, Object> equipment = (Map<String, Object>) snapshot.read("equipment");
		Map<String, Object> coinValue = ((java.util.List<Map<String, Object>>) inventory.get("items")).get(0);
		Map<String, Object> weaponValue = ((java.util.List<Map<String, Object>>) equipment.get("items")).get(0);

		assertEquals(true, inventory.get("available"));
		assertEquals(28L, inventory.get("slot_count"));
		assertEquals(1L, inventory.get("occupied_slots"));
		assertEquals("Coins", coinValue.get("name"));
		assertEquals(125_000L, coinValue.get("quantity"));
		assertEquals("weapon", weaponValue.get("slot_name"));
		assertEquals("Bronze dagger", weaponValue.get("name"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void distinguishesUnknownOpenAndCachedBankState()
	{
		GenericClientAccountSnapshot.ItemSnapshot coins = new GenericClientAccountSnapshot.ItemSnapshot(
			0, null, 995, 5_000_000, "Coins", true, true, true, Collections.emptyList());
		GenericClientAccountSnapshot.ContainerSnapshot contents =
			new GenericClientAccountSnapshot.ContainerSnapshot(true, 816, Collections.singletonList(coins));

		Map<String, Object> unknown = GenericClientAccountSnapshot.BankSnapshot.unknown().toMap();
		Map<String, Object> open = new GenericClientAccountSnapshot.BankSnapshot("open", 88, contents).toMap();
		Map<String, Object> cached = new GenericClientAccountSnapshot.BankSnapshot("cached", 88, contents).toMap();

		assertEquals("unknown", unknown.get("state"));
		assertEquals(false, unknown.get("available"));
		assertEquals(null, unknown.get("captured_game_tick"));
		assertEquals("open", open.get("state"));
		assertEquals(true, open.get("open"));
		assertEquals(88L, open.get("captured_game_tick"));
		assertEquals("cached", cached.get("state"));
		assertEquals(false, cached.get("open"));
		assertEquals(1L, cached.get("occupied_slots"));
	}

	@Test
	public void exposesQuestsByStableKeyWithSummaryCounts()
	{
		GenericClientAccountSnapshot.QuestListSnapshot quests =
			new GenericClientAccountSnapshot.QuestListSnapshot(
				true,
				120,
				Arrays.asList(
					new GenericClientAccountSnapshot.QuestSnapshot(
						"waterfall_quest", 65, "Waterfall Quest", "finished"),
					new GenericClientAccountSnapshot.QuestSnapshot(
						"desert_treasure_i", 101, "Desert Treasure I", "in_progress"),
					new GenericClientAccountSnapshot.QuestSnapshot(
						"kings_ransom", 127, "King's Ransom", "not_started")));

		Map<String, Object> value = quests.toMap();
		@SuppressWarnings("unchecked")
		Map<String, Object> waterfall = (Map<String, Object>) value.get("waterfall_quest");

		assertEquals(true, value.get("available"));
		assertEquals(120L, value.get("refreshed_game_tick"));
		assertEquals(1L, value.get("finished_count"));
		assertEquals(1L, value.get("in_progress_count"));
		assertEquals(3L, value.get("total_count"));
		assertEquals("finished", waterfall.get("state"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void reportsGrandExchangeOfferProgress()
	{
		GenericClientAccountSnapshot.GrandExchangeSnapshot exchange =
			new GenericClientAccountSnapshot.GrandExchangeSnapshot(
				true,
				8,
				Collections.singletonList(new GenericClientAccountSnapshot.GrandExchangeOfferSnapshot(
					2, "buying", 536, "Dragon bones", 3_000, 100, 40, 120_000)));

		Map<String, Object> value = exchange.toMap();
		Map<String, Object> offer = ((java.util.List<Map<String, Object>>) value.get("offers")).get(0);

		assertEquals(8L, value.get("slot_count"));
		assertEquals(1L, value.get("active_count"));
		assertEquals("buying", offer.get("state"));
		assertEquals("Dragon bones", offer.get("item_name"));
		assertEquals(40L, offer.get("completed_quantity"));
		assertEquals(120_000L, offer.get("exchanged_value"));
	}

	@Test
	public void reportsKnownCashWithoutPretendingAnUnseenBankIsEmpty()
	{
		GenericClientAccountSnapshot.ContainerSnapshot inventory =
			new GenericClientAccountSnapshot.ContainerSnapshot(
				true,
				28,
				Arrays.asList(
					new GenericClientAccountSnapshot.ItemSnapshot(
						0, null, net.runelite.api.gameval.ItemID.COINS, 250_000,
						"Coins", true, true, true, Collections.emptyList()),
					new GenericClientAccountSnapshot.ItemSnapshot(
						1, null, net.runelite.api.gameval.ItemID.PLATINUM, 2_000,
						"Platinum token", true, true, true, Collections.emptyList())));

		Map<String, Object> unknownBank = GenericClientAccountSnapshot.CashSnapshot.from(
			inventory, GenericClientAccountSnapshot.BankSnapshot.unknown()).toMap();
		GenericClientAccountSnapshot.ContainerSnapshot bankContents =
			new GenericClientAccountSnapshot.ContainerSnapshot(
				true,
				816,
				Collections.singletonList(new GenericClientAccountSnapshot.ItemSnapshot(
					0, null, net.runelite.api.gameval.ItemID.COINS, 5_000_000,
					"Coins", true, true, true, Collections.emptyList())));
		Map<String, Object> knownBank = GenericClientAccountSnapshot.CashSnapshot.from(
			inventory,
			new GenericClientAccountSnapshot.BankSnapshot("cached", 10, bankContents)).toMap();

		assertEquals(false, unknownBank.get("bank_known"));
		assertEquals(null, unknownBank.get("bank_value"));
		assertEquals(2_250_000L, unknownBank.get("known_total_value"));
		assertEquals(true, knownBank.get("complete"));
		assertEquals(7_250_000L, knownBank.get("known_total_value"));
	}

	@Test
	public void exposesTheCurrentCombatStyleIndex()
	{
		Map<String, Object> value = new GenericClientAccountSnapshot.CombatSnapshot(true, 3, false).toMap();

		assertEquals(true, value.get("available"));
		assertEquals(3L, value.get("attack_style_index"));
		assertEquals(false, value.get("auto_retaliate"));
	}
}
