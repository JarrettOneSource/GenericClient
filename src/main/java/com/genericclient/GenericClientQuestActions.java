package com.genericclient;

import static com.genericclient.GenericClientInteractionReceipts.clickCount;
import static com.genericclient.GenericClientInteractionReceipts.composite;
import static com.genericclient.GenericClientInteractionReceipts.rejected;
import static com.genericclient.GenericClientInteractionReceipts.wasDispatched;

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
	private final GenericClientEquipmentInput equipmentInput;
	private final GenericClientNpcInput npcInput;
	private final GenericClientGroundItemInput groundItemInput;
	private final GenericClientDialogueInput dialogueInput;
	private final GenericClientBankInput bankInput;
	private final GenericClientGrandExchangeInput grandExchangeInput;
	private final GenericClientSpellInput spellInput;
	private final GenericClientAutocastInput autocastInput;
	private final GenericClientPrayerInput prayerInput;
	private final GenericClientUiInput uiInput;
	private final GenericClientWorldInput worldInput;
	private final GenericClientPoisonInput poisonInput;
	private final GenericClientCombatInput combatInput;
	private final GenericClientEmergencyController emergencyController;
	private final GenericClientCombatGuard combatGuard;
	private String scriptOwnedPrayer = "none";

	GenericClientQuestActions(
		GenericClientObjectInput objectInput,
		GenericClientInventoryInput inventoryInput,
		GenericClientEquipmentInput equipmentInput,
		GenericClientNpcInput npcInput,
		GenericClientGroundItemInput groundItemInput,
		GenericClientDialogueInput dialogueInput,
		GenericClientBankInput bankInput,
		GenericClientGrandExchangeInput grandExchangeInput,
		GenericClientSpellInput spellInput,
		GenericClientAutocastInput autocastInput,
		GenericClientPrayerInput prayerInput,
		GenericClientUiInput uiInput,
		GenericClientWorldInput worldInput,
		GenericClientPoisonInput poisonInput,
		GenericClientCombatInput combatInput,
		GenericClientEmergencyController emergencyController,
		GenericClientCombatGuard combatGuard)
	{
		this.objectInput = objectInput;
		this.inventoryInput = inventoryInput;
		this.equipmentInput = equipmentInput;
		this.npcInput = npcInput;
		this.groundItemInput = groundItemInput;
		this.dialogueInput = dialogueInput;
		this.bankInput = bankInput;
		this.grandExchangeInput = grandExchangeInput;
		this.spellInput = spellInput;
		this.autocastInput = autocastInput;
		this.prayerInput = prayerInput;
		this.uiInput = uiInput;
		this.worldInput = worldInput;
		this.poisonInput = poisonInput;
		this.combatInput = combatInput;
		this.emergencyController = emergencyController;
		this.combatGuard = combatGuard;
	}

	CompletableFuture<Map<String, Object>> execute(
		String type,
		Map<String, Object> action,
		GenericClientActivityContext activityContext)
	{
		switch (type)
		{
			case "object.interact":
				return objectInput.interact(
					requiredInt(action, "id", type),
					requiredText(action, "action", type),
					optionalWorld(action, type),
					within(action, type),
					activityContext);
			case "item.interact":
				return inventoryInput.interact(
					requiredInt(action, "id", type),
					optionalSlot(action, type),
					requiredText(action, "action", type),
					activityContext);
			case "equipment.interact":
				return equipmentInput.interact(
					requiredInt(action, "id", type),
					requiredText(action, "action", type),
					activityContext);
			case "item.use_on_object":
				return useItemOnObject(action, activityContext);
			case "item.use_on_npc":
				return useItemOnNpc(action, activityContext);
			case "item.use_on_item":
				return useItemOnItem(action, activityContext);
			case "ground_item.take":
				return groundItemInput.take(
					requiredInt(action, "id", type),
					optionalWorld(action, type),
					within(action, type),
					activityContext);
			case "dialogue.continue":
				return dialogueInput.continueDialogue(
					activityContext,
					optionalBoolean(action, "reading", true, type));
			case "dialogue.choose":
				String choice = requiredText(action, "text", type);
				boolean reading = optionalBoolean(action, "reading", true, type);
				return optionalBoolean(action, "keyboard", false, type)
					? dialogueInput.chooseKeyboard(choice, activityContext, reading)
					: dialogueInput.choose(choice, activityContext, reading);
			case "bank.loadout":
				return bankInput.loadout(
					bankRequirements(action),
					boundedInt(action, "minimum_free_slots", 0, 28, 0, type),
					optionalBoolean(action, "close", true, type),
					activityContext);
			case "ge.buy":
				return grandExchangeInput.buy(
					requiredInt(action, "item_id", type),
					requiredText(action, "item_name", type),
					requiredPositiveInt(action, "quantity", type),
					requiredPositiveInt(action, "maximum_unit_price", type),
					longValue(
						action,
						"minimum_cash_reserve",
						GenericClientGrandExchangePolicy.HARD_MINIMUM_CASH_RESERVE,
						type),
					optionalText(action.get("collect_mode")),
					activityContext);
			case "ui.close":
				return uiInput.closeTopLevel(activityContext);
			case "ui.click":
				return uiInput.click(
					requiredInt(action, "widget_id", type),
					optionalInt(action, "widget_index", type),
					activityContext);
			case "ui.key":
				return uiInput.key(requiredText(action, "key", type), activityContext);
			case "world.select":
				return worldInput.select(
					requiredInt(action, "world", type),
					optionalBoolean(action, "members", false, type), activityContext);
			case "consumable.cure_poison":
				return poisonInput.cure(activityContext);
			default:
				return executeProtectionAndSpells(type, action, activityContext);
		}
	}

	private CompletableFuture<Map<String, Object>> executeProtectionAndSpells(String type, Map<String, Object> action,
		GenericClientActivityContext activityContext)
	{
		switch (type)
		{
			case "combat.cast":
				return spellInput.castOnNpc(
					requiredText(action, "spell", type),
					optionalInt(action, "npc_id", type),
					optionalText(action.get("npc_name")),
					within(action, type),
					activityContext);
			case "spell.cast_on_item":
				return spellInput.castOnItem(
					requiredText(action, "spell", type),
					requiredInt(action, "item_id", type),
					optionalSlot(action, type),
					activityContext);
			case "travel.home_teleport":
				return spellInput.homeTeleport(activityContext);
			case "combat.set_autocast":
				return autocastInput.set(requiredText(action, "spell", type), activityContext);
			case "prayer.set":
				return setPrayer(action, type, activityContext);
			case "client.behaviors.configure":
				return configureClientBehaviors(action, type, activityContext);
			case "safety.configure":
				return emergencyController.configure(
					requiredPositiveInt(action, "minimum_hitpoints", type),
					emergencyConsumables(action),
					emergencyEscape(action),
					optionalBoolean(action, "continue_after_consumable", true, type),
					optionalBoolean(action, "allow_overheal", false, type));
			case "safety.clear":
				return emergencyController.clear();
			case "safety.recover":
				return emergencyController.recoverNow();
			case "safety.escape":
				return emergencyController.forceEscapeNow();
			default:
				throw new IllegalArgumentException("Unsupported quest action: " + type);
		}
	}

	private CompletableFuture<Map<String, Object>> setPrayer(
		Map<String, Object> action,
		String type,
		GenericClientActivityContext activityContext)
	{
		String prayer = requiredText(action, "prayer", type);
		boolean enabled = optionalBoolean(action, "enabled", true, type);
		return prayerInput.set(prayer, enabled, activityContext).thenApply(receipt ->
		{
			boolean applied = activityContext.applyIfCurrent(() ->
			{
				synchronized (this)
				{
					scriptOwnedPrayer = updateOwnedPrayer(scriptOwnedPrayer, receipt, enabled);
				}
			});
			return applied ? receipt : rejected("action_cancelled");
		});
	}

	synchronized CompletableFuture<Map<String, Object>> releaseScriptPrayer()
	{
		if ("none".equals(scriptOwnedPrayer))
		{
			Map<String, Object> receipt = new LinkedHashMap<>();
			receipt.put("status", "unchanged");
			receipt.put("result", "no_script_owned_prayer");
			receipt.put("click_count", 0L);
			return CompletableFuture.completedFuture(receipt);
		}
		String prayer = scriptOwnedPrayer;
		return prayerInput.set(prayer, false, GenericClientActivityContext.none())
			.thenApply(receipt ->
			{
				synchronized (this)
				{
					scriptOwnedPrayer = updateOwnedPrayer(
						scriptOwnedPrayer, receipt, false);
				}
				return receipt;
			});
	}

	static String updateOwnedPrayer(
		String current,
		Map<String, Object> receipt,
		boolean enabled)
	{
		String status = String.valueOf(receipt.get("status"));
		String prayer = String.valueOf(receipt.get("prayer"));
		if (enabled && "set".equals(status))
		{
			return prayer;
		}
		if (!enabled && ("set".equals(status) || "unchanged".equals(status)) &&
			prayer.equals(current))
		{
			return "none";
		}
		return current;
	}

	private CompletableFuture<Map<String, Object>> configureClientBehaviors(
		Map<String, Object> action,
		String type,
		GenericClientActivityContext activityContext)
	{
		boolean emergencyConsumables = optionalBoolean(
			action, "emergency_consumables", true, type);
		boolean emergencyEscape = optionalBoolean(
			action, "emergency_escape", true, type);
		boolean combatPrayer = optionalBoolean(
			action, "combat_prayer", true, type);
		boolean autoRetaliate = optionalBoolean(
			action, "auto_retaliate", true, type);
		return combatInput.setAutoRetaliate(autoRetaliate, activityContext)
			.thenApply(retaliation ->
			{
				String retaliationStatus = String.valueOf(retaliation.get("status"));
				if (!("set".equals(retaliationStatus) || "unchanged".equals(retaliationStatus)))
				{
					Map<String, Object> rejected = new LinkedHashMap<>();
					rejected.put("status", "rejected");
					rejected.put("result", "client_behavior_auto_retaliate_failed");
					rejected.put("auto_retaliate", autoRetaliate);
					rejected.put("retaliation", retaliation);
					return rejected;
				}
				boolean applied = activityContext.applyIfCurrent(() ->
				{
					emergencyController.configureScriptBehavior(emergencyConsumables, emergencyEscape);
					combatGuard.configureScriptBehavior(combatPrayer);
				});
				if (!applied) return rejected("action_cancelled");
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "complete");
				receipt.put("result", "client_behaviors_configured");
				receipt.put("click_count", retaliation.getOrDefault("click_count", 0L));
				receipt.put("emergency_consumables", emergencyConsumables);
				receipt.put("emergency_escape", emergencyEscape);
				receipt.put("combat_prayer", combatPrayer);
				receipt.put("auto_retaliate", autoRetaliate);
				receipt.put("retaliation", retaliation);
				return receipt;
			});
	}

	static GenericClientEmergencyEscape emergencyEscape(
		Map<String, Object> action)
	{
		Object raw = action.get("escape");
		if (raw == null)
		{
			return null;
		}
		if (!(raw instanceof Map))
		{
			throw new IllegalArgumentException("safety.configure escape must be a table");
		}
		Map<?, ?> value = (Map<?, ?>) raw;
		String type = optionalText(value.get("type"));
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
		WorldPoint destination = new WorldPoint(x, y, plane);
		if (type == null || "walk".equalsIgnoreCase(type))
		{
			return new GenericClientEmergencyEscape(destination, within);
		}
		if ("inventory_dialogue".equalsIgnoreCase(type))
		{
			return GenericClientEmergencyEscape.inventoryDialogue(
				escapeItemIds(value),
				requiredText(value, "action", "safety.configure escape"),
				requiredText(value, "choice", "safety.configure escape"),
				destination,
				within);
		}
		throw new IllegalArgumentException("Unsupported safety escape type: " + type);
	}

	private static List<Integer> escapeItemIds(Map<?, ?> value)
	{
		List<Integer> itemIds = new ArrayList<>();
		itemIds.add(requiredInt(value, "item_id", "safety.configure escape"));
		Object rawAlternatives = value.get("alternative_item_ids");
		if (rawAlternatives != null)
		{
			if (!(rawAlternatives instanceof List))
			{
				throw new IllegalArgumentException(
					"safety.configure escape alternative_item_ids must be an array");
			}
			for (Object rawItemId : (List<?>) rawAlternatives)
			{
				if (!(rawItemId instanceof Number))
				{
					throw new IllegalArgumentException(
						"safety.configure escape alternative_item_ids must be numeric");
				}
				itemIds.add(((Number) rawItemId).intValue());
			}
		}		return itemIds;
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
		GenericClientActivityContext activityContext)
	{
		int itemId = requiredInt(action, "item_id", "item.use_on_object");
		int objectId = requiredInt(action, "object_id", "item.use_on_object");
		Integer slot = optionalSlot(action, "item.use_on_object");
		WorldPoint world = optionalWorld(action, "item.use_on_object");
		int within = within(action, "item.use_on_object");
		return inventoryInput.interact(itemId, slot, "Use", activityContext).thenCompose(selection ->
		{
			if (!wasDispatched(selection))
			{
				return CompletableFuture.completedFuture(selection);
			}
			return inventoryInput.waitForSelectedItem(itemId).thenCompose(selected ->
			{
				if (!selected)
				{
					return CompletableFuture.completedFuture(composite(
						"item_used_on_object", selection, rejected("requested_item_not_selected")));
				}
				return objectInput.useSelectedItemOnObject(
					objectId, world, within, itemId, null, activityContext)
					.thenCompose(target -> finishSelectedItemAction(
						"item_used_on_object", itemId, slot, selection, target, activityContext));
			});
		});
	}

	private CompletableFuture<Map<String, Object>> useItemOnNpc(
		Map<String, Object> action,
		GenericClientActivityContext activityContext)
	{
		int itemId = requiredInt(action, "item_id", "item.use_on_npc");
		int npcId = requiredInt(action, "npc_id", "item.use_on_npc");
		Integer slot = optionalSlot(action, "item.use_on_npc");
		String npcName = optionalText(action.get("npc_name"));
		int within = within(action, "item.use_on_npc");
		return inventoryInput.interact(itemId, slot, "Use", activityContext).thenCompose(selection ->
		{
			if (!wasDispatched(selection))
			{
				return CompletableFuture.completedFuture(selection);
			}
			return inventoryInput.waitForSelectedItem(itemId).thenCompose(selected ->
			{
				if (!selected)
				{
					return CompletableFuture.completedFuture(composite(
						"item_used_on_npc", selection, rejected("requested_item_not_selected")));
				}
				return npcInput.useSelectedItemOnNpc(
					npcId, npcName, within, itemId, null, activityContext)
					.thenCompose(target -> finishSelectedItemAction(
						"item_used_on_npc", itemId, slot, selection, target, activityContext));
			});
		});
	}

	private CompletableFuture<Map<String, Object>> useItemOnItem(
		Map<String, Object> action,
		GenericClientActivityContext activityContext)
	{
		int itemId = requiredInt(action, "item_id", "item.use_on_item");
		int targetItemId = requiredInt(action, "target_item_id", "item.use_on_item");
		Integer slot = optionalSlot(action, "item.use_on_item");
		Integer targetSlot = optionalInt(action, "target_slot", "item.use_on_item");
		if (targetSlot != null && (targetSlot < 0 || targetSlot >= 28))
		{
			throw new IllegalArgumentException(
				"item.use_on_item target_slot must be between 0 and 27");
		}
		if (itemId == targetItemId && slot == null && targetSlot == null)
		{
			throw new IllegalArgumentException(
				"item.use_on_item requires slots when both item ids match");
		}
		return inventoryInput.interact(itemId, slot, "Use", activityContext).thenCompose(selection ->
		{
			if (!wasDispatched(selection))
			{
				return CompletableFuture.completedFuture(selection);
			}
			return inventoryInput.waitForSelectedItem(itemId).thenCompose(selected ->
			{
				if (!selected)
				{
					return CompletableFuture.completedFuture(composite(
						"item_used_on_item", selection, rejected("requested_item_not_selected")));
				}
				return inventoryInput.interact(
					targetItemId, targetSlot, "Use", activityContext)
					.thenCompose(target -> finishSelectedItemAction(
						"item_used_on_item", itemId, slot, selection, target, activityContext));
			});
		});
	}

	private CompletableFuture<Map<String, Object>> finishSelectedItemAction(
		String result,
		int itemId,
		Integer slot,
		Map<String, Object> selection,
		Map<String, Object> target, GenericClientActivityContext activityContext)
	{
		Map<String, Object> receipt = composite(result, selection, target);
		if (wasDispatched(target))
		{
			return CompletableFuture.completedFuture(receipt);
		}
		return inventoryInput.clearSelectedItem(itemId, slot, activityContext).thenApply(cleanup ->
		{
			receipt.put("selection_cleanup", cleanup);
			receipt.put("click_count", clickCount(receipt) + clickCount(cleanup));
			return receipt;
		});
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

}
