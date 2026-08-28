package com.genericclient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.coords.WorldPoint;

final class GenericClientQuestActions
{
	private final GenericClientObjectInput objectInput;
	private final GenericClientInventoryInput inventoryInput;
	private final GenericClientNpcInput npcInput;
	private final GenericClientGroundItemInput groundItemInput;
	private final GenericClientDialogueInput dialogueInput;
	private final GenericClientBankInput bankInput;
	private final GenericClientGrandExchangeInput grandExchangeInput;
	private final GenericClientSpellInput spellInput;
	private final GenericClientAutocastInput autocastInput;
	private final GenericClientUiInput uiInput;
	private final GenericClientEmergencyController emergencyController;

	GenericClientQuestActions(
		GenericClientObjectInput objectInput,
		GenericClientInventoryInput inventoryInput,
		GenericClientNpcInput npcInput,
		GenericClientGroundItemInput groundItemInput,
		GenericClientDialogueInput dialogueInput,
		GenericClientBankInput bankInput,
		GenericClientGrandExchangeInput grandExchangeInput,
		GenericClientSpellInput spellInput,
		GenericClientAutocastInput autocastInput,
		GenericClientUiInput uiInput,
		GenericClientEmergencyController emergencyController)
	{
		this.objectInput = objectInput;
		this.inventoryInput = inventoryInput;
		this.npcInput = npcInput;
		this.groundItemInput = groundItemInput;
		this.dialogueInput = dialogueInput;
		this.bankInput = bankInput;
		this.grandExchangeInput = grandExchangeInput;
		this.spellInput = spellInput;
		this.autocastInput = autocastInput;
		this.uiInput = uiInput;
		this.emergencyController = emergencyController;
	}

	CompletableFuture<Map<String, Object>> execute(
		String type,
		Map<String, Object> action,
		boolean breaksEnabled)
	{
		switch (type)
		{
			case "object.interact":
				return objectInput.interact(
					requiredInt(action, "id", type),
					requiredText(action, "action", type),
					optionalWorld(action, type),
					within(action, type),
					breaksEnabled);
			case "item.interact":
				return inventoryInput.interact(
					requiredInt(action, "id", type),
					optionalSlot(action, type),
					requiredText(action, "action", type),
					breaksEnabled);
			case "item.use_on_object":
				return useItemOnObject(action, breaksEnabled);
			case "item.use_on_npc":
				return useItemOnNpc(action, breaksEnabled);
			case "ground_item.take":
				return groundItemInput.take(
					requiredInt(action, "id", type),
					optionalWorld(action, type),
					within(action, type),
					breaksEnabled);
			case "dialogue.continue":
				return dialogueInput.continueDialogue(breaksEnabled);
			case "dialogue.choose":
				return dialogueInput.choose(
					requiredText(action, "text", type), breaksEnabled);
			case "bank.loadout":
				return bankInput.loadout(
					bankRequirements(action),
					boundedInt(action, "minimum_free_slots", 0, 28, 0, type),
					optionalBoolean(action, "close", true, type),
					breaksEnabled);
			case "ge.buy":
				return grandExchangeInput.buy(
					requiredInt(action, "item_id", type),
					requiredText(action, "item_name", type),
					requiredPositiveInt(action, "quantity", type),
					requiredPositiveInt(action, "maximum_unit_price", type),
					longValue(
						action,
						"minimum_cash_reserve",
						GenericClientGrandExchangeInput.HARD_MINIMUM_CASH_RESERVE,
						type),
					breaksEnabled);
			case "combat.cast":
				return spellInput.castOnNpc(
					requiredText(action, "spell", type),
					optionalInt(action, "npc_id", type),
					optionalText(action.get("npc_name")),
					within(action, type),
					breaksEnabled);
			case "combat.set_autocast":
				return autocastInput.set(requiredText(action, "spell", type), breaksEnabled);
			case "ui.close":
				return uiInput.closeTopLevel(breaksEnabled);
			case "safety.configure":
				return emergencyController.configure(
					requiredPositiveInt(action, "minimum_hitpoints", type),
					emergencyConsumables(action),
					emergencyEscape(action),
					optionalBoolean(action, "continue_after_consumable", true, type),
					optionalBoolean(action, "allow_overheal", false, type));
			case "safety.clear":
				return emergencyController.clear();
			default:
				throw new IllegalArgumentException("Unsupported quest action: " + type);
		}
	}

