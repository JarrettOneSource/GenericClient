package com.genericclient.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.dreambot.api.methods.filter.Filter;
import org.dreambot.api.methods.interactive.Players;
import org.dreambot.api.methods.map.Tile;
import org.dreambot.api.wrappers.interactive.Entity;
import org.dreambot.api.wrappers.interactive.Player;

/** Query callbacks read live handles; despawning is distinct from a callback error. */
public final class EntityQueries
{
    private EntityQueries() {}

    public static <T extends Entity> List<T> matching(List<T> entities, Filter<T> filter)
    {
        List<T> matches = new ArrayList<>();
        for (T entity : entities)
        {
            try
            {
                if (entity.exists() && filter.match(entity)) matches.add(entity);
            }
            catch (EntityReference.Unavailable despawned)
            {
                // A game tick can retire a handle between the filter's reads.
            }
        }
        matches.removeIf(entity -> !entity.exists());
        return matches;
    }

    public static <T extends Entity> T closest(List<T> entities, Filter<T> filter, Tile origin)
    {
        if (origin == null) origin = localTile();
        if (origin == null) return null;
        List<Map.Entry<T, Double>> distances = new ArrayList<>();
        for (T entity : matching(entities,filter))
        {
            try
            {
                double distance = entity.distance(origin);
                if (Double.isFinite(distance)) distances.add(Map.entry(entity,distance));
            }
            catch (EntityReference.Unavailable despawned)
            {
                // Do not rank a target that disappeared after filtering.
            }
        }
        distances.sort(Map.Entry.comparingByValue());
        return distances.stream().map(Map.Entry::getKey).filter(Entity::exists).findFirst().orElse(null);
    }

    private static Tile localTile()
    {
        Player player = Players.getLocal();
        if (player == null || !player.exists()) return null;
        try { return player.getTile(); }
        catch (EntityReference.Unavailable loggedOut) { return null; }
    }
}
