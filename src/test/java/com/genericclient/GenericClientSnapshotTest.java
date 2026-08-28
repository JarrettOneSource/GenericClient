package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.CollisionDataFlag;
import org.junit.Test;

public class GenericClientSnapshotTest
{
	@Test
	@SuppressWarnings("unchecked")
	public void queriesNearbyNpcsByNameActionAndLimit()
	{
		GenericClientSnapshot snapshot = new GenericClientSnapshot(
			42,
			"LOGGED_IN",
			231,
			new GenericClientSnapshot.PlayerSnapshot("Player", 3200, 3200, 0, -1),
			Arrays.asList(
				new GenericClientSnapshot.NpcSnapshot(
					1, 100, "Banker", 3201, 3200, 0, 1, 0, -1, null, Arrays.asList("Talk-to", "Bank")),
				new GenericClientSnapshot.NpcSnapshot(
					2, 101, "Banker", 3205, 3200, 0, 5, 0, -1, null, Collections.singletonList("Talk-to")),
				new GenericClientSnapshot.NpcSnapshot(
					3, 102, "Guard", 3202, 3200, 0, 2, 21, -1, null, Collections.singletonList("Attack"))));

		Map<String, Object> where = new LinkedHashMap<>();
		where.put("name", "banker");
		Map<String, Object> query = new LinkedHashMap<>();
		query.put("where", where);
		query.put("within", 3L);
		query.put("action", "Bank");
		query.put("limit", 1L);

		List<Map<String, Object>> result = (List<Map<String, Object>>) snapshot.read("npcs", query);

		assertEquals(1, result.size());
		assertEquals(100L, result.get(0).get("id"));
		assertEquals(1L, result.get(0).get("distance"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void queriesNpcByIdAndExposesFootprintSize()
	{
		GenericClientSnapshot snapshot = new GenericClientSnapshot(
			42,
			"LOGGED_IN",
			231,
			new GenericClientSnapshot.PlayerSnapshot("Player", 2936, 3460, 0, -1),
			Collections.singletonList(new GenericClientSnapshot.NpcSnapshot(
				7, 3999, "Experiment", 2935, 3461, 0, 1, 53, -1, null, 2,
				Collections.singletonList("Attack"))));
		Map<String, Object> query = new LinkedHashMap<>();
		query.put("id", 3999L);

		List<Map<String, Object>> result =
			(List<Map<String, Object>>) snapshot.read("npcs", query);

		assertEquals(1, result.size());
		assertEquals(3999L, result.get(0).get("id"));
		assertEquals(2L, result.get(0).get("size"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void exposesAndFiltersNpcInteractionDiagnostics()
	{
		GenericClientSnapshot snapshot = new GenericClientSnapshot(
			42,
			"LOGGED_IN",
			231,
			new GenericClientSnapshot.PlayerSnapshot("Player", 3017, 3194, 0, 0),
			Collections.singletonList(new GenericClientSnapshot.NpcSnapshot(
				7, 1448, "Thief", 3013, 3196, 0, 4, 16, -1, null, 1,
				Collections.singletonList("Attack"), true, true, false, false, -1, -1,
				new Point(119, 127), new Rectangle(90, 100, 50, 80))));
		Map<String, Object> where = new LinkedHashMap<>();
		where.put("clickable", true);
		where.put("line_of_sight", false);
		Map<String, Object> query = new LinkedHashMap<>();
		query.put("where", where);

		List<Map<String, Object>> result =
			(List<Map<String, Object>>) snapshot.read("npcs", query);
		Map<String, Object> canvas = (Map<String, Object>) result.get(0).get("canvas");
		Map<String, Object> point = (Map<String, Object>) canvas.get("point");

		assertEquals(1, result.size());
		assertEquals(true, result.get(0).get("clickable"));
		assertEquals(false, result.get(0).get("line_of_sight"));
		assertEquals(119L, point.get("x"));
		assertEquals(127L, point.get("y"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void queriesRecentGameMessagesNewestFirst()
	{
		GenericClientSnapshot snapshot = new GenericClientSnapshot(
			15,
			"LOGGED_IN",
			231,
			new GenericClientSnapshot.PlayerSnapshot("Player", 3017, 3194, 0, 0),
			Collections.emptyList(),
			GenericClientAccountSnapshot.empty(),
			GenericClientQuestSnapshot.empty(),
			Arrays.asList(
				new GenericClientGameMessageBuffer.Message(12, "gamemessage", "", "", "Earlier"),
				new GenericClientGameMessageBuffer.Message(
					14, "gamemessage", "", "", "I can't reach that."),
				new GenericClientGameMessageBuffer.Message(
					15, "gamemessage", "", "", "The door is securely locked.")));
		Map<String, Object> query = new LinkedHashMap<>();
		query.put("since_tick", 13L);
		query.put("contains", "reach");

		List<Map<String, Object>> result =
			(List<Map<String, Object>>) snapshot.read("messages", query);

		assertEquals(1, result.size());
		assertEquals(14L, result.get(0).get("game_tick"));
		assertEquals("I can't reach that.", result.get(0).get("text"));
		assertEquals("The door is securely locked.", snapshot.lockedObstacleMessageSince(15));
	}

	@Test
	public void livePlannerBlocksWallsButRetainsClosedDoorEdges()
	{
		int[][] flags = new int[5][5];
		flags[2][1] = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		flags[2][2] = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
		GenericClientQuestSnapshot withDoor = new GenericClientQuestSnapshot(
			true,
			new int[0],
			Collections.singletonList(new GenericClientQuestSnapshot.ObjectSnapshot(
				2000, "Door", "wall", 102, 201, 0, 1, Collections.singletonList("Open"), 1, 0)),
			GenericClientQuestSnapshot.DialogueSnapshot.closed());
		GenericClientSnapshot doorSnapshot = sceneSnapshot(flags, withDoor);
		GenericClientQuestSnapshot withDrawers = new GenericClientQuestSnapshot(
			true,
			new int[0],
			Collections.singletonList(new GenericClientQuestSnapshot.ObjectSnapshot(
				2001, "Drawers", "game", 102, 201, 0, 1,
				Collections.singletonList("Open"), 0, 0)),
			GenericClientQuestSnapshot.DialogueSnapshot.closed());
		GenericClientSnapshot drawersSnapshot = sceneSnapshot(flags, withDrawers);
		GenericClientSnapshot wallSnapshot = sceneSnapshot(flags, GenericClientQuestSnapshot.empty());

		assertEquals(true, doorSnapshot.canPlanMove(101, 201, 0, 1, 0, true));
		assertEquals(false, doorSnapshot.canPlanMove(102, 201, 0, 0, 1, true));
		assertEquals(false, drawersSnapshot.canPlanMove(101, 201, 0, 1, 0, true));
		assertEquals(false, wallSnapshot.canPlanMove(101, 201, 0, 1, 0, true));
	}

	private static GenericClientSnapshot sceneSnapshot(
		int[][] flags,
		GenericClientQuestSnapshot quest)
	{
		return new GenericClientSnapshot(
			15,
			"LOGGED_IN",
			240,
			new GenericClientSnapshot.PlayerSnapshot("Player", 101, 201, 0, 0),
			Collections.emptyList(),
			GenericClientAccountSnapshot.empty(),
			quest,
			Collections.emptyList(),
			new GenericClientSceneCollision(true, 100, 200, 0, flags));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void exposesSkillsByStableLowercaseName()
	{
		GenericClientSnapshot snapshot = new GenericClientSnapshot(
			43,
			"LOGGED_IN",
			231,
			new GenericClientSnapshot.PlayerSnapshot("Player", 3200, 3200, 0, -1),
			Collections.emptyList(),
			new GenericClientAccountSnapshot(
				true,
				36,
				Arrays.asList(
					new GenericClientAccountSnapshot.SkillSnapshot("attack", 1, 3, 12),
					new GenericClientAccountSnapshot.SkillSnapshot("strength", 4, 4, 300)),
				GenericClientAccountSnapshot.ContainerSnapshot.unavailable(),
				GenericClientAccountSnapshot.ContainerSnapshot.unavailable()));

		Map<String, Object> skills = (Map<String, Object>) snapshot.read("skills", Collections.emptyMap());
		Map<String, Object> attack = (Map<String, Object>) skills.get("attack");

		assertEquals(true, skills.get("available"));
		assertEquals(36L, skills.get("total_level"));
		assertEquals(1L, attack.get("level"));
		assertEquals(3L, attack.get("boosted_level"));
		assertEquals(12L, attack.get("xp"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void reportsSkillsUnavailableOutsideTheGame()
	{
		GenericClientSnapshot snapshot = new GenericClientSnapshot(
			0,
			"LOGIN_SCREEN",
			231,
			null,
			Collections.emptyList());

		Map<String, Object> skills = (Map<String, Object>) snapshot.read("skills", Collections.emptyMap());

		assertEquals(false, skills.get("available"));
		assertEquals(0L, skills.get("total_level"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void combinesWorldAndAccountStateInOneRead()
	{
		GenericClientSnapshot snapshot = new GenericClientSnapshot(
			44,
			"LOGGED_IN",
			231,
			new GenericClientSnapshot.PlayerSnapshot("Player", 3200, 3200, 0, -1),
			Collections.emptyList());

		Map<String, Object> account = (Map<String, Object>) snapshot.read("account", Collections.emptyMap());
		Map<String, Object> runtime = (Map<String, Object>) account.get("runtime");
		Map<String, Object> player = (Map<String, Object>) account.get("player");

		assertEquals(44L, runtime.get("game_tick"));
		assertEquals("Player", player.get("name"));
		assertEquals(true, account.containsKey("skills"));
		assertEquals(true, account.containsKey("inventory"));
		assertEquals(true, account.containsKey("equipment"));
		assertEquals(true, account.containsKey("bank"));
		assertEquals(true, account.containsKey("quests"));
		assertEquals(true, account.containsKey("grand_exchange"));
		assertEquals(true, account.containsKey("cash"));
		assertEquals(true, account.containsKey("combat"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void exposesPlayerCombatState()
	{
		GenericClientSnapshot snapshot = new GenericClientSnapshot(
			45,
			"LOGGED_IN",
			231,
			new GenericClientSnapshot.PlayerSnapshot(
				"Player", 3200, 3200, 0, -1, 422, "Goblin"),
			Collections.emptyList());

		Map<String, Object> player = (Map<String, Object>) snapshot.read("player", Collections.emptyMap());

		assertEquals(422L, player.get("animation"));
		assertEquals("Goblin", player.get("interacting"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void exposesPlayerVitalsAndDestination()
	{
		GenericClientSnapshot snapshot = new GenericClientSnapshot(
			46,
			"LOGGED_IN",
			231,
			new GenericClientSnapshot.PlayerSnapshot(
				"Player",
				3200,
				3200,
				0,
				0,
				-1,
				null,
				9,
				24,
				6_250,
				true,
				new net.runelite.api.coords.WorldPoint(3205, 3207, 0)),
			Collections.emptyList());

		Map<String, Object> player = (Map<String, Object>) snapshot.read("player", Collections.emptyMap());
		Map<String, Object> destination = (Map<String, Object>) player.get("destination");

		assertEquals(9L, player.get("current_hitpoints"));
		assertEquals(24L, player.get("max_hitpoints"));
		assertEquals(6_250L, player.get("run_energy"));
		assertEquals(true, player.get("run_enabled"));
		assertEquals(3205L, destination.get("x"));
		assertEquals(3207L, destination.get("y"));
	}
}
