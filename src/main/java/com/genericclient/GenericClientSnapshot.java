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

final class GenericClientSnapshot
{
	private static final int MAX_QUERY_RESULTS = 100;
	private static final String[] TRAVERSAL_ACTIONS =
	{
		"Open",
		"Pass",
		"Climb-over",
		"Climb-through",
		"Squeeze-through",
		"Jump-over",
		"Cross",
		"Go-through"
	};

	private final long gameTick;
	private final String gameState;
	private final int gameRevision;
	private final PlayerSnapshot player;
	private final List<NpcSnapshot> npcs;
	private final GenericClientAccountSnapshot account;
	private final GenericClientQuestSnapshot quest;
	private final List<GenericClientGameMessageBuffer.Message> messages;
	private final GenericClientSceneCollision sceneCollision;

	GenericClientSnapshot(
		long gameTick,
		String gameState,
		int gameRevision,
		PlayerSnapshot player,
		List<NpcSnapshot> npcs)
	{
		this(
			gameTick,
			gameState,
			gameRevision,
			player,
			npcs,
			GenericClientAccountSnapshot.empty(),
			GenericClientQuestSnapshot.empty(),
			Collections.emptyList(),
			GenericClientSceneCollision.empty());
	}

	GenericClientSnapshot(
		long gameTick,
		String gameState,
		int gameRevision,
		PlayerSnapshot player,
		List<NpcSnapshot> npcs,
		GenericClientAccountSnapshot account)
	{
		this(
			gameTick,
			gameState,
			gameRevision,
			player,
			npcs,
			account,
			GenericClientQuestSnapshot.empty(),
			Collections.emptyList(),
			GenericClientSceneCollision.empty());
	}

	GenericClientSnapshot(
		long gameTick,
		String gameState,
		int gameRevision,
		PlayerSnapshot player,
		List<NpcSnapshot> npcs,
		GenericClientAccountSnapshot account,
		GenericClientQuestSnapshot quest)
	{
		this(gameTick, gameState, gameRevision, player, npcs, account, quest,
			Collections.emptyList(), GenericClientSceneCollision.empty());
	}

	GenericClientSnapshot(
		long gameTick,
		String gameState,
		int gameRevision,
		PlayerSnapshot player,
		List<NpcSnapshot> npcs,
		GenericClientAccountSnapshot account,
		GenericClientQuestSnapshot quest,
		List<GenericClientGameMessageBuffer.Message> messages)
	{
		this(gameTick, gameState, gameRevision, player, npcs, account, quest, messages,
			GenericClientSceneCollision.empty());
	}

	GenericClientSnapshot(
		long gameTick,
		String gameState,
		int gameRevision,
		PlayerSnapshot player,
		List<NpcSnapshot> npcs,
		GenericClientAccountSnapshot account,
		GenericClientQuestSnapshot quest,
		List<GenericClientGameMessageBuffer.Message> messages,
		GenericClientSceneCollision sceneCollision)
	{
		this.gameTick = gameTick;
		this.gameState = gameState;
		this.gameRevision = gameRevision;
		this.player = player;
		this.npcs = Collections.unmodifiableList(new ArrayList<>(npcs));
		this.account = account;
		this.quest = quest;
		this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
		this.sceneCollision = sceneCollision;
	}

	static GenericClientSnapshot capture(Client client, long gameTick)
	{
		return capture(client, gameTick, null, null);
	}

	static GenericClientSnapshot capture(
		Client client,
		long gameTick,
		GenericClientBankCache bankCache,
		GenericClientQuestCache questCache)
	{
		return capture(client, gameTick, bankCache, questCache, Collections.emptyList());
	}

