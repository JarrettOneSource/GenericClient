package com.genericclient;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.PreMapLoad;
import net.runelite.api.events.WorldViewUnloaded;
import net.runelite.client.eventbus.EventBus;
import org.junit.Test;

public class GenericClientEntityIdsTest
{
    @Test public void nativeDespawnAndWorldEventsInvalidateOnlyTheirOwnReferences()
    {
        GenericClientEntityIds identities = new GenericClientEntityIds();
        EventBus events = new EventBus();
        events.register(identities);
        WorldView first = reference(WorldView.class,null);
        WorldView second = reference(WorldView.class,null);
        NPC npc = reference(NPC.class,first);
        GameObject object = reference(GameObject.class,first);
        NPC other = reference(NPC.class,second);
        long actor = identities.identify(npc);
        long sceneObject = identities.identify(object);
        long otherActor = identities.identify(other);
        assertEquals(actor,identities.identify(npc));
        events.post(new PreMapLoad(first,reference(Scene.class,null)));
        assertFalse(identities.matches(object,sceneObject));
        assertTrue(identities.matches(npc,actor));
        NpcDespawned despawned = new NpcDespawned(npc);
        events.post(despawned);
        assertFalse(identities.matches(npc,actor));
        assertTrue(identities.identify(npc) > actor);
        events.post(new WorldViewUnloaded(first));
        assertFalse(identities.matches(npc,actor));
        assertTrue(identities.matches(other,otherActor));
        identities.clear();
        assertFalse(identities.matches(other,otherActor));
        assertTrue(identities.identify(other) > otherActor);
        events.unregister(identities);
    }

    private static <T> T reference(Class<T> type, WorldView world)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},
            (proxy,method,args) -> method.getName().equals("getWorldView") ? world : null));
    }
}
