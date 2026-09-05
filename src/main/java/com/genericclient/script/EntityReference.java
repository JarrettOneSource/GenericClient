package com.genericclient.script;

import java.util.Map;
import java.util.Objects;

/** Opaque lifetime handle used by GenericClient's DreamBot wrappers. */
public final class EntityReference
{
    public final String kind;
    public final long identity;

    public EntityReference(String kind, long identity)
    {
        this.kind = kind;
        this.identity = identity;
    }

    public Map<?, ?> read()
    {
        return SnapshotData.map(ScriptScope.current().read("entity", Map.of("kind",kind,"identity",identity)));
    }

    public Map<?, ?> requirePresent()
    {
        Map<?, ?> current = read();
        if (current.isEmpty()) throw new Unavailable(kind);
        return current;
    }

    @Override public boolean equals(Object other)
    {
        if (!(other instanceof EntityReference)) return false;
        EntityReference reference = (EntityReference) other;
        return identity == reference.identity && kind.equals(reference.kind);
    }

    @Override public int hashCode() { return Objects.hash(kind,identity); }

    /** A live handle lost its native entity while a script was reading it. */
    public static final class Unavailable extends IllegalStateException
    {
        private Unavailable(String kind) { super("Entity no longer exists: " + kind); }
    }
}
