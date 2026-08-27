package com.genericclient;

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
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

final class GenericClientSnapshot
{
	private static final int MAX_QUERY_RESULTS = 100;

	private final long gameTick;
	private final String gameState;
	private final int gameRevision;
	private final PlayerSnapshot player;
	private final List<NpcSnapshot> npcs;
	private final GenericClientAccountSnapshot account;

	GenericClientSnapshot(
		long gameTick,
		String gameState,
		int gameRevision,
		PlayerSnapshot player,
		List<NpcSnapshot> npcs)
	{
		this(gameTick, gameState, gameRevision, player, npcs, GenericClientAccountSnapshot.empty());
	}

	GenericClientSnapshot(
		long gameTick,
		String gameState,
		int gameRevision,
		PlayerSnapshot player,
		List<NpcSnapshot> npcs,
		GenericClientAccountSnapshot account)
	{
		this.gameTick = gameTick;
		this.gameState = gameState;
		this.gameRevision = gameRevision;
		this.player = player;
		this.npcs = Collections.unmodifiableList(new ArrayList<>(npcs));
		this.account = account;
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
		Player localPlayer = client.getLocalPlayer();
		PlayerSnapshot playerSnapshot = null;
		List<NpcSnapshot> npcSnapshots = new ArrayList<>();

		if (localPlayer != null && localPlayer.getWorldLocation() != null)
		{
			WorldPoint playerPoint = localPlayer.getWorldLocation();
			WorldView worldView = localPlayer.getWorldView();
			playerSnapshot = new PlayerSnapshot(
				Objects.toString(localPlayer.getName(), ""),
				playerPoint.getX(),
				playerPoint.getY(),
				playerPoint.getPlane(),
				worldView.getId(),
				localPlayer.getAnimation(),
				localPlayer.getInteracting() == null ? null : localPlayer.getInteracting().getName());

			for (NPC npc : worldView.npcs())
			{
				if (npc == null || npc.getWorldLocation() == null)
				{
					continue;
				}

				WorldPoint location = npc.getWorldLocation();
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
					getActions(npc)));
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
			GenericClientAccountSnapshot.capture(client, bankCache, questCache, gameTick));
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

	int countNearbyNpcs(int radius)
	{
		int count = 0;
		for (NpcSnapshot npc : npcs)
		{
			if (npc.distance <= radius)
			{
				count++;
			}
		}
		return count;
	}

	String formatNpcDiagnostics(int radius, int limit)
	{
		StringBuilder output = new StringBuilder();
		List<NpcSnapshot> nearby = npcs.stream()
			.filter(npc -> npc.distance <= radius)
			.collect(Collectors.toList());

		output.append("radius=").append(radius)
			.append(" total=").append(nearby.size())
			.append("\nplayer=")
			.append(player == null ? "unavailable" : player.worldPoint())
			.append("\n\n");

		int logged = Math.min(nearby.size(), limit);
		for (int i = 0; i < logged; i++)
		{
			NpcSnapshot npc = nearby.get(i);
			output.append(String.format(
				"%02d %-18s id=%d idx=%d d=%d\n    at=%s combat=%d animation=%d interacting=%s\n    actions=%s\n",
				i + 1,
				npc.name,
				npc.id,
				npc.index,
				npc.distance,
				npc.worldPoint(),
				npc.combatLevel,
				npc.animation,
				npc.interacting == null ? "none" : npc.interacting,
				npc.actions));
		}

		if (nearby.size() > logged)
		{
			output.append("\n...").append(nearby.size() - logged).append(" additional NPCs omitted");
		}
		return output.toString();
	}

	long getGameTick()
	{
		return gameTick;
	}

	WorldPoint getPlayerWorldPoint()
	{
		return player == null ? null : new WorldPoint(player.x, player.y, player.plane);
	}

	private Map<String, Object> runtimeMap()
	{
		Map<String, Object> runtime = new LinkedHashMap<>();
		runtime.put("api_version", 1L);
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
		if (query != null && query.get("where") instanceof Map)
		{
			requiredName = stringValue(((Map<?, ?>) query.get("where")).get("name"));
		}

		List<Map<String, Object>> result = new ArrayList<>();
		for (NpcSnapshot npc : npcs)
		{
			if (npc.distance > within ||
				(requiredName != null && !npc.name.equalsIgnoreCase(requiredName)) ||
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

	private static int intValue(Object value, int defaultValue)
	{
		return value instanceof Number ? ((Number) value).intValue() : defaultValue;
	}

	private static String stringValue(Object value)
	{
		return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
	}

	private static List<String> getActions(NPC npc)
	{
		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null)
		{
			composition = npc.getComposition();
		}
		if (composition == null || composition.getActions() == null)
		{
			return Collections.emptyList();
		}
		return Arrays.stream(composition.getActions())
			.filter(Objects::nonNull)
			.collect(Collectors.toList());
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

		PlayerSnapshot(String name, int x, int y, int plane, int worldViewId)
		{
			this(name, x, y, plane, worldViewId, -1, null);
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
			this.name = name;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.worldViewId = worldViewId;
			this.animation = animation;
			this.interacting = interacting;
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
		private final List<String> actions;

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
			this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
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
			value.put("actions", actions);
			return value;
		}

		String worldPoint()
		{
			return x + "," + y + "," + plane;
		}
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
