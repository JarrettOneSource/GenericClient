package com.genericclient;

import static com.genericclient.GenericClientWorldSnapshot.worldMap;

import com.genericclient.GenericClientWorldSnapshot.PlayerSnapshot;
import com.genericclient.GenericClientWorldSnapshot.NpcSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;

final class GenericClientSnapshot
{
	private static final java.util.regex.Pattern LOCKED_WORD =
		java.util.regex.Pattern.compile("\\blocked\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
	private static final int MAX_QUERY_RESULTS = 100;
	private static final Set<String> QUEST_SUBJECTS = Set.of(
		"vars", "objects", "ground_items", "dialogue");
	private static final Set<String> ACCOUNT_SUBJECTS = Set.of(
		"skills", "inventory", "equipment", "bank", "quests", "ge",
		"grand_exchange", "cash", "combat");
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
	private final GenericClientWorldSnapshot world;
	private final GenericClientAccountSnapshot account;
	private final GenericClientQuestSnapshot quest;
	private final List<GenericClientGameMessageBuffer.Message> messages;
	private final GenericClientWidgetSnapshot widgets;
	private final WorldPoint mouseTile;

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
		this(gameTick, gameState, gameRevision, player, npcs, account, quest, messages,
			sceneCollision, GenericClientWidgetSnapshot.empty());
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
		GenericClientSceneCollision sceneCollision,
		GenericClientWidgetSnapshot widgets)
	{
		this(gameTick, gameState, gameRevision, player, npcs, account, quest, messages,
			sceneCollision, widgets, null);
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
		GenericClientSceneCollision sceneCollision,
		GenericClientWidgetSnapshot widgets,
		WorldPoint mouseTile)
	{
		this(gameTick, gameState, gameRevision, new GenericClientWorldSnapshot(player, npcs, sceneCollision),
			account, quest, messages, widgets, mouseTile);
	}

	private GenericClientSnapshot(
		long gameTick,
		String gameState,
		int gameRevision,
		GenericClientWorldSnapshot world,
		GenericClientAccountSnapshot account,
		GenericClientQuestSnapshot quest,
		List<GenericClientGameMessageBuffer.Message> messages,
		GenericClientWidgetSnapshot widgets,
		WorldPoint mouseTile)
	{
		this.gameTick = gameTick;
		this.gameState = gameState;
		this.gameRevision = gameRevision;
		this.world = world;
		this.account = account;
		this.quest = quest;
		this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
		this.widgets = widgets;
		this.mouseTile = mouseTile;
	}

	String questStateKey()
	{
		return account.questStateKey();
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
		GenericClientWorldSnapshot world = GenericClientWorldSnapshot.capture(client, localPlayer);

		Tile selectedTile = localPlayer == null
			? null
			: localPlayer.getWorldView().getSelectedSceneTile();
		return new GenericClientSnapshot(
			gameTick,
			client.getGameState().name(),
			client.getRevision(),
			world,
			GenericClientAccountSnapshot.capture(client, bankCache, questCache, gameTick),
			GenericClientQuestSnapshot.capture(client, localPlayer),
			messages,
			GenericClientWidgetSnapshot.capture(client),
			selectedTile == null ? null : selectedTile.getWorldLocation());
	}

	Object read(String subject, Map<?, ?> query)
	{
		if (QUEST_SUBJECTS.contains(subject))
		{
			return quest.read(subject, query);
		}
		if (ACCOUNT_SUBJECTS.contains(subject))
		{
			return account.read(subject);
		}
		switch (subject)
		{
			case "runtime":
				return runtimeMap();
			case "player":
				return world.player == null ? null : world.player.toMap(gameState);
			case "account":
				return accountMap();
			case "npcs":
				return queryNpcs(query);
			case "messages":
				return queryMessages(query);
			case "mouse_tile":
				return mouseTile == null
					? null
					: worldMap(mouseTile.getX(), mouseTile.getY(), mouseTile.getPlane());
			case "scene":
				return world.collision.inspect(
					worldPoint(query == null ? null : query.get("from"), getPlayerWorldPoint()),
					worldPoint(query == null ? null : query.get("to"), null));
			case "instance":
				return world.collision.inspectInstance(
					worldPoint(query == null ? null : query.get("template"), null));
			case "widgets":
				return widgets.read(query);
			case "sliding_puzzle":
				return widgets.readSlidingPuzzle();
			default:
				throw new IllegalArgumentException("unknown subject: " + subject);
		}
	}

