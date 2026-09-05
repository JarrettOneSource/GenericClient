package com.genericclient;

import java.util.function.Consumer;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;

/** Visits loaded tiles in the player's current scene without copying the grid. */
final class GenericClientSceneTiles
{
	private GenericClientSceneTiles() {}

	static void visitNearby(Player player, int radius, Consumer<Tile> visit)
	{
		Scene scene = player.getWorldView().getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		if (tiles == null || tiles.length == 0) return;
		int plane = Math.max(0, Math.min(tiles.length - 1, player.getWorldLocation().getPlane()));
		Tile[][] grid = tiles[plane];
		if (grid.length == 0 || grid[0].length == 0) return;
		LocalPoint local = player.getLocalLocation();
		int minX = Math.max(0, local.getSceneX() - radius);
		int maxX = Math.min(grid.length - 1, local.getSceneX() + radius);
		int minY = Math.max(0, local.getSceneY() - radius);
		int maxY = Math.min(grid[0].length - 1, local.getSceneY() + radius);
		for (int x = minX; x <= maxX; x++)
		{
			for (int y = minY; y <= maxY; y++)
			{
				Tile tile = grid[x][y];
				if (tile != null) visit.accept(tile);
			}
		}
	}
}
