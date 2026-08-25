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
}
