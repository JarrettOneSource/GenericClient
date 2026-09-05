package org.dreambot.api.methods.map;

import java.util.Objects;
import org.dreambot.api.methods.interactive.Players;
import org.dreambot.api.wrappers.interactive.Locatable;
import org.dreambot.api.wrappers.interactive.Player;

public class Tile implements Locatable
{
	private final int x;
	private final int y;
	private final int z;
	public Tile(int x, int y) { this(x, y, 0); }
	public Tile(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
	public int getX() { return x; }
	public int getY() { return y; }
	public int getZ() { return z; }
	@Override public Tile getTile() { return this; }
	@Override public double distance()
	{
		Player player = Players.getLocal();
		return player == null || !player.exists() ? Double.POSITIVE_INFINITY : distance(player.getTile());
	}
	public double distance(Tile other)
	{
		return z == other.z ? Math.hypot((double) x - other.x, (double) y - other.y) : Double.POSITIVE_INFINITY;
	}
	public Tile translate(int dx, int dy) { return new Tile(x + dx, y + dy, z); }
	@Override public boolean equals(Object other)
	{
		if (!(other instanceof Tile)) return false;
		Tile tile = (Tile) other;
		return x == tile.x && y == tile.y && z == tile.z;
	}
	@Override public int hashCode() { return Objects.hash(x, y, z); }
	@Override public String toString() { return "(" + x + ", " + y + ", " + z + ")"; }
}
