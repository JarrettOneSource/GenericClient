package com.genericclient;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/** Source-backed connections; endpoint and requirement evidence is in navigation-transitions.md. */
final class GenericClientTransportCatalog
{
	private static final int INTERACTION_COST = 80;
	private static final List<GenericClientTransport> ENTRIES = create();

	private GenericClientTransportCatalog() { }

	static List<GenericClientTransport> available(GenericClientSnapshot snapshot, Set<WorldPoint> avoid,
		Set<WorldPoint> occupied, Set<String> blocked)
	{
		return ENTRIES.stream().filter(transport -> transport.eligible(snapshot) && !blocked.contains(transport.id) &&
			!avoid.contains(transport.origin) && !avoid.contains(transport.destination) &&
			!occupied.contains(transport.origin) && !occupied.contains(transport.destination)).collect(Collectors.toList());
	}

	private static List<GenericClientTransport> create()
	{
		List<GenericClientTransport> entries = new ArrayList<>();
		entries.add(object("witch_basement_down", point(2906, 3476, 0), point(2906, 9876, 0),
			24718, "Climb-down", point(2907, 3476, 0), List.of()));
		entries.add(object("witch_basement_up", point(2906, 9876, 0), point(2906, 3476, 0),
			24717, "Climb-up", point(2907, 9876, 0), List.of()));
		entries.add(object("tourist_stairs_up", point(2519, 3430, 0), point(2518, 3431, 1),
			16671, "Climb-up", point(2517, 3429, 0), List.of()));
		entries.add(object("tourist_stairs_down", point(2518, 3431, 1), point(2519, 3430, 0),
			16673, "Climb-down", point(2518, 3430, 1), List.of()));
		List<Predicate<GenericClientSnapshot>> waterfall = List.of(frame -> "in_progress".equals(frame.questState("waterfall_quest")));
		entries.add(object("gnome_dungeon_down", point(2533, 3156, 0), point(2533, 9556, 0),
			5250, "Climb-down", point(2533, 3155, 0), waterfall));
		entries.add(object("gnome_dungeon_up", point(2533, 9556, 0), point(2533, 3156, 0),
			17387, "Climb-up", point(2533, 9555, 0), waterfall));
		entries.add(object("waterfall_raft", point(2510, 3493, 0), point(2512, 3481, 0),
			1987, "Board", point(2509, 3493, 0), waterfall));
		entries.add(object("glarial_tomb_exit", point(2557, 9844, 0), point(2557, 3444, 0),
			17387, "Climb-up", point(2556, 9844, 0), List.of()));
		ladder(entries, "hazelmere", 2677, 3087, 0, 16683, 16679);
		ladder(entries, "glough", 2476, 3463, 0, 16683, 16679);
		ladder(entries, "grand_tree_ground", 2466, 3495, 0, 4458, 56233);
		ladder(entries, "grand_tree_first", 2466, 3495, 1, 56233, 56232);
		ladder(entries, "grand_tree_second", 2466, 3495, 2, 56232, 56229);
		entries.add(object("anita_stairs_up", point(2389, 3514, 0), point(2388, 3513, 1),
			16675, "Climb-up", point(2390, 3513, 0), List.of()));
		entries.add(object("anita_stairs_down", point(2388, 3513, 1), point(2389, 3514, 0),
			16677, "Climb-down", point(2390, 3513, 1), List.of()));
		networks(entries);
		return List.copyOf(entries);
	}

