package com.genericclient;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/** Offline route acceptance against the same collision data used by the client. */
public final class GenericClientRouteAudit
{
	public static void main(String[] args) throws Exception
	{
		GenericClientCollisionMap collisionMap = GenericClientCollisionMap.loadBundled();
		GenericClientPathfinder pathfinder = new GenericClientPathfinder(collisionMap);
		Map<String, Route> routes = new LinkedHashMap<>();
		int declaredPoints = readRoutes(args[0], routes);
		if (routes.isEmpty()) throw new IllegalArgumentException("No routes exported");
		int failures = 0;
		int checks = 0;
		System.out.println("route\taccount\tstatus\ttiles\tnodes\ttransports\tmilliseconds");
		for (Map.Entry<String, Route> entry : routes.entrySet())
		{
			Route route = entry.getValue();
			if (route.points.size() < 2) throw new IllegalArgumentException("Route requires start and destination");
			GenericClientWalkRequest request = new GenericClientWalkRequest(
				route.points.get(route.points.size() - 1), route.within, 900,
				GenericClientActivityContext.none(), true, route.avoidTiles, GenericClientWalkInterrupts.NONE,
				route.points.subList(1, route.points.size() - 1), null);
			if (!route.arrivalTiles.isEmpty()) request = request.withArrivalTiles(route.arrivalTiles);
			Set<WorldPoint> avoided = new HashSet<>(route.avoidTiles);
			for (GenericClientNavigationAccount account : route.accounts)
			{
				checks++;
				long start = System.nanoTime();
				GenericClientPathfinder.Result result = pathfinder.findThrough(route.points.get(0), request, 0,
					(x, y, plane, dx, dy, allowed) -> allowed &&
						(avoided.isEmpty() || !avoided.contains(new WorldPoint(x + dx, y + dy, plane))),
					GenericClientTransportCatalog.available(account.snapshot(route.points.get(0)), avoided, Set.of(), Set.of()));
				double elapsedMillis = (System.nanoTime() - start) / 1_000_000.0;
				boolean forbidden = result.getPath().stream().anyMatch(route.forbiddenTiles::contains);
				System.out.printf(java.util.Locale.ROOT, "%s\t%s\t%s\t%d\t%d\t%d\t%.3f%n", entry.getKey(), account,
					forbidden ? "FORBIDDEN_AREA" : result.getStatus(), result.getPath().size(), result.getExpandedNodes(), result.getTransports().size(), elapsedMillis);
				if (result.getStatus() != GenericClientPathfinder.Status.FOUND || forbidden) failures++;
			}
		}
		failures += auditTransports(collisionMap, pathfinder);
		System.out.println("Audited routes=" + routes.size() + " account_checks=" + checks + " failures=" + failures +
			" declared_points=" + declaredPoints + " (point inventory is not standalone reachability proof)");
		if (failures > 0) throw new IllegalStateException(failures + " routes failed navigation audit");
	}

	private static int auditTransports(GenericClientCollisionMap collisionMap, GenericClientPathfinder pathfinder)
	{
		List<GenericClientTransport> transports = GenericClientTransportCatalog.available(
			GenericClientNavigationAccount.QUEST_ROUTES.snapshot(new WorldPoint(3184, 3508, 0)), Set.of(), Set.of(), Set.of());
		int failures = 0;
		for (GenericClientTransport transport : transports)
		{
			GenericClientPathfinder.Result route = pathfinder.find(transport.origin, transport.destination, 0,
				(x, y, plane, dx, dy, allowed) -> allowed, transports);
			if (route.getStatus() != GenericClientPathfinder.Status.FOUND || !route.getTransports().containsValue(transport) ||
				!hasOpenCardinalEdge(collisionMap, transport.origin) || !hasOpenCardinalEdge(collisionMap, transport.destination))
			{
				failures++;
				System.err.println("Transport audit failed: " + transport.id + " from=" + transport.origin + " to=" + transport.destination);
			}
		}
		System.out.println("Catalog edges=" + transports.size() + " failures=" + failures + " (static endpoints and selected connections)");
		return failures;
	}

	private static boolean hasOpenCardinalEdge(GenericClientCollisionMap collisionMap, WorldPoint point)
	{
		for (int[] direction : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
			if (collisionMap.canMove(point.getX(), point.getY(), point.getPlane(), direction[0], direction[1])) return true;
		return false;
	}

	private static int readRoutes(String path, Map<String, Route> routes) throws java.io.IOException
	{
		int declaredPoints = 0;
		for (String line : Files.readAllLines(Paths.get(path)))
		{
			if (line.isBlank() || line.startsWith("#")) continue;
			String[] fields = line.split("\t");
			if (fields.length != 8) throw new IllegalArgumentException("Expected eight audit columns");
			WorldPoint point = new WorldPoint(Integer.parseInt(fields[2]),
				Integer.parseInt(fields[3]), Integer.parseInt(fields[4]));
			if ("point".equals(fields[6]))
			{
				declaredPoints++;
				continue;
			}
			Route route = routes.computeIfAbsent(fields[0], key -> new Route());
			List<WorldPoint> target;
			switch (fields[6])
			{
				case "route":
					target = route.points;
					route.within = Integer.parseInt(fields[5]);
					route.accounts = "ALL".equals(fields[7]) ? List.of(GenericClientNavigationAccount.values()) :
						List.of(GenericClientNavigationAccount.valueOf(fields[7]));
					break;
				case "arrival": target = route.arrivalTiles; break;
				case "avoid": target = route.avoidTiles; break;
				case "forbidden": target = route.forbiddenTiles; break;
				default: throw new IllegalArgumentException("Unknown audit row kind: " + fields[6]);
			}
			if (Integer.parseInt(fields[1]) != target.size() + 1)
				throw new IllegalArgumentException("Nonconsecutive " + fields[6] + " indices: " + fields[0]);
			target.add(point);
		}
		return declaredPoints;
	}

	private static final class Route
	{
		private final List<WorldPoint> points = new ArrayList<>();
		private final List<WorldPoint> arrivalTiles = new ArrayList<>();
		private final List<WorldPoint> avoidTiles = new ArrayList<>();
		private final List<WorldPoint> forbiddenTiles = new ArrayList<>();
		private int within;
		private List<GenericClientNavigationAccount> accounts;
	}
}
