package org.dreambot.api.methods.container.impl;

import com.genericclient.script.ContainerItems;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.dreambot.api.methods.filter.Filter;
import org.dreambot.api.wrappers.items.Item;

public final class Inventory
{
	private Inventory() {}
	public static List<Item> all() { return ContainerItems.all(ContainerType.INVENTORY); }
	public static List<Item> all(Filter<Item> filter) { return all().stream().filter(filter::match).collect(Collectors.toList()); }
	public static Item get(Filter<Item> filter) { return ContainerItems.get(ContainerType.INVENTORY, filter); }
	public static Item get(int id) { return get(item -> item.getId() == id); }
	public static Item get(String name) { return get(item -> name.equals(item.getName())); }
	public static int count(int id) { return count(item -> item.getId() == id); }
	public static int count(String name) { return count(item -> name.equals(item.getName())); }
	public static int count(Filter<Item> filter) { return ContainerItems.count(ContainerType.INVENTORY, filter); }
	public static boolean contains(int... ids) { return get(item -> Arrays.stream(ids).anyMatch(id -> item.getId() == id)) != null; }
	public static boolean contains(String... names) { return get(item -> Arrays.asList(names).contains(item.getName())) != null; }
	public static boolean contains(Filter<Item> filter) { return get(filter) != null; }
	public static boolean isEmpty() { return all().isEmpty(); }
	public static boolean isFull() { return emptySlotCount() == 0; }
	public static int emptySlotCount() { return 28 - all().size(); }
	public static boolean interact(int id, String action) { Item item = get(id); return item != null && item.interact(action); }
	public static boolean interact(String name, String action) { Item item = get(name); return item != null && item.interact(action); }
}