	static GenericClientSnapshot capture(
		Client client,
		long gameTick,
		GenericClientBankCache bankCache,
		GenericClientQuestCache questCache,
		List<GenericClientGameMessageBuffer.Message> messages)
	{
		Player localPlayer = client.getLocalPlayer();
		PlayerSnapshot playerSnapshot = null;
		List<NpcSnapshot> npcSnapshots = new ArrayList<>();
		GenericClientSceneCollision sceneCollision = GenericClientSceneCollision.empty();

		if (localPlayer != null && localPlayer.getWorldLocation() != null)
		{
			WorldPoint playerPoint = localPlayer.getWorldLocation();
			WorldView worldView = localPlayer.getWorldView();
			sceneCollision = GenericClientSceneCollision.capture(worldView, playerPoint.getPlane());
			LocalPoint localDestination = client.getLocalDestinationLocation();
			WorldPoint worldDestination = localDestination == null
				? null
				: WorldPoint.fromLocal(
					worldView,
					localDestination.getX(),
					localDestination.getY(),
					playerPoint.getPlane());
			playerSnapshot = new PlayerSnapshot(
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

			for (NPC npc : worldView.npcs())
			{
				if (npc == null || npc.getWorldLocation() == null)
				{
					continue;
				}

				WorldPoint location = npc.getWorldLocation();
				NPCComposition composition = getComposition(npc);
				Shape clickShape = npc.getConvexHull();
				if (clickShape == null)
				{
					clickShape = npc.getCanvasTilePoly();
				}
				Point canvasPoint = GenericClientMenuInput.firstPointInside(
					clickShape, client.getCanvasWidth(), client.getCanvasHeight());
				Rectangle canvasBounds = visibleBounds(
					clickShape, client.getCanvasWidth(), client.getCanvasHeight());
				npcSnapshots.add(new NpcSnapshot(
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
					GenericClientNpcInput.hasLineOfSight(localPlayer, npc),
					npc.isDead(),
					npc.getHealthRatio(),
					npc.getHealthScale(),
					canvasPoint,
					canvasBounds));
			}
		}

		npcSnapshots.sort(Comparator
			.comparingInt(NpcSnapshot::getDistance)
			.thenComparingInt(NpcSnapshot::getIndex));

		return new GenericClientSnapshot(
			gameTick,
			client.getGameState().name(),
			client.getRevision(),
			playerSnapshot,
			npcSnapshots,
			GenericClientAccountSnapshot.capture(client, bankCache, questCache, gameTick),
			GenericClientQuestSnapshot.capture(client, localPlayer),
			messages,
			sceneCollision);
	}

	Object read(String subject, Map<?, ?> query)
	{
		switch (subject)
		{
			case "runtime":
				return runtimeMap();
			case "player":
				return player == null ? null : player.toMap(gameState);
			case "account":
				return accountMap();
			case "npcs":
				return queryNpcs(query);
			case "messages":
				return queryMessages(query);
			case "scene":
				return sceneCollision.inspect(
					worldPoint(query == null ? null : query.get("from"), getPlayerWorldPoint()),
					worldPoint(query == null ? null : query.get("to"), null));
			case "vars":
			case "objects":
			case "ground_items":
			case "dialogue":
				return quest.read(subject, query);
			case "skills":
			case "inventory":
			case "equipment":
			case "bank":
			case "quests":
			case "ge":
			case "grand_exchange":
			case "cash":
			case "combat":
				return account.read(subject);
			default:
				throw new IllegalArgumentException("unknown subject: " + subject);
		}
	}

	long getGameTick()
	{
		return gameTick;
	}

	WorldPoint getPlayerWorldPoint()
	{
		return player == null ? null : new WorldPoint(player.x, player.y, player.plane);
	}

	int getCurrentHitpoints()
	{
		return player == null ? -1 : player.currentHitpoints;
	}

	int getMaximumHitpoints()
	{
		return player == null ? -1 : player.maxHitpoints;
	}

	int getRunEnergy()
	{
		return player == null ? 0 : player.runEnergy;
	}

	boolean isRunEnabled()
	{
		return player != null && player.runEnabled;
	}

	boolean hasLiveSceneCollision()
	{
		return sceneCollision.isAvailable();
	}

	RouteBlock findRouteBlock(
		List<WorldPoint> path,
		int fromIndex,
		int toIndex)
	{
		if (path == null || path.size() < 2)
		{
			return null;
		}
		int start = Math.max(1, fromIndex + 1);
		int end = Math.min(path.size() - 1, toIndex);
		for (int index = start; index <= end; index++)
		{
			WorldPoint from = path.get(index - 1);
			WorldPoint to = path.get(index);
			if (!Boolean.FALSE.equals(sceneCollision.canMove(from, to)))
			{
				continue;
			}
			GenericClientQuestSnapshot.ObjectSnapshot obstacle =
				findTraversalObject(from, to);
			return new RouteBlock(
				index,
				from,
				to,
				obstacle,
				obstacle == null ? null : traversalAction(obstacle.getActions()));
		}
		return null;
	}

	boolean canPlanMove(
		int x,
		int y,
		int plane,
		int dx,
		int dy,
		boolean staticAllowed)
	{
		WorldPoint from = new WorldPoint(x, y, plane);
		WorldPoint to = new WorldPoint(x + dx, y + dy, plane);
		Boolean liveAllowed = sceneCollision.canMove(from, to);
		if (liveAllowed == null)
		{
			return staticAllowed;
		}
		return liveAllowed || findTraversalObject(from, to) != null;
	}

	boolean routeBlockCleared(RouteBlock block, int currentPathIndex)
	{
		if (block == null || currentPathIndex >= block.pathIndex)
		{
			return true;
		}
		Boolean liveMovement = sceneCollision.canMove(block.from, block.to);
		if (Boolean.TRUE.equals(liveMovement))
		{
			return true;
		}
		if (block.objectId < 0)
		{
			return false;
		}
		for (GenericClientQuestSnapshot.ObjectSnapshot object : quest.getObjects())
		{
			if (object.getId() == block.objectId && object.getWorldPoint().equals(block.world) &&
				containsAction(object.getActions(), block.action))
			{
				return false;
			}
		}
		return true;
	}

	String lockedObstacleMessageSince(long gameTick)
	{
		for (int index = messages.size() - 1; index >= 0; index--)
		{
			GenericClientGameMessageBuffer.Message message = messages.get(index);
			if (message.getGameTick() < gameTick)
			{
				break;
			}
			String lower = message.getText().toLowerCase(java.util.Locale.ROOT);
			if (lower.contains("locked") && !lower.contains("unlocked"))
			{
				return message.getText();
			}
		}
		return null;
	}

	private GenericClientQuestSnapshot.ObjectSnapshot findTraversalObject(
		WorldPoint from,
		WorldPoint to)
	{
		GenericClientQuestSnapshot.ObjectSnapshot best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (GenericClientQuestSnapshot.ObjectSnapshot object : quest.getObjects())
		{
			if (!"wall".equals(object.getKind()) || object.getPlane() != to.getPlane() ||
				traversalAction(object.getActions()) == null)
			{
				continue;
			}
			int edgeDistance = Math.min(
				distance(object.getWorldPoint(), from),
				distance(object.getWorldPoint(), to));
			if (spansEdge(object, from, to) && edgeDistance < bestDistance)
			{
				best = object;
				bestDistance = edgeDistance;
			}
		}
		return best;
	}

	private static boolean spansEdge(
		GenericClientQuestSnapshot.ObjectSnapshot object,
		WorldPoint from,
		WorldPoint to)
	{
		int orientations = object.getOrientationA() | object.getOrientationB();
		WorldPoint anchor = object.getWorldPoint();
		if (orientations == 0)
		{
			return anchor.equals(from) || anchor.equals(to);
		}
		return (orientations & 1) != 0 && sameEdge(
			from, to, anchor, new WorldPoint(anchor.getX() - 1, anchor.getY(), anchor.getPlane())) ||
			(orientations & 2) != 0 && sameEdge(
				from, to, anchor, new WorldPoint(anchor.getX(), anchor.getY() + 1, anchor.getPlane())) ||
			(orientations & 4) != 0 && sameEdge(
				from, to, anchor, new WorldPoint(anchor.getX() + 1, anchor.getY(), anchor.getPlane())) ||
			(orientations & 8) != 0 && sameEdge(
				from, to, anchor, new WorldPoint(anchor.getX(), anchor.getY() - 1, anchor.getPlane()));
	}

	private static boolean sameEdge(
		WorldPoint firstA,
		WorldPoint firstB,
		WorldPoint secondA,
		WorldPoint secondB)
	{
		return firstA.equals(secondA) && firstB.equals(secondB) ||
			firstA.equals(secondB) && firstB.equals(secondA);
	}

	private Map<String, Object> runtimeMap()
	{
		Map<String, Object> runtime = new LinkedHashMap<>();
		runtime.put("api_version", 2L);
		runtime.put("game_tick", gameTick);
		runtime.put("game_state", gameState);
		runtime.put("game_revision", (long) gameRevision);
		return runtime;
	}

	private Map<String, Object> accountMap()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("runtime", runtimeMap());
		value.put("player", player == null ? null : player.toMap(gameState));
		value.putAll(account.toMap());
		return value;
	}

