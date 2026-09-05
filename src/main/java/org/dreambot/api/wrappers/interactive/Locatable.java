package org.dreambot.api.wrappers.interactive;

import org.dreambot.api.methods.map.Tile;

public interface Locatable
{
	Tile getTile();
	default double distance() { return getTile().distance(); }
	default double distance(Locatable other) { return getTile().distance(other.getTile()); }
}
