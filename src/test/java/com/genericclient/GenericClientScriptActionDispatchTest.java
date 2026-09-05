package com.genericclient;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientScriptActionDispatchTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void aNativeTimeoutRevokesOnlyTheExpiredAction() throws Exception
    {
        CompletableFuture<Map<String, Object>> expired = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> next = new CompletableFuture<>();
        CountDownLatch nextStarted = new CountDownLatch(1);
        List<GenericClientActivityContext> inputs = new CopyOnWriteArrayList<>();
        List<String> cancellations = new CopyOnWriteArrayList<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary, "action-timeout")
            .cancel(cancellations::add).npcInteract((id, index, identity, name, action, within, context) -> {
                inputs.add(context);
                if (id == 1) return expired;
                nextStarted.countDown();
                return next;
            }).build())
        {
            host.compile("Timeout", GenericClientTestSupport.javaScript("Timeout", "",
                "public int onLoop() {" +
                "Map<String,Object> expired=ScriptScope.current().execute(\"npc.interact\",Map.of(\"id\",1,\"action\",\"Bank\"),150);" +
                "Map<String,Object> next=ScriptScope.current().execute(\"npc.interact\",Map.of(\"id\",2,\"action\",\"Bank\"),5000);" +
                "Automation.finish(Map.of(\"expired\",expired,\"next\",next)); return -1;}"))
                .get(10, TimeUnit.SECONDS);
            host.start("Timeout").get(5, TimeUnit.SECONDS);
            assertTrue("The script must continue after its first action times out", nextStarted.await(2, TimeUnit.SECONDS));
            assertFalse(inputs.get(0).isInputAllowed());
            assertTrue(inputs.get(1).isInputAllowed());
            assertEquals(List.of("action_timeout"), cancellations);
            expired.complete(Map.of("status", "dispatched"));
            assertTrue(inputs.get(1).isInputAllowed());
            assertEquals(List.of("action_timeout"), cancellations);
            next.complete(Map.of("status", "dispatched"));
            GenericClientTestSupport.waitForStatus(host, "COMPLETED");
            Map<?, ?> result = (Map<?, ?>) host.getActiveScriptView().toMap().get("result");
            assertEquals("timed_out", ((Map<?, ?>) result.get("expired")).get("status"));
            assertEquals("dispatched", ((Map<?, ?>) result.get("next")).get("status"));
        }
    }

    @Test public void preservesExactNpcTargetAndManualInputPolicy() throws Exception
    {
        AtomicReference<String> request = new AtomicReference<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary, "npc")
            .npcInteract((id, index, identity, name, action, within, context) -> {
                request.set(id + ":" + index + ":" + name + ":" + action + ":" + within + ":" + context.allowsBreaks());
                return CompletableFuture.completedFuture(Map.of("status", "dispatched", "result", "menu_action_executed"));
            }).build())
        {
            run(host, "Automation.activity(\"manual\"); log(" + action("npc.interact",
                "Map.of(\"id\",3996,\"index\",17,\"name\",\"Witch's experiment\",\"action\",\"Attack\",\"within\",7)") + ");");
            assertEquals("3996:17:Witch's experiment:Attack:7:false", request.get());
            assertTrue(host.getRecentLogs().contains("menu_action_executed"));
        }
    }

    @Test public void capturesDeclaredActivityAtEachAction() throws Exception
    {
        List<String> observed = new CopyOnWriteArrayList<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary, "activities")
            .walkTo((request, boundary) -> {
                observed.add("walk:" + request.activityContext.getActivity().getValue());
                return CompletableFuture.completedFuture(Map.of("status", "arrived"));
            }).npcInteract((id, index, identity, name, action, within, context) -> {
                observed.add(action + ":" + context.getActivity().getValue());
                return CompletableFuture.completedFuture(Map.of("status", "dispatched"));
            }).questAction((type, arguments, context) -> {
                observed.add(type + ":" + context.getActivity().getValue());
                return CompletableFuture.completedFuture(Map.of("status", "complete"));
            }).build())
        {
            String body = "Automation.activity(\"skilling\");" + action("item.interact", "Map.of(\"id\",1,\"action\",\"Use\")") + ";" +
                action("walk.to", "Map.of(\"destination\",Map.of(\"x\",3200,\"y\",3200,\"plane\",0))") + ";";
            for (String action : List.of("Talk-to", "Attack", "Bank", "Exchange"))
                body += action("npc.interact", "Map.of(\"id\",1,\"action\",\"" + action + "\")") + ";";
            body += action("bank.loadout", "Map.of(\"items\",List.of())") + ";" + action("ge.buy", "Map.of(\"item_id\",1)") + ";";
            run(host, body);
            assertEquals(List.of("item.interact:skilling", "walk:skilling", "Talk-to:skilling", "Attack:skilling",
                "Bank:skilling", "Exchange:skilling", "bank.loadout:skilling", "ge.buy:skilling"), observed);
        }
    }

    @Test public void forwardsItemWidgetAndCombatActionsWithTheirOriginalArguments() throws Exception
    {
        List<String> types = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> arguments = new CopyOnWriteArrayList<>();
        List<Integer> modes = new CopyOnWriteArrayList<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary, "operations")
            .questAction((type, input, context) -> {
                types.add(type); arguments.add(input);
                assertFalse(context.allowsBreaks());
                return CompletableFuture.completedFuture(Map.of("status", "dispatched"));
            }).combatMode((mode, context) -> {
                modes.add(mode); assertFalse(context.allowsBreaks());
                return CompletableFuture.completedFuture(Map.of("status", "set"));
            }).build())
        {
            run(host, "Automation.activity(\"manual\");" +
                action("item.use_on_object", "Map.of(\"item_id\",1985,\"object_id\",2870,\"world\",Map.of(\"x\",2903,\"y\",3466,\"plane\",0))") + ";" +
                action("equipment.interact", "Map.of(\"id\",2560,\"action\",\"Rub\")") + ";" +
                action("ui.click", "Map.of(\"widget_id\",1703941,\"action\",\"Choose\")") + ";" +
                action("combat.set_style", "Map.of(\"style\",3)") + ";" +
                action("combat.set_auto_retaliate", "Map.of(\"enabled\",false)") + ";");
            assertEquals(List.of("item.use_on_object", "equipment.interact", "ui.click"), types);
            assertEquals(1985, arguments.get(0).get("item_id"));
            assertEquals(Map.of("x",2903,"y",3466,"plane",0), arguments.get(0).get("world"));
            assertEquals(Map.of("id",2560,"action","Rub"), arguments.get(1));
            assertEquals(Map.of("widget_id",1703941,"action","Choose"), arguments.get(2));
            assertEquals(List.of(3, 4), modes);
        }
    }

    private static String action(String type, String arguments)
    {
        return "ScriptScope.current().execute(\"" + type + "\"," + arguments + ",5000)";
    }

    private static void run(GenericClientScriptHost host, String body) throws Exception
    {
        host.compile("Dispatch", GenericClientTestSupport.javaScript("Dispatch", "", "public int onLoop() {" + body + "return -1;}"))
            .get(10, TimeUnit.SECONDS);
        host.start("Dispatch").get(5, TimeUnit.SECONDS);
        GenericClientScriptHostTest.await(() -> host.getStatus().equals("COMPLETED"));
    }
}