	private List<Map<String, Object>> queryNpcs(Map<?, ?> query)
	{
		int within = intValue(query == null ? null : query.get("within"), Integer.MAX_VALUE);
		int limit = Math.min(MAX_QUERY_RESULTS, Math.max(0, intValue(query == null ? null : query.get("limit"), 50)));
		if (limit == 0)
		{
			return Collections.emptyList();
		}
		String requiredAction = stringValue(query == null ? null : query.get("action"));
		String requiredName = null;
		Integer requiredId = null;
		Boolean requiredClickable = null;
		Boolean requiredLineOfSight = null;
		Boolean requiredDead = null;
		if (query != null && query.get("where") instanceof Map)
		{
			Map<?, ?> where = (Map<?, ?>) query.get("where");
			requiredName = stringValue(where.get("name"));
			if (where.get("id") instanceof Number)
			{
				requiredId = ((Number) where.get("id")).intValue();
			}
			requiredClickable = booleanValue(where.get("clickable"));
			requiredLineOfSight = booleanValue(where.get("line_of_sight"));
			requiredDead = booleanValue(where.get("dead"));
		}
		if (query != null && query.get("id") instanceof Number)
		{
			requiredId = ((Number) query.get("id")).intValue();
		}

		List<Map<String, Object>> result = new ArrayList<>();
		for (NpcSnapshot npc : npcs)
		{
			if (npc.distance > within ||
				(requiredId != null && npc.id != requiredId) ||
				(requiredName != null && !npc.name.equalsIgnoreCase(requiredName)) ||
				(requiredClickable != null && npc.clickable != requiredClickable) ||
				(requiredLineOfSight != null && npc.lineOfSight != requiredLineOfSight) ||
				(requiredDead != null && npc.dead != requiredDead) ||
				(requiredAction != null && npc.actions.stream().noneMatch(action -> action.equalsIgnoreCase(requiredAction))))
			{
				continue;
			}
			result.add(npc.toMap());
			if (result.size() == limit)
			{
				break;
			}
		}
		return result;
	}

