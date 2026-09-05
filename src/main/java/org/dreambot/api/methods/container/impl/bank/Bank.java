package org.dreambot.api.methods.container.impl.bank;

import com.genericclient.script.ContainerItems;
import org.dreambot.api.methods.container.impl.ContainerType;
import com.genericclient.script.SnapshotData;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.dreambot.api.methods.container.impl.Inventory;
import org.dreambot.api.methods.filter.Filter;
import org.dreambot.api.methods.interactive.GameObjects;
import org.dreambot.api.methods.interactive.NPCs;
import org.dreambot.api.utilities.Sleep;
import org.dreambot.api.wrappers.interactive.GameObject;
import org.dreambot.api.wrappers.interactive.NPC;
import org.dreambot.api.wrappers.items.Item;

public final class Bank
{
	private Bank() {}
	public static boolean isOpen() { return Boolean.TRUE.equals(SnapshotData.read("bank").get("open")); }
	public static List<Item> all() { return ContainerItems.all(ContainerType.BANK); }
	public static Item get(Filter<Item> filter) { return ContainerItems.get(ContainerType.BANK, filter); }
	public static Item get(int id) { return get(item -> item.getId() == id); }
	public static Item get(String name) { return get(item -> name.equals(item.getName())); }
	public static int count(int id) { return ContainerItems.count(ContainerType.BANK, item -> item.getId() == id); }
	public static boolean contains(int... ids) { return get(item -> Arrays.stream(ids).anyMatch(id -> item.getId() == id)) != null; }
	public static boolean open()
	{
		if (isOpen()) return true;
		NPC banker = NPCs.closest(npc -> npc.hasAction("Bank"));
		if (banker != null && banker.interact("Bank")) return Sleep.sleepUntil(Bank::isOpen, 6000);
		GameObject booth = GameObjects.closest(object -> (object.hasAction("Bank") || object.hasAction("Use")) &&
			(object.getName().equals("Bank booth") || object.getName().equals("Bank chest")));
		if (booth == null) return false;
		String action = booth.hasAction("Bank") ? "Bank" : "Use";
		return booth.interact(action) && Sleep.sleepUntil(Bank::isOpen, 6000);
	}
	public static boolean close() { return !isOpen() || SnapshotData.action("bank.close", Map.of()); }
	public static boolean depositAllItems()
	{
		return isOpen() && (Inventory.isEmpty() || SnapshotData.action("bank.deposit_inventory", Map.of()));
	}
	public static boolean depositAllEquipment() { return isOpen() && SnapshotData.action("bank.deposit_equipment", Map.of()); }
	public static boolean withdraw(int id, int quantity) { return transfer("bank.withdraw", id, quantity, false); }
	public static boolean withdraw(String name, int quantity) { Item item = get(name); return item != null && withdraw(item.getId(), quantity); }
	public static boolean withdrawAll(int id) { return transfer("bank.withdraw", id, 1, true); }
	public static boolean deposit(int id, int quantity) { return transfer("bank.deposit", id, quantity, false); }
	public static boolean depositAll(int id) { return transfer("bank.deposit", id, 1, true); }
	private static boolean transfer(String operation, int id, int quantity, boolean all)
	{
		return isOpen() && SnapshotData.action(operation, Map.of("id", id, "quantity", quantity, "all", all));
	}
}
