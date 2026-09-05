package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

public class GenericClientQuestSnapshotTest
{
	@Test
	public void capturesTheDoorFootprintForPlanningAndInteractionWithoutOpeningAdjacentWalls()
	{
		java.util.concurrent.atomic.AtomicInteger width = new java.util.concurrent.atomic.AtomicInteger(3);
		GameObject door = proxy(GameObject.class, (method, arguments) ->
		{
			switch (method)
			{
				case "getId": return 1967;
				case "getWorldLocation": return new WorldPoint(2464, 3492, 0);
				case "sizeX": return width.get();
				case "sizeY": return 1;
				default: return null;
			}
		});
		Tile[][][] tiles = new Tile[1][12][12];
		tiles[0][4][5] = proxy(Tile.class, (method, arguments) ->
			"getGameObjects".equals(method) ? new GameObject[]{door} : null);
		Scene scene = proxy(Scene.class, (method, arguments) -> "getTiles".equals(method) ? tiles : null);
		WorldView view = proxy(WorldView.class, (method, arguments) -> "getScene".equals(method) ? scene : null);
		Player player = proxy(Player.class, (method, arguments) ->
		{
			switch (method)
			{
				case "getWorldLocation": return new WorldPoint(2465, 3491, 0);
				case "getLocalLocation": return new LocalPoint(704, 576, WorldView.TOPLEVEL);
				case "getWorldView": return view;
				default: return null;
			}
		});
		ObjectComposition definition = proxy(ObjectComposition.class, (method, arguments) ->
			"getName".equals(method) ? "Tree door" : "getActions".equals(method) ? new String[]{"Open"} : null);
		Client client = proxy(Client.class, (method, arguments) ->
			"getVarps".equals(method) ? new int[0] : "getObjectDefinition".equals(method) ? definition : null);
		GenericClientQuestSnapshot captured = GenericClientQuestSnapshot.capture(client, player, new GenericClientEntityIds());
		width.set(1);
		int[][] flags = new int[12][12];
		for (int x = 2; x <= 6; x++) flags[x][5] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
		GenericClientSnapshot snapshot = new GenericClientSnapshot(1, "LOGGED_IN", 240,
			new GenericClientPlayerSnapshot(1L, "Player", 2465, 3491, 0, 0), List.of(),
			GenericClientAccountSnapshot.empty(), captured, List.of(),
			new GenericClientSceneCollision(true, 2460, 3487, 0, flags));
		assertTrue(snapshot.canPlanMove(2465, 3491, 0, 0, 1, false));
		assertTrue(snapshot.canPlanMove(2465, 3492, 0, 0, 1, false));
		assertFalse(snapshot.canPlanMove(2462, 3491, 0, 0, 1, false));
		assertFalse(snapshot.canPlanMove(2466, 3491, 0, 0, 1, false));
		GenericClientSnapshot.RouteBlock block = snapshot.findRouteBlock(
			List.of(new WorldPoint(2465, 3491, 0), new WorldPoint(2465, 3492, 0)), 0, 1);
		assertEquals(1967, block.getObjectId());
		assertEquals(new WorldPoint(2464, 3492, 0), block.getWorld());
		assertEquals("Open", block.getAction());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void readsOnlyRequestedCopiedVarps()
	{
		int[] varps = new int[100];
		varps[65] = 3;
		GenericClientQuestSnapshot snapshot = new GenericClientQuestSnapshot(
			true,
			varps,
			Collections.emptyList(),
			GenericClientQuestSnapshot.DialogueSnapshot.closed());
		Map<String, Object> query = new LinkedHashMap<>();
		query.put("varps", Arrays.asList(65L, 66L));

		Map<String, Object> value = (Map<String, Object>) snapshot.read("vars", query);
		Map<Long, Object> selected = (Map<Long, Object>) value.get("varps");

		assertEquals(true, value.get("available"));
		assertEquals(3L, selected.get(65L));
		assertEquals(0L, selected.get(66L));
		assertEquals(2, selected.size());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void readsRequestedCapturedVarbits()
	{
		Map<Integer, Integer> captured = new LinkedHashMap<>();
		captured.put(9110, 1);
		captured.put(VarbitID.MACRO_PINBALL_SCORE, 7);
		captured.put(9585, 3);
		GenericClientQuestSnapshot snapshot = new GenericClientQuestSnapshot(
			true,
			new int[0],
			captured,
			Collections.emptyList(),
			GenericClientQuestSnapshot.DialogueSnapshot.closed());
		Map<String, Object> query = new LinkedHashMap<>();
		query.put("varbits", Arrays.asList(
			9110L,
			(long) VarbitID.MACRO_PINBALL_SCORE,
			9585L));

		Map<String, Object> value = (Map<String, Object>) snapshot.read("vars", query);
		Map<Long, Object> selected = (Map<Long, Object>) value.get("varbits");

		assertEquals(1L, selected.get(9110L));
		assertEquals(7L, selected.get((long) VarbitID.MACRO_PINBALL_SCORE));
		assertEquals(3L, selected.get(9585L));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void keepsDistinctSameIdObjectsAndFiltersByAction()
	{
		GenericClientQuestSnapshot snapshot = new GenericClientQuestSnapshot(
			true,
			new int[0],
			Arrays.asList(
				new GenericClientQuestSnapshot.ObjectSnapshot(3L,
					2005, "Stone pillar", "game", 2562, 9910, 0, 2, Collections.singletonList("Use")),
				new GenericClientQuestSnapshot.ObjectSnapshot(3L,
					2005, "Stone pillar", "game", 2569, 9910, 0, 5, Collections.singletonList("Use")),
				new GenericClientQuestSnapshot.ObjectSnapshot(3L,
					2006, "Statue of Glarial", "game", 2603, 9915, 0, 8, Collections.singletonList("Use"))),
			GenericClientQuestSnapshot.DialogueSnapshot.closed());
		Map<String, Object> query = new LinkedHashMap<>();
		query.put("id", 2005L);
		query.put("action", "use");
		query.put("within", 10L);

		List<Map<String, Object>> objects =
			(List<Map<String, Object>>) snapshot.read("objects", query);

		assertEquals(2, objects.size());
		assertEquals(2562L, ((Map<String, Object>) objects.get(0).get("world")).get("x"));
		assertEquals(2569L, ((Map<String, Object>) objects.get(1).get("world")).get("x"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void exposesOrderedDialogueChoices()
	{
		GenericClientQuestSnapshot snapshot = new GenericClientQuestSnapshot(
			true,
			new int[0],
			Collections.emptyList(),
			GenericClientQuestSnapshot.DialogueSnapshot.choice(Arrays.asList("Yes.", "No.")));

		Map<String, Object> dialogue =
			(Map<String, Object>) snapshot.read("dialogue", Collections.emptyMap());
		List<Map<String, Object>> options = (List<Map<String, Object>>) dialogue.get("options");

		assertEquals(true, dialogue.get("open"));
		assertEquals("choice", dialogue.get("type"));
		assertEquals(1L, options.get(0).get("index"));
		assertEquals("Yes.", options.get(0).get("text"));
		assertEquals(2L, options.get(1).get("index"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void filtersGroundItemsWithoutCollapsingQuantities()
	{
		GenericClientQuestSnapshot snapshot = new GenericClientQuestSnapshot(
			true,
			new int[0],
			Collections.emptyList(),
			Arrays.asList(
				new GenericClientQuestSnapshot.GroundItemSnapshot(
					2407, "Ball", 1, 2935, 3460, 0, 2, 1, true),
				new GenericClientQuestSnapshot.GroundItemSnapshot(
					2408, "Witch's diary", 1, 2903, 3471, 0, 12, 1, true)),
			GenericClientQuestSnapshot.DialogueSnapshot.closed());
		Map<String, Object> query = new LinkedHashMap<>();
		query.put("id", 2407L);
		query.put("within", 5L);

		List<Map<String, Object>> items =
			(List<Map<String, Object>>) snapshot.read("ground_items", query);

		assertEquals(1, items.size());
		assertEquals("Ball", items.get(0).get("name"));
		assertEquals(1L, items.get(0).get("quantity"));
		assertEquals(Collections.singletonList("Take"), items.get(0).get("actions"));

		query.put("limit", 0L);
		assertEquals(Collections.emptyList(), snapshot.read("ground_items", query));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void capturesEmptyTilesWhoseGroundItemListIsNull()
	{
		Tile tile = proxy(Tile.class, (method, arguments) ->
		{
			if ("getGroundItems".equals(method))
			{
				return null;
			}
			return null;
		});
		Tile[][][] tiles = new Tile[1][104][104];
		tiles[0][50][50] = tile;
		GenericClientQuestSnapshot snapshot = capture(tiles);
		assertEquals(Collections.emptyList(), snapshot.read("ground_items", Collections.emptyMap()));
	}

	@Test public void sceneUnloadingCanPublishAnEmptyTileGrid()
	{
		for (Tile[][][] tiles : List.of(new Tile[0][][], new Tile[1][0][], new Tile[1][1][0]))
		{
			GenericClientQuestSnapshot snapshot = capture(tiles);
			assertEquals(List.of(), snapshot.read("objects", Map.of()));
			assertEquals(List.of(), snapshot.read("ground_items", Map.of()));
		}
	}

	private static GenericClientQuestSnapshot capture(Tile[][][] tiles)
	{
		Scene scene = proxy(Scene.class, (method, arguments) ->
			"getTiles".equals(method) ? tiles : null);
		WorldView worldView = proxy(WorldView.class, (method, arguments) ->
		{
			if ("getScene".equals(method))
			{
				return scene;
			}
			if ("getId".equals(method))
			{
				return 0;
			}
			return null;
		});
		Player player = proxy(Player.class, (method, arguments) ->
		{
			if ("getWorldLocation".equals(method))
			{
				return new WorldPoint(3200, 3200, 0);
			}
			if ("getLocalLocation".equals(method))
			{
				return LocalPoint.fromScene(50, 50, worldView);
			}
			if ("getWorldView".equals(method))
			{
				return worldView;
			}
			return null;
		});
		Client client = proxy(Client.class, (method, arguments) ->
			"getVarps".equals(method) ? new int[0] : null);

		return GenericClientQuestSnapshot.capture(client, player, new GenericClientEntityIds());
	}

	private static <T> T proxy(Class<T> type, Invocation invocation)
	{
		return type.cast(Proxy.newProxyInstance(
			type.getClassLoader(),
			new Class<?>[]{type},
			(proxy, method, arguments) ->
			{
				Object value = invocation.invoke(method.getName(), arguments);
				if (value != null || !method.getReturnType().isPrimitive())
				{
					return value;
				}
				return method.getReturnType() == boolean.class ? false : 0;
			}));
	}

	@FunctionalInterface
	private interface Invocation
	{
		Object invoke(String method, Object[] arguments);
	}
}
