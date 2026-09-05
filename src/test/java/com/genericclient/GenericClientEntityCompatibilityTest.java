package com.genericclient;

import static org.junit.Assert.*;
import static com.genericclient.GenericClientScriptHostTest.await;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientEntityCompatibilityTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void queryOverloadsAcceptBoxedIdsAndAnExplicitOrigin() throws Exception
    {
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            host.publishGameTick(queryFrame(true));
            for (String type : List.of("NPCs","GameObjects"))
            {
                String api = "org.dreambot.api.methods.interactive." + type;
                Map<String,Object> result = host.evaluate("Integer[] ids={124}; String[] names={\"Target\"}; return Map.of(" +
                    "\"byId\"," + api + ".all(ids).size(),\"byName\"," + api + ".all(names).size()," +
                    "\"closestId\"," + api + ".closest(ids).getId()," +
                    "\"closestName\"," + api + ".closest(names).getId()," +
                    "\"toTile\"," + api + ".closest(entity->true,new org.dreambot.api.methods.map.Tile(3203,3200)).getId());")
                    .get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(Map.of("byId",1,"byName",2,"closestId",124,"closestName",123,"toTile",124),result.get("value"));
                assertEquals(true,host.evaluate("return " + api + ".closest(entity->true,new org.dreambot.api.methods.map.Tile(3203,3200,1))==null;")
                    .get(5,java.util.concurrent.TimeUnit.SECONDS).get("value"));
            }
        }
    }

    @Test public void equivalentWrappersShareIdentityWithoutMergingDifferentKinds() throws Exception
    {
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            host.publishGameTick(queryFrame(true));
            Map<String,Object> result = host.evaluate("var first=org.dreambot.api.methods.interactive.NPCs.closest(123);" +
                "var second=org.dreambot.api.methods.interactive.NPCs.closest(123);" +
                "var object=org.dreambot.api.methods.interactive.GameObjects.closest(123);" +
                "return Map.of(\"same\",first.equals(second),\"hash\",first.hashCode()==second.hashCode()," +
                "\"reference\",first.getReference().equals(second.getReference()),\"distinct\",!first.equals(object)," +
                "\"otherType\",first.equals(\"123\")||first.getReference().equals(\"123\")," +
                "\"sameIdOtherKind\",first.getReference().equals(new com.genericclient.script.EntityReference(\"object\",123L))," +
                "\"set\",new java.util.HashSet<>(List.of(first,second,object)).size());").get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(Map.of("same",true,"hash",true,"reference",true,"distinct",true,"otherType",false,"sameIdOtherKind",false,"set",2),result.get("value"));
        }
    }

    @Test public void queriesSkipEntitiesThatDisappearInsideTheFilter() throws Exception
    {
        for (String kind : List.of("NPCs","GameObjects"))
        {
            java.util.concurrent.atomic.AtomicReference<GenericClientScriptHost> running = new java.util.concurrent.atomic.AtomicReference<>();
            try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"query-race-"+kind)
                .questAction((type,arguments,context) -> {
                    assertEquals("test.advance",type);
                    running.get().publishGameTick(queryFrame(false));
                    return java.util.concurrent.CompletableFuture.completedFuture(Map.of("status","complete"));
                }).build())
            {
                running.set(host);
                for (String operation : List.of("all","closest")) for (int trigger : List.of(122,123,124))
                {
                    host.publishGameTick(queryFrame(true));
                    String result = "all".equals(operation) ? "found.size() == 1 && found.get(0).getId() == 124" : "found != null && found.getId() == 124";
                    Map<String,Object> evaluated = host.evaluate("var found=org.dreambot.api.methods.interactive." + kind + "." + operation + "(entity->{" +
                        "if(entity.getId()==" + trigger + ")ScriptScope.current().execute(\"test.advance\",Map.of(),5000);" +
                        "return entity.getName().equals(\"Target\");}); return " + result + ";").get(5,java.util.concurrent.TimeUnit.SECONDS);
                    assertEquals(kind + "." + operation,true,evaluated.get("value"));
                }
            }
        }
    }

    @Test public void queryDoesNotHideScriptPredicateFailures() throws Exception
    {
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            host.publishGameTick(queryFrame(true));
            for (String kind : List.of("NPCs","GameObjects"))
            {
                java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class, () ->
                    host.evaluate("return org.dreambot.api.methods.interactive." + kind + ".closest(entity->{throw new IllegalStateException(\"predicate bug\");});")
                        .get(5,java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(failure.getMessage().contains("predicate bug"));
            }
        }
    }

    @Test public void npcQueriesIncludeTheEntireLoadedScene() throws Exception
    {
        List<GenericClientNpcSnapshot> npcs = java.util.stream.IntStream.range(0,105)
            .mapToObj(index -> new GenericClientNpcSnapshot(1000L+index,index,123,"Target",3201,3200,0,1,1,-1,null,List.of("Talk-to")))
            .collect(java.util.stream.Collectors.toList());
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            host.publishGameTick(new GenericClientSnapshot(1,"LOGGED_IN",240,
                new GenericClientPlayerSnapshot(1L,"Player",3200,3200,0,-1),npcs));
            Map<String,Object> result = host.evaluate(
                "org.dreambot.api.wrappers.interactive.NPC last=org.dreambot.api.methods.interactive.NPCs.closest(npc->npc.getIndex()==104);" +
                "return Map.of(\"count\",org.dreambot.api.methods.interactive.NPCs.all().size(),\"last\",last==null?-1:last.getIndex());")
                .get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(Map.of("count",105,"last",104),result.get("value"));
        }
    }

    @Test public void retainedNpcObservesNewAnimationAndDisappearance() throws Exception
    {
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            host.publishGameTick(frame(1, 0));
            host.compile("NpcReference", GenericClientTestSupport.javaScript("NpcReference", "",
                "public int onLoop(){org.dreambot.api.wrappers.interactive.NPC target=org.dreambot.api.methods.interactive.NPCs.closest(123);" +
                "log(\"observing\"); if(!sleepUntil(()->target.getAnimation()==5,2000)) throw new IllegalStateException(\"animation stayed stale\");" +
                "log(\"animated\"); if(!sleepUntil(()->!target.exists(),2000)) throw new IllegalStateException(\"despawn not observed\");" +
                "log(\"gone\"); return -1;}" )).get();
            host.start("NpcReference").get();
            await(() -> host.getRecentLogs().contains("observing"));
            host.publishGameTick(frame(2, 5));
            await(() -> host.getRecentLogs().contains("animated"));
            host.publishGameTick(new GenericClientSnapshot(3,"LOGGED_IN",240,
                new GenericClientPlayerSnapshot(1L, "player",3200,3200,0,-1),List.of()));
            await(() -> host.getStatus().equals("COMPLETED"));
            assertTrue(host.getRecentLogs().contains("gone"));
        }
    }

    @Test public void lastLocalReferenceSurvivesLogoutButPresenceDoesNot() throws Exception
    {
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            host.publishGameTick(frame(1,0));
            host.clearSnapshot();
            java.util.Map<String,Object> result = host.evaluate(
                "org.dreambot.api.wrappers.interactive.Player local=org.dreambot.api.methods.interactive.Players.getLocal();" +
                "return java.util.Map.of(\"reference\",local!=null,\"exists\",local!=null&&local.exists(),\"logged_in\",org.dreambot.api.Client.isLoggedIn());")
                .get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(java.util.Map.of("reference",true,"exists",false,"logged_in",false),result.get("value"));
        }
    }

    @Test public void reusedNpcIndexCannotRebindAnOldScriptReference() throws Exception
    {
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            host.publishGameTick(frame(1,0));
            host.compile("ReusedIndex",GenericClientTestSupport.javaScript("ReusedIndex","",
                "public int onLoop(){org.dreambot.api.wrappers.interactive.NPC old=org.dreambot.api.methods.interactive.NPCs.closest(123);log(\"bound\");" +
                "if(!sleepUntil(()->!old.exists(),2000))throw new IllegalStateException(\"old reference remained live\");" +
                "log(\"interact:\"+old.interact(\"Talk-to\"));return -1;}" )).get();
            host.start("ReusedIndex").get();
            await(() -> host.getRecentLogs().contains("bound"));
            host.publishGameTick(new GenericClientSnapshot(2,"LOGGED_IN",240,
                new GenericClientPlayerSnapshot(1L,"player",3200,3200,0,-1),List.of(
                    new GenericClientNpcSnapshot(9L,5,123,"Target",3201,3200,0,1,1,0,null,List.of("Talk-to")))));
            await(() -> host.getStatus().equals("COMPLETED"));
            assertTrue(host.getRecentLogs().contains("interact:false"));
        }
    }

    @Test public void itemUseAcceptsAnEntityAndDoesNotRetargetADespawnedObject() throws Exception
    {
        java.util.List<Map<String,Object>> inputs=new java.util.concurrent.CopyOnWriteArrayList<>();
        try (GenericClientScriptHost host=GenericClientTestSupport.scriptHost(temporary,"entity-item")
            .questAction((type,arguments,context) ->
            {
                assertEquals("item.use_on_object",type);
                inputs.add(arguments);
                return java.util.concurrent.CompletableFuture.completedFuture(Map.of("status","dispatched"));
            }).build())
        {
            host.publishGameTick(itemFrame(1,20L));
            host.compile("EntityItem",GenericClientTestSupport.javaScript("EntityItem","",
                "public int onLoop(){org.dreambot.api.wrappers.interactive.Entity target=org.dreambot.api.methods.interactive.GameObjects.closest(100);" +
                "org.dreambot.api.wrappers.items.Item item=org.dreambot.api.methods.container.impl.Inventory.get(1);" +
                "log(\"used:\"+item.useOn(target));" +
                "if(!sleepUntil(()->!target.exists(),2000))throw new AssertionError(\"Old target did not despawn\");" +
                "log(\"gone:\"+item.useOn(target));return -1;}" )).get();
            host.start("EntityItem").get();
            await(() -> host.getRecentLogs().contains("used:true"));
            host.publishGameTick(itemFrame(2,21L));
            await(() -> host.getStatus().equals("COMPLETED"));
            assertEquals("EntityItem: used:true\nEntityItem: gone:false",host.getRecentLogs());
            assertEquals(1,inputs.size());
            assertEquals(20L,inputs.get(0).get("entity_identity"));
            assertEquals(1,inputs.get(0).get("item_id"));
            assertEquals(100,inputs.get(0).get("object_id"));
        }
    }

    private static GenericClientSnapshot itemFrame(int tick, long identity)
    {
        GenericClientAccountSnapshot account=new GenericClientAccountSnapshot(true,1,List.of(),
            new GenericClientAccountSnapshot.ContainerSnapshot(true,28,List.of(
                new GenericClientAccountSnapshot.ItemSnapshot(0,null,1,1,"Item",false,false,false,List.of("Use")))),
            new GenericClientAccountSnapshot.ContainerSnapshot(true,14,List.of()));
        GenericClientQuestSnapshot quest=new GenericClientQuestSnapshot(true,new int[0],List.of(
            new GenericClientQuestSnapshot.ObjectSnapshot(identity,100,"Object","game",3201,3200,0,1,List.of("Use"))),
            GenericClientQuestSnapshot.DialogueSnapshot.closed());
        return new GenericClientSnapshot(tick,"LOGGED_IN",240,
            new GenericClientPlayerSnapshot(1L,"Player",3200,3200,0,-1),List.of(),account,quest);
    }

    private static GenericClientSnapshot frame(int tick, int animation)
    {
        return new GenericClientSnapshot(tick,"LOGGED_IN",240,
            new GenericClientPlayerSnapshot(1L, "player",3200,3200,0,-1),List.of(
                new GenericClientNpcSnapshot(105L, 5,123,"Target",3201,3200,0,1,1,animation,null,List.of("Talk-to"))));
    }

    private static GenericClientSnapshot queryFrame(boolean firstPresent)
    {
        java.util.ArrayList<GenericClientNpcSnapshot> npcs = new java.util.ArrayList<>();
        java.util.ArrayList<GenericClientQuestSnapshot.ObjectSnapshot> objects = new java.util.ArrayList<>();
        for (int id = 122; id <= 124; id++)
        {
            if (id == 123 && !firstPresent) continue;
            String name = id == 122 ? "Other" : "Target";
            npcs.add(new GenericClientNpcSnapshot(id,id, id,name,3200+id-122,3200,0,id-122,1,-1,null,List.of("Talk-to")));
            objects.add(new GenericClientQuestSnapshot.ObjectSnapshot(id+1000L,id,name,"game",3200+id-122,3200,0,id-122,List.of("Open")));
        }
        return new GenericClientSnapshot(firstPresent ? 1 : 2,"LOGGED_IN",240,
            new GenericClientPlayerSnapshot(1L,"Player",3200,3200,0,-1),npcs,GenericClientAccountSnapshot.empty(),
            new GenericClientQuestSnapshot(true,new int[0],objects,GenericClientQuestSnapshot.DialogueSnapshot.closed()));
    }
}
