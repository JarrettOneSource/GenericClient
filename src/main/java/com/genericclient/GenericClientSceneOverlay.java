package com.genericclient;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

final class GenericClientSceneOverlay extends Overlay
{
	private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);
	private static final int ARROW_LENGTH = 64;

	private final Client client;
	private final Supplier<List<GenericClientSceneMarker>> markerSupplier;

	GenericClientSceneOverlay(
		Client client,
		Supplier<List<GenericClientSceneMarker>> markerSupplier)
	{
		this.client = client;
		this.markerSupplier = markerSupplier;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		List<GenericClientSceneMarker> markers = markerSupplier.get();
		if (markers == null || markers.isEmpty())
		{
			return null;
		}
		Player player = client.getLocalPlayer();
		WorldView worldView = player == null ? null : player.getWorldView();
		if (worldView == null)
		{
			return null;
		}

		Graphics2D copy = (Graphics2D) graphics.create();
		try
		{
			copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			copy.setFont(LABEL_FONT);
			for (GenericClientSceneMarker marker : markers)
			{
				if (marker.isMouseTile())
				{
					renderMouseTile(copy, marker, worldView);
				}
				else if (marker.getTile() != null)
				{
					renderWorldTile(copy, marker, worldView);
				}
				else if (marker.getNpcId() != null)
				{
					renderNpcs(copy, marker, worldView);
				}
				else if (marker.getPlayerName() != null)
				{
					renderPlayers(copy, marker, worldView);
				}
				else if (marker.getObjectId() != null)
				{
					renderObjects(copy, marker, worldView);
				}
				else if (marker.getGroundItemId() != null)
				{
					renderGroundItems(copy, marker, worldView);
				}
			}
		}
		finally
		{
			copy.dispose();
		}
		return null;
	}

	private void renderMouseTile(
		Graphics2D graphics,
		GenericClientSceneMarker marker,
		WorldView worldView)
	{
		Tile selected = worldView.getSelectedSceneTile();
		if (selected == null || selected.getLocalLocation() == null ||
			selected.getWorldLocation() == null)
		{
			return;
		}
		LocalPoint local = selected.getLocalLocation();
		WorldPoint world = selected.getWorldLocation();
		Polygon tile = Perspective.getCanvasTilePoly(client, local);
		if (tile == null)
		{
			return;
		}
		net.runelite.api.Point point = Perspective.localToCanvas(client, local, world.getPlane());
		renderTile(graphics, tile, point, world, marker);
	}

	private void renderWorldTile(
		Graphics2D graphics,
		GenericClientSceneMarker marker,
		WorldView worldView)
	{
		WorldPoint world = marker.getTile();
		LocalPoint local = LocalPoint.fromWorld(worldView, world);
		if (local == null) return;
		Polygon tile = Perspective.getCanvasTilePoly(client, local);
		net.runelite.api.Point point = Perspective.localToCanvas(client, local, world.getPlane());
		renderTile(graphics, tile, point, world, marker);
	}

	private void renderNpcs(
		Graphics2D graphics,
		GenericClientSceneMarker marker,
		WorldView worldView)
	{
		for (NPC npc : worldView.npcs())
		{
			if (npc != null && npc.getId() == marker.getNpcId())
			{
				renderNpc(graphics, npc, marker);
			}
		}
	}

	private void renderPlayers(
		Graphics2D graphics,
		GenericClientSceneMarker marker,
		WorldView worldView)
	{
		for (Player player : worldView.players())
		{
			if (player == null || player.getName() == null ||
				!player.getName().equalsIgnoreCase(marker.getPlayerName()))
			{
				continue;
			}
			LocalPoint local = player.getLocalLocation();
			WorldPoint world = player.getWorldLocation();
			if (local == null || world == null) continue;
			renderTile(
				graphics,
				Perspective.getCanvasTilePoly(client, local),
				Perspective.localToCanvas(client, local, world.getPlane()),
				world,
				marker);
		}
	}

	private void renderObjects(
		Graphics2D graphics,
		GenericClientSceneMarker marker,
		WorldView worldView)
	{
		Tile[][] plane = currentPlane(worldView);
		if (plane == null) return;
		Set<TileObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Tile[] column : plane)
		{
			if (column == null) continue;
			for (Tile tile : column)
			{
				if (tile == null) continue;
				renderObject(graphics, tile.getWallObject(), marker, seen);
				renderObject(graphics, tile.getGroundObject(), marker, seen);
				renderObject(graphics, tile.getDecorativeObject(), marker, seen);
				GameObject[] objects = tile.getGameObjects();
				if (objects == null) continue;
				for (GameObject object : objects)
				{
					renderObject(graphics, object, marker, seen);
				}
			}
		}
	}

	private void renderObject(
		Graphics2D graphics,
		TileObject object,
		GenericClientSceneMarker marker,
		Set<TileObject> seen)
	{
		if (object == null || object.getId() != marker.getObjectId() || !seen.add(object)) return;
		WorldPoint world = object.getWorldLocation();
		LocalPoint local = object.getLocalLocation();
		if (world == null || local == null) return;
		renderTile(
			graphics,
			object.getCanvasTilePoly(),
			object.getCanvasLocation(),
			world,
			marker);
	}

	private void renderGroundItems(
		Graphics2D graphics,
		GenericClientSceneMarker marker,
		WorldView worldView)
	{
		Tile[][] plane = currentPlane(worldView);
		if (plane == null) return;
		for (Tile[] column : plane)
		{
			if (column == null) continue;
			for (Tile tile : column)
			{
				if (tile == null || tile.getGroundItems() == null) continue;
				for (TileItem item : tile.getGroundItems())
				{
					if (item != null && item.getId() == marker.getGroundItemId())
					{
						WorldPoint world = tile.getWorldLocation();
						LocalPoint local = tile.getLocalLocation();
						renderTile(
							graphics,
							Perspective.getCanvasTilePoly(client, local),
							Perspective.localToCanvas(client, local, world.getPlane()),
							world,
							marker);
						break;
					}
				}
			}
		}
	}

	private static Tile[][] currentPlane(WorldView worldView)
	{
		Scene scene = worldView.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		int plane = worldView.getPlane();
		return tiles == null || plane < 0 || plane >= tiles.length ? null : tiles[plane];
	}

	private void renderNpc(Graphics2D graphics, NPC npc, GenericClientSceneMarker marker)
	{
		LocalPoint local = npc.getLocalLocation();
		WorldPoint world = npc.getWorldLocation();
		if (local == null || world == null)
		{
			return;
		}
		Polygon tile = Perspective.getCanvasTilePoly(client, local);
		if (tile == null)
		{
			return;
		}

		int orientation = npc.getCurrentOrientation() & 2047;
		double angle = orientation * Math.PI * 2.0 / 2048.0;
		int dx = (int) Math.round(-Math.sin(angle) * ARROW_LENGTH);
		int dy = (int) Math.round(-Math.cos(angle) * ARROW_LENGTH);
		net.runelite.api.Point start = Perspective.localToCanvas(client, local, world.getPlane());
		net.runelite.api.Point end = Perspective.localToCanvas(
			client, local.plus(dx, dy), world.getPlane());
		if (start != null)
		{
			drawTile(graphics, tile, marker.getColor());
			if (end != null)
			{
				drawArrow(graphics, start.getX(), start.getY(), end.getX(), end.getY());
			}
			String caption = marker.getLabel() + "  " + direction(orientation) +
				"  " + world.getX() + "," + world.getY();
			int textX = start.getX() - graphics.getFontMetrics().stringWidth(caption) / 2;
			int textY = start.getY() - 14;
			graphics.setColor(Color.BLACK);
			graphics.drawString(caption, textX + 1, textY + 1);
			graphics.setColor(marker.getColor());
			graphics.drawString(caption, textX, textY);
		}
	}

	private static void renderTile(
		Graphics2D graphics,
		Polygon tile,
		net.runelite.api.Point point,
		WorldPoint world,
		GenericClientSceneMarker marker)
	{
		if (tile == null || point == null || world == null) return;
		drawTile(graphics, tile, marker.getColor());
		String caption = marker.getLabel() + "  " + world.getX() + "," + world.getY();
		int textX = point.getX() - graphics.getFontMetrics().stringWidth(caption) / 2;
		int textY = point.getY() - 14;
		graphics.setColor(Color.BLACK);
		graphics.drawString(caption, textX + 1, textY + 1);
		graphics.setColor(marker.getColor());
		graphics.drawString(caption, textX, textY);
	}

	private static void drawTile(Graphics2D graphics, Polygon tile, Color color)
	{
		graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 55));
		graphics.fill(tile);
		graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 235));
		graphics.setStroke(new BasicStroke(2.0f));
		graphics.draw(tile);
	}

	private static void drawArrow(Graphics2D graphics, int startX, int startY, int endX, int endY)
	{
		graphics.drawLine(startX, startY, endX, endY);
		double angle = Math.atan2(endY - startY, endX - startX);
		int size = 7;
		for (double offset : new double[]{Math.PI * 0.78, -Math.PI * 0.78})
		{
			graphics.drawLine(
				endX,
				endY,
				endX + (int) Math.round(Math.cos(angle + offset) * size),
				endY + (int) Math.round(Math.sin(angle + offset) * size));
		}
	}

	static String direction(int orientation)
	{
		String[] directions = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		return directions[((orientation & 2047) + 128) / 256 & 7].toUpperCase(Locale.ROOT);
	}
}
