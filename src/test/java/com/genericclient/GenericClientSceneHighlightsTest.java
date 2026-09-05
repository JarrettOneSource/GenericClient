package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class GenericClientSceneHighlightsTest
{
	@Test
	public void combinesSettingRemoteAndScriptMarkersAndClearsOnlyRemoteMarkers()
	{
		Map<String, Object> scriptNpc = new LinkedHashMap<>();
		scriptNpc.put("npc_id", 5247);
		AtomicReference<List<GenericClientSceneMarker>> scriptMarkers = new AtomicReference<>(
			GenericClientSceneMarker.parse(Collections.singletonList(scriptNpc)));
		GenericClientSceneHighlights highlights = new GenericClientSceneHighlights(scriptMarkers::get);

		assertEquals(1, highlights.visibleMarkers().size());
		assertFalse(highlights.isShowMouseTile());

		highlights.setShowMouseTile(true);
		Map<String, Object> tile = new LinkedHashMap<>();
		Map<String, Object> world = new LinkedHashMap<>();
		world.put("x", 2746);
		world.put("y", 2799);
		world.put("plane", 0);
		tile.put("tile", world);
		Map<String, Object> object = new LinkedHashMap<>();
		object.put("object_id", 4749);

		Map<String, Object> replaced = highlights.replace(Arrays.asList(tile, object));

		assertEquals("complete", replaced.get("status"));
		assertEquals(2L, replaced.get("marker_count"));
		assertEquals(4, highlights.visibleMarkers().size());
		assertTrue(highlights.visibleMarkers().get(0).isMouseTile());

		Map<String, Object> cleared = highlights.clear();
		assertEquals(2L, cleared.get("cleared_count"));
		assertEquals(2, highlights.visibleMarkers().size());
		assertTrue(highlights.isShowMouseTile());
	}

	@Test
	public void hidesEveryMouseTileMarkerWhileSyntheticMouseIsMoving()
	{
		Map<String, Object> scriptMouse = new LinkedHashMap<>();
		scriptMouse.put("mouse_tile", true);
		Map<String, Object> scriptNpc = new LinkedHashMap<>();
		scriptNpc.put("npc_id", 5247);
		AtomicBoolean syntheticMouseMoving = new AtomicBoolean();
		GenericClientSceneHighlights highlights = new GenericClientSceneHighlights(
			() -> GenericClientSceneMarker.parse(Arrays.asList(scriptMouse, scriptNpc)),
			syntheticMouseMoving::get);
		highlights.setShowMouseTile(true);
		Map<String, Object> remoteMouse = new LinkedHashMap<>();
		remoteMouse.put("mouse_tile", true);
		highlights.replace(Collections.singletonList(remoteMouse));

		assertEquals(4, highlights.visibleMarkers().size());
		syntheticMouseMoving.set(true);
		List<GenericClientSceneMarker> movingMarkers = highlights.visibleMarkers();

		assertEquals(1, movingMarkers.size());
		assertFalse(movingMarkers.get(0).isMouseTile());
		syntheticMouseMoving.set(false);
		assertEquals(4, highlights.visibleMarkers().size());
	}
}
