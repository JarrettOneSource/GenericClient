package com.genericclient;

import static org.junit.Assert.*;
import static com.genericclient.GenericClientScriptHostTest.await;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientCursorAdmissionTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void explicitTickWaitPolicyControlsRestWithoutAnActionBoundary() throws Exception
    {
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"cursor-policy").build())
        {
            host.publishGameTick(GenericClientDamageTrackerTest.snapshot(10,80,0));
            CompletableFuture<Map<String,Object>> evaluation = host.evaluate(
                "com.genericclient.script.Automation.activity(\"combat\");" +
                "com.genericclient.script.Automation.sleepTicks(4,Map.of(\"activity\",\"travel\",\"humanize\",true," +
                "\"policy\",Map.of(\"fidget\",\"drift\",\"walk_refresh\",true)));return Map.of();");
            await(() -> host.quietMillis(null,0) == 1800);
            GenericClientActivityContext context = host.getBehaviorContext();
            assertEquals(GenericClientActivityContext.Activity.TRAVEL,context.getActivity());
            assertEquals(GenericClientBehaviorPolicy.Fidget.DRIFT,context.policy().fidget);
            assertTrue(context.refreshesWalkClicks());
            assertFalse(host.behaviorPaused());
            assertFalse(evaluation.isDone());
            host.publishGameTick(GenericClientDamageTrackerTest.snapshot(14,80,0));
            evaluation.get(5,TimeUnit.SECONDS);
            assertFalse(context.isInputAllowed());
        }
    }

    @Test public void walkerWindowsBelongOnlyToTheOperationThatStartedTheJourney() throws Exception
    {
        CompletableFuture<GenericClientWalkRequest> started = new CompletableFuture<>();
        CompletableFuture<Map<String,Object>> walking = new CompletableFuture<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"cursor-walk")
            .walkTo((request,clicks) -> { started.complete(request); return walking; }).build())
        {
            host.publishGameTick(GenericClientDamageTrackerTest.snapshot(10,80,0));
            CompletableFuture<Map<String,Object>> evaluation = host.evaluate(
                "return ScriptScope.current().execute(\"walk.to\",Map.of(\"destination\",Map.of(\"x\",3250,\"y\",3250,\"plane\",0)),5000);");
            GenericClientWalkRequest request = started.get(5,TimeUnit.SECONDS);
            assertEquals(0,host.quietMillis(new GenericClientActionBoundary.Ticket(),9000));
            assertEquals(9000,host.quietMillis(request.activityContext.inputTicket(),9000));
            assertEquals(0,host.quietMillis(request.activityContext.inputTicket(),0));
            walking.complete(Map.of("status","arrived"));
            assertEquals("arrived",((Map<?,?>)evaluation.get(5,TimeUnit.SECONDS).get("value")).get("status"));
            assertEquals(Long.MAX_VALUE,host.quietMillis(null,0));
        }
    }

    @Test public void tickRestClosesBeforeTheWakeTickAndKeepsExclusiveWorkerOwnership() throws Exception
    {
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"cursor-wait").build())
        {
            host.publishGameTick(GenericClientDamageTrackerTest.snapshot(10,80,0));
            host.compile("Rest",GenericClientTestSupport.javaScript("Rest","",
                "public int onLoop(){Automation.activity(\"skilling\");org.dreambot.api.utilities.Sleep.sleepTicks(10);return -1;}"))
                .get(5,TimeUnit.SECONDS);
            host.start("Rest").get(5,TimeUnit.SECONDS);
            await(() -> host.quietMillis(null,0) == 5400);
            GenericClientActivityContext context = host.getBehaviorContext();
            assertThrows(ExecutionException.class,() -> host.evaluate("return null;").get(5,TimeUnit.SECONDS));
            assertEquals(5400,host.quietMillis(null,0));
            host.pauseForManualInput("mouse").get(5,TimeUnit.SECONDS);
            assertEquals(0,host.quietMillis(null,0));
            assertFalse(context.isInputAllowed());
            host.resumeAfterManualInput("idle").get(5,TimeUnit.SECONDS);
            assertTrue(context.isInputAllowed());
            host.publishGameTick(GenericClientDamageTrackerTest.snapshot(19,80,0));
            assertEquals(0,host.quietMillis(null,0));
            assertEquals("RUNNING",host.getStatus());
            host.publishGameTick(GenericClientDamageTrackerTest.snapshot(20,80,0));
            GenericClientTestSupport.waitForStatus(host,"COMPLETED");
            assertFalse(context.isInputAllowed());
        }
    }

    @Test public void stoppingARealTimeSleepCancelsItsRestScope() throws Exception
    {
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"cursor-time").build())
        {
            host.compile("TimedRest",GenericClientTestSupport.javaScript("TimedRest","",
                "public int onLoop(){Automation.activity(\"skilling\");sleep(10000);throw new AssertionError(\"Stopped sleep resumed\");}"))
                .get(5,TimeUnit.SECONDS);
            host.start("TimedRest").get(5,TimeUnit.SECONDS);
            await(() -> host.quietMillis(null,0)>0 && host.quietMillis(null,0)<10001);
            GenericClientActivityContext context = host.getBehaviorContext();
            host.stop().get(5,TimeUnit.SECONDS);
            assertFalse(context.isInputAllowed());
            assertEquals("STOPPED",host.getStatus());
            assertEquals(Long.MAX_VALUE,host.quietMillis(null,0));
        }
    }

    @Test public void finishingASleepRevokesItsCursorMovementBeforeTheRunEnds() throws Exception
    {
        java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong(TimeUnit.SECONDS.toNanos(5));
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"cursor-next-sleep")
            .nanoClock(nanos::get).build())
        {
            host.compile("RestLifetime",GenericClientTestSupport.javaScript("RestLifetime","",
                "public int onLoop(){Automation.activity(\"skilling\");sleep(1000);log(\"next sleep\");sleep(10000);return -1;}"))
                .get(5,TimeUnit.SECONDS);
            host.start("RestLifetime").get(5,TimeUnit.SECONDS);
            await(() -> host.quietMillis(null,0)==1000);
            GenericClientActivityContext previous = host.getBehaviorContext().forkInputScope();
            assertTrue(previous.isInputAllowed());
            nanos.set(TimeUnit.SECONDS.toNanos(6));
            await(() -> host.getRecentLogs().contains("next sleep") && host.quietMillis(null,0)==10000);
            assertTrue(host.getRunState().isRunning());
            assertTrue(host.getBehaviorContext().isInputAllowed());
            assertFalse(previous.applyIfCurrent(() -> fail("An expired rest applied a cursor update")));
        }
    }
}
