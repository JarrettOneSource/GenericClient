package com.genericclient;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

final class GenericClientWorldSnapshot
{
	final GenericClientPlayerSnapshot player;
	final List<GenericClientPlayerSnapshot> players;
	final List<GenericClientNpcSnapshot> npcs;
	final GenericClientSceneCollision collision;

	GenericClientWorldSnapshot(
		GenericClientPlayerSnapshot player,
		List<GenericClientNpcSnapshot> npcs,
		GenericClientSceneCollision collision)
	{
		this(player,player == null ? List.of() : List.of(player),npcs,collision);
	}

	private GenericClientWorldSnapshot(GenericClientPlayerSnapshot player, List<GenericClientPlayerSnapshot> players,
		List<GenericClientNpcSnapshot> npcs, GenericClientSceneCollision collision)
	{
		this.player = player;
		this.players = List.copyOf(players);
		this.npcs = List.copyOf(npcs);
		this.collision = collision;
	}

	private static GenericClientWorldSnapshot empty()
	{
		return new GenericClientWorldSnapshot(
			null, Collections.emptyList(), GenericClientSceneCollision.empty());
	}
	static GenericClientWorldSnapshot capture(Client client, Player localPlayer, GenericClientEntityIds identities)
	{
		if (localPlayer == null || localPlayer.getWorldLocation() == null)
		{
			return GenericClientWorldSnapshot.empty();
		}
		WorldPoint playerPoint = localPlayer.getWorldLocation();
		WorldView worldView = localPlayer.getWorldView();
		GenericClientSceneCollision collision =
			GenericClientSceneCollision.capture(worldView, playerPoint.getPlane());
		LocalPoint localDestination = client.getLocalDestinationLocation();
		WorldPoint worldDestination = localDestination == null
			? null
			: WorldPoint.fromLocal(
				worldView,
				localDestination.getX(),
				localDestination.getY(),
				playerPoint.getPlane());
		GenericClientPlayerSnapshot player = GenericClientPlayerSnapshot.capture(client,localPlayer,identities,worldDestination,true);
		List<GenericClientPlayerSnapshot> players = new ArrayList<>();
		players.add(player);
		for (Player other : worldView.players())
		{
			if (other == null || other == localPlayer || other.getWorldLocation() == null || other.getWorldLocation().getPlane() != playerPoint.getPlane()) continue;
			players.add(GenericClientPlayerSnapshot.capture(client,other,identities,null,false));
		}

		Rectangle viewport = GenericClientMenuInput.viewportBounds(client);
		List<GenericClientNpcSnapshot> npcs = new ArrayList<>();
		for (NPC npc : worldView.npcs())
		{
			GenericClientNpcSnapshot snapshot = captureNpc(localPlayer, playerPoint, worldView, viewport, npc, identities);
			if (snapshot != null)
			{
				npcs.add(snapshot);
			}
		}
		npcs.sort(Comparator
			.comparingInt(GenericClientNpcSnapshot::getDistance)
			.thenComparingInt(GenericClientNpcSnapshot::getIndex));
		return new GenericClientWorldSnapshot(player, players, npcs, collision);
	}

	private static GenericClientNpcSnapshot captureNpc(
		Player player,
		WorldPoint playerPoint,
		WorldView worldView,
		Rectangle viewport,
		NPC npc, GenericClientEntityIds identities)
	{
		if (npc == null || npc.getWorldLocation() == null)
		{
			return null;
		}
		WorldPoint location = npc.getWorldLocation();
		NPCComposition composition = getComposition(npc);
		Shape clickShape = npc.getConvexHull();
		if (clickShape == null)
		{
			clickShape = npc.getCanvasTilePoly();
		}
		Point canvasPoint = GenericClientMenuInput.firstPointInside(clickShape, viewport);
		return new GenericClientNpcSnapshot(
			identities.identify(npc),
			npc.getIndex(),
			npc.getId(),
			Objects.toString(npc.getName(), "<unnamed>"),
			location.getX(),
			location.getY(),
			location.getPlane(),
			playerPoint.distanceTo(location),
			npc.getCombatLevel(),
			npc.getAnimation(),
			npc.getInteracting() == null ? null : npc.getInteracting().getName(),
			composition == null ? 1 : composition.getSize(),
			getActions(composition),
			npc.getLocalLocation() != null && worldView.contains(npc.getLocalLocation()),
			canvasPoint != null,
			GenericClientNpcInput.hasLineOfSight(player, npc),
			npc.isDead(),
			npc.getHealthRatio(),
			npc.getHealthScale(),
			canvasPoint,
			visibleBounds(clickShape, viewport),npc.getPoseAnimation() != npc.getIdlePoseAnimation());
	}

	private static NPCComposition getComposition(NPC npc)
	{
		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null)
		{
			composition = npc.getComposition();
		}
		return composition;
	}

	private static List<String> getActions(NPCComposition composition)
	{
		if (composition == null || composition.getActions() == null)
		{
			return Collections.emptyList();
		}
		return Arrays.stream(composition.getActions())
			.filter(Objects::nonNull)
			.collect(Collectors.toList());
	}

	private static Rectangle visibleBounds(Shape shape, Rectangle clipBounds)
	{
		if (shape == null || clipBounds == null || clipBounds.isEmpty())
		{
			return null;
		}
		Rectangle bounds = shape.getBounds().intersection(clipBounds);
		return bounds.isEmpty() ? null : bounds;
	}

	static Map<String, Object> canvasMap(Point point, Rectangle bounds)
	{
		Map<String, Object> canvas = new LinkedHashMap<>();
		if (point == null)
		{
			canvas.put("point", null);
		}
		else
		{
			Map<String, Object> canvasPoint = new LinkedHashMap<>();
			canvasPoint.put("x", (long) point.x);
			canvasPoint.put("y", (long) point.y);
			canvas.put("point", canvasPoint);
		}
		if (bounds == null)
		{
			canvas.put("bounds", null);
		}
		else
		{
			Map<String, Object> canvasBounds = new LinkedHashMap<>();
			canvasBounds.put("x", (long) bounds.x);
			canvasBounds.put("y", (long) bounds.y);
			canvasBounds.put("width", (long) bounds.width);
			canvasBounds.put("height", (long) bounds.height);
			canvas.put("bounds", canvasBounds);
		}
		return canvas;
	}

	static Map<String, Object> worldMap(int x, int y, int plane)
	{
		Map<String, Object> world = new LinkedHashMap<>();
		world.put("x", (long) x);
		world.put("y", (long) y);
		world.put("plane", (long) plane);
		return world;
	}
}