	private static GenericClientEmergencyController.Escape emergencyEscape(
		Map<String, Object> action)
	{
		Object raw = action.get("escape");
		if (raw == null)
		{
			return null;
		}
		if (!(raw instanceof Map))
		{
			throw new IllegalArgumentException("safety.configure escape must be a coordinate table");
		}
		Map<?, ?> value = (Map<?, ?>) raw;
		int x = requiredInt(value, "x", "safety.configure escape");
		int y = requiredInt(value, "y", "safety.configure escape");
		int plane = requiredInt(value, "plane", "safety.configure escape");
		Object rawWithin = value.get("within");
		if (rawWithin != null && !(rawWithin instanceof Number))
		{
			throw new IllegalArgumentException("safety.configure escape within must be numeric");
		}
		int within = rawWithin == null ? 3 : ((Number) rawWithin).intValue();
		if (x < 0 || x > 0x7FFF || y < 0 || y > 0x7FFF || plane < 0 || plane > 3)
		{
			throw new IllegalArgumentException("safety.configure escape is outside coordinate bounds");
		}
		return new GenericClientEmergencyController.Escape(
			new WorldPoint(x, y, plane), within);
	}

	private static List<GenericClientEmergencyController.Consumable> emergencyConsumables(
		Map<String, Object> action)
	{
		Object raw = action.get("consumables");
		if (!(raw instanceof List))
		{
			throw new IllegalArgumentException("safety.configure requires a consumables array");
		}
		List<?> values = (List<?>) raw;
		List<GenericClientEmergencyController.Consumable> consumables = new ArrayList<>();
		for (Object value : values)
		{
			if (!(value instanceof Map))
			{
				throw new IllegalArgumentException("Safety consumables must be tables");
			}
			Map<?, ?> item = (Map<?, ?>) value;
			consumables.add(new GenericClientEmergencyController.Consumable(
				requiredInt(item, "id", "safety consumable"),
				requiredText(item, "action", "safety consumable"),
				requiredPositiveInt(item, "heal_amount", "safety consumable")));
		}
		return consumables;
	}

	static List<GenericClientBankInput.Requirement> bankRequirements(
		Map<String, Object> action)
	{
		Object rawItems = action.get("items");
		if (rawItems instanceof Map && ((Map<?, ?>) rawItems).isEmpty())
		{
			return new ArrayList<>();
		}
		if (!(rawItems instanceof List))
		{
			throw new IllegalArgumentException("bank.loadout requires an items array");
		}
		List<?> items = (List<?>) rawItems;
		if (items.size() > 28)
		{
			throw new IllegalArgumentException("bank.loadout supports at most 28 item requirements");
		}
		List<GenericClientBankInput.Requirement> requirements = new ArrayList<>();
		for (Object rawItem : items)
		{
			if (!(rawItem instanceof Map))
			{
				throw new IllegalArgumentException("bank.loadout items must be tables");
			}
			Map<?, ?> item = (Map<?, ?>) rawItem;
			requirements.add(new GenericClientBankInput.Requirement(
				requiredInt(item, "id", "bank.loadout item"),
				requiredPositiveInt(item, "quantity", "bank.loadout item")));
		}
		return requirements;
	}

	private static int boundedInt(
		Map<String, Object> action,
		String key,
		int minimum,
		int maximum,
		int defaultValue,
		String type)
	{
		Object raw = action.get(key);
		if (raw == null)
		{
			return defaultValue;
		}
		if (!(raw instanceof Number))
		{
			throw new IllegalArgumentException(type + " " + key + " must be numeric");
		}
		int value = ((Number) raw).intValue();
		if (value < minimum || value > maximum)
		{
			throw new IllegalArgumentException(
				type + " " + key + " must be between " + minimum + " and " + maximum);
		}
		return value;
	}

