package com.genericclient;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientContainerCompatibilityTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void queriesDistinguishQuantitySlotsAndContainerOwnership() throws Exception
    {
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            Scene scene = new Scene();
            scene.inventory.add(item(0,995,100,"Coins"));
            scene.inventory.add(item(4,526,1,"Bones"));
            scene.inventory.add(item(9,526,1,"Bones"));
            scene.equipment.add(item(3,1205,1,"Bronze dagger"));
            scene.bank.add(item(7,995,2000,"Coins"));
            host.publishGameTick(scene.frame());
            assertEquals(List.of(3,2,100,2,100,25,false,false,true,true,false,true,1,3,3,true,false,2000,true,7),
                run(host,"Filter<Item> bones=i->i.getId()==526; Filter<Item> stack=i->i.getAmount()>1;" +
                    "return List.of(Inventory.all().size(),Inventory.all(bones).size(),Inventory.count(995)," +
                    "Inventory.count(\"Bones\"),Inventory.count(stack),Inventory.emptySlotCount(),Inventory.isFull(),Inventory.isEmpty()," +
                    "Inventory.contains(999,995),Inventory.contains(\"Missing\",\"Bones\"),Inventory.contains(999),Inventory.contains(bones)," +
                    "Inventory.all(stack.and(bones.negate())).size(),Inventory.all(bones.or(stack)).size()," +
                    "Equipment.getItemInSlot(3).getSlot(),Equipment.contains(999,1205)&&Equipment.contains(\"Bronze dagger\")," +
                    "Equipment.getItemInSlot(0)!=null,Bank.count(995),Bank.contains(999,995),Bank.get(\"Coins\").getSlot());"));
            assertEquals(List.of(true,100,0,true,List.of("Use","Drop"),false),run(host,
                "Item coin=Inventory.get(\"Coins\"); String[] actions=coin.getActions(); actions[0]=\"corrupted\";" +
                "return List.of(coin.isStackable(),coin.getAmount(),coin.getSlot(),coin.hasAction(\"Missing\",\"Use\")," +
                "Arrays.asList(coin.getActions()),coin.hasAction(\"Missing\"));"));
            scene.inventory.clear();
            host.publishGameTick(scene.frame());
            assertEquals(List.of(true,false,28,false,false),run(host,
                "return List.of(Inventory.isEmpty(),Inventory.isFull(),Inventory.emptySlotCount()," +
                "Inventory.interact(995,\"Use\"),Inventory.interact(\"Coins\",\"Use\"));"));
            for (int slot=0;slot<28;slot++) scene.inventory.add(item(slot,526,1,"Bones"));
            host.publishGameTick(scene.frame());
            assertEquals(List.of(true,0,28),run(host,
                "return List.of(Inventory.isFull(),Inventory.emptySlotCount(),Inventory.count(526));"));
        }
    }

    @Test public void itemInputRetainsItsSlotAndStopsWhenTheObservedItemDisappears() throws Exception
    {
        Scene scene = new Scene();
        scene.inventory.add(item(6,995,100,"Coins"));
        scene.inventory.add(item(8,526,1,"Bones"));
        scene.equipment.add(item(3,1205,1,"Bronze dagger"));
        scene.bank.add(item(6,995,1000,"Coins"));
        List<Map<String,Object>> actions = new ArrayList<>();
        AtomicReference<GenericClientScriptHost> current = new AtomicReference<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"item-lifetime")
            .questAction((type,arguments,context) -> {
                actions.add(Map.of("type",type,"arguments",arguments));
                if (type.equals("item.use_on_item"))
                {
                    scene.inventory.clear();
                    scene.inventory.add(item(6,526,1,"Bones"));
                    current.get().publishGameTick(scene.frame());
                }
                return CompletableFuture.completedFuture(Map.of("status","dispatched"));
            }).build())
        {
            current.set(host);
            host.publishGameTick(scene.frame());
            assertEquals(List.of(true,true,true,true,false,false,false,false),run(host,
                "Item coins=Inventory.get(995); Item bones=Inventory.get(i->i.getId()==526);" +
                "boolean first=Inventory.interact(\"Coins\",\"Use\"); boolean second=Inventory.interact(995,\"Use\");" +
                "boolean removed=Equipment.getItemInSlot(3).interact(\"Remove\");" +
                "try{Bank.get(995).interact(\"Withdraw-All\");throw new AssertionError();}catch(IllegalStateException expected){}" +
                "boolean combined=coins.useOn(bones); return List.of(first,second,removed,combined," +
                "coins.exists(),coins.interact(\"Drop\"),coins.useOn(bones),bones.exists());"));
            assertEquals(List.of(
                Map.of("type","item.interact","arguments",Map.of("id",995,"slot",6,"action","Use")),
                Map.of("type","item.interact","arguments",Map.of("id",995,"slot",6,"action","Use")),
                Map.of("type","equipment.interact","arguments",Map.of("id",1205,"action","Remove")),
                Map.of("type","item.use_on_item","arguments",Map.of("item_id",995,"slot",6,"target_item_id",526,"target_slot",8))),actions);
        }
    }

    @Test public void bankTransfersUseObservedOpenStateAndPreserveRequestedQuantities() throws Exception
    {
        Scene scene = new Scene();
        scene.inventory.add(item(0,995,100,"Coins"));
        scene.bank.add(item(7,995,2000,"Coins"));
        List<Map<String,Object>> actions = new ArrayList<>();
        AtomicReference<GenericClientScriptHost> current = new AtomicReference<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"bank-transfers")
            .questAction((type,arguments,context) -> {
                assertTrue(scene.bankOpen);
                actions.add(Map.of("type",type,"arguments",arguments));
                if (type.equals("bank.close")) scene.bankOpen=false;
                if (type.equals("bank.deposit_inventory")) scene.inventory.clear();
                current.get().publishGameTick(scene.frame());
                return CompletableFuture.completedFuture(Map.of("status","complete"));
            }).build())
        {
            current.set(host);
            host.publishGameTick(scene.frame());
            assertEquals(List.of(false,false,false,false,false,false,true,false),run(host,
                "return List.of(Bank.withdraw(995,5),Bank.deposit(995,5),Bank.withdrawAll(995),Bank.depositAll(995)," +
                "Bank.depositAllItems(),Bank.depositAllEquipment(),Bank.close(),Bank.open());"));
            assertTrue(actions.isEmpty());
            scene.bankOpen=true;
            host.publishGameTick(scene.frame());
            assertEquals(List.of(true,true,true,true,true,true,true,true,false,true,true,false),run(host,
                "return List.of(Bank.open(),Bank.withdraw(995,5),Bank.withdraw(\"Coins\",10),Bank.withdrawAll(995)," +
                "Bank.deposit(995,7),Bank.depositAll(995),Bank.depositAllItems(),Bank.depositAllItems()," +
                "Bank.withdraw(\"Missing\",1),Bank.depositAllEquipment(),Bank.close(),Bank.isOpen());"));
            assertEquals(List.of(
                Map.of("type","bank.withdraw","arguments",Map.of("id",995,"quantity",5,"all",false)),
                Map.of("type","bank.withdraw","arguments",Map.of("id",995,"quantity",10,"all",false)),
                Map.of("type","bank.withdraw","arguments",Map.of("id",995,"quantity",1,"all",true)),
                Map.of("type","bank.deposit","arguments",Map.of("id",995,"quantity",7,"all",false)),
                Map.of("type","bank.deposit","arguments",Map.of("id",995,"quantity",1,"all",true)),
                Map.of("type","bank.deposit_inventory","arguments",Map.of()),
                Map.of("type","bank.deposit_equipment","arguments",Map.of()),
                Map.of("type","bank.close","arguments",Map.of())),actions);
        }
    }

    @Test public void bankOpeningUsesABankerOrTheObservedBoothAction() throws Exception
    {
        for (String target : List.of("banker","Bank","Use","chest","rejected"))
        {
            Scene scene = new Scene();
            scene.npcs=List.of(
                new GenericClientNpcSnapshot(69L,6,122,"Merchant",3200,3200,0,0,1,-1,null,List.of("Trade")),
                new GenericClientNpcSnapshot(70L,7,123,"Banker",3201,3200,0,1,1,-1,null,List.of("Bank")));
            String verb = target.equals("Use") || target.equals("chest") ? "Use" : "Bank";
            scene.objects=List.of(
                new GenericClientQuestSnapshot.ObjectSnapshot(78L,98,"Bank booth","game",3200,3200,0,0,List.of("Examine")),
                new GenericClientQuestSnapshot.ObjectSnapshot(79L,99,"Crate","game",3201,3200,0,1,List.of("Use")),
                new GenericClientQuestSnapshot.ObjectSnapshot(80L,100,target.equals("chest")?"Bank chest":"Bank booth","game",3202,3200,0,2,List.of(verb)));
            AtomicReference<GenericClientScriptHost> current = new AtomicReference<>();
            List<String> actions = new ArrayList<>();
            try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"open-"+target)
                .npcInteract((id,index,identity,name,action,within,context) -> {
                    assertEquals(Long.valueOf(70),identity);
                    assertEquals("Bank",action);
                    actions.add("npc");
                    scene.bankOpen=target.equals("banker");
                    current.get().publishGameTick(scene.frame());
                    return CompletableFuture.completedFuture(Map.of("status",scene.bankOpen?"dispatched":"rejected"));
                })
                .questAction((type,arguments,context) -> {
                    assertEquals("object.interact",type);
                    assertEquals(80L,arguments.get("identity"));
                    assertEquals(verb,arguments.get("action"));
                    actions.add("object");
                    scene.bankOpen=!target.equals("rejected");
                    current.get().publishGameTick(scene.frame());
                    return CompletableFuture.completedFuture(Map.of("status",scene.bankOpen?"dispatched":"rejected"));
                }).build())
            {
                current.set(host);
                host.publishGameTick(scene.frame());
                assertEquals(!target.equals("rejected"),run(host,"return Bank.open();"));
                assertEquals(target.equals("banker")?List.of("npc"):List.of("npc","object"),actions);
            }
        }
    }

    @Test public void nonemptyContainersStillRejectUnmatchedItemsAndFilters() throws Exception
    {
        Scene scene = new Scene();
        scene.inventory.add(item(1,1511,1,"Logs"));
        scene.inventory.add(item(4,995,100,"Coins"));
        scene.inventory.add(item(7,526,1,"Bones"));
        scene.equipment.add(item(3,1205,1,"Bronze dagger"));
        scene.bank.add(item(0,1511,12,"Logs"));
        scene.bank.add(item(8,995,2000,"Coins"));
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            host.publishGameTick(scene.frame());
            assertEquals(List.of(995,995,false,false,false,false,2,2,0,1,false,false,2,995,2000,false),run(host,
                "Filter<Item> bones=i->i.getId()==526; Filter<Item> stacks=i->i.isStackable();" +
                "return List.of(Inventory.get(995).getId(),Inventory.get(\"Coins\").getId()," +
                "Inventory.contains(\"Missing\"),Inventory.contains(i->i.getId()==999),Inventory.get(999)!=null,Inventory.get(\"Missing\")!=null," +
                "Inventory.all(bones.negate()).size(),Inventory.all(bones.or(stacks)).size(),Inventory.all(stacks.and(bones)).size(),Equipment.all().size()," +
                "Equipment.contains(999),Equipment.contains(\"Missing\"),Bank.all().size(),Bank.get(995).getId(),Bank.count(995),Bank.contains(999));"));
        }
    }

    @Test public void aDispatchedBankerClickStillRequiresTheBankToOpen() throws Exception
    {
        for (boolean banker : new boolean[]{false,true})
        {
            Scene scene = new Scene();
            if (banker) scene.npcs=List.of(new GenericClientNpcSnapshot(70L,7,123,"Banker",3201,3200,0,1,1,-1,null,List.of("Bank")));
            scene.objects=List.of(new GenericClientQuestSnapshot.ObjectSnapshot(80L,100,"Bank booth","game",3202,3200,0,2,List.of("Bank")));
            java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong(TimeUnit.SECONDS.toNanos(5));
            try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"bank-unverified-"+banker)
                .nanoClock(nanos::get).npcInteract((id,index,identity,name,action,within,context) ->
                    CompletableFuture.completedFuture(Map.of("status","dispatched")))
                .questAction((type,arguments,context) -> CompletableFuture.completedFuture(Map.of("status","dispatched"))).build())
            {
                host.publishGameTick(scene.frame());
                CompletableFuture<Map<String,Object>> result=host.evaluate("return org.dreambot.api.methods.container.impl.bank.Bank.open();");
                GenericClientScriptHostTest.await(() -> host.quietMillis(null,0)==50);
                assertFalse(result.isDone());
                nanos.set(TimeUnit.SECONDS.toNanos(11));
                assertEquals(false,result.get(5,TimeUnit.SECONDS).get("value"));
            }
        }
    }

    @Test public void itemAndSpellOperationsPreserveBothAcceptedAndRejectedNativeResults() throws Exception
    {
        for (boolean accepted : new boolean[]{false,true})
        {
            Scene scene = new Scene();
            scene.inventory.add(item(3,1205,1,"Bronze dagger"));
            scene.inventory.add(item(6,526,1,"Bones"));
            scene.equipment.add(item(3,1205,1,"Bronze dagger"));
            scene.bank.add(item(3,1205,20,"Bronze dagger"));
            scene.bankOpen=true;
            scene.npcs=List.of(new GenericClientNpcSnapshot(70L,7,123,"Banker",3201,3200,0,1,1,-1,null,List.of("Bank")));
            scene.objects=List.of(new GenericClientQuestSnapshot.ObjectSnapshot(80L,100,"Bank booth","game",3202,3200,0,2,List.of("Bank")));
            List<Map<String,Object>> actions = new ArrayList<>();
            try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"input-result-"+accepted)
                .questAction((type,arguments,context) -> {
                    actions.add(Map.of("type",type,"arguments",arguments));
                    return CompletableFuture.completedFuture(Map.of("status",accepted?"complete":"rejected"));
                }).build())
            {
                host.publishGameTick(scene.frame());
                assertEquals(List.of(accepted,accepted,accepted,accepted,accepted,accepted,accepted,accepted,accepted,accepted,accepted,accepted,accepted,accepted,false,false,false),run(host,
                    "Item item=Inventory.get(1205); Item bones=Inventory.get(526); Entity banker=NPCs.closest(123);" +
                    "Entity booth=org.dreambot.api.methods.interactive.GameObjects.closest(100);" +
                    "return List.of(Inventory.interact(1205,\"Drop\"),Inventory.interact(\"Bronze dagger\",\"Drop\"),Equipment.getItemInSlot(3).interact(\"Remove\"),item.useOn(bones)," +
                    "item.useOn(banker),item.useOn(booth),Magic.setAutocastSpell(Normal.WIND_STRIKE),Magic.castSpell(Normal.HOME_TELEPORT)," +
                    "Magic.castSpellOn(Normal.WIND_STRIKE,banker),Magic.castSpellOn(Normal.LOW_LEVEL_ALCHEMY,item),"+
                    "Bank.withdraw(\"Bronze dagger\",5),Bank.depositAllItems(),Bank.depositAllEquipment(),"+
                    "Bank.close(),Bank.get(1205).useOn(bones),item.useOn(Bank.get(1205)),Bank.get(1205).useOn(banker));"));
                assertEquals(List.of("item.interact","item.interact","equipment.interact","item.use_on_item","item.use_on_npc","item.use_on_object",
                    "combat.set_autocast","travel.home_teleport","combat.cast","spell.cast_on_item","bank.withdraw","bank.deposit_inventory","bank.deposit_equipment","bank.close"),
                    actions.stream().map(row->row.get("type")).collect(java.util.stream.Collectors.toList()));
                assertEquals(Map.of("item_id",1205,"slot",3,"entity_identity",70L,"within",32,"npc_id",123,"npc_index",7),actions.get(4).get("arguments"));
            }
        }
    }

    @Test public void itemUseRejectsARetiredPartnerAndARetiredSourceWithoutSelectingAnythingElse() throws Exception
    {
        Scene scene=new Scene();
        scene.inventory.add(item(3,995,100,"Coins"));
        scene.inventory.add(item(6,526,1,"Bones"));
        scene.inventory.add(item(7,526,1,"Bones"));
        scene.npcs=List.of(new GenericClientNpcSnapshot(70L,7,123,"Target",3201,3200,0,1,1,-1,null,List.of("Attack")));
        AtomicReference<GenericClientScriptHost> current=new AtomicReference<>();
        List<Integer> dropped=new ArrayList<>();
        try (GenericClientScriptHost host=GenericClientTestSupport.scriptHost(temporary,"item-use-presence")
            .questAction((type,arguments,context) -> {
                assertEquals("item.interact",type);
                assertEquals("Drop",arguments.get("action"));
                int slot=((Number)arguments.get("slot")).intValue();
                dropped.add(slot);
                scene.inventory.removeIf(item -> ((Number)item.toMap().get("slot")).intValue()==slot);
                current.get().publishGameTick(scene.frame());
                return CompletableFuture.completedFuture(Map.of("status","dispatched"));
            }).build())
        {
            current.set(host);
            host.publishGameTick(scene.frame());
            assertEquals(List.of(true,false,false,true,false),run(host,
                "Item coins=Inventory.get(995);Item bones=Inventory.get(526);Entity target=NPCs.closest(123);"+
                "try{coins.useOn(org.dreambot.api.methods.interactive.Players.getLocal());throw new AssertionError();}catch(UnsupportedOperationException expected){}"+
                "boolean droppedBones=bones.interact(\"Drop\");boolean used=coins.useOn(bones);boolean exists=bones.exists();"+
                "boolean droppedCoins=coins.interact(\"Drop\");return List.of(droppedBones,used,exists,droppedCoins,coins.useOn(target));"));
            assertEquals(List.of(6,3),dropped);
        }
    }

    @Test public void spellsAcceptAnEntityVariableAndKeepTheCapturedNpcIdentity() throws Exception
    {
        Scene scene = new Scene();
        scene.npcs=List.of(new GenericClientNpcSnapshot(77L,5,123,"Target",3201,3200,0,1,1,-1,null,List.of("Attack")));
        List<Map<String,Object>> actions = new ArrayList<>();
        AtomicReference<GenericClientScriptHost> current = new AtomicReference<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"spell-entity")
            .questAction((type,arguments,context) -> {
                actions.add(Map.of("type",type,"arguments",arguments));
                scene.npcs=List.of();
                current.get().publishGameTick(scene.frame());
                return CompletableFuture.completedFuture(Map.of("status","cast"));
            }).build())
        {
            current.set(host);
            host.publishGameTick(scene.frame());
            assertEquals(List.of(true,false,false),run(host,
                "try{Magic.castSpellOn(Normal.WIND_STRIKE,org.dreambot.api.methods.interactive.Players.getLocal());" +
                "throw new AssertionError();}catch(UnsupportedOperationException expected){}" +
                "Entity target=NPCs.closest(123); boolean first=Magic.castSpellOn(Normal.WIND_STRIKE,target);" +
                "return List.of(first,Magic.castSpellOn(Normal.WIND_STRIKE,target),Magic.castSpellOn(Normal.WIND_STRIKE,(Entity)null));"));
            assertEquals(List.of(Map.of("type","combat.cast","arguments",Map.of("spell","wind_strike","npc_id",123,
                "npc_index",5,"entity_identity",77L,"within",32))),actions);
        }
    }

    @Test public void itemSpellsCannotRetargetABankOrEquipmentItemIntoInventory() throws Exception
    {
        Scene scene = new Scene();
        scene.inventory.add(item(3,1205,1,"Bronze dagger"));
        scene.bank.add(item(3,1205,100,"Bronze dagger"));
        scene.equipment.add(item(3,1205,1,"Bronze dagger"));
        List<Map<String,Object>> actions = new ArrayList<>();
        AtomicReference<GenericClientScriptHost> current = new AtomicReference<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"spell-item")
            .questAction((type,arguments,context) -> {
                actions.add(Map.of("type",type,"arguments",arguments));
                if (type.equals("spell.cast_on_item")) scene.inventory.clear();
                current.get().publishGameTick(scene.frame());
                return CompletableFuture.completedFuture(Map.of("status","cast"));
            }).build())
        {
            current.set(host);
            host.publishGameTick(scene.frame());
            assertEquals(List.of(false,false,true,false,false,true,true),run(host,
                "Item ore=Inventory.get(1205);" +
                "boolean bank=Magic.castSpellOn(Normal.LOW_LEVEL_ALCHEMY,Bank.get(1205));" +
                "boolean equipment=Magic.castSpellOn(Normal.LOW_LEVEL_ALCHEMY,Equipment.getItemInSlot(3));" +
                "boolean inventory=Magic.castSpellOn(Normal.LOW_LEVEL_ALCHEMY,ore);" +
                "try{Magic.castSpell(Normal.FIRE_STRIKE);throw new AssertionError();}catch(IllegalArgumentException expected){}" +
                "return List.of(bank,equipment,inventory,Magic.castSpellOn(Normal.LOW_LEVEL_ALCHEMY,ore)," +
                "Magic.castSpellOn(Normal.LOW_LEVEL_ALCHEMY,(Item)null),Magic.setAutocastSpell(Normal.FIRE_STRIKE),Magic.castSpell(Normal.HOME_TELEPORT));"));
            assertEquals(List.of(
                Map.of("type","spell.cast_on_item","arguments",Map.of("spell","low_alchemy","item_id",1205,"slot",3)),
                Map.of("type","combat.set_autocast","arguments",Map.of("spell","fire_strike")),
                Map.of("type","travel.home_teleport","arguments",Map.of())),actions);
        }
    }

    @Test public void loadoutsRequireBothExactObservedQuantitiesAndEnoughFreeSlots() throws Exception
    {
        for (String state : List.of("short","excess","slots","ready","rejected","late"))
        {
            Scene scene = new Scene();
            scene.inventory.add(item(0,995,state.equals("short") || state.equals("late") ? 99 : state.equals("excess") ? 101 : 100,"Coins"));
            if (state.equals("slots")) scene.inventory.add(item(1,526,1,"Bones"));
            java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong(TimeUnit.SECONDS.toNanos(5));
            try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"loadout-"+state)
                .nanoClock(nanos::get).questAction((type,arguments,context) -> {
                    assertEquals("bank.loadout",type);
                    assertEquals(Map.of("items",List.of(Map.of("id",995,"quantity",100)),"minimum_free_slots",27,"close",true),arguments);
                    return CompletableFuture.completedFuture(Map.of("status",state.equals("rejected")?"rejected":"complete"));
                }).build())
            {
                host.publishGameTick(scene.frame());
                CompletableFuture<Map<String,Object>> result = host.evaluate("return com.genericclient.script.Banking.loadout(Map.of(995,100),27,true);");
                if (!state.equals("ready") && !state.equals("rejected"))
                {
                    GenericClientScriptHostTest.await(() -> host.quietMillis(null,0) == 50 || result.isDone());
                    assertFalse("The native receipt did not verify this inventory",result.isDone());
                    if (state.equals("late"))
                    {
                        scene.inventory.clear();
                        scene.inventory.add(item(0,995,100,"Coins"));
                        host.publishGameTick(scene.frame());
                        nanos.set(TimeUnit.MILLISECONDS.toNanos(5050));
                    }
                    else nanos.set(TimeUnit.SECONDS.toNanos(11));
                }
                assertEquals(state,state.equals("ready") || state.equals("late"),result.get(5,TimeUnit.SECONDS).get("value"));
            }
        }
    }

    @Test public void semanticReceiptsKeepTheirSuccessAndFailureMeaningAtTheSdkBoundary() throws Exception
    {
        List<String> statuses = List.of("dispatched","set","unchanged","complete","completed","arrived","cast",
            "rejected","cancelled","timed_out","failed","unavailable");
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"receipt-states")
            .questAction((type,arguments,context) -> {
                assertEquals("test.receipt",type);
                return CompletableFuture.completedFuture(Map.of("status",statuses.get(count.getAndIncrement())));
            }).build())
        {
            assertEquals(List.of(true,true,true,true,true,true,true,false,false,false,false,false),host.evaluate(
                "List<Boolean> results=new ArrayList<>();for(int step=0;step<12;step++)"+
                "results.add(com.genericclient.script.SnapshotData.action(\"test.receipt\",Map.of()));return results;")
                .get(5,TimeUnit.SECONDS).get("value"));
            assertEquals(statuses.size(),count.get());
        }
    }

    private static Object run(GenericClientScriptHost host, String body) throws Exception
    {
        String imports = "import org.dreambot.api.methods.container.impl.Inventory;\n" +
            "import org.dreambot.api.methods.container.impl.bank.Bank;\n" +
            "import org.dreambot.api.methods.container.impl.equipment.Equipment;\n" +
            "import org.dreambot.api.methods.filter.Filter;\nimport org.dreambot.api.methods.magic.*;\n" +
            "import org.dreambot.api.methods.interactive.NPCs;\nimport org.dreambot.api.wrappers.interactive.Entity;\n" +
            "import org.dreambot.api.wrappers.items.Item;\n";
        host.compile("ContainerContract",imports+GenericClientTestSupport.javaScript("ContainerContract","",
            "public int onLoop(){Automation.activity(\"manual\");Automation.finish(inspect());return -1;}" +
            "private Object inspect(){"+body+"}" )).get(5,TimeUnit.SECONDS);
        host.start("ContainerContract").get(5,TimeUnit.SECONDS);
        GenericClientScriptHostTest.await(() -> List.of("COMPLETED","FAULTED","STOPPED").contains(host.getStatus()));
        assertEquals(host.getActiveScriptView().toMap().toString(),"COMPLETED",host.getStatus());
        return host.getActiveScriptView().toMap().get("result");
    }

    private static GenericClientAccountSnapshot.ItemSnapshot item(int slot,int id,int quantity,String name)
    {
        return new GenericClientAccountSnapshot.ItemSnapshot(slot,null,id,quantity,name,id==995,true,true,List.of("Use","Drop"));
    }

    private static final class Scene
    {
        final List<GenericClientAccountSnapshot.ItemSnapshot> inventory=new ArrayList<>();
        final List<GenericClientAccountSnapshot.ItemSnapshot> equipment=new ArrayList<>();
        final List<GenericClientAccountSnapshot.ItemSnapshot> bank=new ArrayList<>();
        List<GenericClientNpcSnapshot> npcs=List.of();
        List<GenericClientQuestSnapshot.ObjectSnapshot> objects=List.of();
        boolean bankOpen;
        long tick;

        GenericClientSnapshot frame()
        {
            return new GenericClientSnapshot(++tick,"LOGGED_IN",240,
                new GenericClientPlayerSnapshot(1L,"Player",3200,3200,0,-1),npcs,
                new GenericClientAccountSnapshot(true,100,List.of(),
                    new GenericClientAccountSnapshot.ContainerSnapshot(true,28,inventory),
                    new GenericClientAccountSnapshot.ContainerSnapshot(true,14,equipment),
                    new GenericClientAccountSnapshot.BankSnapshot(bankOpen?"open":"cached",tick,
                        new GenericClientAccountSnapshot.ContainerSnapshot(true,816,bank))),
                new GenericClientQuestSnapshot(true,new int[0],objects,GenericClientQuestSnapshot.DialogueSnapshot.closed()));
        }
    }
}
