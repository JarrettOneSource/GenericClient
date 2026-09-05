package com.genericclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class GenericClientCollisionMap
{
	static final String SOURCE_REVISION = "44a691aafad48bd8f4ef6d00680d627d2aa8153c";
	static final String SOURCE_SHA256 = "2fca3c83778995c96a6511cc523e157352ef526f3b0a969892b62010d5c5e717";
	static final int SOURCE_CACHE_ID = 2664;
	static final int SOURCE_GAME_REVISION = 240;
	static final String DOOR_DUMPER_REVISION = "51d94a082fcd5f1c80be83656e3e27a820e46b27";
	static final String DOOR_RUNELITE_REVISION = "84402b97c378ce2aeed93d633940e3307a4d377b";
	static final int DOOR_SOURCE_CACHE_ID = 2686;
	static final int DOOR_SOURCE_GAME_REVISION = 240;
	static final String DOOR_SOURCE_SHA256 = "a5d95b4ddecda08bf0016af72f48b358b68d34d0af7930c3ae55eb57cd3eb2ec";

	private static final String RESOURCE = "/com/genericclient/navigation/collision-map.zip";
	private static final String DOOR_RESOURCE = "/com/genericclient/navigation/door-map.zip";
	private static final String SUPPLEMENT_RESOURCE = "/com/genericclient/navigation/edge-supplement.json";
	private static final int REGION_SIZE = 64;
	private static final int REGION_MASK = REGION_SIZE - 1;
	private static final int FLAG_COUNT = 2;
	private static final int REGIONS_PER_AXIS = 512;

	private final byte[][] regions;
	private final byte[][] doorRegions;

	static void reportRevisionDrift(int liveRevision, java.util.function.Consumer<String> reporter)
	{
		if (liveRevision <= 0)
		{
			reporter.accept("NAVIGATION_MAP_REVISION_UNAVAILABLE live=" + liveRevision);
			return;
		}
		if (liveRevision != SOURCE_GAME_REVISION)
			reporter.accept("NAVIGATION_MAP_REVISION_DRIFT map=collision live=" + liveRevision +
				" bundled=" + SOURCE_GAME_REVISION + " cache=" + SOURCE_CACHE_ID);
		if (liveRevision != DOOR_SOURCE_GAME_REVISION)
			reporter.accept("NAVIGATION_MAP_REVISION_DRIFT map=doors live=" + liveRevision +
				" bundled=" + DOOR_SOURCE_GAME_REVISION + " cache=" + DOOR_SOURCE_CACHE_ID);
	}

	private GenericClientCollisionMap(
		byte[][] regions,
		byte[][] doorRegions)
	{
		this.regions = regions;
		this.doorRegions = doorRegions;
	}

	static GenericClientCollisionMap loadBundled() throws IOException
	{
		byte[][] regions = loadRegions(RESOURCE);
		byte[][] doorRegions = loadRegions(DOOR_RESOURCE);
		GenericClientCollisionMap map = new GenericClientCollisionMap(regions, doorRegions);
		try (InputStream resource = GenericClientCollisionMap.class.getResourceAsStream(SUPPLEMENT_RESOURCE))
		{
			if (resource == null)
			{
				throw new IOException("Missing navigation edge supplement");
			}
			for (JsonElement value : new Gson().fromJson(
				new InputStreamReader(resource, StandardCharsets.UTF_8), JsonArray.class))
			{
				JsonObject edge = value.getAsJsonObject();
				if (edge.get("object").getAsString().isEmpty() || edge.get("action").getAsString().isEmpty() ||
					edge.get("note").getAsString().isEmpty() || edge.get("added").getAsString().isEmpty())
				{
					throw new IllegalArgumentException("Supplement edges require traversal and evidence metadata");
				}
				map.addTraversalEdge(
					edge.getAsJsonArray("from").get(0).getAsInt(), edge.getAsJsonArray("from").get(1).getAsInt(),
					edge.getAsJsonArray("to").get(0).getAsInt(), edge.getAsJsonArray("to").get(1).getAsInt(),
					edge.get("plane").getAsInt());
			}
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Invalid navigation edge supplement", exception);
		}
		return map;
	}

	void addTraversalEdge(int fromX, int fromY, int toX, int toY, int plane)
	{
		if (plane < 0 || plane > 3 || Math.abs(fromX - toX) + Math.abs(fromY - toY) != 1 ||
			Math.min(fromX, toX) < 0 || Math.min(fromY, toY) < 0 ||
			Math.max(fromX, toX) > 0x7FFF || Math.max(fromY, toY) > 0x7FFF)
		{
			throw new IllegalArgumentException("Traversal supplement requires adjacent cardinal world tiles");
		}
		int x = Math.min(fromX, toX);
		int y = Math.min(fromY, toY);
		int flag = fromX == toX ? 0 : 1;
		set(regions, x, y, plane, flag);
		set(doorRegions, x, y, plane, flag);
	}

	private static void set(byte[][] source, int x, int y, int plane, int flag)
	{
		int key = packRegion(x / REGION_SIZE, y / REGION_SIZE);
		int bit = (plane * REGION_SIZE * REGION_SIZE + (y & REGION_MASK) * REGION_SIZE +
			(x & REGION_MASK)) * FLAG_COUNT + flag;
		int byteIndex = bit >>> 3;
		byte[] data = source[key];
		if (data == null || data.length <= byteIndex)
		{
			data = data == null ? new byte[byteIndex + 1] : java.util.Arrays.copyOf(data, byteIndex + 1);
			source[key] = data;
		}
		data[byteIndex] |= (byte) (1 << (bit & 7));
	}

	private static byte[][] loadRegions(String resourceName) throws IOException
	{
		InputStream resource = GenericClientCollisionMap.class.getResourceAsStream(resourceName);
		if (resource == null)
		{
			throw new IOException("Missing bundled navigation map: " + resourceName);
		}

		byte[][] regions = new byte[REGIONS_PER_AXIS * REGIONS_PER_AXIS][];
		try (ZipInputStream zip = new ZipInputStream(resource))
		{
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null)
			{
				if (entry.isDirectory())
				{
					continue;
				}

				String name = entry.getName();
				int separator = name.indexOf('_');
				if (separator < 1 || separator == name.length() - 1)
				{
					throw new IOException("Invalid navigation region entry: " + name);
				}

				int regionX;
				int regionY;
				try
				{
					regionX = Integer.parseInt(name.substring(0, separator));
					regionY = Integer.parseInt(name.substring(separator + 1));
				}
				catch (NumberFormatException exception)
				{
					throw new IOException("Invalid navigation region entry: " + name, exception);
				}

				if (regionX < 0 || regionY < 0 || regionX >= REGIONS_PER_AXIS || regionY >= REGIONS_PER_AXIS)
					throw new IOException("Navigation region is outside world coordinates: " + name);
				int key = packRegion(regionX, regionY);
				if (regions[key] != null)
				{
					throw new IOException("Duplicate navigation region entry: " + name);
				}
				regions[key] = zip.readAllBytes();
			}
		}

		if (countRegions(regions) == 0)
		{
			throw new IOException("Bundled navigation map is empty: " + resourceName);
		}
		return regions;
	}

	int getRegionCount()
	{
		return countRegions(regions);
	}

	int getDoorRegionCount()
	{
		return countRegions(doorRegions);
	}

	boolean crossesDoor(int x, int y, int plane, int dx, int dy)
	{
		if (dx == 0 && dy == 0 || Math.abs(dx) > 1 || Math.abs(dy) > 1)
		{
			return false;
		}
		if (dx == -1 && dy == 0)
		{
			return doorWest(x, y, plane);
		}
		if (dx == 1 && dy == 0)
		{
			return doorEast(x, y, plane);
		}
		if (dx == 0 && dy == -1)
		{
			return doorSouth(x, y, plane);
		}
		if (dx == 0 && dy == 1)
		{
			return doorNorth(x, y, plane);
		}
		if (dx == -1 && dy == -1)
		{
			return doorSouth(x, y, plane) || doorWest(x, y - 1, plane) ||
				doorWest(x, y, plane) || doorSouth(x - 1, y, plane);
		}
		if (dx == 1 && dy == -1)
		{
			return doorSouth(x, y, plane) || doorEast(x, y - 1, plane) ||
				doorEast(x, y, plane) || doorSouth(x + 1, y, plane);
		}
		if (dx == -1 && dy == 1)
		{
			return doorNorth(x, y, plane) || doorWest(x, y + 1, plane) ||
				doorWest(x, y, plane) || doorNorth(x - 1, y, plane);
		}
		return doorNorth(x, y, plane) || doorEast(x, y + 1, plane) ||
			doorEast(x, y, plane) || doorNorth(x + 1, y, plane);
	}

	boolean canMove(int x, int y, int plane, int dx, int dy)
	{
		if ((dx == 0 && dy == 0) || Math.abs(dx) > 1 || Math.abs(dy) > 1)
		{
			return false;
		}

		if (isBlocked(x, y, plane))
		{
			return canLeaveBlockedTile(x, y, plane, dx, dy);
		}

		if (dx == 0)
		{
			return verticalOpen(x, y, plane, dy);
		}
		if (dy == 0)
		{
			return horizontalOpen(x, y, plane, dx);
		}
		return horizontalOpen(x, y, plane, dx) &&
			horizontalOpen(x, y + dy, plane, dx) &&
			verticalOpen(x, y, plane, dy) &&
			verticalOpen(x + dx, y, plane, dy);
	}

	private boolean canLeaveBlockedTile(int x, int y, int plane, int dx, int dy)
	{
		if (isBlocked(x + dx, y + dy, plane))
		{
			return false;
		}
		return dx == 0 || dy == 0 ||
			!isBlocked(x + dx, y, plane) && !isBlocked(x, y + dy, plane);
	}

	private boolean horizontalOpen(int x, int y, int plane, int dx)
	{
		return dx < 0 ? west(x, y, plane) : east(x, y, plane);
	}

	private boolean verticalOpen(int x, int y, int plane, int dy)
	{
		return dy < 0 ? south(x, y, plane) : north(x, y, plane);
	}

	private boolean isBlocked(int x, int y, int plane)
	{
		return !north(x, y, plane) && !south(x, y, plane) &&
			!east(x, y, plane) && !west(x, y, plane);
	}

	private boolean north(int x, int y, int plane)
	{
		return get(x, y, plane, 0);
	}

	private boolean south(int x, int y, int plane)
	{
		return north(x, y - 1, plane);
	}

	private boolean east(int x, int y, int plane)
	{
		return get(x, y, plane, 1);
	}

	private boolean west(int x, int y, int plane)
	{
		return east(x - 1, y, plane);
	}

	private boolean get(int x, int y, int plane, int flag)
	{
		return get(regions, x, y, plane, flag);
	}

	private boolean doorNorth(int x, int y, int plane)
	{
		return get(doorRegions, x, y, plane, 0);
	}

	private boolean doorSouth(int x, int y, int plane)
	{
		return doorNorth(x, y - 1, plane);
	}

	private boolean doorEast(int x, int y, int plane)
	{
		return get(doorRegions, x, y, plane, 1);
	}

	private boolean doorWest(int x, int y, int plane)
	{
		return doorEast(x - 1, y, plane);
	}

	private static boolean get(
		byte[][] source,
		int x,
		int y,
		int plane,
		int flag)
	{
		if (x < 0 || y < 0 || x > 0x7FFF || y > 0x7FFF || plane < 0 || plane > 3)
		{
			return false;
		}

		byte[] data = source[packRegion(x / REGION_SIZE, y / REGION_SIZE)];
		if (data == null)
		{
			return false;
		}

		int bit = (plane * REGION_SIZE * REGION_SIZE +
			(y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK)) * FLAG_COUNT + flag;
		int byteIndex = bit >>> 3;
		return byteIndex < data.length && (data[byteIndex] & 0xFF & (1 << (bit & 7))) != 0;
	}

	private static int countRegions(byte[][] regions)
	{
		int count = 0;
		for (byte[] data : regions) if (data != null) count++;
		return count;
	}

	private static int packRegion(int x, int y)
	{
		return x + y * REGIONS_PER_AXIS;
	}
}
