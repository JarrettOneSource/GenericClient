package com.genericclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Owns persistent diagnostic markers while preserving script-owned overlays. */
final class GenericClientSceneHighlights
{
	private final Supplier<List<GenericClientSceneMarker>> scriptMarkers;
	private final BooleanSupplier syntheticMouseMoving;
	private volatile List<GenericClientSceneMarker> remoteMarkers = Collections.emptyList();
	private volatile boolean showMouseTile;

	GenericClientSceneHighlights(Supplier<List<GenericClientSceneMarker>> scriptMarkers)
	{
		this(scriptMarkers, () -> false);
	}

	GenericClientSceneHighlights(
		Supplier<List<GenericClientSceneMarker>> scriptMarkers,
		BooleanSupplier syntheticMouseMoving)
	{
		if (scriptMarkers == null)
		{
			throw new IllegalArgumentException("Script marker supplier is required");
		}
		if (syntheticMouseMoving == null)
		{
			throw new IllegalArgumentException("Synthetic mouse state supplier is required");
		}
		this.scriptMarkers = scriptMarkers;
		this.syntheticMouseMoving = syntheticMouseMoving;
	}

	void setShowMouseTile(boolean enabled)
	{
		showMouseTile = enabled;
	}

	boolean isShowMouseTile()
	{
		return showMouseTile;
	}

	List<GenericClientSceneMarker> visibleMarkers()
	{
		List<GenericClientSceneMarker> scripts = scriptMarkers.get();
		boolean hideMouseTile = syntheticMouseMoving.getAsBoolean();
		List<GenericClientSceneMarker> visible = new ArrayList<>(
			(showMouseTile ? 1 : 0) + remoteMarkers.size() +
			(scripts == null ? 0 : scripts.size()));
		if (showMouseTile && !hideMouseTile)
		{
			visible.add(GenericClientSceneMarker.settingsMouseTile());
		}
		for (GenericClientSceneMarker marker : remoteMarkers)
		{
			if (!hideMouseTile || !marker.isMouseTile()) visible.add(marker);
		}
		if (scripts != null)
		{
			for (GenericClientSceneMarker marker : scripts)
			{
				if (!hideMouseTile || !marker.isMouseTile()) visible.add(marker);
			}
		}
		return Collections.unmodifiableList(visible);
	}

	Map<String, Object> replace(Object rawMarkers)
	{
		List<GenericClientSceneMarker> parsed = GenericClientSceneMarker.parse(rawMarkers);
		if (parsed.isEmpty())
		{
			throw new IllegalArgumentException("scene.highlight requires at least one marker");
		}
		remoteMarkers = parsed;
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "complete");
		receipt.put("result", "scene_highlights_replaced");
		receipt.put("marker_count", (long) parsed.size());
		receipt.put("markers", markerMaps(parsed));
		return receipt;
	}

	Map<String, Object> clear()
	{
		long cleared = remoteMarkers.size();
		remoteMarkers = Collections.emptyList();
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "complete");
		receipt.put("result", "scene_highlights_cleared");
		receipt.put("cleared_count", cleared);
		return receipt;
	}

	private static List<Map<String, Object>> markerMaps(
		List<GenericClientSceneMarker> markers)
	{
		List<Map<String, Object>> values = new ArrayList<>();
		for (GenericClientSceneMarker marker : markers)
		{
			values.add(marker.toMap());
		}
		return Collections.unmodifiableList(values);
	}
}