	private List<Map<String, Object>> queryMessages(Map<?, ?> query)
	{
		int limit = Math.min(50, Math.max(0, intValue(query == null ? null : query.get("limit"), 20)));
		if (limit == 0)
		{
			return Collections.emptyList();
		}
		long sinceTick = longValue(query == null ? null : query.get("since_tick"), Long.MIN_VALUE);
		String contains = stringValue(query == null ? null : query.get("contains"));
		String requiredType = null;
		if (query != null && query.get("where") instanceof Map)
		{
			requiredType = stringValue(((Map<?, ?>) query.get("where")).get("type"));
		}
		String lowerContains = contains == null ? null : contains.toLowerCase(java.util.Locale.ROOT);
		List<Map<String, Object>> result = new ArrayList<>();
		for (int index = messages.size() - 1; index >= 0 && result.size() < limit; index--)
		{
			GenericClientGameMessageBuffer.Message message = messages.get(index);
			if (message.getGameTick() < sinceTick ||
				(requiredType != null && !message.getType().equalsIgnoreCase(requiredType)) ||
				(lowerContains != null &&
					!message.getText().toLowerCase(java.util.Locale.ROOT).contains(lowerContains)))
			{
				continue;
			}
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("game_tick", message.getGameTick());
			value.put("type", message.getType());
			value.put("name", message.getName());
			value.put("sender", message.getSender());
			value.put("text", message.getText());
			result.add(value);
		}
		return result;
	}

	private static int intValue(Object value, int defaultValue)
	{
		return value instanceof Number ? ((Number) value).intValue() : defaultValue;
	}

	private static long longValue(Object value, long defaultValue)
	{
		return value instanceof Number ? ((Number) value).longValue() : defaultValue;
	}

	private static Boolean booleanValue(Object value)
	{
		return value instanceof Boolean ? (Boolean) value : null;
	}

