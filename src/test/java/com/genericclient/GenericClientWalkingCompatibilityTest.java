package com.genericclient;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientWalkingCompatibilityTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String WALKING = "org.dreambot.api.methods.walking.impl.Walking";

    @Test public void compiledWalkingReturnsAfterOneVerifiedClickWhileThePlayerIsStillFarAway() throws Exception
    {
        List<WorldPoint> clicked = new CopyOnWriteArrayList<>();
        GenericClientWalker.WalkInput input = new GenericClientWalker.WalkInput()
        {
            @Override public CompletableFuture<GenericClientInteractionResult> walkToFarthest(
                List<WorldPoint> candidates, GenericClientActivityContext context, double reach)
            {
                WorldPoint point = candidates.get(Math.max(0,candidates.size()-10));
                clicked.add(point);
                return CompletableFuture.completedFuture(new GenericClientInteractionResult(point,"WALK_TILE_CLICK_EXECUTED",true,Map.of(),Map.of()));
            }
            @Override public void cancelWalkToTile(GenericClientActivityContext context) {}
        };
        GenericClientWalker.ObstacleInput obstacles = new GenericClientWalker.ObstacleInput()
        {
            @Override public CompletableFuture<Map<String,Object>> interact(int id,String action,WorldPoint point,int within,GenericClientActivityContext context)
            { throw new AssertionError("This route must not require an obstacle"); }
            @Override public void cancel(String reason,GenericClientActivityContext context) {}
        };
        try (GenericClientWalker walker = GenericClientTestSupport.walker(input,obstacles,GenericClientCollisionMap.loadBundled(),message -> {});
            GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"walking-step").walkTo(walker::walkTo).build())
        {
            GenericClientSnapshot start = frame(3202,3428,null,false,false,10000);
            host.publishGameTick(start);
            walker.publishGameTick(start);
            CompletableFuture<Map<String,Object>> result = host.evaluate("return " + WALKING + ".walk(3230,3428);");
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while (!result.isDone() && System.nanoTime()<deadline)
            {
                walker.publishGameTick(start);
                Thread.sleep(10);
            }
            assertEquals(true,result.get(5,TimeUnit.SECONDS).get("value"));
            for (int tick=0;tick<5;tick++) walker.publishGameTick(start);
            assertEquals(1,clicked.size());
            assertNotEquals(new WorldPoint(3230,3428,0),clicked.get(0));
            assertEquals(new WorldPoint(3202,3428,0),start.getPlayerWorldPoint());
        }
    }

    @Test public void everyWalkOverloadUsesStepSemanticsAndReturnsTheObservedOutcome() throws Exception
    {
        List<WorldPoint> requested = new CopyOnWriteArrayList<>();
        List<String> outcomes = List.of("stepped","arrived","rejected","cancelled","rejected","cancelled","arrived","stepped");
        AtomicInteger count = new AtomicInteger();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"walking-overloads")
            .walkTo((request,boundary) -> {
                assertTrue(boundary.singleStep());
                requested.add(request.destination);
                return CompletableFuture.completedFuture(Map.of("status",outcomes.get(count.getAndIncrement())));
            }).build())
        {
            Map<String,Object> result = host.evaluate("return List.of(" + WALKING + ".walk(new org.dreambot.api.methods.map.Tile(3201,3202))," +
                WALKING + ".walk(3203,3204)," + WALKING + ".walk(3205,3206,2)," +
                WALKING + ".walk((org.dreambot.api.wrappers.interactive.Locatable)()->new org.dreambot.api.methods.map.Tile(3207,3208,1)));")
                .get(5,TimeUnit.SECONDS);
            assertEquals(List.of(true,true,false,false),result.get("value"));
            assertEquals(List.of(new WorldPoint(3201,3202,0),new WorldPoint(3203,3204,0),
                new WorldPoint(3205,3206,2),new WorldPoint(3207,3208,1)),requested);
            assertEquals(List.of(false,false,true,true),host.evaluate("return List.of(" + WALKING + ".walk(new org.dreambot.api.methods.map.Tile(3201,3202))," +
                WALKING + ".walk(3203,3204)," + WALKING + ".walk(3205,3206,2)," +
                WALKING + ".walk((org.dreambot.api.wrappers.interactive.Locatable)()->new org.dreambot.api.methods.map.Tile(3207,3208,1)));")
                .get(5,TimeUnit.SECONDS).get("value"));
        }
    }

    @Test public void walkingStatusUsesTheDestinationFlagAndStrictDistanceThreshold() throws Exception
    {
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            host.publishGameTick(frame(3200,3200,new WorldPoint(3210,3200,0),true,true,7350));
            Map<String,Object> result = host.evaluate("return Map.of(\"default\"," + WALKING + ".shouldWalk(),\"boundary\"," + WALKING + ".shouldWalk(10)," +
                "\"near\"," + WALKING + ".shouldWalk(11),\"energy\"," + WALKING + ".getRunEnergy(),\"enabled\"," + WALKING + ".isRunEnabled()," +
                "\"destination\"," + WALKING + ".getDestination().equals(new org.dreambot.api.methods.map.Tile(3210,3200)));")
                .get(5,TimeUnit.SECONDS);
            assertEquals(Map.of("default",false,"boundary",false,"near",true,"energy",73,"enabled",true,"destination",true),result.get("value"));
            host.publishGameTick(frame(3200,3200,null,false,false,0));
            assertEquals(Map.of("walk",true,"destination",true,"enabled",false),host.evaluate(
                "return Map.of(\"walk\"," + WALKING + ".shouldWalk(),\"destination\"," + WALKING + ".getDestination()==null,\"enabled\"," + WALKING + ".isRunEnabled());")
                .get(5,TimeUnit.SECONDS).get("value"));
            host.publishGameTick(frame(3200,3200,null,true,false,0));
            assertEquals(true,host.evaluate("return " + WALKING + ".shouldWalk();").get(5,TimeUnit.SECONDS).get("value"));
        }
    }

    @Test public void missingAndLoggedOutPlayersDoNotRequestAnotherStep() throws Exception
    {
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            assertEquals(false,host.evaluate("return " + WALKING + ".shouldWalk();").get(5,TimeUnit.SECONDS).get("value"));
            host.publishGameTick(frame(3200,3200,null,false,false,0));
            host.clearSnapshot();
            assertEquals(Map.of("walk",false,"destination",true),host.evaluate("return Map.of(\"walk\"," + WALKING +
                ".shouldWalk(),\"destination\"," + WALKING + ".getDestination()==null);").get(5,TimeUnit.SECONDS).get("value"));
        }
    }

    @Test public void journeyBuildersKeepTheirConstraintsThroughContinuationAndDoNotChangeTheOriginal() throws Exception
    {
        List<GenericClientWalkRequest> requests = new CopyOnWriteArrayList<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"journey-builders")
            .walkTo((request,boundary) -> {
                assertFalse(boundary.singleStep());
                requests.add(request);
                return CompletableFuture.completedFuture(requests.size()==1
                    ? Map.of("status","interrupted","reason","dialogue","continuation","trip") : Map.of("status","arrived"));
            }).build())
        {
            assertEquals(List.of("interrupted","arrived","arrived"),host.evaluate(
                "var base=new com.genericclient.script.Navigation.Journey(new org.dreambot.api.methods.map.Tile(3203,3200),1);"+
                "var constrained=base.via(new org.dreambot.api.methods.map.Tile(3201,3200))"+
                ".arrivingAt(new org.dreambot.api.methods.map.Tile(3202,3200),new org.dreambot.api.methods.map.Tile(3203,3201))"+
                ".avoiding(List.of(new org.dreambot.api.methods.map.Tile(3203,3199))).timeout(120);"+
                "var first=com.genericclient.script.Navigation.walk(constrained,Map.of(\"dialogue\",true),null);"+
                "var resumed=com.genericclient.script.Navigation.walk(constrained,Map.of(\"poisoned\",true),(String)first.get(\"continuation\"));"+
                "var original=com.genericclient.script.Navigation.walk(base,Map.of(),null);"+
                "return List.of(first.get(\"status\"),resumed.get(\"status\"),original.get(\"status\"));")
                .get(5,TimeUnit.SECONDS).get("value"));
            assertEquals(3,requests.size());
            for (GenericClientWalkRequest request : requests.subList(0,2))
            {
                assertEquals(new WorldPoint(3203,3200,0),request.destination);
                assertEquals(List.of(new WorldPoint(3201,3200,0)),request.via);
                assertEquals(List.of(new WorldPoint(3202,3200,0),new WorldPoint(3203,3201,0)),request.arrivalTiles);
                assertEquals(List.of(new WorldPoint(3203,3199,0)),request.avoidTiles);
                assertEquals(120,request.timeoutTicks);
            }
            assertEquals("trip",requests.get(1).resume);
            assertTrue(requests.get(2).via.isEmpty());
            assertTrue(requests.get(2).arrivalTiles.isEmpty());
            assertTrue(requests.get(2).avoidTiles.isEmpty());
            assertEquals(1200,requests.get(2).timeoutTicks);
        }
    }

    private static GenericClientSnapshot frame(int x,int y,WorldPoint destination,boolean moving,boolean running,int energy)
    {
        return new GenericClientSnapshot(1,"LOGGED_IN",240,new GenericClientPlayerSnapshot(1L,"Player",x,y,0,-1,-1,null,
            10,10,energy,running,destination,moving),List.of());
    }
}
