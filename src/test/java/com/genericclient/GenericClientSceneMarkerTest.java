package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GenericClientSceneMarkerTest
{
	@Test
	public void parsesNpcMarkerAndDirection()
	{
		Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("npc_id", 5247);
		raw.put("label", "Trefaji");
		raw.put("color", "#ffb347");

		List<GenericClientSceneMarker> markers = GenericClientSceneMarker.parse(Arrays.asList(raw));

		assertEquals(1, markers.size());
		assertEquals(5247, markers.get(0).getNpcId().intValue());
		assertEquals("Trefaji", markers.get(0).getLabel());
		assertEquals(new Color(255, 179, 71), markers.get(0).getColor());
		assertEquals("S", GenericClientSceneOverlay.direction(0));
		assertEquals("W", GenericClientSceneOverlay.direction(512));
		assertEquals("N", GenericClientSceneOverlay.direction(1024));
		assertEquals("E", GenericClientSceneOverlay.direction(1536));

		Map<String, Object> mouse = new LinkedHashMap<>();
		mouse.put("mouse_tile", true);
		mouse.put("label", "Mouse");
		assertTrue(GenericClientSceneMarker.parse(Arrays.asList(mouse)).get(0).isMouseTile());
	}

	@Test
	public void parsesEverySupportedDiagnosticTargetWithoutTruncatingLabels()
	{
		Map<String, Object> tile = new LinkedHashMap<>();
		tile.put("tile", world(2746, 2799, 0));
		tile.put("label", "Monkey child safe waiting passage");

		Map<String, Object> object = new LinkedHashMap<>();
		object.put("object_id", 4749);

		Map<String, Object> groundItem = new LinkedHashMap<>();
		groundItem.put("ground_item_id", 1963);

		Map<String, Object> player = new LinkedHashMap<>();
		player.put("player_name", "genericBoss");

		List<GenericClientSceneMarker> markers = GenericClientSceneMarker.parse(
			Arrays.asList(tile, object, groundItem, player));

		assertEquals(4, markers.size());
		assertEquals(new net.runelite.api.coords.WorldPoint(2746, 2799, 0),
			markers.get(0).getTile());
		assertEquals("Monkey child safe waiting passage", markers.get(0).getLabel());
		assertEquals(4749, markers.get(1).getObjectId().intValue());
		assertEquals(1963, markers.get(2).getGroundItemId().intValue());
		assertEquals("genericBoss", markers.get(3).getPlayerName());
	}

	private static Map<String, Object> world(int x, int y, int plane)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("x", x);
		value.put("y", y);
		value.put("plane", plane);
		return value;
	}
}
