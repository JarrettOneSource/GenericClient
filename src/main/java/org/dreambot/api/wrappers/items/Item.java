package org.dreambot.api.wrappers.items;

import com.genericclient.script.ContainerItems;
import com.genericclient.script.EntityReference;
import com.genericclient.script.SnapshotData;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dreambot.api.methods.container.impl.ContainerType;
import org.dreambot.api.wrappers.interactive.Entity;
import org.dreambot.api.wrappers.interactive.GameObject;
import org.dreambot.api.wrappers.interactive.NPC;

public class Item implements org.dreambot.api.wrappers.interactive.Identifiable
{
	private final Map<?, ?> data;
	private final ContainerType container;
	public Item(Map<?, ?> snapshot, ContainerType container) { data = snapshot; this.container = container; }
	public ContainerType getContainerType() { return container; }
	public int getId() { return SnapshotData.integer(data, "id"); }
	public String getName() { return (String) data.get("name"); }
	public int getAmount() { return SnapshotData.integer(data, "quantity"); }
	public int getSlot() { return SnapshotData.integer(data, "slot"); }
	public boolean isStackable() { return Boolean.TRUE.equals(data.get("stackable")); }
	public String[] getActions() { return SnapshotData.strings(data.get("actions")); }
	public boolean hasAction(String... actions)
	{
		return Arrays.stream(actions).anyMatch(action -> Arrays.asList(getActions()).contains(action));
	}
	public boolean exists()
	{
		return ContainerItems.get(container, item -> item.getId() == getId() && item.getSlot() == getSlot()) != null;
	}
	public boolean interact(String action)
	{
		if (!exists()) return false;
		if (container == ContainerType.EQUIPMENT) return SnapshotData.action("equipment.interact", Map.of("id", getId(), "action", action));
		if (container != ContainerType.INVENTORY) throw new IllegalStateException("Use Bank operations for bank items");
		return SnapshotData.action("item.interact", Map.of("id", getId(), "slot", getSlot(), "action", action));
	}
	public boolean useOn(Item other)
	{
		return container == ContainerType.INVENTORY && other.container == ContainerType.INVENTORY && exists() && other.exists() &&
			SnapshotData.action("item.use_on_item", Map.of(
			"item_id", getId(), "slot", getSlot(), "target_item_id", other.getId(), "target_slot", other.getSlot()));
	}
	public boolean useOn(Entity entity)
	{
		if (container != ContainerType.INVENTORY || !exists()) return false;
		EntityReference reference = (EntityReference)entity.getReference();
		Map<?, ?> target = reference.read();
		if (target.isEmpty()) return false;
		Map<String,Object> parameters = new LinkedHashMap<>(Map.of("item_id",getId(),"slot",getSlot(),
			"entity_identity",reference.identity,"within",32));
		if (entity instanceof GameObject)
		{
			parameters.put("object_id",SnapshotData.integer(target,"id"));
			parameters.put("world",target.get("world"));
			return SnapshotData.action("item.use_on_object",parameters);
		}
		if (entity instanceof NPC)
		{
			parameters.put("npc_id",SnapshotData.integer(target,"id"));
			parameters.put("npc_index",SnapshotData.integer(target,"index"));
			return SnapshotData.action("item.use_on_npc",parameters);
		}
		throw new UnsupportedOperationException("Item use is unavailable for " + entity.getClass().getName());
	}
}
