package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
}
