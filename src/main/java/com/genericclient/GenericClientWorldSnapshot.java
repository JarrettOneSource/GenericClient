package com.genericclient;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;

/** Player, NPC and collision observations captured from one world view. */
final class GenericClientWorldSnapshot
{
	final PlayerSnapshot player;
	final List<NpcSnapshot> npcs;
	final GenericClientSceneCollision collision;

	GenericClientWorldSnapshot(
		PlayerSnapshot player,
		List<NpcSnapshot> npcs,
		GenericClientSceneCollision collision)
	{
		this.player = player;
		this.npcs = Collections.unmodifiableList(new ArrayList<>(npcs));
		this.collision = collision;
	}

	static GenericClientWorldSnapshot capture(Client client, Player localPlayer)
	{
		if (localPlayer == null || localPlayer.getWorldLocation() == null)
		{
			return new GenericClientWorldSnapshot(null, Collections.emptyList(), GenericClientSceneCollision.empty());
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
		PlayerSnapshot player = new PlayerSnapshot(
			Objects.toString(localPlayer.getName(), ""),
			playerPoint.getX(),
			playerPoint.getY(),
			playerPoint.getPlane(),
			worldView.getId(),
			localPlayer.getAnimation(),
			localPlayer.getInteracting() == null ? null : localPlayer.getInteracting().getName(),
			client.getBoostedSkillLevel(Skill.HITPOINTS),
			client.getRealSkillLevel(Skill.HITPOINTS),
			client.getEnergy(),
			client.getVarpValue(VarPlayerID.OPTION_RUN) == 1,
			worldDestination);

		Rectangle viewport = GenericClientMenuInput.viewportBounds(client);
		List<NpcSnapshot> npcs = new ArrayList<>();
		for (NPC npc : worldView.npcs())
		{
			NpcSnapshot snapshot = captureNpc(localPlayer, playerPoint, worldView, viewport, npc);
			if (snapshot != null)
			{
				npcs.add(snapshot);
			}
		}
		npcs.sort(Comparator
			.comparingInt(NpcSnapshot::getDistance)
			.thenComparingInt(NpcSnapshot::getIndex));
		return new GenericClientWorldSnapshot(player, npcs, collision);
	}

	private static NpcSnapshot captureNpc(
		Player player,
		WorldPoint playerPoint,
		WorldView worldView,
		Rectangle viewport,
		NPC npc)
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
		return new NpcSnapshot(
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
			visibleBounds(clickShape, viewport));
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

	static final class PlayerSnapshot
	{
		final String name;
		final int x;
		final int y;
		final int plane;
		private final int worldViewId;
		private final int animation;
		private final String interacting;
		final int currentHitpoints;
		final int maxHitpoints;
		final int runEnergy;
		final boolean runEnabled;
		private final WorldPoint destination;

		PlayerSnapshot(String name, int x, int y, int plane, int worldViewId)
		{
			this(name, x, y, plane, worldViewId, -1, null, 0, 0, 0, false, null);
		}

		PlayerSnapshot(
			String name,
			int x,
			int y,
			int plane,
			int worldViewId,
			int animation,
			String interacting)
		{
			this(
				name,
				x,
				y,
				plane,
				worldViewId,
				animation,
				interacting,
				0,
				0,
				0,
				false,
				null);
		}

		PlayerSnapshot(
			String name,
			int x,
			int y,
			int plane,
			int worldViewId,
			int animation,
			String interacting,
			int currentHitpoints,
			int maxHitpoints,
			int runEnergy,
			boolean runEnabled,
			WorldPoint destination)
		{
			this.name = name;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.worldViewId = worldViewId;
			this.animation = animation;
			this.interacting = interacting;
			this.currentHitpoints = currentHitpoints;
			this.maxHitpoints = maxHitpoints;
			this.runEnergy = runEnergy;
			this.runEnabled = runEnabled;
			this.destination = destination;
		}

		Map<String, Object> toMap(String gameState)
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("logged_in", GameState.LOGGED_IN.name().equals(gameState));
			value.put("name", name);
			value.put("world", worldMap(x, y, plane));
			value.put("world_view", (long) worldViewId);
			value.put("animation", (long) animation);
			value.put("interacting", interacting);
			value.put("current_hitpoints", (long) currentHitpoints);
			value.put("max_hitpoints", (long) maxHitpoints);
			value.put("run_energy", (long) runEnergy);
			value.put("run_enabled", runEnabled);
			value.put("destination", destination == null
				? null
				: worldMap(destination.getX(), destination.getY(), destination.getPlane()));
			return value;
		}

		String getName()
		{
			return name;
		}

		int getWorldViewId() { return worldViewId; }

		String worldPoint()
		{
			return x + "," + y + "," + plane;
		}
	}

