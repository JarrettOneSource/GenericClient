package com.genericclient;

import java.util.IdentityHashMap;
import java.util.Map;
import net.runelite.api.Actor;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.events.AccountHashChanged;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PreMapLoad;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WorldViewUnloaded;
import net.runelite.client.eventbus.Subscribe;

/** Native lifetime identity only; mutable entity state remains in game snapshots. */
final class GenericClientEntityIds
{
    private final Map<Object, Long> identities = new IdentityHashMap<>();
    private long sequence;

    long identify(Object reference)
    {
        return identities.computeIfAbsent(reference, ignored -> ++sequence);
    }

    boolean matches(Object reference, long identity)
    {
        return Long.valueOf(identity).equals(identities.get(reference));
    }

    void clear() { identities.clear(); }

    @Subscribe public void onNpcDespawned(NpcDespawned event) { identities.remove(event.getNpc()); }
    @Subscribe public void onPlayerDespawned(PlayerDespawned event) { identities.remove(event.getPlayer()); }
    @Subscribe public void onGameObjectDespawned(GameObjectDespawned event) { identities.remove(event.getGameObject()); }
    @Subscribe public void onWallObjectDespawned(WallObjectDespawned event) { identities.remove(event.getWallObject()); }
    @Subscribe public void onGroundObjectDespawned(GroundObjectDespawned event) { identities.remove(event.getGroundObject()); }
    @Subscribe public void onDecorativeObjectDespawned(DecorativeObjectDespawned event) { identities.remove(event.getDecorativeObject()); }
    @Subscribe public void onAccountHashChanged(AccountHashChanged event) { clear(); }

    @Subscribe public void onGameStateChanged(GameStateChanged event)
    {
        switch (event.getGameState())
        {
            case LOGIN_SCREEN:
            case LOGIN_SCREEN_AUTHENTICATOR:
            case LOGGING_IN:
            case HOPPING:
                clear();
                break;
            default:
                break;
        }
    }

    @Subscribe public void onPreMapLoad(PreMapLoad event)
    {
        identities.keySet().removeIf(reference -> reference instanceof TileObject &&
            ((TileObject) reference).getWorldView() == event.getWorldView());
    }

    @Subscribe public void onWorldViewUnloaded(WorldViewUnloaded event)
    {
        WorldView world = event.getWorldView();
        identities.keySet().removeIf(reference ->
            reference instanceof Actor && ((Actor) reference).getWorldView() == world ||
            reference instanceof TileObject && ((TileObject) reference).getWorldView() == world);
    }
}
