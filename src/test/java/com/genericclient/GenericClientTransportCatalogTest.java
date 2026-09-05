package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientTransportCatalogTest
{
	@Test
	public void networkAccessRequiresTheCapturedQuestStates()
	{
		assertFalse(ids(snapshot(Map.of())).contains("spirit_tree_ge_stronghold"));
		assertFalse(ids(snapshot(Map.of("tree_gnome_village", "finished"))).contains("spirit_tree_ge_stronghold"));
		Set<String> grandTree = ids(snapshot(Map.of("the_grand_tree", "finished")));
		assertFalse(grandTree.contains("spirit_tree_ge_stronghold"));
		assertTrue(grandTree.contains("glider_grand_tree_gandius"));
		assertTrue(grandTree.contains("glider_gandius_grand_tree"));
		Set<String> both = ids(snapshot(Map.of("tree_gnome_village", "finished", "the_grand_tree", "finished")));
		assertTrue(both.contains("spirit_tree_ge_stronghold"));
	}

	@Test
	public void theQuestDependentGnomeLadderCannotChooseItsOtherDungeon()
	{
		assertFalse(ids(snapshot(Map.of())).contains("gnome_dungeon_down"));
		assertTrue(ids(snapshot(Map.of("waterfall_quest", "in_progress"))).contains("gnome_dungeon_down"));
		assertFalse(ids(snapshot(Map.of("waterfall_quest", "finished"))).contains("gnome_dungeon_down"));
	}

	@Test
	public void forbiddenOriginsDestinationsAndFailedGroupsAreRemovedTogether()
	{
		GenericClientSnapshot frame = snapshot(Map.of());
		List<GenericClientTransport> all = GenericClientTransportCatalog.available(frame, Set.of(), Set.of(), Set.of());
		GenericClientTransport basement = all.stream().filter(edge -> edge.id.equals("witch_basement_down")).findFirst().orElseThrow();
		for (WorldPoint point : List.of(basement.origin, basement.destination))
		{
			assertFalse(GenericClientTransportCatalog.available(frame, Set.of(point), Set.of(), Set.of()).contains(basement));
			assertFalse(GenericClientTransportCatalog.available(frame, Set.of(), Set.of(point), Set.of()).contains(basement));
		}
		List<GenericClientTransport> blocked = GenericClientTransportCatalog.available(frame, Set.of(), Set.of(), Set.of("grand_tree_first_up"));
		assertTrue(all.stream().anyMatch(edge -> edge.id.equals("grand_tree_first_up")));
		assertFalse(blocked.stream().anyMatch(edge -> edge.id.equals("grand_tree_first_up")));
		assertTrue(blocked.stream().anyMatch(edge -> edge.id.equals("grand_tree_first_down")));
	}

	@Test
	public void repeatServicesRequireTheObservedPuzzleAndInterventionStages()
	{
		Set<String> hangar = Set.of("daero_hangar", "waydar_crash_island");
		assertFalse(ids(snapshot(Map.of())).stream().anyMatch(hangar::contains));
		assertFalse(stageIds(6, 2).stream().anyMatch(hangar::contains));
		assertTrue(stageIds(7, 2).containsAll(hangar));
		assertFalse(stageIds(7, 2).contains("lumdo_ape_atoll"));
		assertTrue(stageIds(7, 3).contains("lumdo_ape_atoll"));
	}

	private static Set<String> stageIds(int puzzle, int intervention)
	{
		return ids(new GenericClientSnapshot(1, "LOGGED_IN", 240,
			new GenericClientWorldSnapshot.PlayerSnapshot("transport-test", 2649, 4518, 0, 0), List.of(),
			GenericClientAccountSnapshot.empty(), new GenericClientQuestSnapshot(true, new int[0], Map.of(123, puzzle, 125, intervention),
				List.of(), GenericClientQuestSnapshot.DialogueSnapshot.closed())));
	}

	@Test
	public void aJourneyCanClimbThreeFloorsAndTakeTheGlider() throws Exception
	{
		GenericClientPathfinder planner = new GenericClientPathfinder(GenericClientCollisionMap.loadBundled());
		GenericClientSnapshot frame = snapshot(Map.of("the_grand_tree", "finished"));
		GenericClientPathfinder.Result route = planner.find(new WorldPoint(2465, 3495, 0), new WorldPoint(2971, 2968, 0), 0,
			(x, y, plane, dx, dy, allowed) -> allowed,
			GenericClientTransportCatalog.available(frame, Set.of(), Set.of(), Set.of()));
		assertEquals(GenericClientPathfinder.Status.FOUND, route.getStatus());
		assertEquals(List.of("grand_tree_ground_up", "grand_tree_first_up", "grand_tree_second_up", "glider_grand_tree_gandius"),
			route.getTransports().entrySet().stream().sorted(Map.Entry.comparingByKey())
				.map(entry -> entry.getValue().id).collect(Collectors.toList()));
	}

	private static Set<String> ids(GenericClientSnapshot frame)
	{
		return GenericClientTransportCatalog.available(frame, Set.of(), Set.of(), Set.of()).stream()
			.map(transport -> transport.id).collect(Collectors.toSet());
	}

	static GenericClientSnapshot snapshot(Map<String, String> questStates)
	{
		GenericClientAccountSnapshot account = new GenericClientAccountSnapshot(true, 1, List.of(),
			GenericClientAccountSnapshot.ContainerSnapshot.unavailable(), GenericClientAccountSnapshot.ContainerSnapshot.unavailable(),
			GenericClientAccountSnapshot.BankSnapshot.unknown(), new GenericClientAccountSnapshot.QuestListSnapshot(true, 0,
				questStates.entrySet().stream().map(entry -> new GenericClientAccountSnapshot.QuestSnapshot(
					entry.getKey(), entry.getKey().hashCode(), entry.getKey(), entry.getValue(), 0)).collect(Collectors.toList())));
		return new GenericClientSnapshot(1, "LOGGED_IN", 240,
			new GenericClientWorldSnapshot.PlayerSnapshot("transport-test", 3184, 3508, 0, 0), List.of(), account);
	}
}