	PlayerSnapshot getPlayer()
	{
		return world.player;
	}

	List<NpcSnapshot> getNpcs()
	{
		return world.npcs;
	}

	List<GenericClientQuestSnapshot.ObjectSnapshot> getObjects() { return quest.getObjects(); }
	String questState(String key) { return account.questState(key); }

	long getGameTick()
	{
		return gameTick;
	}

	boolean isLoggedIn() { return GameState.LOGGED_IN.name().equals(gameState); }

	WorldPoint getPlayerWorldPoint()
	{
		return world.player == null ? null : new WorldPoint(world.player.x, world.player.y, world.player.plane);
	}

	int getCurrentHitpoints()
	{
		return world.player == null ? -1 : world.player.currentHitpoints;
	}

	int getMaximumHitpoints()
	{
		return world.player == null ? -1 : world.player.maxHitpoints;
	}

	long getInventoryQuantity(int itemId)
	{
		return account.inventoryQuantity(itemId);
	}

	Boolean inventoryContainsPrefix(String prefix) { return account.inventoryContainsPrefix(prefix); }
	GenericClientWidgetSnapshot getWidgets() { return widgets; }
	GenericClientQuestSnapshot.DialogueSnapshot getDialogue() { return quest.getDialogue(); }
	Integer boostedSkill(String skill) { return account.boostedSkill(skill); }
	Integer varp(int id) { return quest.varp(id); }
	Integer varbit(int id) { return quest.varbit(id); }

	int getRunEnergy()
	{
		return world.player == null ? 0 : world.player.runEnergy;
	}

	boolean isRunEnabled()
	{
		return world.player != null && world.player.runEnabled;
	}

	boolean isDialogueOpen()
	{
		return quest.isDialogueOpen();
	}

