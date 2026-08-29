package com.genericclient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

final class GenericClientSceneCollision
{
	private static final int SCENE_BORDER_SENTINEL = 0xFFFFFF;
	private static final int UNINITIALIZED_SENTINEL = 0x1000000;
	private static final GenericClientSceneCollision EMPTY =
		new GenericClientSceneCollision(false, 0, 0, 0, new int[0][]);

	private final boolean available;
	private final int baseX;
	private final int baseY;
	private final int plane;
	private final int[][] flags;
	private final boolean[][] loaded;

	GenericClientSceneCollision(
		boolean available,
		int baseX,
		int baseY,
		int plane,
		int[][] flags)
	{
		this(available, baseX, baseY, plane, flags, allLoaded(flags));
	}

	GenericClientSceneCollision(
		boolean available,
		int baseX,
		int baseY,
		int plane,
		int[][] flags,
		boolean[][] loaded)
	{
		this.available = available;
		this.baseX = baseX;
		this.baseY = baseY;
		this.plane = plane;
		this.flags = copy(flags);
		this.loaded = copy(loaded);
	}

	static GenericClientSceneCollision empty()
	{
		return EMPTY;
	}

	static GenericClientSceneCollision capture(WorldView worldView, int plane)
	{
		if (worldView == null || worldView.isInstance())
		{
			return empty();
		}
		CollisionData[] maps = worldView.getCollisionMaps();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null ||
			maps[plane].getFlags() == null)
		{
			return empty();
		}
		Scene scene = worldView.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		if (tiles == null || plane >= tiles.length || tiles[plane] == null)
		{
			return empty();
		}
		int[][] flags = maps[plane].getFlags();
		boolean[][] loaded = new boolean[flags.length][];
		for (int x = 0; x < flags.length; x++)
		{
			int height = flags[x] == null ? 0 : flags[x].length;
			loaded[x] = new boolean[height];
			for (int y = 0; y < height; y++)
			{
				loaded[x][y] = x < tiles[plane].length && tiles[plane][x] != null &&
					y < tiles[plane][x].length && tiles[plane][x][y] != null;
			}
		}
		return new GenericClientSceneCollision(
			true,
			worldView.getBaseX(),
			worldView.getBaseY(),
			plane,
			flags,
			loaded);
	}

	boolean isAvailable()
	{
		return available;
	}

	Boolean canMove(WorldPoint from, WorldPoint to)
	{
		if (!available || from == null || to == null || from.getPlane() != plane ||
			to.getPlane() != plane)
		{
			return null;
		}
		int dx = to.getX() - from.getX();
		int dy = to.getY() - from.getY();
		if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || dx == 0 && dy == 0)
		{
			return null;
		}
		if (dx != 0 && dy != 0)
		{
			Integer target = flagAtOrNull(to);
			Integer horizontal = flagAtOrNull(
				new WorldPoint(from.getX() + dx, from.getY(), plane));
			Integer vertical = flagAtOrNull(
				new WorldPoint(from.getX(), from.getY() + dy, plane));
			if (target == null || horizontal == null || vertical == null)
			{
				return null;
			}
			int xFlags = CollisionDataFlag.BLOCK_MOVEMENT_FULL |
				(dx < 0 ? CollisionDataFlag.BLOCK_MOVEMENT_EAST :
					CollisionDataFlag.BLOCK_MOVEMENT_WEST);
			int yFlags = CollisionDataFlag.BLOCK_MOVEMENT_FULL |
				(dy < 0 ? CollisionDataFlag.BLOCK_MOVEMENT_NORTH :
					CollisionDataFlag.BLOCK_MOVEMENT_SOUTH);
			int diagonalFlags = CollisionDataFlag.BLOCK_MOVEMENT_FULL | cornerFlag(-dx, -dy);
			return (target & xFlags) == 0 && (target & yFlags) == 0 &&
				(target & diagonalFlags) == 0 && (horizontal & xFlags) == 0 &&
				(vertical & yFlags) == 0;
		}
		return cardinalCanMove(from, to);
	}

	Map<String, Object> inspect(WorldPoint from, WorldPoint to)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("available", available);
		value.put("plane", (long) plane);
		value.put("base", worldMap(new WorldPoint(baseX, baseY, plane)));
		value.put("from", tileMap(from));
		value.put("to", tileMap(to));
		value.put("can_move", canMove(from, to));
		return value;
	}

	private Boolean cardinalCanMove(WorldPoint from, WorldPoint to)
	{
		Integer target = flagAtOrNull(to);
		if (target == null)
		{
			return null;
		}
		int dx = to.getX() - from.getX();
		int dy = to.getY() - from.getY();
		int targetWall;
		if (dx == 1 && dy == 0)
		{
			targetWall = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		}
		else if (dx == -1 && dy == 0)
		{
			targetWall = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		}
		else if (dx == 0 && dy == 1)
		{
			targetWall = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
		}
		else if (dx == 0 && dy == -1)
		{
			targetWall = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
		}
		else
		{
			return null;
		}
		return (target & (CollisionDataFlag.BLOCK_MOVEMENT_FULL | targetWall)) == 0;
	}

	private Integer flagAtOrNull(WorldPoint point)
	{
		if (!available || point == null || point.getPlane() != plane)
		{
			return null;
		}
		int sceneX = point.getX() - baseX;
		int sceneY = point.getY() - baseY;
		if (sceneX < 0 || sceneX >= flags.length || flags[sceneX] == null ||
			sceneY < 0 || sceneY >= flags[sceneX].length ||
			sceneX >= loaded.length || loaded[sceneX] == null ||
			sceneY >= loaded[sceneX].length || !loaded[sceneX][sceneY])
		{
			return null;
		}
		int tileFlags = flags[sceneX][sceneY];
		if (tileFlags == SCENE_BORDER_SENTINEL ||
			(tileFlags & UNINITIALIZED_SENTINEL) != 0)
		{
			return null;
		}
		return tileFlags;
	}

	private Map<String, Object> tileMap(WorldPoint point)
	{
		if (point == null)
		{
			return null;
		}
		Map<String, Object> value = worldMap(point);
		Integer tileFlags = flagAtOrNull(point);
		value.put("loaded", tileFlags != null);
		value.put("flags", tileFlags == null ? null : Integer.toUnsignedLong(tileFlags));
		value.put("blocked_by", tileFlags == null ? new ArrayList<>() : flagNames(tileFlags));
		return value;
	}

	private static List<String> flagNames(int flags)
	{
		List<String> names = new ArrayList<>();
		addFlag(names, flags, CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST,
			"movement_north_west");
		addFlag(names, flags, CollisionDataFlag.BLOCK_MOVEMENT_NORTH, "movement_north");
		addFlag(names, flags, CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST,
			"movement_north_east");
		addFlag(names, flags, CollisionDataFlag.BLOCK_MOVEMENT_EAST, "movement_east");
		addFlag(names, flags, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST,
			"movement_south_east");
		addFlag(names, flags, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH, "movement_south");
		addFlag(names, flags, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST,
			"movement_south_west");
		addFlag(names, flags, CollisionDataFlag.BLOCK_MOVEMENT_WEST, "movement_west");
		addFlag(names, flags, CollisionDataFlag.BLOCK_MOVEMENT_OBJECT, "movement_object");
		addFlag(names, flags, CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION,
			"movement_floor_decoration");
		addFlag(names, flags, CollisionDataFlag.BLOCK_MOVEMENT_FLOOR, "movement_floor");
		addFlag(names, flags, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_NORTH,
			"line_of_sight_north");
		addFlag(names, flags, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_EAST,
			"line_of_sight_east");
		addFlag(names, flags, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_SOUTH,
			"line_of_sight_south");
		addFlag(names, flags, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_WEST,
			"line_of_sight_west");
		addFlag(names, flags, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL,
			"line_of_sight_full");
		return names;
	}

	private static void addFlag(List<String> names, int flags, int flag, String name)
	{
		if ((flags & flag) != 0)
		{
			names.add(name);
		}
	}

	private static int cornerFlag(int dx, int dy)
	{
		if (dx < 0 && dy < 0)
		{
			return CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
		}
		if (dx > 0 && dy < 0)
		{
			return CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
		}
		if (dx < 0)
		{
			return CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
		}
		return CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
	}

	private static int[][] copy(int[][] source)
	{
		int[][] result = new int[source.length][];
		for (int index = 0; index < source.length; index++)
		{
			result[index] = source[index] == null ? null : source[index].clone();
		}
		return result;
	}

	private static boolean[][] copy(boolean[][] source)
	{
		boolean[][] result = new boolean[source.length][];
		for (int index = 0; index < source.length; index++)
		{
			result[index] = source[index] == null ? null : source[index].clone();
		}
		return result;
	}

	private static boolean[][] allLoaded(int[][] flags)
	{
		boolean[][] loaded = new boolean[flags.length][];
		for (int index = 0; index < flags.length; index++)
		{
			int length = flags[index] == null ? 0 : flags[index].length;
			loaded[index] = new boolean[length];
			Arrays.fill(loaded[index], true);
		}
		return loaded;
	}

	private static Map<String, Object> worldMap(WorldPoint point)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("x", (long) point.getX());
		value.put("y", (long) point.getY());
		value.put("plane", (long) point.getPlane());
		return value;
	}
}
