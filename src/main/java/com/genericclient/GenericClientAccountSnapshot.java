package com.genericclient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;

final class GenericClientAccountSnapshot
{
	private final boolean loggedIn;
	private final int totalLevel;
	private final List<SkillSnapshot> skills;
	private final ContainerSnapshot inventory;
	private final ContainerSnapshot equipment;
	private final BankSnapshot bank;
	private final QuestListSnapshot quests;
	private final GrandExchangeSnapshot grandExchange;
	private final CashSnapshot cash;
	private final CombatSnapshot combat;

	GenericClientAccountSnapshot(
		boolean loggedIn,
		int totalLevel,
		List<SkillSnapshot> skills,
		ContainerSnapshot inventory,
		ContainerSnapshot equipment)
	{
		this(
			loggedIn,
			totalLevel,
			skills,
			inventory,
			equipment,
			BankSnapshot.unknown(),
			QuestListSnapshot.unavailable(),
			GrandExchangeSnapshot.unavailable(),
			CashSnapshot.unavailable());
	}

	GenericClientAccountSnapshot(
		boolean loggedIn,
		int totalLevel,
		List<SkillSnapshot> skills,
		ContainerSnapshot inventory,
		ContainerSnapshot equipment,
		BankSnapshot bank)
	{
		this(
			loggedIn,
			totalLevel,
			skills,
			inventory,
			equipment,
			bank,
			QuestListSnapshot.unavailable(),
			GrandExchangeSnapshot.unavailable(),
			CashSnapshot.unavailable());
	}

	GenericClientAccountSnapshot(
		boolean loggedIn,
		int totalLevel,
		List<SkillSnapshot> skills,
		ContainerSnapshot inventory,
		ContainerSnapshot equipment,
		BankSnapshot bank,
		QuestListSnapshot quests)
	{
		this(
			loggedIn,
			totalLevel,
			skills,
			inventory,
			equipment,
			bank,
			quests,
			GrandExchangeSnapshot.unavailable(),
			CashSnapshot.unavailable());
	}

	GenericClientAccountSnapshot(
		boolean loggedIn,
		int totalLevel,
		List<SkillSnapshot> skills,
		ContainerSnapshot inventory,
		ContainerSnapshot equipment,
		BankSnapshot bank,
		QuestListSnapshot quests,
		GrandExchangeSnapshot grandExchange,
		CashSnapshot cash)
	{
		this(
			loggedIn,
			totalLevel,
			skills,
			inventory,
			equipment,
			bank,
			quests,
			grandExchange,
			cash,
			CombatSnapshot.unavailable());
	}

	GenericClientAccountSnapshot(
		boolean loggedIn,
		int totalLevel,
		List<SkillSnapshot> skills,
		ContainerSnapshot inventory,
		ContainerSnapshot equipment,
		BankSnapshot bank,
		QuestListSnapshot quests,
		GrandExchangeSnapshot grandExchange,
		CashSnapshot cash,
		CombatSnapshot combat)
	{
		this.loggedIn = loggedIn;
		this.totalLevel = totalLevel;
		this.skills = Collections.unmodifiableList(new ArrayList<>(skills));
		this.inventory = inventory;
		this.equipment = equipment;
		this.bank = bank;
		this.quests = quests;
		this.grandExchange = grandExchange;
		this.cash = cash;
		this.combat = combat;
	}

	static GenericClientAccountSnapshot empty()
	{
		return new GenericClientAccountSnapshot(
			false,
			0,
			Collections.emptyList(),
			ContainerSnapshot.unavailable(),
			ContainerSnapshot.unavailable());
	}

	static GenericClientAccountSnapshot capture(Client client)
	{
		return capture(client, null, null, 0);
	}

