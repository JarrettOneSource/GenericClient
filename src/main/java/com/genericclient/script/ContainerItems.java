package com.genericclient.script;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.dreambot.api.methods.container.impl.ContainerType;
import org.dreambot.api.methods.filter.Filter;
import org.dreambot.api.wrappers.items.Item;

public final class ContainerItems
{
	private ContainerItems() {}
	public static List<Item> all(ContainerType container)
	{
		return SnapshotData.rows(container.name().toLowerCase(Locale.ROOT), Map.of()).stream()
			.map(row -> new Item(row, container)).collect(Collectors.toList());
	}
	public static Item get(ContainerType container, Filter<Item> filter)
	{
		return all(container).stream().filter(filter::match).findFirst().orElse(null);
	}
	public static int count(ContainerType container, Filter<Item> filter)
	{
		return all(container).stream().filter(filter::match).mapToInt(Item::getAmount).sum();
	}
}
