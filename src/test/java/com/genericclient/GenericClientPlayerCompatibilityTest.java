package com.genericclient;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientPlayerCompatibilityTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void loadedPlayersExposeNativeMetadataThroughTheCharacterHierarchy() throws Exception
    {
        Scene scene = new Scene();
        scene.add(17,"Local",3200,3200,0,42,-1,false);
        scene.add(28,"Friend",3201,3200,0,88,3,true);
        scene.add(29,"Other plane",3201,3200,1,90,-1,false);
        scene.addNpc();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            host.publishGameTick(scene.snapshot(1));
            Map<String,Object> result = host.evaluate(
                "org.dreambot.api.wrappers.interactive.Character target=org.dreambot.api.methods.interactive.Players.closest(\"Friend\");" +
                "org.dreambot.api.wrappers.interactive.Entity local=org.dreambot.api.methods.interactive.Players.getLocal();" +
                "org.dreambot.api.wrappers.interactive.Character npc=org.dreambot.api.methods.interactive.NPCs.closest(123);" +
                "return Map.of(\"count\",org.dreambot.api.methods.interactive.Players.all().size()," +
                "\"id\",target.getId(),\"index\",target.getIndex(),\"level\",target.getLevel(),\"animation\",target.getAnimation()," +
                "\"moving\",target.isMoving(),\"animating\",target.isAnimating(),\"distance\",target.distance(local)," +
                "\"localId\",local.getId(),\"npcMoving\",npc.isMoving());").get(5,TimeUnit.SECONDS);
            assertEquals(Map.of("count",2,"id",28,"index",28,"level",88,"animation",3,"moving",true,"animating",true,
                "distance",1.0,"localId",17,"npcMoving",true),result.get("value"));
        }
    }

    @Test public void playerQueriesSupportIdsNamesIndexesAndArrayResults() throws Exception
    {
        Scene scene = new Scene();
        scene.add(17,"Local",3200,3200,0,42,-1,false);
        scene.add(28,"Friend",3201,3200,0,88,3,true);
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            host.publishGameTick(scene.snapshot(1));
            String api = "org.dreambot.api.methods.interactive.Players";
            Map<String,Object> result = host.evaluate("Integer[] ids={28}; String[] names={\"Friend\"}; return Map.of(" +
                "\"ids\"," + api + ".all(ids).size(),\"names\"," + api + ".all(names).size()," +
                "\"filter\"," + api + ".all(player->player.getLevel()>50).size(),\"array\"," + api + ".getArray().length," +
                "\"index\"," + api + ".getAtIndex(28).equals(" + api + ".closest(28)),\"missing\"," + api + ".getAtIndex(99)==null," +
                "\"present\"," + api + ".referenceExists(28),\"absent\",!" + api + ".referenceExists(99)," +
                "\"origin\"," + api + ".closest(player->true,new org.dreambot.api.methods.map.Tile(3203,3200)).getId());")
                .get(5,TimeUnit.SECONDS);
            assertEquals(Map.of("ids",1,"names",1,"filter",1,"array",2,"index",true,"missing",true,"present",true,"absent",true,
                "origin",28),result.get("value"));
        }
    }

    @Test public void playerActionsRetainNullSlotsAndUpdateOnlyWithTheNextSnapshot() throws Exception
    {
        Scene scene = new Scene();
        scene.add(17,"Local",3200,3200,0,42,-1,false);
        scene.add(28,"Friend",3201,3200,0,88,-1,false);
        java.util.concurrent.atomic.AtomicReference<GenericClientScriptHost> running = new java.util.concurrent.atomic.AtomicReference<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"player-actions")
            .questAction((type,arguments,context) -> {
                assertEquals("test.advance",type);
                running.get().publishGameTick(scene.snapshot(2));
                return java.util.concurrent.CompletableFuture.completedFuture(Map.of("status","complete"));
            }).build())
        {
            running.set(host);
            host.publishGameTick(scene.snapshot(1));
            scene.actions[0] = "Attack";
            Map<String,Object> result = host.evaluate("var player=org.dreambot.api.methods.interactive.Players.closest(\"Friend\");" +
                "String[] original=player.getActions(); String[] changed=player.getActions(); changed[0]=\"Corrupted\";" +
                "boolean isolated=player.getActions()[0].equals(\"Follow\");" +
                "boolean any=player.hasAction(\"Unavailable\",\"Trade with\"); boolean none=player.hasAction();" +
                "ScriptScope.current().execute(\"test.advance\",Map.of(),5000);" +
                "return Map.of(\"original\",Arrays.asList(original),\"isolated\",isolated,\"any\",any,\"none\",none," +
                "\"fresh\",player.getActions()[0],\"localHealth\",org.dreambot.api.methods.interactive.Players.getLocal().getHealthPercent()," +
                "\"observedHealth\",player.getHealthPercent());").get(5,TimeUnit.SECONDS);
            assertEquals(Map.of("original",java.util.Arrays.asList("Follow",null,"Trade with",null),"isolated",true,"any",true,"none",false,
                "fresh","Attack","localHealth",80,"observedHealth",50),result.get("value"));
        }
    }

    @Test public void retainedActorFlagsRefreshWhenANativeTargetDiesOrLeavesTheScreen() throws Exception
    {
        Scene scene = new Scene();
        scene.add(17,"Local",3200,3200,0,42,-1,false);
        scene.add(28,"Friend",3201,3200,0,88,-1,false);
        scene.addNpc();
        java.util.concurrent.atomic.AtomicReference<GenericClientScriptHost> current = new java.util.concurrent.atomic.AtomicReference<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"actor-flags")
            .questAction((type,arguments,context) -> {
                assertEquals("test.advance",type);
                scene.npcDead=true;
                scene.npcMoving=false;
                scene.playerHealthScale=0;
                scene.shapes.remove(7);
                current.get().publishGameTick(scene.snapshot(2));
                return java.util.concurrent.CompletableFuture.completedFuture(Map.of("status","complete"));
            }).build())
        {
            current.set(host);
            host.publishGameTick(scene.snapshot(1));
            assertEquals(List.of(List.of(false,true,true,false),List.of(true,false,false,false),0,100),host.evaluate(
                "var npc=org.dreambot.api.methods.interactive.NPCs.closest(123);"+
                "List<Boolean> before=List.of(npc.isDead(),npc.isOnScreen(),npc.isMoving(),npc.isAnimating());"+
                "ScriptScope.current().execute(\"test.advance\",Map.of(),5000);"+
                "return List.of(before,List.of(npc.isDead(),npc.isOnScreen(),npc.isMoving(),npc.isAnimating()),"+
                "org.dreambot.api.methods.interactive.NPCs.all().size(),org.dreambot.api.methods.interactive.Players.closest(28).getHealthPercent());")
                .get(5,TimeUnit.SECONDS).get("value"));
        }
    }

    static final class Scene
    {
        final GenericClientEntityIds identities = new GenericClientEntityIds();
        final Map<Integer,Player> players = new LinkedHashMap<>();
        final Map<Integer,java.awt.Shape> shapes = new LinkedHashMap<>();
        final Map<Integer,net.runelite.api.NPC> npcs = new LinkedHashMap<>();
        boolean npcDead;
        boolean npcMoving = true;
        int playerHealthScale = 30;
        final String[] actions = {"Follow",null,"Trade with",null};
        final WorldView world = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(),new Class<?>[]{WorldView.class},
            (proxy,method,args) -> {
                switch (method.getName())
                {
                    case "getId": return -1;
                    case "getCollisionMaps": return null;
                    case "players": return new IndexedObjectSet<Player>()
                    {
                        @Override public Player byIndex(int index) { return players.get(index); }
                        @Override public Iterator<Player> iterator() { return players.values().iterator(); }
                    };
                    case "npcs": return new IndexedObjectSet<net.runelite.api.NPC>()
                    {
                        @Override public net.runelite.api.NPC byIndex(int index) { return npcs.get(index); }
                        @Override public Iterator<net.runelite.api.NPC> iterator() { return npcs.values().iterator(); }
                    };
                    default: throw new AssertionError("Unexpected world read: " + method.getName());
                }
            });
        final Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),new Class<?>[]{Client.class},
            (proxy,method,args) -> {
                switch (method.getName())
                {
                    case "getLocalPlayer": return players.get(17);
                    case "getLocalDestinationLocation": return null;
                    case "getPlayerOptions": return actions;
                    case "getBoostedSkillLevel": return 40;
                    case "getRealSkillLevel": return 50;
                    case "getEnergy": return 8000;
                    case "getVarpValue": return 1;
                    case "getCanvasWidth": case "getViewportWidth": return 800;
                    case "getCanvasHeight": case "getViewportHeight": return 600;
                    case "getViewportXOffset": case "getViewportYOffset": return 0;
                    default: throw new AssertionError("Unexpected client read: " + method.getName());
                }
            });

        GenericClientSnapshot snapshot(int tick)
        {
            return new GenericClientSnapshot(tick,"LOGGED_IN",240,GenericClientWorldSnapshot.capture(client,players.get(17),identities),
                GenericClientAccountSnapshot.empty(),GenericClientQuestSnapshot.empty(),List.of(),GenericClientWidgetSnapshot.empty(),null);
        }

        Player add(int id,String name,int x,int y,int plane,int level,int animation,boolean moving)
        {
            Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},
                (proxy,method,args) -> {
                    switch (method.getName())
                    {
                        case "getId": return id;
                        case "getName": return name;
                        case "getWorldLocation": return new WorldPoint(x,y,plane);
                        case "getWorldView": return world;
                        case "getCombatLevel": return level;
                        case "getAnimation": return animation;
                        case "getPoseAnimation": return moving ? 2 : 1;
                        case "getIdlePoseAnimation": return 1;
                        case "getInteracting": return null;
                        case "getWorldArea": return null;
                        case "getConvexHull": return shapes.get(id);
                        case "getCanvasTilePoly": return null;
                        case "getHealthRatio": return 15;
                        case "getHealthScale": return playerHealthScale;
                        default: throw new AssertionError("Unexpected player read: " + method.getName());
                    }
                });
            players.put(id,player);
            shapes.put(id,new java.awt.Rectangle(180,140,24,36));
            return player;
        }

        void addNpc()
        {
            net.runelite.api.NPCComposition composition = (net.runelite.api.NPCComposition) Proxy.newProxyInstance(
                net.runelite.api.NPCComposition.class.getClassLoader(),new Class<?>[]{net.runelite.api.NPCComposition.class},
                (proxy,method,args) -> {
                    switch (method.getName())
                    {
                        case "getActions": return new String[]{"Attack"};
                        case "getSize": return 1;
                        default: throw new AssertionError("Unexpected NPC definition read: " + method.getName());
                    }
                });
            net.runelite.api.NPC npc = (net.runelite.api.NPC) Proxy.newProxyInstance(net.runelite.api.NPC.class.getClassLoader(),
                new Class<?>[]{net.runelite.api.NPC.class},(proxy,method,args) -> {
                    switch (method.getName())
                    {
                        case "getId": return 123;
                        case "getIndex": return 7;
                        case "getName": return "Target";
                        case "getWorldLocation": return new WorldPoint(3202,3200,0);
                        case "getCombatLevel": return 12;
                        case "getAnimation": return -1;
                        case "getPoseAnimation": return npcMoving ? 2 : 1;
                        case "getIdlePoseAnimation": return 1;
                        case "getHealthRatio": return 15;
                        case "getHealthScale": return 30;
                        case "isDead": return npcDead;
                        case "getTransformedComposition": case "getComposition": return composition;
                        case "getConvexHull": return shapes.get(7);
                        case "getInteracting": case "getCanvasTilePoly": case "getLocalLocation": return null;
                        default: throw new AssertionError("Unexpected NPC read: " + method.getName());
                    }
                });
            npcs.put(7,npc);
            shapes.put(7,new java.awt.Rectangle(180,140,24,36));
        }
    }
}
