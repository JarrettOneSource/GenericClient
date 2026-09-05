package com.genericclient;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientSleepCompatibilityTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String SLEEP="org.dreambot.api.utilities.Sleep.";
    private static final String NPC="org.dreambot.api.methods.interactive.NPCs.closest(123)";

    @Test public void resetsExtendTheDeadlineAndSuccessMustBeConsecutive() throws Exception
    {
        AtomicLong nanos=new AtomicLong(TimeUnit.SECONDS.toNanos(5));
        try (GenericClientScriptHost host=GenericClientTestSupport.scriptHost(temporary,"sleep-reset").nanoClock(nanos::get).build())
        {
            host.publishGameTick(frame(1,0));
            CompletableFuture<Map<String,Object>> result=host.evaluate(
                "long began=ScriptScope.current().activeTimeNanos();boolean ready="+SLEEP+"sleepUntil(()->"+NPC+".getAnimation()==5,"+
                "()->"+NPC+".getAnimation()==7,2000,100,2);return Map.of(\"ready\",ready,\"elapsed\","+
                "java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(ScriptScope.current().activeTimeNanos()-began));");
            polling(host,result,100);
            advance(host,nanos,2,5,6500);
            polling(host,result,100);
            advance(host,nanos,3,7,6700);
            polling(host,result,100);
            advance(host,nanos,4,5,7100);
            polling(host,result,100);
            advance(host,nanos,5,5,7200);
            assertEquals(Map.of("ready",true,"elapsed",2200L),result.get(5,TimeUnit.SECONDS).get("value"));
        }
    }

    @Test public void sleepWhileUsesTheDefaultOrExplicitPollAndStopsWhenTheConditionChanges() throws Exception
    {
        try
        {
            for (boolean explicit:new boolean[]{false,true})
            {
                AtomicLong nanos=new AtomicLong(TimeUnit.SECONDS.toNanos(5));
                try (GenericClientScriptHost host=GenericClientTestSupport.scriptHost(temporary,"sleep-while-"+explicit).nanoClock(nanos::get).build())
                {
                    host.publishGameTick(frame(1,0));
                    int poll=explicit?70:37;
                    CompletableFuture<Map<String,Object>> result=host.evaluate(SLEEP+"setDefaultPoll(37);return "+SLEEP+
                        "sleepWhile(org.dreambot.api.Client::isLoggedIn,2000"+(explicit?",70":"")+");");
                    polling(host,result,poll);
                    host.publishGameTick(new GenericClientSnapshot(2,"LOGIN_SCREEN",240,null,List.of()));
                    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(poll));
                    assertEquals(true,result.get(5,TimeUnit.SECONDS).get("value"));
                }
            }
        }
        finally { org.dreambot.api.utilities.Sleep.setDefaultPoll(50); }
    }

    @Test public void zeroWaitsCheckOnceAndInvalidDurationsCannotStartAWait() throws Exception
    {
        try (GenericClientScriptHost host=GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            host.publishGameTick(frame(1,0));
            assertEquals(List.of(6,true,false,true,false,false,false),host.evaluate(
                "List<Runnable> invalid=List.of(()->"+SLEEP+"sleep(-1),()->"+SLEEP+"sleepTicks(-1),()->"+SLEEP+"setDefaultPoll(0),"+
                "()->"+SLEEP+"sleepUntil(()->false,-1),()->"+SLEEP+"sleepUntil(()->false,1,0),"+
                "()->"+SLEEP+"sleepUntil(()->false,()->false,1,1,0));int rejected=0;"+
                "for(Runnable wait:invalid){try{wait.run();throw new AssertionError();}catch(IllegalArgumentException expected){rejected++;}}"+
                SLEEP+"sleep(0);"+SLEEP+"sleepTicks(0);return List.of(rejected,"+SLEEP+"sleepUntil(()->true,0),"+
                "org.dreambot.api.methods.MethodProvider.sleepUntil(()->false,0),org.dreambot.api.methods.MethodProvider.sleepWhile(()->false,0),"+
                "org.dreambot.api.methods.MethodProvider.sleepWhile(()->true,0),org.dreambot.api.methods.MethodProvider.sleepUntil(()->false,0,1),"+
                SLEEP+"sleepUntil(()->false,()->false,0,1));")
                .get(5,TimeUnit.SECONDS).get("value"));
        }
    }

    @Test public void aRandomSleepHonorsItsInclusiveMinimumAndExclusiveMaximum() throws Exception
    {
        AtomicLong nanos=new AtomicLong(TimeUnit.SECONDS.toNanos(5));
        try (GenericClientScriptHost host=GenericClientTestSupport.scriptHost(temporary,"sleep-range").nanoClock(nanos::get).build())
        {
            CompletableFuture<Map<String,Object>> result=host.evaluate("org.dreambot.api.methods.MethodProvider.sleep(1000,1001);return true;");
            polling(host,result,1000);
            nanos.set(TimeUnit.MILLISECONDS.toNanos(5999));
            assertEquals(1,host.quietMillis(null,0));
            assertFalse(result.isDone());
            nanos.set(TimeUnit.SECONDS.toNanos(6));
            assertEquals(true,result.get(5,TimeUnit.SECONDS).get("value"));
        }
    }

    private static void polling(GenericClientScriptHost host,CompletableFuture<?> result,int millis) throws Exception
    {
        GenericClientScriptHostTest.await(()->host.quietMillis(null,0)==millis || result.isDone());
        assertFalse("Wait returned before its condition and deadline",result.isDone());
    }

    private static void advance(GenericClientScriptHost host,AtomicLong nanos,int tick,int animation,long millis)
    {
        host.publishGameTick(frame(tick,animation));
        nanos.set(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    private static GenericClientSnapshot frame(int tick,int animation)
    {
        return new GenericClientSnapshot(tick,"LOGGED_IN",240,new GenericClientPlayerSnapshot(1L,"Player",3200,3200,0,-1),
            List.of(new GenericClientNpcSnapshot(70L,7,123,"Target",3201,3200,0,1,1,animation,null,List.of("Attack"))));
    }
}