	private static boolean optionalBoolean(
		Map<String, Object> action,
		String key,
		boolean defaultValue,
		String type)
	{
		Object raw = action.get(key);
		if (raw == null)
		{
			return defaultValue;
		}
		if (!(raw instanceof Boolean))
		{
			throw new IllegalArgumentException(type + " " + key + " must be true or false");
		}
		return (Boolean) raw;
	}

	private static int requiredPositiveInt(Map<?, ?> value, String key, String type)
	{
		int result = requiredInt(value, key, type);
		if (result < 1)
		{
			throw new IllegalArgumentException(type + " " + key + " must be positive");
		}
		return result;
	}

	private static long longValue(
		Map<String, Object> action,
		String key,
		long defaultValue,
		String type)
	{
		Object raw = action.get(key);
		if (raw == null)
		{
			return defaultValue;
		}
		if (!(raw instanceof Number))
		{
			throw new IllegalArgumentException(type + " " + key + " must be numeric");
		}
		return ((Number) raw).longValue();
	}

	private CompletableFuture<Map<String, Object>> useItemOnObject(
		Map<String, Object> action,
		boolean breaksEnabled)
	{
		int itemId = requiredInt(action, "item_id", "item.use_on_object");
		int objectId = requiredInt(action, "object_id", "item.use_on_object");
		Integer slot = optionalSlot(action, "item.use_on_object");
		WorldPoint world = optionalWorld(action, "item.use_on_object");
		int within = within(action, "item.use_on_object");
		return inventoryInput.interact(itemId, slot, "Use", breaksEnabled).thenCompose(selection ->
		{
			if (!wasDispatched(selection))
			{
				return CompletableFuture.completedFuture(selection);
			}
			return inventoryInput.waitForSelectedItem(itemId).thenCompose(selected ->
			{
				if (!selected)
				{
					return CompletableFuture.completedFuture(compositeReceipt(
						"item_used_on_object", selection, rejected("requested_item_not_selected")));
				}
				return objectInput.useSelectedItemOnObject(
					objectId, world, within, itemId, null, breaksEnabled)
					.thenCompose(target -> finishSelectedItemAction(
						"item_used_on_object", itemId, slot, selection, target));
			});
		});
	}

	private CompletableFuture<Map<String, Object>> useItemOnNpc(
		Map<String, Object> action,
		boolean breaksEnabled)
	{
		int itemId = requiredInt(action, "item_id", "item.use_on_npc");
		int npcId = requiredInt(action, "npc_id", "item.use_on_npc");
		Integer slot = optionalSlot(action, "item.use_on_npc");
		String npcName = optionalText(action.get("npc_name"));
		int within = within(action, "item.use_on_npc");
		return inventoryInput.interact(itemId, slot, "Use", breaksEnabled).thenCompose(selection ->
		{
			if (!wasDispatched(selection))
			{
				return CompletableFuture.completedFuture(selection);
			}
			return inventoryInput.waitForSelectedItem(itemId).thenCompose(selected ->
			{
				if (!selected)
				{
					return CompletableFuture.completedFuture(compositeReceipt(
						"item_used_on_npc", selection, rejected("requested_item_not_selected")));
				}
				return npcInput.useSelectedItemOnNpc(
					npcId, npcName, within, itemId, null, breaksEnabled)
					.thenCompose(target -> finishSelectedItemAction(
						"item_used_on_npc", itemId, slot, selection, target));
			});
		});
	}