	static final class NpcSnapshot
	{
		private final int index;
		final int id;
		final String name;
		final int x;
		final int y;
		final int plane;
		final int distance;
		private final int combatLevel;
		private final int animation;
		private final String interacting;
		private final int size;
		final List<String> actions;
		private final boolean inScene;
		final boolean clickable;
		final boolean lineOfSight;
		final boolean dead;
		private final int healthRatio;
		private final int healthScale;
		private final Point canvasPoint;
		private final Rectangle canvasBounds;

		NpcSnapshot(
			int index,
			int id,
			String name,
			int x,
			int y,
			int plane,
			int distance,
			int combatLevel,
			int animation,
			String interacting,
			List<String> actions)
		{
			this(
				index,
				id,
				name,
				x,
				y,
				plane,
				distance,
				combatLevel,
				animation,
				interacting,
				1,
				actions,
				false,
				false,
				false,
				false,
				-1,
				-1,
				null,
				null);
		}

		NpcSnapshot(
			int index,
			int id,
			String name,
			int x,
			int y,
			int plane,
			int distance,
			int combatLevel,
			int animation,
			String interacting,
			int size,
			List<String> actions)
		{
			this(
				index,
				id,
				name,
				x,
				y,
				plane,
				distance,
				combatLevel,
				animation,
				interacting,
				size,
				actions,
				false,
				false,
				false,
				false,
				-1,
				-1,
				null,
				null);
		}

		NpcSnapshot(
			int index,
			int id,
			String name,
			int x,
			int y,
			int plane,
			int distance,
			int combatLevel,
			int animation,
			String interacting,
			int size,
			List<String> actions,
			boolean inScene,
			boolean clickable,
			boolean lineOfSight,
			boolean dead,
			int healthRatio,
			int healthScale,
			Point canvasPoint,
			Rectangle canvasBounds)
		{
			this.index = index;
			this.id = id;
			this.name = name;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.distance = distance;
			this.combatLevel = combatLevel;
			this.animation = animation;
			this.interacting = interacting;
			this.size = size;
			this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
			this.inScene = inScene;
			this.clickable = clickable;
			this.lineOfSight = lineOfSight;
			this.dead = dead;
			this.healthRatio = healthRatio;
			this.healthScale = healthScale;
			this.canvasPoint = canvasPoint == null ? null : new Point(canvasPoint);
			this.canvasBounds = canvasBounds == null ? null : new Rectangle(canvasBounds);
		}

		int getIndex()
		{
			return index;
		}

		int getDistance()
		{
			return distance;
		}

		int getId()
		{
			return id;
		}

		String getName()
		{
			return name;
		}

		int getCombatLevel()
		{
			return combatLevel;
		}

		int getAnimation()
		{
			return animation;
		}

		String getInteracting()
		{
			return interacting;
		}

		int getSize()
		{
			return size;
		}

		WorldPoint getWorldPoint()
		{
			return new WorldPoint(x, y, plane);
		}

		boolean isDead()
		{
			return dead;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("index", (long) index);
			value.put("id", (long) id);
			value.put("name", name);
			value.put("world", worldMap(x, y, plane));
			value.put("distance", (long) distance);
			value.put("combat_level", (long) combatLevel);
			value.put("animation", (long) animation);
			value.put("interacting", interacting);
			value.put("size", (long) size);
			value.put("actions", actions);
			value.put("in_scene", inScene);
			value.put("clickable", clickable);
			value.put("line_of_sight", lineOfSight);
			value.put("dead", dead);
			value.put("health_ratio", (long) healthRatio);
			value.put("health_scale", (long) healthScale);
			value.put("canvas", canvasMap(canvasPoint, canvasBounds));
			return value;
		}

		String worldPoint()
		{
			return x + "," + y + "," + plane;
		}
	}

	private static Map<String, Object> canvasMap(Point point, Rectangle bounds)
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
