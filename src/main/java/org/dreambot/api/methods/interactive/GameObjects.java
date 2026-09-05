package org.dreambot.api.methods.interactive;

import com.genericclient.script.SnapshotData;
import com.genericclient.script.EntityQueries;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.dreambot.api.methods.filter.Filter;
import org.dreambot.api.methods.map.Tile;
import org.dreambot.api.wrappers.interactive.GameObject;

public final class GameObjects
{
	private GameObjects() {}
	public static List<GameObject> all()
	{
		return SnapshotData.rows("objects", Map.of("within", 104, "limit", Integer.MAX_VALUE)).stream().map(GameObject::new).collect(Collectors.toList());
	}
	public static List<GameObject> all(Filter<GameObject> filter) { return EntityQueries.matching(all(),filter); }
	public static List<GameObject> all(Integer... ids) { return all(object -> Arrays.asList(ids).contains(object.getId())); }
	public static List<GameObject> all(String... names) { return all(object -> Arrays.asList(names).contains(object.getName())); }
	public static GameObject closest(Filter<GameObject> filter)
	{
		return EntityQueries.closest(all(),filter,null);
	}
	public static GameObject closest(Filter<GameObject> filter, Tile toTile) { return EntityQueries.closest(all(),filter,toTile); }
	public static GameObject closest(String... names) { return closest(object -> Arrays.asList(names).contains(object.getName())); }
	public static GameObject closest(Integer... ids) { return closest(object -> Arrays.asList(ids).contains(object.getId())); }
}