	boolean hasLiveSceneCollision()
	{
		return world.collision.isAvailable();
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
			if (!Boolean.FALSE.equals(world.collision.canMove(from, to)))
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
		Boolean liveAllowed = world.collision.canMove(from, to);
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
		Boolean liveMovement = world.collision.canMove(block.from, block.to);
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
			if (!"gamemessage".equals(message.getType()) && !"spam".equals(message.getType())) continue;
			if (LOCKED_WORD.matcher(message.getText()).find())
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
			String action = traversalAction(object.getActions());
			if (object.getPlane() != to.getPlane() || !isTraversalObject(object, action))
			{
				continue;
			}
			int edgeDistance = Math.min(
				distance(object.getWorldPoint(), from),
				distance(object.getWorldPoint(), to));
			if ((spansEdge(object, from, to) || adjacentPairedGate(object, action, from, to)) &&
				edgeDistance < bestDistance)
			{
				best = object;
				bestDistance = edgeDistance;
			}
		}
		return best;
	}

	private static boolean adjacentPairedGate(
		GenericClientQuestSnapshot.ObjectSnapshot object,
		String action,
		WorldPoint from,
		WorldPoint to)
	{
		if (!"wall".equals(object.getKind()) || !"Open".equalsIgnoreCase(action) ||
			!object.getName().toLowerCase(java.util.Locale.ROOT).contains("gate") ||
			(object.getOrientationA() | object.getOrientationB()) == 0 ||
			Math.abs(from.getX() - to.getX()) + Math.abs(from.getY() - to.getY()) != 1)
		{
			return false;
		}
		// The other leaf lies one tile along the wall, with the same crossing direction.
		int offsetX = from.getX() == to.getX() ? 1 : 0;
		int offsetY = offsetX == 0 ? 1 : 0;
		for (int sign : new int[]{-1, 1})
		{
			if (spansEdge(object,
				new WorldPoint(from.getX() + sign * offsetX, from.getY() + sign * offsetY, from.getPlane()),
				new WorldPoint(to.getX() + sign * offsetX, to.getY() + sign * offsetY, to.getPlane())))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean isTraversalObject(
		GenericClientQuestSnapshot.ObjectSnapshot object,
		String action)
	{
		if (action == null)
		{
			return false;
		}
		if ("wall".equals(object.getKind()))
		{
			return true;
		}
		if (!"game".equals(object.getKind()) || !"Open".equalsIgnoreCase(action))
		{
			return false;
		}
		String name = object.getName().toLowerCase(java.util.Locale.ROOT);
		return name.contains("door") || name.contains("gate");
	}

	private static boolean spansEdge(
		GenericClientQuestSnapshot.ObjectSnapshot object,
		WorldPoint from,
		WorldPoint to)
	{
		if ("game".equals(object.getKind()))
		{
			return Math.abs(from.getX() - to.getX()) + Math.abs(from.getY() - to.getY()) == 1 &&
				(object.occupies(from) || object.occupies(to));
		}
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
		runtime.put("api_version", 3L);
		runtime.put("game_tick", gameTick);
		runtime.put("game_state", gameState);
		runtime.put("game_revision", (long) gameRevision);
		return runtime;
	}

	private Map<String, Object> accountMap()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("runtime", runtimeMap());
		value.put("player", world.player == null ? null : world.player.toMap(gameState));
		value.putAll(account.toMap());
		return value;
	}

	private List<Map<String, Object>> queryNpcs(Map<?, ?> query)
	{
		NpcQuery criteria = NpcQuery.from(query);
		if (criteria.limit == 0)
		{
			return Collections.emptyList();
		}

		List<Map<String, Object>> result = new ArrayList<>();
		for (NpcSnapshot npc : world.npcs)
		{
			if (!criteria.matches(npc))
			{
				continue;
			}
			result.add(npc.toMap());
			if (result.size() == criteria.limit)
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

	private static final class NpcQuery
	{
		private final int within;
		private final int limit;
		private final String action;
		private final String name;
		private final Integer id;
		private final Boolean clickable;
		private final Boolean lineOfSight;
		private final Boolean dead;

		private NpcQuery(
			int within,
			int limit,
			String action,
			String name,
			Integer id,
			Boolean clickable,
			Boolean lineOfSight,
			Boolean dead)
		{
			this.within = within;
			this.limit = limit;
			this.action = action;
			this.name = name;
			this.id = id;
			this.clickable = clickable;
			this.lineOfSight = lineOfSight;
			this.dead = dead;
		}

		private static NpcQuery from(Map<?, ?> query)
		{
			int within = intValue(query == null ? null : query.get("within"), Integer.MAX_VALUE);
			int limit = Math.min(
				MAX_QUERY_RESULTS,
				Math.max(0, intValue(query == null ? null : query.get("limit"), 50)));
			String action = stringValue(query == null ? null : query.get("action"));
			Map<?, ?> where = query != null && query.get("where") instanceof Map
				? (Map<?, ?>) query.get("where")
				: Collections.emptyMap();
			Integer id = where.get("id") instanceof Number
				? ((Number) where.get("id")).intValue()
				: null;
			if (query != null && query.get("id") instanceof Number)
			{
				id = ((Number) query.get("id")).intValue();
			}
			return new NpcQuery(
				within,
				limit,
				action,
				stringValue(where.get("name")),
				id,
				booleanValue(where.get("clickable")),
				booleanValue(where.get("line_of_sight")),
				booleanValue(where.get("dead")));
		}

		private boolean matches(NpcSnapshot npc)
		{
			return npc.distance <= within &&
				(id == null || npc.id == id) &&
				(name == null || npc.name.equalsIgnoreCase(name)) &&
				(clickable == null || npc.clickable == clickable) &&
				(lineOfSight == null || npc.lineOfSight == lineOfSight) &&
				(dead == null || npc.dead == dead) &&
				(action == null || containsAction(npc.actions, action));
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

}
