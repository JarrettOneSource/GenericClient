package org.dreambot.api.methods.interactive;

import com.genericclient.script.SnapshotData;
import com.genericclient.script.EntityQueries;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.dreambot.api.methods.filter.Filter;
import org.dreambot.api.methods.map.Tile;
import org.dreambot.api.wrappers.interactive.NPC;

public final class NPCs
{
	private NPCs() {}
	public static List<NPC> all()
	{
		return SnapshotData.rows("npcs", Map.of("within", 104, "limit", Integer.MAX_VALUE)).stream().filter(npc -> !Boolean.TRUE.equals(npc.get("dead"))).map(NPC::new).collect(Collectors.toList());
	}
	public static List<NPC> all(Filter<NPC> filter) { return EntityQueries.matching(all(),filter); }
	public static List<NPC> all(Integer... ids) { return all(npc -> Arrays.asList(ids).contains(npc.getId())); }
	public static List<NPC> all(String... names) { return all(npc -> Arrays.asList(names).contains(npc.getName())); }
	public static NPC closest(Filter<NPC> filter) { return EntityQueries.closest(all(),filter,null); }
	public static NPC closest(Filter<NPC> filter, Tile toTile) { return EntityQueries.closest(all(),filter,toTile); }
	public static NPC closest(String... names) { return closest(npc -> Arrays.asList(names).contains(npc.getName())); }
	public static NPC closest(Integer... ids) { return closest(npc -> Arrays.asList(ids).contains(npc.getId())); }
}
