package org.dreambot.api.methods.container.impl.equipment;

import com.genericclient.script.ContainerItems;
import org.dreambot.api.methods.container.impl.ContainerType;
import java.util.Arrays;
import java.util.List;
import org.dreambot.api.methods.filter.Filter;
import org.dreambot.api.wrappers.items.Item;

public final class Equipment
{
	private Equipment() {}
	public static List<Item> all() { return ContainerItems.all(ContainerType.EQUIPMENT); }
	public static Item get(Filter<Item> filter) { return ContainerItems.get(ContainerType.EQUIPMENT, filter); }
	public static boolean contains(int... ids) { return get(item -> Arrays.stream(ids).anyMatch(id -> item.getId() == id)) != null; }
	public static boolean contains(String... names) { return get(item -> Arrays.asList(names).contains(item.getName())) != null; }
	public static Item getItemInSlot(int slot) { return get(item -> item.getSlot() == slot); }
}
