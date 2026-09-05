package com.genericclient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class GenericClientEmergencyTestFixtures
{
	private GenericClientEmergencyTestFixtures() { }

	static GenericClientSnapshot snapshotWithHitpoints(int hitpoints)
	{
		return snapshotWithHitpoints(hitpoints, 10);
	}

	static GenericClientSnapshot snapshotWithHitpoints(int hitpoints, int maximumHitpoints)
	{
		return new GenericClientSnapshot(
			1,
			"LOGGED_IN",
			231,
			new GenericClientWorldSnapshot.PlayerSnapshot(
				"Player", 3200, 3200, 0, 0, -1, null,
				hitpoints, maximumHitpoints, 10_000, false, null),
			Collections.emptyList());
	}

	static GenericClientSnapshot snapshotWithInventory(
		long gameTick,
		int hitpoints,
		int maximumHitpoints,
		int itemId,
		int quantity)
	{
		GenericClientAccountSnapshot account = new GenericClientAccountSnapshot(
			true,
			1,
			Collections.emptyList(),
			new GenericClientAccountSnapshot.ContainerSnapshot(
				true,
				28,
				Collections.singletonList(new GenericClientAccountSnapshot.ItemSnapshot(
					0,
					null,
					itemId,
					quantity,
					"Lobster",
					false,
					true,
					true,
					Collections.singletonList("Eat")))),
			GenericClientAccountSnapshot.ContainerSnapshot.unavailable());
		return new GenericClientSnapshot(
			gameTick,
			"LOGGED_IN",
			231,
			new GenericClientWorldSnapshot.PlayerSnapshot(
				"Player", 3200, 3200, 0, 0, -1, null,
				hitpoints, maximumHitpoints, 10_000, false, null),
			Collections.emptyList(),
			account);
	}

	static Map<String, Object> dispatched()
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "dispatched");
		receipt.put("result", "item_interaction_dispatched");
		receipt.put("click_count", 1L);
		return receipt;
	}

	static Map<String, Object> rejected()
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "rejected");
		receipt.put("result", "missing_item");
		receipt.put("click_count", 0L);
		return receipt;
	}

	static Map<String, Object> escapeStarted()
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "dispatched");
		receipt.put("result", "emergency_walk_started");
		receipt.put("click_count", 0L);
		return receipt;
	}
}
