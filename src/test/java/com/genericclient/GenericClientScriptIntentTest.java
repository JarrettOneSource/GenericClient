package com.genericclient;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientScriptIntentTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void stoppingAnIntentRevokesInputBeforeAReplacementStarts() throws Exception
    {
        CountDownLatch dispatched = new CountDownLatch(1);
        CompletableFuture<Map<String, Object>> old = new CompletableFuture<>();
        AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
        List<String> cancellations = new CopyOnWriteArrayList<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary, "intent-stop")
            .cancel(cancellations::add).npcInteract((id, index, identity, name, action, within, context) -> {
                input.set(context);
                dispatched.countDown();
                return old;
            }).build())
        {
            compile(host, "OldIntent", "Automation.intent(\"old\",()->" + npc(1, "Bank", 5000) + ");");
            compile(host, "ReplacementIntent", "Automation.finish(Automation.intent(\"replacement\",()->" + behavior() + "));");
            host.start("OldIntent").get(5, TimeUnit.SECONDS);
            assertTrue(dispatched.await(2, TimeUnit.SECONDS));
            host.stop().get(2, TimeUnit.SECONDS);
            assertFalse(input.get().isInputAllowed());
            host.start("ReplacementIntent").get(5, TimeUnit.SECONDS);
            GenericClientTestSupport.waitForStatus(host, "COMPLETED");
            int cancelled = cancellations.size();
            old.complete(Map.of("status", "dispatched"));
            assertEquals(cancelled, cancellations.size());
            assertEquals("replacement", result(host).get("intent"));
            assertEquals("ReplacementIntent", host.getActiveScriptView().toMap().get("id"));
        }
    }

    @Test public void anExpiredIntentCannotRebindItsLateActionToTheNextScope() throws Exception
    {
        CompletableFuture<Map<String, Object>> expired = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> fresh = new CompletableFuture<>();
        CountDownLatch nextStarted = new CountDownLatch(1);
        List<GenericClientActivityContext> inputs = new CopyOnWriteArrayList<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary, "intent-timeout")
            .npcInteract((id, index, identity, name, action, within, context) -> {
                inputs.add(context);
                if (id == 1) return expired;
                nextStarted.countDown();
                return fresh;
            }).build())
        {
            compile(host, "ExpiredIntent", "Object expired=Automation.intent(\"expired\",()->" + npc(1, "Bank", 150) + ");" +
                "Object fresh=Automation.intent(\"fresh\",()->" + npc(2, "Bank", 5000) + ");" +
                "Automation.finish(Map.of(\"expired\",expired,\"fresh\",fresh,\"after\"," + behavior() + "));");
            host.start("ExpiredIntent").get(5, TimeUnit.SECONDS);
            assertTrue(nextStarted.await(2, TimeUnit.SECONDS));
            assertFalse(inputs.get(0).isInputAllowed());
            assertTrue(inputs.get(1).isInputAllowed());
            expired.complete(Map.of("status", "dispatched"));
            assertTrue(inputs.get(1).isInputAllowed());
            fresh.complete(Map.of("status", "dispatched"));
            GenericClientTestSupport.waitForStatus(host, "COMPLETED");
            Map<?, ?> result = result(host);
            assertEquals("expired", ((Map<?, ?>) result.get("expired")).get("intent"));
            assertEquals("timed_out", ((Map<?, ?>) result.get("expired")).get("status"));
            assertEquals("fresh", ((Map<?, ?>) result.get("fresh")).get("intent"));
            assertEquals("dispatched", ((Map<?, ?>) result.get("fresh")).get("status"));
            assertEquals("none", ((Map<?, ?>) result.get("after")).get("intent"));
        }
    }

    @Test public void aLongIntentWarnsOnceAfterThirtyActiveSeconds() throws Exception
    {
        java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.BlockingQueue<CompletableFuture<Map<String,Object>>> pulses = new java.util.concurrent.LinkedBlockingQueue<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"intent-long")
            .nanoClock(nanos::get).npcInteract((id,index,identity,name,verb,within,context) -> {
                CompletableFuture<Map<String,Object>> pulse = new CompletableFuture<>();
                pulses.add(pulse);
                return pulse;
            }).build())
        {
            compile(host,"LongIntent","Automation.intent(\"slow\",()->{for(int pulse=0;pulse<3;pulse++)" +
                npc(1,"Bank",60_000) + ";return null;});Automation.finish(" + behavior() + ");");
            host.start("LongIntent").get(5,TimeUnit.SECONDS);
            CompletableFuture<Map<String,Object>> first = pulses.poll(5,TimeUnit.SECONDS);
            assertNotNull(first);
            nanos.set(TimeUnit.MILLISECONDS.toNanos(30_000));
            first.complete(Map.of("status","dispatched"));
            CompletableFuture<Map<String,Object>> second = pulses.poll(5,TimeUnit.SECONDS);
            assertNotNull(second);
            assertFalse(host.getRecentLogs().contains("INTENT_LONG"));
            nanos.set(TimeUnit.MILLISECONDS.toNanos(30_001));
            second.complete(Map.of("status","dispatched"));
            CompletableFuture<Map<String,Object>> third = pulses.poll(5,TimeUnit.SECONDS);
            assertNotNull(third);
            assertTrue(host.getRecentLogs().contains("INTENT_LONG"));
            assertFalse(third.isDone());
            nanos.set(TimeUnit.SECONDS.toNanos(36));
            third.complete(Map.of("status","dispatched"));
            GenericClientTestSupport.waitForStatus(host,"COMPLETED");
            assertEquals(1,host.getRecentLogs().lines().filter(line -> line.contains("INTENT_LONG")).count());
            Map<?,?> last = (Map<?,?>)result(host).get("last_intent");
            assertEquals("complete",last.get("status"));
            assertEquals(36_000L,((Number)last.get("elapsed_millis")).longValue());
        }
    }

    @Test public void nestedErrorsUnwindAndLaterDiagnosticsStartWithAFreshScope() throws Exception
    {
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary, "intent-error").build())
        {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> host.evaluate(
                "return ScriptScope.current().intent(\"outer\",()->ScriptScope.current().intent(\"inner\",()->{" +
                "throw new IllegalArgumentException(\"intent exploded\");}));").get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("intent exploded"));
            Map<String, Object> next = host.evaluate("Object before=" + behavior() + ";" +
                "try {ScriptScope.current().intent(\"caught\",()->{throw new IllegalStateException(\"caught\");});}" +
                "catch(IllegalStateException expected){log(expected.getMessage());}" +
                "Object caught=" + behavior() + "; Object nil=ScriptScope.current().intent(\"fresh\",()->null);" +
                "return Map.of(\"before\",before,\"caught\",caught,\"values\",Arrays.asList(nil,42,nil),\"after\"," + behavior() + ");")
                .get(10, TimeUnit.SECONDS);
            Map<?, ?> value = (Map<?, ?>) next.get("value");
            assertEquals("none", ((Map<?, ?>) value.get("before")).get("intent"));
            Map<?, ?> caught = (Map<?, ?>) value.get("caught");
            assertEquals("none", caught.get("intent"));
            assertEquals("failed", ((Map<?, ?>) caught.get("last_intent")).get("status"));
            assertEquals(java.util.Arrays.asList(null, 42, null), value.get("values"));
            Map<?, ?> after = (Map<?, ?>) value.get("after");
            assertEquals("none", after.get("intent"));
            assertEquals("fresh", ((Map<?, ?>) after.get("last_intent")).get("intent"));
        }
    }

    @Test public void aBreakEndingDuringEmergencyPauseDefersIntentEntryUntilResume() throws Exception
    {
        Path root = temporary.newFolder("intent-before-pause").toPath();
        Path directory = root.resolve("behavior");
        GenericClientBehaviorState state = new GenericClientBehaviorState(
            GenericClientBehaviorProfile.fromAccountHash(1L).getId(), 100.0, 1.0);
        long now = System.currentTimeMillis();
        state.startBreak("micro", "none", now, now + 120_000L);
        new GenericClientBehaviorStore(directory).save(state, now);
        List<GenericClientActivityContext> inputs = new CopyOnWriteArrayList<>();
        try (GenericClientBehaviorController behavior = GenericClientTestSupport.behavior(directory);
            GenericClientScriptHost host = GenericClientTestSupport.scriptHost(root.resolve("scripts"), behavior)
                .npcInteract((id, index, identity, name, action, within, context) -> {
                    inputs.add(context);
                    return CompletableFuture.completedFuture(Map.of("status", "dispatched"));
                }).build())
        {
            compile(host, "DeferredIntent", "Automation.finish(Automation.intent(\"waiting\",()->" + npc(1, "Bank", 5000) + "));");
            host.start("DeferredIntent").get(5, TimeUnit.SECONDS);
            GenericClientScriptHostTest.await(() -> "waiting".equals(
                ((Map<?, ?>) host.read("behavior", Map.of())).get("intent")));
            host.pauseForEmergency("food").get(2, TimeUnit.SECONDS);
            behavior.endActiveBreak().get(2, TimeUnit.SECONDS);
            assertTrue(inputs.isEmpty());
            assertFalse(host.getRecentLogs().contains("INTENT_STARTED"));
            host.resumeAfterEmergency("food").get(2, TimeUnit.SECONDS);
            GenericClientTestSupport.waitForStatus(host, "COMPLETED");
            assertEquals(1, inputs.size());
            assertEquals("dispatched", result(host).get("status"));
        }
    }

    @Test public void independentPausesPreserveTheScopeThroughActionAndPhaseWaits() throws Exception
    {
        CountDownLatch dispatched = new CountDownLatch(1);
        CompletableFuture<Map<String, Object>> opened = new CompletableFuture<>();
        AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary, "intent-pause")
            .npcInteract((id, index, identity, name, action, within, context) -> {
                input.set(context);
                dispatched.countDown();
                return opened;
            }).build())
        {
            compile(host, "PauseIntent", "Object value=Automation.intent(\"bank\",()->{" +
                "Map<String,Object> opened=" + npc(1, "Bank", 1000) + ";" +
                "Automation.phase(\"bank.opened\"); ScriptScope.current().sleep(50);" +
                "return Map.of(\"opened\",opened,\"behavior\"," + behavior() + ");}); Automation.finish(value);");
            host.start("PauseIntent").get(5, TimeUnit.SECONDS);
            assertTrue(dispatched.await(2, TimeUnit.SECONDS));
            host.pauseForManualInput("mouse").get(2, TimeUnit.SECONDS);
            host.pauseForEmergency("food").get(2, TimeUnit.SECONDS);
            assertFalse(input.get().isInputAllowed());
            opened.complete(Map.of("status", "dispatched"));
            Thread.sleep(150);
            assertEquals("starting", host.getScriptState());
            host.resumeAfterEmergency("food").get(2, TimeUnit.SECONDS);
            assertEquals(true, host.controlState().get("paused"));
            assertEquals("starting", host.getScriptState());
            host.resumeAfterManualInput("mouse").get(2, TimeUnit.SECONDS);
            GenericClientTestSupport.waitForStatus(host, "COMPLETED");
            Map<?, ?> result = result(host);
            assertEquals("dispatched", ((Map<?, ?>) result.get("opened")).get("status"));
            assertEquals("bank", ((Map<?, ?>) result.get("behavior")).get("intent"));
            assertEquals("bank.opened", host.getScriptState());
        }
    }

    @Test public void nestedIntentsRetainReturnValuesAndSafetyWithOneOuterBoundary() throws Exception
    {
        List<GenericClientActivityContext> inputs = new CopyOnWriteArrayList<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary, "nested-intent")
            .npcInteract((id, index, identity, name, action, within, context) -> {
                inputs.add(context);
                return CompletableFuture.completedFuture(Map.of("status", "dispatched"));
            }).build())
        {
            String body = "Automation.activity(\"travel\");" +
                "Object value = Automation.intent(\"bank\", () -> {" +
                "Map<String,Object> opened = " + npc(1, "Bank", 5000) + ";" +
                "Map<String,Object> nested = Automation.intent(\"withdraw\", () -> {" +
                "Automation.activity(\"hazardous_travel\");" +
                "Map<String,Object> selected = " + npc(2, "Attack", 5000) + ";" +
                "return Map.of(\"opened\",opened,\"selected\",selected,\"answer\",42,\"nested\"," + behavior() + ");});" +
                "Map<String,Object> returned=new java.util.LinkedHashMap<>(nested);returned.put(\"outer\"," + behavior() + ");return returned;});" +
                "Automation.finish(Map.of(\"value\",value,\"after\"," + behavior() + "));";
            compile(host, "Nested", body);
            host.start("Nested").get(5, TimeUnit.SECONDS);
            GenericClientTestSupport.waitForStatus(host, "COMPLETED");
            Map<?, ?> result = result(host);
            Map<?, ?> value = (Map<?, ?>) result.get("value");
            assertEquals(42, value.get("answer"));
            assertEquals("bank", ((Map<?, ?>) value.get("opened")).get("intent"));
            assertEquals("bank", ((Map<?, ?>) value.get("selected")).get("intent"));
            assertEquals("bank", ((Map<?, ?>) value.get("nested")).get("intent"));
            assertEquals(2, ((Map<?, ?>) value.get("nested")).get("intent_depth"));
            assertEquals("bank", ((Map<?, ?>) value.get("outer")).get("intent"));
            assertEquals(1, ((Map<?, ?>) value.get("outer")).get("intent_depth"));
            Map<?, ?> after = (Map<?, ?>) result.get("after");
            assertEquals("none", after.get("intent"));
            assertEquals(0, after.get("intent_depth"));
            Map<?, ?> last = (Map<?, ?>) after.get("last_intent");
            assertEquals("bank", last.get("intent"));
            assertEquals("complete", last.get("status"));
            assertTrue(last.containsKey("behavior_before"));
            assertTrue(last.containsKey("behavior_after"));
            assertEquals(2, inputs.size());
            for (GenericClientActivityContext input : inputs)
            {
                assertFalse(input.allowsBreaks());
                assertFalse(input.allowsCursorRelease());
            }
            assertEquals(GenericClientBehaviorPolicy.PrayerOwner.GUARD, inputs.get(1).policy().prayerOwner);
            assertTrue(inputs.get(1).refreshesWalkClicks());
            assertEquals(180, inputs.get(1).mouseMoveDurationMillis(500));
            assertEquals(1, host.getRecentLogs().lines().filter(line -> line.contains("INTENT_STARTED")).count());
            assertEquals(1, host.getRecentLogs().lines().filter(line -> line.contains("INTENT_ENDED")).count());
        }
    }

    private static String npc(int id, String action, int timeout)
    {
        return "ScriptScope.current().execute(\"npc.interact\",Map.of(\"id\"," + id + ",\"action\",\"" + action + "\")," + timeout + ")";
    }

    private static String behavior() { return "ScriptScope.current().read(\"behavior\",Map.of())"; }

    private static void compile(GenericClientScriptHost host, String name, String body) throws Exception
    {
        host.compile(name, GenericClientTestSupport.javaScript(name, "", "public int onLoop(){" + body + "return -1;}"))
            .get(10, TimeUnit.SECONDS);
    }

    private static Map<?, ?> result(GenericClientScriptHost host)
    {
        return (Map<?, ?>) host.getActiveScriptView().toMap().get("result");
    }
}
