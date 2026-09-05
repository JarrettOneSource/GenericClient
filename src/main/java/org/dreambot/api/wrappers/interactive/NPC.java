package org.dreambot.api.wrappers.interactive;

import java.util.Map;

public class NPC extends Character
{
    public NPC(Map<?, ?> snapshot) { super(snapshot,"npc"); }
    public boolean isOnScreen() { return Boolean.TRUE.equals(data().get("clickable")); }
    public boolean isDead() { return Boolean.TRUE.equals(data().get("dead")); }
}
