package org.dreambot.api.methods.map;

import org.dreambot.api.wrappers.interactive.Locatable;

public class Area
{
	private final int minimumX;
	private final int maximumX;
	private final int minimumY;
	private final int maximumY;
	private final int plane;
	public Area(int x1, int y1, int x2, int y2) { this(x1, y1, x2, y2, 0); }
	public Area(int x1, int y1, int x2, int y2, int plane)
	{
		minimumX = Math.min(x1, x2); maximumX = Math.max(x1, x2);
		minimumY = Math.min(y1, y2); maximumY = Math.max(y1, y2);
		this.plane = plane;
	}
	public boolean contains(Locatable value)
	{
		if (value == null) return false;
		Tile tile = value.getTile();
		return tile.getZ() == plane && tile.getX() >= minimumX && tile.getX() <= maximumX &&
			tile.getY() >= minimumY && tile.getY() <= maximumY;
	}
	public Tile getCenter() { return new Tile((minimumX + maximumX) / 2, (minimumY + maximumY) / 2, plane); }
}
