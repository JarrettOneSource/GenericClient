package com.genericclient;

import static org.junit.Assert.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientScriptClockTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void overlappingPausesShareOneFrozenActiveInterval() throws Exception
    {
        AtomicLong nanos = new AtomicLong(TimeUnit.SECONDS.toNanos(5));
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Map<String,Object>> action = new CompletableFuture<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"overlapping-clock")
            .nanoClock(nanos::get).npcInteract((id,index,identity,name,verb,within,context) -> {
                entered.countDown();
                return action;
            }).build())
        {
            host.compile("ActiveClock",GenericClientTestSupport.javaScript("ActiveClock","",
                "public int onLoop(){Automation.intent(\"measured\",()->ScriptScope.current().execute(\"npc.interact\"," +
                "Map.of(\"id\",1,\"action\",\"Bank\"),60000));Automation.finish(ScriptScope.current().read(\"behavior\",Map.of()));return -1;}"))
                .get(5,TimeUnit.SECONDS);
            host.start("ActiveClock").get(5,TimeUnit.SECONDS);
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            nanos.set(TimeUnit.SECONDS.toNanos(6));
            host.pauseForManualInput("mouse").get(5,TimeUnit.SECONDS);
            nanos.set(TimeUnit.SECONDS.toNanos(7));
            host.pauseForEmergency("heal").get(5,TimeUnit.SECONDS);
            nanos.set(TimeUnit.SECONDS.toNanos(8));
            host.resumeAfterManualInput("idle").get(5,TimeUnit.SECONDS);
            assertEquals(1000L,((Map<?,?>)host.readCurrentSnapshot("behavior").get(5,TimeUnit.SECONDS)).get("intent_elapsed_millis"));
            nanos.set(TimeUnit.SECONDS.toNanos(10));
            host.resumeAfterEmergency("healed").get(5,TimeUnit.SECONDS);
            nanos.set(TimeUnit.MILLISECONDS.toNanos(11500));
            assertEquals(2500L,((Map<?,?>)host.readCurrentSnapshot("behavior").get(5,TimeUnit.SECONDS)).get("intent_elapsed_millis"));
            action.complete(Map.of("status","dispatched"));
            GenericClientTestSupport.waitForStatus(host,"COMPLETED");
            assertEquals(6500,host.getActiveScriptView().getRuntimeMillis());
            Map<?,?> value = (Map<?,?>)host.getActiveScriptView().toMap().get("result");
            assertEquals(2500L,((Map<?,?>)value.get("last_intent")).get("elapsed_millis"));
        }
    }

    @Test public void aRunFinishedAtZeroDoesNotKeepAging() throws Exception
    {
        AtomicLong nanos = new AtomicLong();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"finished-clock")
            .nanoClock(nanos::get).build())
        {
            assertEquals(42,host.evaluate("return 42;").get(5,TimeUnit.SECONDS).get("value"));
            assertEquals(0,host.getActiveScriptView().getRuntimeMillis());
            nanos.set(TimeUnit.SECONDS.toNanos(10));
            assertEquals(0,host.getActiveScriptView().getRuntimeMillis());
        }
    }

    @Test public void aPauseThatEndsDuringOneResultPollDoesNotConsumeTheActionTimeout() throws Exception
    {
        AtomicLong nanos = new AtomicLong();
        CountDownLatch polling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Map<String,Object>> action = new CompletableFuture<>()
        {
            @Override public Map<String,Object> get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException
            {
                polling.countDown();
                release.await();
                return super.get(timeout,unit);
            }
        };
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"paused-poll")
            .nanoClock(nanos::get).walkTo((request,boundary) -> action).build())
        {
            CompletableFuture<Map<String,Object>> evaluation = host.evaluate(
                "return ScriptScope.current().execute(\"walk.to\",Map.of(\"destination\",Map.of(\"x\",3200,\"y\",3200,\"plane\",0)),1000);");
            assertTrue(polling.await(5,TimeUnit.SECONDS));
            host.pauseForManualInput("mouse").get(5,TimeUnit.SECONDS);
            nanos.set(TimeUnit.SECONDS.toNanos(5));
            host.resumeAfterManualInput("idle").get(5,TimeUnit.SECONDS);
            release.countDown();
            assertThrows(TimeoutException.class,() -> evaluation.get(150,TimeUnit.MILLISECONDS));
            action.complete(Map.of("status","arrived"));
            assertEquals("arrived",((Map<?,?>)evaluation.get(5,TimeUnit.SECONDS).get("value")).get("status"));
        }
        finally { release.countDown(); }
    }
}
