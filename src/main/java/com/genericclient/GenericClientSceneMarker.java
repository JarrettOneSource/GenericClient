package com.genericclient;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

final class GenericClientSceneMarker
{
	private static final Color DEFAULT_COLOR = new Color(76, 220, 162);
	private static final Color MOUSE_TILE_COLOR = new Color(244, 92, 255);

	private final Integer npcId;
	private final Integer objectId;
	private final Integer groundItemId;
	private final String playerName;
	private final WorldPoint tile;
	private final boolean mouseTile;
	private final String label;
	private final Color color;

	private GenericClientSceneMarker(
		Integer npcId,
		Integer objectId,
		Integer groundItemId,
		String playerName,
		WorldPoint tile,
		boolean mouseTile,
		String label,
		Color color)
	{
		this.npcId = npcId;
		this.objectId = objectId;
		this.groundItemId = groundItemId;
		this.playerName = playerName;
		this.tile = tile;
		this.mouseTile = mouseTile;
		this.label = label;
		this.color = color;
	}

	Integer getNpcId()
	{
		return npcId;
	}

	Integer getObjectId()
	{
		return objectId;
	}

	Integer getGroundItemId()
	{
		return groundItemId;
	}

	String getPlayerName()
	{
		return playerName;
	}

	WorldPoint getTile()
	{
		return tile;
	}

	boolean isMouseTile()
	{
		return mouseTile;
	}

	String getLabel()
	{
		return label;
	}

	Color getColor()
	{
		return color;
	}

	Map<String, Object> toMap()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		if (npcId != null)
		{
			value.put("npc_id", (long) npcId);
		}
		if (objectId != null)
		{
			value.put("object_id", (long) objectId);
		}
		if (groundItemId != null)
		{
			value.put("ground_item_id", (long) groundItemId);
		}
		if (playerName != null)
		{
			value.put("player_name", playerName);
		}
		if (tile != null)
		{
			Map<String, Object> world = new LinkedHashMap<>();
			world.put("x", (long) tile.getX());
			world.put("y", (long) tile.getY());
			world.put("plane", (long) tile.getPlane());
			value.put("tile", world);
		}
		if (mouseTile)
		{
			value.put("mouse_tile", true);
		}
		value.put("label", label);
		value.put("color", String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue()));
		return value;
	}

	static GenericClientSceneMarker settingsMouseTile()
	{
		return new GenericClientSceneMarker(
			null, null, null, null, null, true, "Mouse", MOUSE_TILE_COLOR);
	}

	static List<GenericClientSceneMarker> parse(Object rawMarkers)
	{
		if (rawMarkers == null)
		{
			return Collections.emptyList();
		}
		if (!(rawMarkers instanceof List))
		{
			throw new IllegalArgumentException("Scene markers must be an array");
		}
		List<?> raw = (List<?>) rawMarkers;
		List<GenericClientSceneMarker> markers = new ArrayList<>();
		for (Object item : raw)
		{
			markers.add(parseMarker(item));
		}
		return Collections.unmodifiableList(markers);
	}

	private static GenericClientSceneMarker parseMarker(Object item)
	{
		if (!(item instanceof Map))
		{
			throw new IllegalArgumentException("Each scene marker must be a table");
		}
		Map<?, ?> marker = (Map<?, ?>) item;
		Integer npcId = positiveInteger(marker.get("npc_id"), "npc_id");
		Integer objectId = positiveInteger(marker.get("object_id"), "object_id");
		Integer groundItemId = positiveInteger(
			marker.get("ground_item_id"), "ground_item_id");
		String playerName = optionalText(marker.get("player_name"), "player_name");
		WorldPoint tile = worldPoint(marker.get("tile"));
		boolean mouseTile = Boolean.TRUE.equals(marker.get("mouse_tile"));
		int targets = (npcId == null ? 0 : 1) +
			(objectId == null ? 0 : 1) +
			(groundItemId == null ? 0 : 1) +
			(playerName == null ? 0 : 1) +
			(tile == null ? 0 : 1) +
			(mouseTile ? 1 : 0);
		if (targets != 1)
		{
			throw new IllegalArgumentException(
				"Scene marker requires exactly one tile, npc_id, object_id, " +
					"ground_item_id, player_name, or mouse_tile=true target");
		}
		return new GenericClientSceneMarker(
			npcId,
			objectId,
			groundItemId,
			playerName,
			tile,
			mouseTile,
			text(marker.get("label"), defaultLabel(npcId, objectId, groundItemId, playerName, tile, mouseTile)),
			color(marker.get("color")));
	}

	private static String defaultLabel(Integer npcId, Integer objectId, Integer groundItemId, String playerName,
		WorldPoint tile, boolean mouseTile)
	{
		return mouseTile ? "Mouse" :
			npcId != null ? "NPC " + npcId :
			objectId != null ? "Object " + objectId :
			groundItemId != null ? "Item " + groundItemId :
			playerName != null ? playerName :
			"Tile " + tile.getX() + "," + tile.getY();
	}

	private static String text(Object raw, String fallback)
	{
		String value = raw == null ? fallback : String.valueOf(raw).trim();
		if (value.isEmpty() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
		{
			throw new IllegalArgumentException("Scene marker label must be one non-empty line");
		}
		return value;
	}

	private static String optionalText(Object raw, String field)
	{
		if (raw == null)
		{
			return null;
		}
		String value = String.valueOf(raw).trim();
		if (value.isEmpty() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
		{
			throw new IllegalArgumentException("Scene marker " + field + " must be one non-empty line");
		}
		return value;
	}

	private static Integer positiveInteger(Object raw, String field)
	{
		if (raw == null)
		{
			return null;
		}
		if (!(raw instanceof Number))
		{
			throw new IllegalArgumentException("Scene marker " + field + " must be a positive integer");
		}
		double value = ((Number) raw).doubleValue();
		if (!Double.isFinite(value) || value <= 0 || value != Math.rint(value) || value > Integer.MAX_VALUE)
		{
			throw new IllegalArgumentException("Scene marker " + field + " must be a positive integer");
		}
		return (int) value;
	}

	private static WorldPoint worldPoint(Object raw)
	{
		if (raw == null)
		{
			return null;
		}
		if (!(raw instanceof Map))
		{
			throw new IllegalArgumentException("Scene marker tile must be a world-point table");
		}
		Map<?, ?> world = (Map<?, ?>) raw;
		return new WorldPoint(
			requiredInteger(world.get("x"), "tile.x"),
			requiredInteger(world.get("y"), "tile.y"),
			requiredInteger(world.get("plane"), "tile.plane"));
	}

	private static int requiredInteger(Object raw, String field)
	{
		if (!(raw instanceof Number))
		{
			throw new IllegalArgumentException("Scene marker " + field + " must be an integer");
		}
		double value = ((Number) raw).doubleValue();
		if (!Double.isFinite(value) || value != Math.rint(value) ||
			value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
		{
			throw new IllegalArgumentException("Scene marker " + field + " must be an integer");
		}
		return (int) value;
	}

	private static Color color(Object raw)
	{
		if (raw == null)
		{
			return DEFAULT_COLOR;
		}
		String value = String.valueOf(raw).trim();
		if (!value.matches("#[0-9a-fA-F]{6}"))
		{
			throw new IllegalArgumentException("Scene marker color must use #RRGGBB");
		}
		return Color.decode(value);
	}
}
