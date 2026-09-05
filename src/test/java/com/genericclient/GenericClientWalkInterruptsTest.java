package com.genericclient;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GenericClientWalkInterruptsTest
{
	@Test
	public void anOwnedTransportDialogueDoesNotSuppressThePoisonInterrupt()
	{
		GenericClientWalkInterrupts interrupts = GenericClientWalkInterrupts.parse(Map.of("dialogue", true, "poisoned", true));
		assertEquals("poisoned", interrupts.evaluate(snapshot(5000, 12, 4, 12, 1, true, true), true).reason);
	}

	@Test
	public void namedAreaWinsOverDialogueAndResourceConditions()
	{
		GenericClientWalkInterrupts interrupts = parse(Map.of(
			"area", Map.of("name", "prison", "bounds", List.of(bounds(100, 100, 100, 100, 0))),
			"dialogue", true, "run_energy_below", 20, "poisoned", true));
		GenericClientWalkInterrupts.Match match = interrupts.evaluate(snapshot(0, 12, 3, 12, 1, true, true), false);
		assertEquals("area", match.reason);
		assertEquals("prison", match.detail);
	}

	@Test
	public void energyThresholdUsesPercentAndIsStrictlyBelow()
	{
		GenericClientWalkInterrupts interrupts = parse(Map.of("run_energy_below", 20));
		assertNull(interrupts.evaluate(snapshot(2000, 0, 4, 12, 1, false, true), false));
		GenericClientWalkInterrupts.Match match = interrupts.evaluate(snapshot(1999, 0, 4, 12, 1, false, true), false);
		assertEquals("run_energy_below", match.reason);
		assertEquals(19.99, (Double) match.detail, 0.0001);
	}

	@Test
	public void missingItemsMatchPrefixesAcrossDoseNamesAndPreserveUnknownInventory()
	{
		GenericClientWalkInterrupts present = parse(Map.of("missing_item", List.of("prayer potion", "Lobster")));
		assertNull(present.evaluate(snapshot(5000, 0, 4, 12, 1, false, true), false));
		GenericClientWalkInterrupts absent = parse(Map.of("missing_item", List.of("Shark", "Prayer potion")));
		assertEquals("Shark", absent.evaluate(snapshot(5000, 0, 4, 12, 1, false, true), false).detail);
		GenericClientWalkInterrupts.Match unavailable = present.evaluate(snapshot(5000, 0, 4, 12, 1, false, false), false);
		assertEquals("unavailable", unavailable.status);
		assertEquals("inventory_snapshot_unavailable", unavailable.reason);
	}

	@Test
	public void reserveAndUpkeepPredicatesPreserveTheLuaThresholds()
	{
		GenericClientWalkInterrupts interrupts = parse(Map.of("inventory_below", List.of(Map.of("id", 379, "quantity", 4)),
			"skill_below", Map.of("prayer", 12), "varbit_equals", List.of(Map.of("id", 25, "value", 0))));
		assertEquals("inventory_below", interrupts.evaluate(snapshot(5000, 0, 3, 12, 1, false, true), false).reason);
		assertEquals("skill_below", interrupts.evaluate(snapshot(5000, 0, 4, 11, 1, false, true), false).reason);
		assertEquals("varbit_equals", interrupts.evaluate(snapshot(5000, 0, 4, 12, 0, false, true), false).reason);
		assertNull(interrupts.evaluate(snapshot(5000, 0, 4, 12, 1, false, true), false));
	}

	@Test
	public void activePoisonInterruptsButImmunityDoesNot()
	{
		GenericClientWalkInterrupts interrupts = parse(Map.of("poisoned", true));
		assertEquals("poisoned", interrupts.evaluate(snapshot(5000, 12, 4, 12, 1, false, true), false).reason);
		assertNull(interrupts.evaluate(snapshot(5000, -15, 4, 12, 1, false, true), false));
	}

	@Test
	public void areaListsRespectPlaneAndInclusiveBounds()
	{
		GenericClientWalkInterrupts interrupts = parse(Map.of("area", List.of(
			Map.of("name", "upstairs", "bounds", List.of(bounds(100, 100, 101, 101, 1))),
			Map.of("name", "ground", "bounds", List.of(bounds(99, 99, 100, 100, 0))))));
		assertEquals("ground", interrupts.evaluate(snapshot(5000, 0, 4, 12, 1, false, true), false).detail);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsUnknownPredicates() { parse(Map.of("poisond", true)); }

	@Test(expected = IllegalArgumentException.class)
	public void rejectsReversedBounds()
	{
		parse(Map.of("area", Map.of("name", "bad", "bounds", List.of(bounds(101, 100, 100, 101, 0)))));
	}

	private static GenericClientWalkInterrupts parse(Map<String, Object> values)
	{
		return GenericClientWalkInterrupts.parse(values);
	}
	private static Map<String, Object> bounds(int x1, int y1, int x2, int y2, int plane)
	{
		return Map.of("x1", x1, "y1", y1, "x2", x2, "y2", y2, "plane", plane);
	}
	private static GenericClientSnapshot snapshot(int energy, int poison, int food, int prayer,
		int stamina, boolean dialogue, boolean inventoryAvailable)
	{
		GenericClientAccountSnapshot account = new GenericClientAccountSnapshot(true, 100,
			List.of(new GenericClientAccountSnapshot.SkillSnapshot("prayer", 43, prayer, 50_000)),
			new GenericClientAccountSnapshot.ContainerSnapshot(inventoryAvailable, 28, List.of(
				new GenericClientAccountSnapshot.ItemSnapshot(0, null, 143, 1, "Prayer potion(1)", false, true, true, Collections.emptyList()),
				new GenericClientAccountSnapshot.ItemSnapshot(1, null, 379, food, "Lobster", false, true, true, Collections.emptyList()))),
			GenericClientAccountSnapshot.ContainerSnapshot.unavailable());
		int[] varps = new int[103];
		varps[102] = poison;
		return new GenericClientSnapshot(1, "LOGGED_IN", 240,
			new GenericClientWorldSnapshot.PlayerSnapshot("interrupt-test", 100, 100, 0, 0, -1, null, 31, 31, energy, false, null),
			Collections.emptyList(), account, new GenericClientQuestSnapshot(true, varps, Map.of(25, stamina),
				Collections.emptyList(), dialogue ? GenericClientQuestSnapshot.DialogueSnapshot.continueDialogue("Test", "Continue")
					: GenericClientQuestSnapshot.DialogueSnapshot.closed()));
	}
}
