package com.genericclient.script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.dreambot.api.methods.map.Tile;

/** Complete journeys for catalog workflows; DreamBot Walking retains step semantics. */
public final class Navigation
{
    private Navigation() {}

    public static boolean walkTo(Tile destination, int within)
    {
        return walkTo(destination, within, 1200, List.of());
    }

    public static boolean walkTo(Tile destination, int within, int timeoutTicks, List<Tile> avoid)
    {
        return "arrived".equals(walk(new Journey(destination, within).timeout(timeoutTicks).avoiding(avoid), Map.of(), null).get("status"));
    }

    public static Map<String, Object> walk(Journey journey, Map<String, Object> interrupts, String continuation)
    {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("destination", point(journey.destination));
        request.put("within", journey.within);
        request.put("timeout_ticks", journey.timeoutTicks);
        request.put("via", points(journey.via));
        request.put("avoid_tiles", points(journey.avoid));
        if (!journey.arrival.isEmpty()) request.put("arrival_tiles", points(journey.arrival));
        request.put("interrupt_on", interrupts);
        if (continuation != null) request.put("resume", continuation);
        return ScriptScope.current().execute("walk.to", request, journey.timeoutTicks * 600L);
    }

    private static Map<String, Integer> point(Tile tile)
    {
        return Map.of("x", tile.getX(), "y", tile.getY(), "plane", tile.getZ());
    }

    private static List<Map<String, Integer>> points(List<Tile> tiles)
    {
        return tiles.stream().map(Navigation::point).collect(Collectors.toList());
    }

    /** Immutable route geometry; upkeep and interruption decisions belong to the script. */
    public static final class Journey
    {
        public final Tile destination;
        public final int within;
        public final int timeoutTicks;
        public final List<Tile> via;
        public final List<Tile> arrival;
        public final List<Tile> avoid;

        public Journey(Tile destination, int within)
        {
            this(destination, within, 1200, List.of(), List.of(), List.of());
        }

        private Journey(Tile destination, int within, int timeoutTicks, List<Tile> via, List<Tile> arrival, List<Tile> avoid)
        {
            this.destination = destination;
            this.within = within;
            this.timeoutTicks = timeoutTicks;
            this.via = List.copyOf(via);
            this.arrival = List.copyOf(arrival);
            this.avoid = List.copyOf(avoid);
        }

        public Journey via(Tile... tiles)
        {
            return new Journey(destination, within, timeoutTicks, List.of(tiles), arrival, avoid);
        }

        public Journey arrivingAt(Tile... tiles)
        {
            return new Journey(destination, within, timeoutTicks, via, List.of(tiles), avoid);
        }

        public Journey avoiding(List<Tile> tiles)
        {
            return new Journey(destination, within, timeoutTicks, via, arrival, tiles);
        }

        public Journey timeout(int ticks)
        {
            return new Journey(destination, within, ticks, via, arrival, avoid);
        }
    }
}