	static GenericClientAccountSnapshot capture(
		Client client,
		GenericClientBankCache bankCache,
		GenericClientQuestCache questCache,
		long gameTick)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			if (bankCache != null)
			{
				bankCache.clear();
			}
			if (questCache != null)
			{
				questCache.clear();
			}
			return empty();
		}

		List<SkillSnapshot> skills = new ArrayList<>();
		for (Skill skill : Skill.values())
		{
			if ("OVERALL".equals(skill.name()))
			{
				continue;
			}
			skills.add(new SkillSnapshot(
				skill.name().toLowerCase(Locale.ROOT),
				client.getRealSkillLevel(skill),
				client.getBoostedSkillLevel(skill),
				client.getSkillExperience(skill)));
		}

		ContainerSnapshot inventory = captureContainer(client, InventoryID.INV, false);
		ContainerSnapshot equipment = captureContainer(client, InventoryID.WORN, true);
		BankSnapshot bank = bankCache == null ? BankSnapshot.unknown() : bankCache.capture(client, gameTick);

		return new GenericClientAccountSnapshot(
			true,
			client.getTotalLevel(),
			skills,
			inventory,
			equipment,
			bank,
			questCache == null ? QuestListSnapshot.unavailable() : questCache.capture(client, gameTick),
			captureGrandExchange(client),
			CashSnapshot.from(inventory, bank),
			new CombatSnapshot(
				true,
				client.getVarpValue(VarPlayerID.COM_MODE),
				client.getVarpValue(VarPlayerID.OPTION_NODEF) == 0));
	}

	Object read(String subject)
	{
		switch (subject)
		{
			case "skills":
				return skillsMap();
			case "inventory":
				return inventory.toMap();
			case "equipment":
				return equipment.toMap();
			case "bank":
				return bank.toMap();
			case "quests":
				return quests.toMap();
			case "ge":
			case "grand_exchange":
				return grandExchange.toMap();
			case "cash":
				return cash.toMap();
			case "combat":
				return combat.toMap();
			default:
				throw new IllegalArgumentException("unknown account subject: " + subject);
		}
	}

	Map<String, Object> toMap()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("skills", skillsMap());
		value.put("inventory", inventory.toMap());
		value.put("equipment", equipment.toMap());
		value.put("bank", bank.toMap());
		value.put("quests", quests.toMap());
		value.put("grand_exchange", grandExchange.toMap());
		value.put("cash", cash.toMap());
		value.put("combat", combat.toMap());
		return value;
	}

	private Map<String, Object> skillsMap()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("available", loggedIn && !skills.isEmpty());
		value.put("total_level", (long) totalLevel);
		for (SkillSnapshot skill : skills)
		{
			value.put(skill.name, skill.toMap());
		}
		return value;
	}

	static ContainerSnapshot captureContainer(
		Client client,
		int inventoryId,
		boolean equipment)
	{
		ItemContainer container = client.getItemContainer(inventoryId);
		if (container == null)
		{
			if (inventoryId == InventoryID.INV)
			{
				return new ContainerSnapshot(true, 28, Collections.emptyList());
			}
			if (inventoryId == InventoryID.WORN)
			{
				return new ContainerSnapshot(true, 14, Collections.emptyList());
			}
			return ContainerSnapshot.unavailable();
		}

		Item[] source = container.getItems();
		List<ItemSnapshot> items = new ArrayList<>();
		for (int slot = 0; slot < source.length; slot++)
		{
			Item item = source[slot];
			if (item == null || item.getId() < 0)
			{
				continue;
			}
			ItemComposition composition = client.getItemDefinition(item.getId());
			items.add(new ItemSnapshot(
				slot,
				equipment ? equipmentSlotName(slot) : null,
				item.getId(),
				item.getQuantity(),
				composition == null ? "<unknown>" : Objects.toString(composition.getName(), "<unknown>"),
				composition != null && composition.isStackable(),
				composition != null && composition.isTradeable(),
				composition != null && composition.isGeTradeable(),
				composition == null ? Collections.emptyList() : actions(composition.getInventoryActions())));
		}
		int slotCount = inventoryId == InventoryID.INV
			? 28
			: inventoryId == InventoryID.WORN ? 14 : source.length;
		return new ContainerSnapshot(true, slotCount, items);
	}

	private static String equipmentSlotName(int slot)
	{
		for (EquipmentInventorySlot equipmentSlot : EquipmentInventorySlot.values())
		{
			if (equipmentSlot.getSlotIdx() == slot)
			{
				return equipmentSlot.name().toLowerCase(Locale.ROOT);
			}
		}
		return null;
	}

	private static List<String> actions(String[] source)
	{
		if (source == null)
		{
			return Collections.emptyList();
		}
		return Arrays.stream(source)
			.filter(Objects::nonNull)
			.collect(Collectors.toList());
	}

	private static GrandExchangeSnapshot captureGrandExchange(Client client)
	{
		GrandExchangeOffer[] source = client.getGrandExchangeOffers();
		if (source == null)
		{
			return GrandExchangeSnapshot.unavailable();
		}
		List<GrandExchangeOfferSnapshot> offers = new ArrayList<>();
		for (int slot = 0; slot < source.length; slot++)
		{
			GrandExchangeOffer offer = source[slot];
			if (offer == null || "EMPTY".equals(offer.getState().name()))
			{
				continue;
			}
			ItemComposition composition = client.getItemDefinition(offer.getItemId());
			offers.add(new GrandExchangeOfferSnapshot(
				slot,
				offer.getState().name().toLowerCase(Locale.ROOT),
				offer.getItemId(),
				composition == null ? "<unknown>" : Objects.toString(composition.getName(), "<unknown>"),
				offer.getPrice(),
				offer.getTotalQuantity(),
				offer.getQuantitySold(),
				offer.getSpent()));
		}
		return new GrandExchangeSnapshot(true, source.length, offers);
	}

	static final class SkillSnapshot
	{
		private final String name;
		private final int realLevel;
		private final int boostedLevel;
		private final int experience;

		SkillSnapshot(String name, int realLevel, int boostedLevel, int experience)
		{
			this.name = name;
			this.realLevel = realLevel;
			this.boostedLevel = boostedLevel;
			this.experience = experience;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("level", (long) realLevel);
			value.put("boosted_level", (long) boostedLevel);
			value.put("xp", (long) experience);
			return value;
		}
	}

	static final class ContainerSnapshot
	{
		private final boolean available;
		private final int slotCount;
		private final List<ItemSnapshot> items;

		ContainerSnapshot(boolean available, int slotCount, List<ItemSnapshot> items)
		{
			this.available = available;
			this.slotCount = slotCount;
			this.items = Collections.unmodifiableList(new ArrayList<>(items));
		}

		static ContainerSnapshot unavailable()
		{
			return new ContainerSnapshot(false, 0, Collections.emptyList());
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("available", available);
			value.put("slot_count", (long) slotCount);
			value.put("occupied_slots", (long) items.size());
			value.put("items", items.stream().map(ItemSnapshot::toMap).collect(Collectors.toList()));
			return value;
		}

		long quantity(int itemId)
		{
			long quantity = 0;
			for (ItemSnapshot item : items)
			{
				if (item.id == itemId)
				{
					quantity += item.quantity;
				}
			}
			return quantity;
		}
	}

	static final class ItemSnapshot
	{
		private final int slot;
		private final String slotName;
		private final int id;
		private final int quantity;
		private final String name;
		private final boolean stackable;
		private final boolean tradeable;
		private final boolean geTradeable;
		private final List<String> actions;

		ItemSnapshot(
			int slot,
			String slotName,
			int id,
			int quantity,
			String name,
			boolean stackable,
			boolean tradeable,
			boolean geTradeable,
			List<String> actions)
		{
			this.slot = slot;
			this.slotName = slotName;
			this.id = id;
			this.quantity = quantity;
			this.name = name;
			this.stackable = stackable;
			this.tradeable = tradeable;
			this.geTradeable = geTradeable;
			this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("slot", (long) slot);
			if (slotName != null)
			{
				value.put("slot_name", slotName);
			}
			value.put("id", (long) id);
			value.put("quantity", (long) quantity);
			value.put("name", name);
			value.put("stackable", stackable);
			value.put("tradeable", tradeable);
			value.put("ge_tradeable", geTradeable);
			value.put("actions", actions);
			return value;
		}
	}

	static final class BankSnapshot
	{
		private final String state;
		private final long capturedGameTick;
		private final ContainerSnapshot contents;

		BankSnapshot(String state, long capturedGameTick, ContainerSnapshot contents)
		{
			this.state = state;
			this.capturedGameTick = capturedGameTick;
			this.contents = contents;
		}

		static BankSnapshot unknown()
		{
			return new BankSnapshot("unknown", -1, ContainerSnapshot.unavailable());
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>(contents.toMap());
			value.put("state", state);
			value.put("open", "open".equals(state));
			value.put("captured_game_tick", capturedGameTick < 0 ? null : capturedGameTick);
			return value;
		}

		boolean isAvailable()
		{
			return contents.available;
		}

		long quantity(int itemId)
		{
			return contents.quantity(itemId);
		}
	}

	static final class QuestListSnapshot
	{
		private final boolean available;
		private final long refreshedGameTick;
		private final List<QuestSnapshot> quests;

		QuestListSnapshot(boolean available, long refreshedGameTick, List<QuestSnapshot> quests)
		{
			this.available = available;
			this.refreshedGameTick = refreshedGameTick;
			this.quests = Collections.unmodifiableList(new ArrayList<>(quests));
		}

		static QuestListSnapshot unavailable()
		{
			return new QuestListSnapshot(false, -1, Collections.emptyList());
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("available", available);
			value.put("refreshed_game_tick", refreshedGameTick < 0 ? null : refreshedGameTick);
			int finished = 0;
			int inProgress = 0;
			for (QuestSnapshot quest : quests)
			{
				if ("finished".equals(quest.state))
				{
					finished++;
				}
				else if ("in_progress".equals(quest.state))
				{
					inProgress++;
				}
				value.put(quest.key, quest.toMap());
			}
			value.put("finished_count", (long) finished);
			value.put("in_progress_count", (long) inProgress);
			value.put("total_count", (long) quests.size());
			return value;
		}
	}

	static final class QuestSnapshot
	{
		private final String key;
		private final int id;
		private final String name;
		private final String state;

		QuestSnapshot(String key, int id, String name, String state)
		{
			this.key = key;
			this.id = id;
			this.name = name;
			this.state = state;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("id", (long) id);
			value.put("name", name);
			value.put("state", state);
			return value;
		}
	}

	static final class GrandExchangeSnapshot
	{
		private final boolean available;
		private final int slotCount;
		private final List<GrandExchangeOfferSnapshot> offers;

		GrandExchangeSnapshot(boolean available, int slotCount, List<GrandExchangeOfferSnapshot> offers)
		{
			this.available = available;
			this.slotCount = slotCount;
			this.offers = Collections.unmodifiableList(new ArrayList<>(offers));
		}

		static GrandExchangeSnapshot unavailable()
		{
			return new GrandExchangeSnapshot(false, 0, Collections.emptyList());
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("available", available);
			value.put("slot_count", (long) slotCount);
			value.put("active_count", (long) offers.size());
			value.put("offers", offers.stream()
				.map(GrandExchangeOfferSnapshot::toMap)
				.collect(Collectors.toList()));
			return value;
		}
	}

	static final class GrandExchangeOfferSnapshot
	{
		private final int slot;
		private final String state;
		private final int itemId;
		private final String itemName;
		private final int unitPrice;
		private final int totalQuantity;
		private final int completedQuantity;
		private final int exchangedValue;

		GrandExchangeOfferSnapshot(
			int slot,
			String state,
			int itemId,
			String itemName,
			int unitPrice,
			int totalQuantity,
			int completedQuantity,
			int exchangedValue)
		{
			this.slot = slot;
			this.state = state;
			this.itemId = itemId;
			this.itemName = itemName;
			this.unitPrice = unitPrice;
			this.totalQuantity = totalQuantity;
			this.completedQuantity = completedQuantity;
			this.exchangedValue = exchangedValue;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("slot", (long) slot);
			value.put("state", state);
			value.put("item_id", (long) itemId);
			value.put("item_name", itemName);
			value.put("unit_price", (long) unitPrice);
			value.put("total_quantity", (long) totalQuantity);
			value.put("completed_quantity", (long) completedQuantity);
			value.put("exchanged_value", (long) exchangedValue);
			return value;
		}
	}

	static final class CashSnapshot
	{
		private final boolean available;
		private final boolean bankKnown;
		private final long inventoryCoins;
		private final long inventoryPlatinum;
		private final long bankCoins;
		private final long bankPlatinum;

		CashSnapshot(
			boolean available,
			boolean bankKnown,
			long inventoryCoins,
			long inventoryPlatinum,
			long bankCoins,
			long bankPlatinum)
		{
			this.available = available;
			this.bankKnown = bankKnown;
			this.inventoryCoins = inventoryCoins;
			this.inventoryPlatinum = inventoryPlatinum;
			this.bankCoins = bankCoins;
			this.bankPlatinum = bankPlatinum;
		}

		static CashSnapshot unavailable()
		{
			return new CashSnapshot(false, false, 0, 0, 0, 0);
		}

		static CashSnapshot from(ContainerSnapshot inventory, BankSnapshot bank)
		{
			return new CashSnapshot(
				inventory.available,
				bank.isAvailable(),
				inventory.quantity(ItemID.COINS),
				inventory.quantity(ItemID.PLATINUM),
				bank.quantity(ItemID.COINS),
				bank.quantity(ItemID.PLATINUM));
		}

		Map<String, Object> toMap()
		{
			long inventoryValue = inventoryCoins + inventoryPlatinum * 1_000L;
			long bankValue = bankCoins + bankPlatinum * 1_000L;
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("available", available);
			value.put("bank_known", bankKnown);
			value.put("inventory_coins", inventoryCoins);
			value.put("inventory_platinum_tokens", inventoryPlatinum);
			value.put("inventory_value", inventoryValue);
			value.put("bank_coins", bankKnown ? bankCoins : null);
			value.put("bank_platinum_tokens", bankKnown ? bankPlatinum : null);
			value.put("bank_value", bankKnown ? bankValue : null);
			value.put("known_total_value", inventoryValue + (bankKnown ? bankValue : 0));
			value.put("complete", available && bankKnown);
			return value;
		}
	}

	static final class CombatSnapshot
	{
		private final boolean available;
		private final int attackStyleIndex;
		private final boolean autoRetaliate;

		CombatSnapshot(boolean available, int attackStyleIndex)
		{
			this(available, attackStyleIndex, false);
		}

		CombatSnapshot(boolean available, int attackStyleIndex, boolean autoRetaliate)
		{
			this.available = available;
			this.attackStyleIndex = attackStyleIndex;
			this.autoRetaliate = autoRetaliate;
		}

		static CombatSnapshot unavailable()
		{
			return new CombatSnapshot(false, -1);
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("available", available);
			value.put("attack_style_index", available ? (long) attackStyleIndex : null);
			value.put("auto_retaliate", available ? autoRetaliate : null);
			return value;
		}
	}
}
