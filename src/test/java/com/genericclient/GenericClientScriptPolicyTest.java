package com.genericclient;

import static org.junit.Assert.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientScriptPolicyTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void retiredOperationFlagsFailBeforeInputAndCannotReplaceTheCurrentPhase() throws Exception
    {
        java.util.List<String> phases = new java.util.ArrayList<>();
        AtomicReference<GenericClientScriptHost> current = new AtomicReference<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"retired-flags")
            .questAction((type,arguments,context) -> {
                assertEquals("No native input may be issued for invalid options", "test.inspect",type);
                phases.add(current.get().getScriptState());
                return CompletableFuture.completedFuture(Map.of("status","complete"));
            }).build())
        {
            current.set(host);
            assertEquals(3,host.evaluate("Automation.activity(\"manual\");Automation.phase(\"valid\");int rejected=0;"+
                "for(Runnable action:List.<Runnable>of("+
                "()->Automation.phase(\"invalid\",Map.of(\"breaks\",false)),"+
                "()->Automation.sleepTicks(0,Map.of(\"breaks\",false)),"+
                "()->ScriptScope.current().execute(\"ui.close\",Map.of(\"breaks\",false),1000))){"+
                "try{action.run();throw new AssertionError(\"Retired behavior flag was accepted\");}"+
                "catch(IllegalArgumentException expected){rejected++;}"+
                "ScriptScope.current().execute(\"test.inspect\",Map.of(),1000);}return rejected;")
                .get(5,TimeUnit.SECONDS).get("value"));
            assertEquals(java.util.List.of("valid","valid","valid"),phases);
        }
    }

    @Test public void activeTimeUsesTheStandaloneDeclarationAcrossOperationOverrides() throws Exception
    {
        CompletableFuture<Void> entered = new CompletableFuture<>();
        CompletableFuture<GenericClientInteractionResult> action = new CompletableFuture<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"clock-owner")
            .walkRandom(context -> { entered.complete(null); return action; }).build())
        {
            host.compile("ClockOwner",GenericClientTestSupport.javaScript("ClockOwner","",
                "public int onLoop(){Automation.activity(\"skilling\");" +
                action("walk.random","Map.of(\"activity\",\"manual\")") + ";return -1;}"))
                .get(5,TimeUnit.SECONDS);
            host.start("ClockOwner").get(5,TimeUnit.SECONDS);
            entered.get(5,TimeUnit.SECONDS);
            assertEquals(GenericClientActivityContext.Activity.MANUAL,host.getBehaviorContext().getActivity());
            assertEquals(GenericClientActivityContext.Activity.SKILLING,host.ownedBehaviorContext().getActivity());
            assertTrue(host.ownedBehaviorContext().isInputAllowed());
            host.pauseForManualInput("mouse").get(5,TimeUnit.SECONDS);
            assertFalse(host.ownedBehaviorContext().isInputAllowed());
            host.resumeAfterManualInput("idle").get(5,TimeUnit.SECONDS);
            assertTrue(host.ownedBehaviorContext().isInputAllowed());
            assertThrows(ExecutionException.class,() -> host.evaluate("return 1;").get(5,TimeUnit.SECONDS));
            assertEquals(GenericClientActivityContext.Activity.SKILLING,host.ownedBehaviorContext().getActivity());
            action.complete(GenericClientTestSupport.interaction("walked"));
            GenericClientTestSupport.waitForStatus(host,"COMPLETED");
            assertEquals(GenericClientActivityContext.Activity.MANUAL,host.ownedBehaviorContext().getActivity());
        }
    }

    @Test public void diagnosticsCannotAccrueStandaloneActiveTime() throws Exception
    {
        CompletableFuture<Void> entered = new CompletableFuture<>();
        CompletableFuture<GenericClientInteractionResult> action = new CompletableFuture<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"operator-clock")
            .walkRandom(context -> { entered.complete(null); return action; }).build())
        {
            CompletableFuture<Map<String,Object>> evaluation = host.evaluate(
                "com.genericclient.script.Automation.activity(\"skilling\"); return " + action("walk.random","Map.of()") + ";");
            entered.get(5,TimeUnit.SECONDS);
            assertEquals(GenericClientActivityContext.Activity.SKILLING,host.getBehaviorContext().getActivity());
            assertEquals(GenericClientActivityContext.Activity.MANUAL,host.ownedBehaviorContext().getActivity());
            action.complete(GenericClientTestSupport.interaction("walked"));
            evaluation.get(5,TimeUnit.SECONDS);
        }
    }

    @Test public void combatCanEnableBreaksWithoutChangingItsMouseOrPrayerOwner() throws Exception
    {
        AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
        try (GenericClientScriptHost host = host(input))
        {
            Map<?, ?> receipt = evaluate(host, "Automation.activity(\"combat\",Map.of(\"breaks\",true)); return " +
                action("combat.set_style", "Map.of(\"style\",0,\"humanize\",true)") + ";");
            assertEquals("ready", ((Map<?, ?>) receipt.get("behavior_before")).get("status"));
            assertTrue(input.get().allowsBreaks());
            assertEquals(GenericClientBehaviorPolicy.PrayerOwner.GUARD, input.get().policy().prayerOwner);
            assertFalse(input.get().allowsCursorRelease());
            assertEquals(180, input.get().mouseMoveDurationMillis(550));
        }
    }

    @Test public void plainOperatorInputRetainsHazardousRoutePolicy() throws Exception
    {
        AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
        try (GenericClientScriptHost host = host(input))
        {
            evaluate(host, "Automation.activity(\"hazardous_travel\"); return " + action("walk.random", "Map.of()") + ";");
            assertTrue(input.get().refreshesWalkClicks());
            assertEquals(GenericClientBehaviorPolicy.PrayerOwner.GUARD, input.get().policy().prayerOwner);
            assertEquals(550, input.get().mouseMoveDurationMillis(550));
            assertFalse(input.get().allowsBreaks());
            assertFalse(input.get().allowsCursorRelease());
        }
    }

    @Test public void declaredActivityWinsOverDefaultsAndCanLeaveHazardousTravel() throws Exception
    {
        AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
        try (GenericClientScriptHost host = host(input))
        {
            evaluate(host, "return " + action("combat.set_style", "Map.of(\"style\",0,\"humanize\",true)") + ";");
            assertEquals(GenericClientActivityContext.Activity.COMBAT, input.get().getActivity());
            evaluate(host, "Automation.activity(\"general\"); return " +
                action("combat.set_style", "Map.of(\"style\",0,\"humanize\",true)") + ";");
            assertEquals(GenericClientActivityContext.Activity.GENERAL, input.get().getActivity());
            assertTrue(input.get().allowsBreaks());
            assertEquals(550, input.get().mouseMoveDurationMillis(550));
            evaluate(host, "Automation.activity(\"hazardous_travel\"); Automation.activity(\"combat\"); return " +
                action("walk.random", "Map.of(\"humanize\",true)") + ";");
            assertEquals(GenericClientActivityContext.Activity.COMBAT, input.get().getActivity());
            assertFalse(input.get().refreshesWalkClicks());
        }
    }

    @Test public void operationPolicyOverridesEveryFieldWithoutLeaking() throws Exception
    {
        AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
        try (GenericClientScriptHost host = host(input))
        {
            Map<?, ?> value = evaluate(host,
                "Automation.activity(\"skilling\", Map.of(\"breaks\",false,\"mouse\",\"fast\",\"prayer_owner\",\"guard\"," +
                "\"cursor_release\",\"none\",\"damage_expected\",true,\"walk_refresh\",true,\"fidget\",\"drift\"));" +
                "Map<?,?> first=" + action("walk.random", "Map.of(\"humanize\",true,\"policy\",Map.of(\"breaks\",true,\"mouse\",\"natural\"," +
                "\"prayer_owner\",\"script\",\"cursor_release\",\"with_break\",\"damage_expected\",false,\"walk_refresh\",false,\"fidget\",\"full\"))") + ";" +
                "Map<?,?> second=" + action("walk.random", "Map.of(\"humanize\",true)") + ";" +
                "return Map.of(\"first\",((Map<?,?>)first.get(\"behavior_before\")).get(\"policy\")," +
                "\"second\",((Map<?,?>)second.get(\"behavior_before\")).get(\"policy\"));");
            assertEquals(Map.of("breaks",true,"mouse","natural","prayer_owner","script","cursor_release","with_break",
                "damage_expected",false,"walk_refresh",false,"fidget","full"), value.get("first"));
            assertEquals(Map.of("breaks",false,"mouse","fast","prayer_owner","guard","cursor_release","none",
                "damage_expected",true,"walk_refresh",true,"fidget","drift"), value.get("second"));
        }
    }

    @Test public void phasesAndJourneysForwardOverridesAndIntentsPreserveFastInput() throws Exception
    {
        AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
        try (GenericClientScriptHost host = host(input))
        {
            Map<?, ?> phase = evaluate(host, "return Automation.phase(\"test\",Map.of(\"humanize\",true,\"policy\",Map.of(\"breaks\",false,\"mouse\",\"fast\")));");
            assertEquals("fast", ((Map<?, ?>) phase.get("policy")).get("mouse"));
            assertEquals(false, ((Map<?, ?>) phase.get("policy")).get("breaks"));
            evaluate(host, "return " + action("walk.to", "Map.of(\"destination\",Map.of(\"x\",3201,\"y\",3200,\"plane\",0)," +
                "\"activity\",\"hazardous_travel\",\"humanize\",true,\"policy\",Map.of(\"walk_refresh\",false,\"mouse\",\"natural\"))") + ";");
            assertFalse(input.get().refreshesWalkClicks());
            assertEquals(550, input.get().mouseMoveDurationMillis(550));
            evaluate(host, "Automation.activity(\"combat\",Map.of(\"breaks\",true)); return Automation.intent(\"setup\",()->" +
                action("combat.set_style", "Map.of(\"style\",0,\"humanize\",true)") + ");");
            assertFalse(input.get().allowsBreaks());
            assertEquals(180, input.get().mouseMoveDurationMillis(550));
        }
    }

    @Test public void invalidPolicyCannotDispatchOrReplaceTheDeclaredActivity() throws Exception
    {
        AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
        try (GenericClientScriptHost host = host(input))
        {
            for (String policy : new String[]{"Map.of(\"breaks\",\"yes\")", "Map.of(\"cursor_release\",false)",
                "Map.of(\"mouse\",\"warp\")", "Map.of(\"typo\",true)", "false"})
            {
                ExecutionException error = assertThrows(ExecutionException.class, () ->
                    evaluate(host, "return " + action("walk.random", "Map.of(\"policy\"," + policy + ")") + ";"));
                assertTrue(error.getMessage().contains("policy"));
                assertNull(input.get());
            }
            Map<?, ?> retained = evaluate(host, "Automation.activity(\"skilling\");" +
                "try{Automation.activity(\"combat\",Map.of(\"prayer_owner\",\"nobody\"));throw new AssertionError();}" +
                "catch(IllegalArgumentException expected){} return " + action("walk.random", "Map.of(\"humanize\",true)") + ";");
            assertNotNull(retained);
            assertEquals(GenericClientActivityContext.Activity.SKILLING, input.get().getActivity());
        }
    }

    private GenericClientScriptHost host(AtomicReference<GenericClientActivityContext> input) throws Exception
    {
        GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"policy")
            .combatMode((mode,context) -> { input.set(context); return CompletableFuture.completedFuture(Map.of("status","set")); })
            .walkRandom(context -> { input.set(context); return CompletableFuture.completedFuture(GenericClientTestSupport.interaction("walked")); })
            .walkTo((request,clicks) -> { input.set(request.activityContext); return CompletableFuture.completedFuture(Map.of("status","arrived")); })
            .build();
        host.publishGameTick(GenericClientDamageTrackerTest.snapshot(1,80,0));
        return host;
    }

    private static Map<?, ?> evaluate(GenericClientScriptHost host, String code) throws Exception
    {
        return (Map<?, ?>) host.evaluate(code)
            .get(5,TimeUnit.SECONDS).get("value");
    }

    private static String action(String type, String arguments)
    {
        return "ScriptScope.current().execute(\"" + type + "\"," + arguments + ",5000)";
    }
}