	private static void networks(List<GenericClientTransport> entries)
	{
		List<Predicate<GenericClientSnapshot>> grandTree = List.of(frame -> "finished".equals(frame.questState("the_grand_tree")));
		entries.add(new GenericClientTransport("spirit_tree_ge_stronghold", point(3184, 3508, 0), point(2461, 3444, 0),
			2 * INTERACTION_COST, area(point(2461, 3444, 0), 1), List.of(
				new GenericClientTransport.ObjectStep(1295, "Travel", area(point(3184, 3509, 0), 2)),
				new GenericClientTransport.WidgetStep(12255235, "Gnome Stronghold")), List.of(
					frame -> "finished".equals(frame.questState("the_grand_tree")),
					frame -> "finished".equals(frame.questState("tree_gnome_village")))));
		entries.add(new GenericClientTransport("glider_grand_tree_gandius", point(2465, 3501, 3), point(2971, 2968, 0),
			2 * INTERACTION_COST, area(point(2971, 2968, 0), 1), List.of(
				new GenericClientTransport.NpcStep(Set.of(10467, 6091), "Glider", area(point(2465, 3501, 3), 8)),
				new GenericClientTransport.WidgetStep(9043984)), grandTree));
		entries.add(new GenericClientTransport("glider_gandius_grand_tree", point(2970, 2972, 0), point(2465, 3501, 3),
			2 * INTERACTION_COST, area(point(2465, 3501, 3), 1), List.of(
				new GenericClientTransport.NpcStep(Set.of(10479, 10468), "Glider", area(point(2970, 2972, 0), 8)),
				new GenericClientTransport.WidgetStep(9043972)), grandTree));
		entries.add(new GenericClientTransport("daero_hangar", point(2484, 3486, 1), point(2649, 4516, 0),
			INTERACTION_COST, area(point(2649, 4516, 0), 1), List.of(
				new GenericClientTransport.NpcStep(Set.of(1444, 1445, 2020), "Travel", area(point(2484, 3486, 1), 8))),
			List.of(frame -> frame.varbit(123) != null && frame.varbit(123) >= 7)));
		entries.add(new GenericClientTransport("waydar_crash_island", point(2649, 4518, 0), point(2894, 2726, 0),
			2 * INTERACTION_COST, area(point(2894, 2726, 0), 1), List.of(
				new GenericClientTransport.NpcStep(Set.of(1446, 6675), "Talk-to", area(point(2649, 4518, 0), 8)),
				new GenericClientTransport.ConversationStep("Waydar", Set.of("Yes", "Yes."))),
			List.of(frame -> frame.varbit(123) != null && frame.varbit(123) >= 7)));
		entries.add(new GenericClientTransport("lumdo_ape_atoll", point(2892, 2724, 0), point(2803, 2706, 0),
			2 * INTERACTION_COST, area(point(2803, 2706, 0), 1), List.of(
				new GenericClientTransport.NpcStep(Set.of(1453, 1454, 1438), "Talk-to", area(point(2892, 2724, 0), 8)),
				new GenericClientTransport.ConversationStep("Lumdo", Set.of())),
			List.of(frame -> frame.varbit(125) != null && frame.varbit(125) >= 3)));
	}

	private static void ladder(List<GenericClientTransport> entries, String id, int x, int y, int plane, int up, int down)
	{
		for (int[] offset : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}})
		{
			WorldPoint bottom = point(x + offset[0], y + offset[1], plane);
			WorldPoint top = point(bottom.getX(), bottom.getY(), plane + 1);
			entries.add(object(id + "_up", bottom, top, up, "Climb-up", point(x, y, plane), List.of()));
			entries.add(object(id + "_down", top, bottom, down, "Climb-down", point(x, y, plane + 1), List.of()));
		}
	}

	private static GenericClientTransport object(String id, WorldPoint origin, WorldPoint destination,
		int objectId, String action, WorldPoint target, List<Predicate<GenericClientSnapshot>> requirements)
	{
		return new GenericClientTransport(id, origin, destination, INTERACTION_COST,
			area(destination, 1),
			List.of(new GenericClientTransport.ObjectStep(objectId, action,
				area(target, 2))), requirements);
	}

	private static WorldArea area(WorldPoint point, int radius)
	{
		return new WorldArea(point.getX() - radius, point.getY() - radius, 2 * radius + 1, 2 * radius + 1, point.getPlane());
	}

	private static WorldPoint point(int x, int y, int plane) { return new WorldPoint(x, y, plane); }
}
