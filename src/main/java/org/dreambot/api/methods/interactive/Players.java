package org.dreambot.api.methods.interactive;

import com.genericclient.script.SnapshotData;
import com.genericclient.script.EntityQueries;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.dreambot.api.methods.filter.Filter;
import org.dreambot.api.methods.map.Tile;
import org.dreambot.api.wrappers.interactive.Player;

public final class Players
{
	private Players() {}
	public static List<Player> all()
	{
		return SnapshotData.rows("players",Map.of()).stream().map(Player::new).collect(Collectors.toList());
	}
	public static List<Player> all(Filter<Player> filter) { return EntityQueries.matching(all(),filter); }
	public static List<Player> all(Integer... ids) { return all(player -> Arrays.asList(ids).contains(player.getId())); }
	public static List<Player> all(String... names) { return all(player -> Arrays.asList(names).contains(player.getName())); }
	public static Player closest(Filter<Player> filter) { return EntityQueries.closest(all(),filter,null); }
	public static Player closest(Filter<Player> filter, Tile toTile) { return EntityQueries.closest(all(),filter,toTile); }
	public static Player closest(Integer... ids) { return closest(player -> Arrays.asList(ids).contains(player.getId())); }
	public static Player closest(String... names) { return closest(player -> Arrays.asList(names).contains(player.getName())); }
	public static Player getAtIndex(int index) { return all(player -> player.getIndex() == index).stream().findFirst().orElse(null); }
	public static boolean referenceExists(int index) { return getAtIndex(index) != null; }
	public static Player[] getArray() { return all().toArray(Player[]::new); }
	public static Player getLocal()
	{
		Map<?, ?> player = SnapshotData.read("local_player");
		return player.isEmpty() ? null : new Player(player);
	}
}
