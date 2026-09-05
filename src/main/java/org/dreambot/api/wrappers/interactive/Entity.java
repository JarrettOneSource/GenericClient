package org.dreambot.api.wrappers.interactive;

import com.genericclient.script.EntityReference;
import com.genericclient.script.SnapshotData;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dreambot.api.methods.map.Tile;

public abstract class Entity implements Locatable, Identifiable
{
    private final EntityReference reference;

    protected Entity(Map<?, ?> snapshot, String kind)
    {
        reference = new EntityReference(kind,((Number)snapshot.get("identity")).longValue());
    }

    public final Object getReference() { return reference; }
    protected final Map<?, ?> data() { return reference.requirePresent(); }
    protected final Map<?, ?> current() { return reference.read(); }
    protected final long identity() { return reference.identity; }
    public int getId() { return SnapshotData.integer(data(),"id"); }
    public String getName() { return (String)data().get("name"); }
    public String[] getActions() { return SnapshotData.strings(data().get("actions")); }
    public boolean hasAction(String... actions)
    {
        return Arrays.asList(getActions()).containsAll(Arrays.asList(actions));
    }

    @Override public Tile getTile()
    {
        Map<?, ?> world = SnapshotData.map(data().get("world"));
        return new Tile(SnapshotData.integer(world,"x"),SnapshotData.integer(world,"y"),SnapshotData.integer(world,"plane"));
    }

    public boolean exists() { return !current().isEmpty(); }
    @Override public boolean equals(Object other)
    {
        return other instanceof Entity && reference.equals(((Entity) other).reference);
    }
    @Override public int hashCode() { return reference.hashCode(); }
    public boolean interact(String action)
    {
        Map<?, ?> target = current();
        if (target.isEmpty()) return false;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id",SnapshotData.integer(target,"id"));
        request.put("identity",identity());
        request.put("action",action);
        request.put("within",32);
        if (reference.kind.equals("object")) request.put("world",target.get("world"));
        else request.put("index",SnapshotData.integer(target,"index"));
        if (reference.kind.equals("player")) request.put("world_view",SnapshotData.integer(target,"world_view"));
        return SnapshotData.action(reference.kind + ".interact",request);
    }
}
