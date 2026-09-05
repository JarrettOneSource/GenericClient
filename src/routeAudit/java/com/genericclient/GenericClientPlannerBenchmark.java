package com.genericclient;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import net.runelite.api.coords.WorldPoint;

/** Offline, deterministic planner workloads; never attaches to a live client. */
public final class GenericClientPlannerBenchmark
{
	public static void main(String[] args) throws Exception
	{
		GenericClientPathfinder planner = new GenericClientPathfinder(GenericClientCollisionMap.loadBundled());
		Supplier<GenericClientPathfinder.Result> road = () -> planner.find(
			new WorldPoint(3202, 3428, 0), new WorldPoint(3230, 3428, 0), 0);
		Supplier<GenericClientPathfinder.Result> detour = () -> planner.find(
			new WorldPoint(2580, 3315, 0), new WorldPoint(2466, 3482, 0), 2);
		List<WorldPoint> route = detour.get().getPath();
		WorldPoint displaced = new WorldPoint(2583, 3318, 0);
		Supplier<GenericClientPathfinder.Result> rejoin = () -> planner.rejoin(displaced, route, 1, List.of(), java.util.Map.of(),
			(x, y, plane, dx, dy, allowed) -> allowed);
		Supplier<GenericClientPathfinder.Result> exhausted = () -> planner.find(
			new WorldPoint(16000, 16000, 0), new WorldPoint(16005, 16000, 0), 0,
			(x, y, plane, dx, dy, allowed) -> x + dx != 16005 || y + dy != 16000);
		WorldPoint start = new WorldPoint(2580, 3315, 0);
		List<GenericClientTransport> transports = GenericClientTransportCatalog.available(
			GenericClientNavigationAccount.QUEST_ROUTES.snapshot(start), java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
		Supplier<GenericClientPathfinder.Result> catalogDetour = () -> planner.find(start, new WorldPoint(2466, 3482, 0), 2,
			(x, y, plane, dx, dy, allowed) -> allowed, transports);
		Supplier<GenericClientPathfinder.Result> catalogExhausted = () -> planner.find(
			new WorldPoint(16000, 16000, 0), new WorldPoint(16005, 16000, 0), 0,
			(x, y, plane, dx, dy, allowed) -> x + dx != 16005 || y + dy != 16000, transports);
		Supplier<GenericClientPathfinder.Result> glider = () -> planner.find(new WorldPoint(2465, 3495, 0), new WorldPoint(2971, 2968, 0), 0,
			(x, y, plane, dx, dy, allowed) -> allowed, transports);
		for (int round = 0; round < 12; round++) { road.get(); detour.get(); rejoin.get(); }
		for (int round = 0; round < 12; round++) { catalogDetour.get(); glider.get(); }
		exhausted.get();
		catalogExhausted.get();
		try (Recording recording = new Recording(Configuration.getConfiguration("profile")))
		{
			if (args.length > 0) recording.start();
			System.out.println("workload\tstatus\ttiles\tnodes\tpath_hash\tmedian_ms\tmedian_allocated_bytes");
			measure("road_baseline", road, 11);
			measure("zoo_detour", detour, 11);
			measure("local_rejoin", rejoin, 11);
			measure("exhausted_search", exhausted, 5);
			measure("road_restored", road, 11);
			measure("catalog_zoo_detour", catalogDetour, 11);
			measure("catalog_exhausted_search", catalogExhausted, 5);
			measure("catalog_three_climbs_and_glider", glider, 11);
			if (args.length > 0) recording.dump(Paths.get(args[0]));
		}
	}

	private static void measure(String name, Supplier<GenericClientPathfinder.Result> search, int repetitions)
	{
		ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Thread allocation counters required");
		bean.setThreadAllocatedMemoryEnabled(true);
		long thread = Thread.currentThread().getId();
		long[] nanos = new long[repetitions];
		long[] allocated = new long[repetitions];
		GenericClientPathfinder.Result expected = search.get();
		for (int trial = 0; trial < repetitions; trial++)
		{
			long beforeBytes = bean.getThreadAllocatedBytes(thread);
			long before = System.nanoTime();
			GenericClientPathfinder.Result result = search.get();
			nanos[trial] = System.nanoTime() - before;
			allocated[trial] = bean.getThreadAllocatedBytes(thread) - beforeBytes;
			if (result.getStatus() != expected.getStatus() || !result.getPath().equals(expected.getPath()) ||
				result.getExpandedNodes() != expected.getExpandedNodes()) throw new IllegalStateException("Nondeterministic " + name);
		}
		Arrays.sort(nanos);
		Arrays.sort(allocated);
		System.out.printf(Locale.ROOT, "%s\t%s\t%d\t%d\t%d\t%.3f\t%d%n", name, expected.getStatus(),
			expected.getPath().size(), expected.getExpandedNodes(), expected.getPath().hashCode(),
			nanos[repetitions / 2] / 1_000_000.0, allocated[repetitions / 2]);
	}
}
