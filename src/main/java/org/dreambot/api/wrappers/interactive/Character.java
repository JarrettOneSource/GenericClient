package org.dreambot.api.wrappers.interactive;

import com.genericclient.script.SnapshotData;
import java.util.Map;

public abstract class Character extends Entity
{
    protected Character(Map<?, ?> snapshot, String kind) { super(snapshot,kind); }
    public int getIndex() { return SnapshotData.integer(data(),"index"); }
    public int getLevel() { return SnapshotData.integer(data(),"combat_level"); }
    public int getAnimation() { return SnapshotData.integer(data(),"animation"); }
    public boolean isAnimating() { return getAnimation() != -1; }
    public boolean isInCombat() { return data().get("interacting") != null; }
    public boolean isMoving() { return Boolean.TRUE.equals(data().get("moving")); }
}
