package com.genericclient;

import static org.junit.Assert.*;
import static com.genericclient.GenericClientScriptHostTest.await;
import static com.genericclient.GenericClientScriptHostTest.snapshot;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientActionLifetimeTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void passesJourneyConstraintsWithoutChangingCallerOptions() throws Exception
    {
        AtomicReference<GenericClientWalkRequest> request = new AtomicReference<>();
        try (GenericClientScriptHost host = host((input, boundary) -> {
            request.set(input); return CompletableFuture.completedFuture(Map.of("status","arrived"));
        }))
        {
            Map<String, Object> result = host.evaluate("Map<String,Object> options=Map.of(\"destination\",Map.of(\"x\",3210,\"y\",3200,\"plane\",0)," +
                "\"via\",List.of(Map.of(\"x\",3200,\"y\",3210,\"plane\",0)),\"arrival_tiles\",List.of(Map.of(\"x\",3209,\"y\",3200,\"plane\",0)),\"timeout_ticks\",123);" +
                "Map<String,Object> receipt=ScriptScope.current().execute(\"walk.to\",options,5000); return options.size()==4 && receipt.get(\"status\").equals(\"arrived\");")
                .get(10, TimeUnit.SECONDS);
            assertEquals(true, result.get("value"));
            assertEquals(123, request.get().timeoutTicks);
            assertEquals(1, request.get().via.size());
            assertEquals(1, request.get().arrivalTiles.size());
        }
    }

    @Test public void invalidationDiscardsStateWhileScriptStartupIsQueued() throws Exception
    {
        CompletableFuture<Void> release = new CompletableFuture<>();
        try (GenericClientScriptHost host = host((input, boundary) -> CompletableFuture.completedFuture(Map.of())))
        {
            host.publishGameTick(snapshot(1));
            host.compile("Waiting", GenericClientTestSupport.javaScript("Waiting", "", "public int onLoop(){sleep(60000);return -1;}")).get();
            CountDownLatch entered = new CountDownLatch(1);
            host.setScriptStartListener((id, owner, context) -> { entered.countDown(); release.join(); });
            CompletableFuture<String> started = host.start("Waiting");
            try
            {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                host.publishGameTick(snapshot(2));
                host.clearSnapshot();
            }
            finally { release.complete(null); }
            started.get(5, TimeUnit.SECONDS);
            assertNull(host.readCurrentSnapshot("account").get());
            host.publishGameTick(snapshot(3));
            assertNotNull(host.readCurrentSnapshot("player").get());
        }
    }

    @Test public void lateCompletionFromTimedOutConsoleCannotCompleteTheNextInvocation() throws Exception
    {
        CompletableFuture<Map<String,Object>> first = new CompletableFuture<>();
        CompletableFuture<Map<String,Object>> second = new CompletableFuture<>();
        AtomicInteger dispatches = new AtomicInteger();
        try (GenericClientScriptHost host = host((input,boundary) -> dispatches.incrementAndGet()==1 ? first : second))
        {
            CompletableFuture<Map<String,Object>> oldEval = host.evaluate("return " + walk(60) + ";");
            await(() -> dispatches.get()==1);
            Map<?,?> expired = (Map<?,?>) oldEval.get(5, TimeUnit.SECONDS).get("value");
            assertEquals("timed_out", expired.get("status"));
            CompletableFuture<Map<String,Object>> next = host.evaluate("return " + walk(5000) + ";");
            await(() -> dispatches.get()==2);
            first.complete(Map.of("status","arrived"));
            assertFalse(next.isDone());
            second.complete(Map.of("status","arrived"));
            assertEquals("arrived", ((Map<?,?>)next.get(5,TimeUnit.SECONDS).get("value")).get("status"));
        }
    }

    @Test public void pauseTemporarilyRevokesAuthorityAndStopRevokesItPermanently() throws Exception
    {
        CompletableFuture<Map<String,Object>> action = new CompletableFuture<>();
        AtomicReference<GenericClientActivityContext> captured = new AtomicReference<>();
        try (GenericClientScriptHost host = host((input,boundary) -> { captured.set(input.activityContext); return action; }))
        {
            host.compile("Owned", GenericClientTestSupport.javaScript("Owned", "", "public int onLoop(){"+walk(5000)+";return -1;}")).get();
            host.start("Owned").get();
            await(() -> captured.get()!=null);
            assertTrue(captured.get().isInputAllowed());
            host.pauseForManualInput("mouse").get();
            assertFalse(captured.get().isInputAllowed());
            assertTrue(captured.get().humanize);
            host.resumeAfterManualInput("idle").get();
            assertTrue(captured.get().isInputAllowed());
            action.complete(Map.of("status","arrived"));
            await(() -> host.getStatus().equals("COMPLETED"));
            assertFalse(captured.get().isInputAllowed());
        }
    }

    @Test public void completedOrFailedInputCannotKeepAuthorityDuringTheNextSleep() throws Exception
    {
        for (boolean fail : new boolean[]{false,true})
        {
            AtomicReference<GenericClientActivityContext> captured = new AtomicReference<>();
            try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"released-"+fail).walkTo((request,boundary) -> {
                captured.set(request.activityContext);
                return fail ? CompletableFuture.failedFuture(new IllegalStateException("native input failed"))
                    : CompletableFuture.completedFuture(Map.of("status","arrived"));
            }).build())
            {
                host.compile("Released",GenericClientTestSupport.javaScript("Released","",
                    "public int onLoop(){try{" + walk(5000) + ";}catch(RuntimeException failure){log(\"handled\");}" +
                    "log(\"waiting\");sleep(60000);return -1;}" )).get(5,TimeUnit.SECONDS);
                host.start("Released").get(5,TimeUnit.SECONDS);
                await(() -> host.getRecentLogs().contains("waiting") && host.quietMillis(null,0)>0);
                assertTrue(host.getRunState().isRunning());
                assertTrue(host.getBehaviorContext().isInputAllowed());
                assertFalse(captured.get().applyIfCurrent(() -> fail("A completed input applied a late update")));
                assertEquals(fail,host.getRecentLogs().contains("handled"));
            }
        }
    }

    private GenericClientScriptHost host(GenericClientScriptActions.WalkToAction walk) throws Exception
    {
        return GenericClientTestSupport.scriptHost(temporary, "runtime").walkTo(walk).build();
    }
    private static String walk(int millis)
    {
        return "ScriptScope.current().execute(\"walk.to\",Map.of(\"destination\",Map.of(\"x\",3210,\"y\",3200,\"plane\",0))," + millis + ")";
    }
}
