package org.dreambot.api.methods.magic;

import com.genericclient.script.SnapshotData;
import com.genericclient.script.EntityReference;
import java.util.Map;
import org.dreambot.api.methods.container.impl.ContainerType;
import org.dreambot.api.wrappers.interactive.Entity;
import org.dreambot.api.wrappers.interactive.NPC;
import org.dreambot.api.wrappers.items.Item;

public final class Magic
{
	private Magic() {}
	public static boolean castSpellOn(Spell spell, Item item)
	{
		return item != null && item.getContainerType() == ContainerType.INVENTORY && item.exists() && SnapshotData.action("spell.cast_on_item",
			Map.of("spell", ((Normal) spell).action, "item_id", item.getId(), "slot", item.getSlot()));
	}
	public static boolean castSpellOn(Spell spell, Entity entity)
	{
		if (entity == null) return false;
		if (!(entity instanceof NPC)) throw new UnsupportedOperationException("Spell casting is supported for NPC targets");
		EntityReference reference = (EntityReference) entity.getReference();
		Map<?, ?> target = reference.read();
		return !target.isEmpty() && SnapshotData.action("combat.cast",
			Map.of("spell", ((Normal) spell).action, "npc_id", SnapshotData.integer(target,"id"),
				"npc_index", SnapshotData.integer(target,"index"), "entity_identity", reference.identity, "within", 32));
	}
	public static boolean castSpell(Spell spell)
	{
		if (spell != Normal.HOME_TELEPORT) throw new IllegalArgumentException("This spell requires a target");
		return SnapshotData.action("travel.home_teleport", Map.of());
	}
	public static boolean setAutocastSpell(Spell spell)
	{
		return SnapshotData.action("combat.set_autocast", Map.of("spell", ((Normal) spell).action));
	}
}