	private CompletableFuture<Map<String, Object>> finishSelectedItemAction(
		String result,
		int itemId,
		Integer slot,
		Map<String, Object> selection,
		Map<String, Object> target)
	{
		Map<String, Object> receipt = compositeReceipt(result, selection, target);
		if (wasDispatched(target))
		{
			return CompletableFuture.completedFuture(receipt);
		}
		return inventoryInput.clearSelectedItem(itemId, slot).thenApply(cleanup ->
		{
			receipt.put("selection_cleanup", cleanup);
			receipt.put("click_count", clickCount(receipt) + clickCount(cleanup));
			return receipt;
		});
	}

	private static Map<String, Object> rejected(String result)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "rejected");
		receipt.put("result", result);
		receipt.put("click_count", 0L);
		return receipt;
	}

	private static int within(Map<String, Object> action, String type)
	{
		Object value = action.get("within");
		int within = value instanceof Number ? ((Number) value).intValue() : 15;
		if (within < 1 || within > 32)
		{
			throw new IllegalArgumentException(type + " within must be between 1 and 32");
		}
		return within;
	}

	private static Integer optionalSlot(Map<String, Object> action, String type)
	{
		Object value = action.get("slot");
		if (value == null)
		{
			return null;
		}
		if (!(value instanceof Number))
		{
			throw new IllegalArgumentException(type + " slot must be numeric");
		}
		int slot = ((Number) value).intValue();
		if (slot < 0 || slot >= 28)
		{
			throw new IllegalArgumentException(type + " slot must be between 0 and 27");
		}
		return slot;
	}

	private static Integer optionalInt(Map<String, Object> action, String key, String type)
	{
		Object value = action.get(key);
		if (value == null)
		{
			return null;
		}
		if (!(value instanceof Number))
		{
			throw new IllegalArgumentException(type + " " + key + " must be numeric");
		}
		return ((Number) value).intValue();
	}

	private static WorldPoint optionalWorld(Map<String, Object> action, String type)
	{
		Object rawWorld = action.get("world");
		if (rawWorld == null)
		{
			return null;
		}
		if (!(rawWorld instanceof Map))
		{
			throw new IllegalArgumentException(type + " world must be a coordinate table");
		}
		Map<?, ?> world = (Map<?, ?>) rawWorld;
		int x = requiredInt(world, "x", type + " world");
		int y = requiredInt(world, "y", type + " world");
		int plane = requiredInt(world, "plane", type + " world");
		if (x < 0 || x > 0x7FFF || y < 0 || y > 0x7FFF || plane < 0 || plane > 3)
		{
			throw new IllegalArgumentException(type + " world is outside coordinate bounds");
		}
		return new WorldPoint(x, y, plane);
	}

	private static int requiredInt(Map<?, ?> value, String key, String type)
	{
		Object raw = value.get(key);
		if (!(raw instanceof Number))
		{
			throw new IllegalArgumentException(type + " requires numeric " + key);
		}
		int result = ((Number) raw).intValue();
		if (result < 0)
		{
			throw new IllegalArgumentException(type + " " + key + " cannot be negative");
		}
		return result;
	}

	private static String requiredText(Map<?, ?> value, String key, String type)
	{
		String result = optionalText(value.get(key));
		if (result == null)
		{
			throw new IllegalArgumentException(type + " requires non-empty " + key);
		}
		return result;
	}

	private static String optionalText(Object value)
	{
		return value instanceof String && !((String) value).trim().isEmpty()
			? ((String) value).trim()
			: null;
	}

	private static boolean wasDispatched(Map<String, Object> receipt)
	{
		return receipt != null && "dispatched".equals(receipt.get("status"));
	}

	private static Map<String, Object> compositeReceipt(
		String result,
		Map<String, Object> first,
		Map<String, Object> second)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", second.get("status"));
		receipt.put("result", result);
		List<Map<String, Object>> steps = new ArrayList<>();
		steps.add(first);
		steps.add(second);
		receipt.put("steps", steps);
		receipt.put("click_count", clickCount(first) + clickCount(second));
		return receipt;
	}

	private static long clickCount(Map<String, Object> receipt)
	{
		Object value = receipt.get("click_count");
		return value instanceof Number ? ((Number) value).longValue() : 0L;
	}
}
