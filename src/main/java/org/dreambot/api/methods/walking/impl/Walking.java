package org.dreambot.api.methods.walking.impl;

import com.genericclient.script.ScriptScope;
import com.genericclient.script.SnapshotData;
import java.util.Map;
import org.dreambot.api.methods.interactive.Players;
import org.dreambot.api.methods.map.Tile;
import org.dreambot.api.wrappers.interactive.Locatable;
import org.dreambot.api.wrappers.interactive.Player;

public final class Walking
{
	private Walking() {}
	public static boolean walk(Tile destination)
	{
		Map<String, Object> result = ScriptScope.current().execute("walk.step", Map.of(
			"destination", Map.of("x", destination.getX(), "y", destination.getY(), "plane", destination.getZ()), "within", 0, "timeout_ticks", 100), 60_000);
		return "stepped".equals(result.get("status")) || "arrived".equals(result.get("status"));
	}
	public static boolean walk(Locatable destination) { return walk(destination.getTile()); }
	public static boolean walk(int x, int y) { return walk(new Tile(x, y)); }
	public static boolean walk(int x, int y, int z) { return walk(new Tile(x, y, z)); }
	public static Tile getDestination()
	{
		Map<?, ?> destination = SnapshotData.map(SnapshotData.read("player").get("destination"));
		return destination.isEmpty() ? null : new Tile(SnapshotData.integer(destination, "x"),
			SnapshotData.integer(destination, "y"), SnapshotData.integer(destination, "plane"));
	}
	public static boolean shouldWalk() { return shouldWalk(4); }
	public static boolean shouldWalk(int distance)
	{
		Player player = Players.getLocal();
		if (player == null || !player.exists()) return false;
		Tile destination = getDestination();
		return !player.isMoving() || destination == null || player.distance(destination) < distance;
	}
	public static int getRunEnergy() { return SnapshotData.integer(SnapshotData.read("player"), "run_energy") / 100; }
	public static boolean isRunEnabled() { return Boolean.TRUE.equals(SnapshotData.read("player").get("run_enabled")); }
}