	private static String stringValue(Object value)
	{
		return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
	}

	private static WorldPoint worldPoint(Object raw, WorldPoint defaultValue)
	{
		if (raw == null)
		{
			return defaultValue;
		}
		if (!(raw instanceof Map))
		{
			throw new IllegalArgumentException("world point must be a table with x, y, and plane");
		}
		Map<?, ?> value = (Map<?, ?>) raw;
		if (!(value.get("x") instanceof Number) || !(value.get("y") instanceof Number) ||
			!(value.get("plane") instanceof Number))
		{
			throw new IllegalArgumentException("world point must contain numeric x, y, and plane");
		}
		return new WorldPoint(
			((Number) value.get("x")).intValue(),
			((Number) value.get("y")).intValue(),
			((Number) value.get("plane")).intValue());
	}

	private static String traversalAction(List<String> actions)
	{
		for (String preferred : TRAVERSAL_ACTIONS)
		{
			for (String action : actions)
			{
				if (preferred.equalsIgnoreCase(action))
				{
					return action;
				}
			}
		}
		return null;
	}

	private static boolean containsAction(List<String> actions, String expected)
	{
		for (String action : actions)
		{
			if (action.equalsIgnoreCase(expected))
			{
				return true;
			}
		}
		return false;
	}

	private static int distance(WorldPoint first, WorldPoint second)
	{
		if (first.getPlane() != second.getPlane())
		{
			return Integer.MAX_VALUE;
		}
		return Math.max(Math.abs(first.getX() - second.getX()),
			Math.abs(first.getY() - second.getY()));
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

	private static Rectangle visibleBounds(Shape shape, int canvasWidth, int canvasHeight)
	{
		if (shape == null || canvasWidth <= 0 || canvasHeight <= 0)
		{
			return null;
		}
		Rectangle bounds = shape.getBounds().intersection(
			new Rectangle(0, 0, canvasWidth, canvasHeight));
		return bounds.isEmpty() ? null : bounds;
	}

	static final class PlayerSnapshot
	{
		private final String name;
		private final int x;
		private final int y;
		private final int plane;
		private final int worldViewId;
		private final int animation;
		private final String interacting;
		private final int currentHitpoints;
		private final int maxHitpoints;
		private final int runEnergy;
		private final boolean runEnabled;
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

		String worldPoint()
		{
			return x + "," + y + "," + plane;
		}
	}

	static final class NpcSnapshot
	{
		private final int index;
		private final int id;
		private final String name;
		private final int x;
		private final int y;
		private final int plane;
		private final int distance;
		private final int combatLevel;
		private final int animation;
		private final String interacting;
		private final int size;
		private final List<String> actions;
		private final boolean inScene;
		private final boolean clickable;
		private final boolean lineOfSight;
		private final boolean dead;
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

	static final class RouteBlock
	{
		private final int pathIndex;
		private final WorldPoint from;
		private final WorldPoint to;
		private final int objectId;
		private final String objectName;
		private final String action;
		private final WorldPoint world;

		private RouteBlock(
			int pathIndex,
			WorldPoint from,
			WorldPoint to,
			GenericClientQuestSnapshot.ObjectSnapshot object,
			String action)
		{
			this.pathIndex = pathIndex;
			this.from = from;
			this.to = to;
			this.objectId = object == null ? -1 : object.getId();
			this.objectName = object == null ? null : object.getName();
			this.action = action;
			this.world = object == null ? null : object.getWorldPoint();
		}

		int getPathIndex()
		{
			return pathIndex;
		}

		WorldPoint getFrom()
		{
			return from;
		}

		WorldPoint getTo()
		{
			return to;
		}

		int getObjectId()
		{
			return objectId;
		}

		String getObjectName()
		{
			return objectName;
		}

		String getAction()
		{
			return action;
		}

		WorldPoint getWorld()
		{
			return world;
		}

		boolean isTraversable()
		{
			return objectId >= 0 && action != null && world != null;
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

	private static Map<String, Object> worldMap(int x, int y, int plane)
	{
		Map<String, Object> world = new LinkedHashMap<>();
		world.put("x", (long) x);
		world.put("y", (long) y);
		world.put("plane", (long) plane);
		return world;
	}
}
