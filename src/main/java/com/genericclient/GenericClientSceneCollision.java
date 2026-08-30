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
		new GenericClientSceneCollision(false, 0, 0, 0, new int[0][], new boolean[0][],
			false, new int[0][][]);

	private final boolean available;
	private final int baseX;
	private final int baseY;
	private final int plane;
	private final int[][] flags;
	private final boolean[][] loaded;
	private final boolean instance;
	private final int[][][] instanceTemplateChunks;

	GenericClientSceneCollision(
		boolean available,
		int baseX,
		int baseY,
		int plane,
		int[][] flags)
	{
		this(available, baseX, baseY, plane, flags, allLoaded(flags), false, new int[0][][]);
	}

	GenericClientSceneCollision(
		boolean available,
		int baseX,
		int baseY,
		int plane,
		int[][] flags,
		boolean[][] loaded)
	{
		this(available, baseX, baseY, plane, flags, loaded, false, new int[0][][]);
	}

	GenericClientSceneCollision(
		boolean available,
		int baseX,
		int baseY,
		int plane,
		int[][] flags,
		boolean[][] loaded,
		boolean instance,
		int[][][] instanceTemplateChunks)
	{
		this.available = available;
		this.baseX = baseX;
		this.baseY = baseY;
		this.plane = plane;
		this.flags = copy(flags);
		this.loaded = copy(loaded);
		this.instance = instance;
		this.instanceTemplateChunks = copy(instanceTemplateChunks);
	}

	static GenericClientSceneCollision empty()
	{
		return EMPTY;
	}

	static GenericClientSceneCollision capture(WorldView worldView, int plane)
	{
		if (worldView == null)
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
			loaded,
			worldView.isInstance(),
			worldView.getInstanceTemplateChunks());
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
		value.put("instance", instance);
		value.put("plane", (long) plane);
		value.put("base", worldMap(new WorldPoint(baseX, baseY, plane)));
		value.put("from", tileMap(from));
		value.put("to", tileMap(to));
		value.put("can_move", canMove(from, to));
		return value;
	}

	Map<String, Object> inspectInstance(WorldPoint template)
	{
		if (template == null)
		{
			throw new IllegalArgumentException("instance reads require a template world point");
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("available", available);
		value.put("instance", instance);
		value.put("template", worldMap(template));
		List<Map<String, Object>> matches = new ArrayList<>();
		if (available && instance)
		{
			for (WorldPoint match : toLocalInstance(template))
			{
				matches.add(worldMap(match));
			}
		}
		else if (available && template.getPlane() == plane &&
			template.getX() >= baseX && template.getX() < baseX + flags.length)
		{
			matches.add(worldMap(template));
		}
		value.put("matches", matches);
		return value;
	}

	private List<WorldPoint> toLocalInstance(WorldPoint template)
	{
		List<WorldPoint> matches = new ArrayList<>();
		for (int localPlane = 0; localPlane < instanceTemplateChunks.length; localPlane++)
		{
			int[][] planeChunks = instanceTemplateChunks[localPlane];
			if (planeChunks == null)
			{
				continue;
			}
			for (int chunkX = 0; chunkX < planeChunks.length; chunkX++)
			{
				int[] column = planeChunks[chunkX];
				if (column == null)
				{
					continue;
				}
				for (int chunkY = 0; chunkY < column.length; chunkY++)
				{
					int chunk = column[chunkY];
					int rotation = chunk >> 1 & 3;
					int templateY = (chunk >> 3 & 2047) * 8;
					int templateX = (chunk >> 14 & 1023) * 8;
					int templatePlane = chunk >> 24 & 3;
					if (template.getPlane() != templatePlane ||
						template.getX() < templateX || template.getX() >= templateX + 8 ||
						template.getY() < templateY || template.getY() >= templateY + 8)
					{
						continue;
					}
					WorldPoint local = new WorldPoint(
						baseX + chunkX * 8 + (template.getX() & 7),
						baseY + chunkY * 8 + (template.getY() & 7),
						localPlane);
					matches.add(rotate(local, rotation));
				}
			}
		}
		return matches;
	}

	private static WorldPoint rotate(WorldPoint point, int rotation)
	{
		int baseX = point.getX() & -8;
		int baseY = point.getY() & -8;
		int x = point.getX() & 7;
		int y = point.getY() & 7;
		switch (rotation)
		{
			case 1:
				return new WorldPoint(baseX + y, baseY + 7 - x, point.getPlane());
			case 2:
				return new WorldPoint(baseX + 7 - x, baseY + 7 - y, point.getPlane());
			case 3:
				return new WorldPoint(baseX + 7 - y, baseY + x, point.getPlane());
			default:
				return point;
		}
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

	private static int[][][] copy(int[][][] source)
	{
		if (source == null)
		{
			return new int[0][][];
		}
		int[][][] result = new int[source.length][][];
		for (int index = 0; index < source.length; index++)
		{
			result[index] = source[index] == null ? null : copy(source[index]);
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
