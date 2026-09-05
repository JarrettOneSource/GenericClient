package com.genericclient.script;

import java.util.Map;
import java.util.stream.Collectors;

/** Verified complete inventory preparation for catalog workflows. */
public final class Banking
{
	private Banking() {}
	public static boolean loadout(Map<Integer, Integer> items, int freeSlots, boolean close)
	{
		boolean completed = SnapshotData.action("bank.loadout", Map.of("items", items.entrySet().stream()
			.map(item -> Map.of("id", item.getKey(), "quantity", item.getValue())).collect(Collectors.toList()),
			"minimum_free_slots", freeSlots, "close", close));
		return completed && org.dreambot.api.utilities.Sleep.sleepUntil(() -> items.entrySet().stream()
			.allMatch(item -> org.dreambot.api.methods.container.impl.Inventory.count(item.getKey()) == item.getValue()) &&
			org.dreambot.api.methods.container.impl.Inventory.emptySlotCount() >= freeSlots, 6000);
	}
}
