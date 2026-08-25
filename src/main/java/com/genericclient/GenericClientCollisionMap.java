package com.genericclient;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class GenericClientCollisionMap
{
	static final String SOURCE_REVISION = "44a691aafad48bd8f4ef6d00680d627d2aa8153c";
	static final String SOURCE_SHA256 = "2fca3c83778995c96a6511cc523e157352ef526f3b0a969892b62010d5c5e717";

	private static final String RESOURCE = "/com/genericclient/navigation/collision-map.zip";
	private static final int REGION_SIZE = 64;
	private static final int REGION_MASK = REGION_SIZE - 1;
	private static final int FLAG_COUNT = 2;

	private final Map<Integer, byte[]> regions;

	private GenericClientCollisionMap(Map<Integer, byte[]> regions)
	{
		this.regions = regions;
	}

	static GenericClientCollisionMap loadBundled() throws IOException
	{
		InputStream resource = GenericClientCollisionMap.class.getResourceAsStream(RESOURCE);
		if (resource == null)
		{
			throw new IOException("Missing bundled collision map: " + RESOURCE);
		}

		Map<Integer, byte[]> regions = new HashMap<>();
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
					throw new IOException("Invalid collision region entry: " + name);
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
					throw new IOException("Invalid collision region entry: " + name, exception);
				}

				byte[] previous = regions.put(packRegion(regionX, regionY), zip.readAllBytes());
				if (previous != null)
				{
					throw new IOException("Duplicate collision region entry: " + name);
				}
			}
		}

		if (regions.isEmpty())
		{
			throw new IOException("Bundled collision map is empty");
		}
		return new GenericClientCollisionMap(regions);
	}

	int getRegionCount()
	{
		return regions.size();
	}

	boolean canMove(int x, int y, int plane, int dx, int dy)
	{
		if (dx == 0 && dy == 0 || Math.abs(dx) > 1 || Math.abs(dy) > 1)
		{
			return false;
		}

		if (isBlocked(x, y, plane))
		{
			if (isBlocked(x + dx, y + dy, plane))
			{
				return false;
			}
			return dx == 0 || dy == 0 ||
				(!isBlocked(x + dx, y, plane) && !isBlocked(x, y + dy, plane));
		}

		if (dx == -1 && dy == 0)
		{
			return west(x, y, plane);
		}
		if (dx == 1 && dy == 0)
		{
			return east(x, y, plane);
		}
		if (dx == 0 && dy == -1)
		{
			return south(x, y, plane);
		}
		if (dx == 0 && dy == 1)
		{
			return north(x, y, plane);
		}
		if (dx == -1 && dy == -1)
		{
			return south(x, y, plane) && west(x, y - 1, plane) &&
				west(x, y, plane) && south(x - 1, y, plane);
		}
		if (dx == 1 && dy == -1)
		{
			return south(x, y, plane) && east(x, y - 1, plane) &&
				east(x, y, plane) && south(x + 1, y, plane);
		}
		if (dx == -1 && dy == 1)
		{
			return north(x, y, plane) && west(x, y + 1, plane) &&
				west(x, y, plane) && north(x - 1, y, plane);
		}
		return north(x, y, plane) && east(x, y + 1, plane) &&
			east(x, y, plane) && north(x + 1, y, plane);
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
		if (x < 0 || y < 0 || plane < 0 || plane > 3)
		{
			return false;
		}

		byte[] data = regions.get(packRegion(x / REGION_SIZE, y / REGION_SIZE));
		if (data == null)
		{
			return false;
		}

		int bit = (plane * REGION_SIZE * REGION_SIZE +
			(y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK)) * FLAG_COUNT + flag;
		int byteIndex = bit >>> 3;
		return byteIndex < data.length && ((data[byteIndex] & 0xFF) & (1 << (bit & 7))) != 0;
	}

	private static int packRegion(int x, int y)
	{
		return (x & 0xFFFF) | ((y & 0xFFFF) << 16);
	}
}
