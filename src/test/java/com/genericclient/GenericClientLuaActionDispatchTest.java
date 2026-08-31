package com.genericclient;

import static com.genericclient.GenericClientTestSupport.receipt;
import static com.genericclient.GenericClientTestSupport.script;
import static com.genericclient.GenericClientTestSupport.waitForStatus;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientLuaActionDispatchTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void dispatchesNpcInteractionWithExplicitTargetAndBreakPolicy() throws Exception
	{
		AtomicReference<String> request = new AtomicReference<>();
		GenericClientLuaHost host = GenericClientTestSupport
			.luaHost(temporaryFolder, "npc-action")
			.npcInteract((id, name, action, within, breaks) ->
			{
				request.set(id + ":" + name + ":" + action + ":" + within + ":" +
					breaks.allowsBreaks());
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "dispatched");
				receipt.put("result", "menu_action_executed");
				return CompletableFuture.completedFuture(receipt);
			})
			.build();
		try
		{
			host.saveScript(
				"npc-action",
				"NPC action",
				"Exercise npc.interact dispatch.",
				script(
					"local receipt = gc.await { action = { type = 'npc.interact', " +
					"id = 3996, name = \"Witch's experiment\", action = 'Attack', within = 7 }, " +
					"breaks = false }\n" +
					"gc.log('info', 'npc-result', receipt)"))
				.get(2, TimeUnit.SECONDS);

			host.start("npc-action").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");

			assertEquals("3996:Witch's experiment:Attack:7:false", request.get());
			assertTrue(host.getRecentLogs().contains("npc-result"));
			assertTrue(host.getRecentLogs().contains("menu_action_executed"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void dispatchesQuestActionWithoutMovingQuestFactsIntoJava() throws Exception
	{
		AtomicReference<String> requestedType = new AtomicReference<>();
		AtomicReference<Map<String, Object>> requestedAction = new AtomicReference<>();
		AtomicReference<Boolean> requestedBreaks = new AtomicReference<>();
		GenericClientLuaHost host = GenericClientTestSupport
			.luaHost(temporaryFolder, "quest-action")
			.questAction((type, action, breaks) ->
			{
				requestedType.set(type);
				requestedAction.set(action);
				requestedBreaks.set(breaks.allowsBreaks());
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "dispatched");
				receipt.put("result", "item_used_on_object");
				return CompletableFuture.completedFuture(receipt);
			})
			.build();
		try
		{
			host.saveScript(
				"quest-action",
				"Quest action",
				"Exercise semantic quest action dispatch.",
				script(
					"local receipt = gc.await { action = { type = 'item.use_on_object', " +
					"item_id = 1985, object_id = 2870, " +
					"world = { x = 2903, y = 3466, plane = 0 }, within = 8 }, breaks = false }\n" +
					"gc.log('info', 'quest-action-result', receipt)"))
				.get(2, TimeUnit.SECONDS);

			host.start("quest-action").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");

			assertEquals("item.use_on_object", requestedType.get());
			assertEquals(1985.0, requestedAction.get().get("item_id"));
			assertEquals(2870.0, requestedAction.get().get("object_id"));
			assertEquals(2903.0, ((Map<?, ?>) requestedAction.get().get("world")).get("x"));
			assertEquals(Boolean.FALSE, requestedBreaks.get());
			assertTrue(host.getRecentLogs().contains("item_used_on_object"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void capturesAndInfersAnImmutableActivityForEveryAwaitedAction() throws Exception
	{
		List<String> activities = new CopyOnWriteArrayList<>();
		GenericClientLuaHost host = GenericClientTestSupport
			.luaHost(temporaryFolder, "activity")
			.walkTo((destination, within, timeout, context, useRun) ->
			{
				activities.add("walk:" + context.getActivity().getValue());
				return CompletableFuture.completedFuture(Collections.singletonMap("status", "arrived"));
			})
			.npcInteract((id, name, action, within, context) ->
			{
				activities.add(action + ":" + context.getActivity().getValue());
				return CompletableFuture.completedFuture(Collections.singletonMap("status", "dispatched"));
			})
			.combatMode((mode, context) ->
				CompletableFuture.completedFuture(Collections.singletonMap("status", "set")))
			.questAction((type, action, context) ->
			{
				activities.add(type + ":" + context.getActivity().getValue());
				return CompletableFuture.completedFuture(Collections.singletonMap("status", "complete"));
			})
			.build();
		try
		{
			host.saveScript(
				"activity",
				"Activity",
				"Exercise activity capture and semantic inference.",
				script(
					"gc.activity('skilling')\n" +
					"gc.await { activity = 'banking', action = { type = 'item.interact', id = 1, action = 'Use' } }\n" +
					"gc.await { action = { type = 'item.interact', id = 1, action = 'Use' } }\n" +
					"gc.await { action = { type = 'walk.to', destination = { x = 3200, y = 3200, plane = 0 } } }\n" +
					"gc.await { action = { type = 'npc.interact', id = 1, action = 'Talk-to' } }\n" +
					"gc.await { action = { type = 'npc.interact', id = 1, action = 'Attack' } }\n" +
					"gc.await { action = { type = 'npc.interact', id = 1, action = 'Bank' } }\n" +
					"gc.await { action = { type = 'npc.interact', id = 1, action = 'Exchange' } }\n" +
					"gc.await { action = { type = 'bank.loadout', items = {} } }\n" +
					"gc.await { action = { type = 'ge.buy', item_id = 1, item_name = 'Test', quantity = 1, maximum_unit_price = 1 } }"))
				.get(2, TimeUnit.SECONDS);

			host.start("activity").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");

			assertEquals(Arrays.asList(
				"item.interact:banking",
				"item.interact:skilling",
				"walk:travel",
				"Talk-to:dialogue",
				"Attack:combat",
				"Bank:banking",
				"Exchange:trading",
				"bank.loadout:banking",
				"ge.buy:trading"), activities);
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void dispatchesEquipmentInteractionThroughTheLuaSurface() throws Exception
	{
		AtomicReference<String> requestedType = new AtomicReference<>();
		AtomicReference<Map<String, Object>> requestedAction = new AtomicReference<>();
		GenericClientLuaHost host = GenericClientTestSupport
			.luaHost(temporaryFolder, "equipment-action")
			.questAction((type, action, breaks) ->
			{
				requestedType.set(type);
				requestedAction.set(action);
				return CompletableFuture.completedFuture(receipt("dispatched"));
			})
			.build();
		try
		{
			host.saveScript(
				"equipment-action",
				"Equipment action",
				"Exercise equipped-item interaction dispatch.",
				script("gc.await { action = { type = 'equipment.interact', " +
					"id = 2560, action = 'Rub' }, breaks = false }"))
				.get(2, TimeUnit.SECONDS);

			host.start("equipment-action").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");

			assertEquals("equipment.interact", requestedType.get());
			assertEquals(2560.0, requestedAction.get().get("id"));
			assertEquals("Rub", requestedAction.get().get("action"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void dispatchesWidgetClickThroughTheLuaSurface() throws Exception
	{
		AtomicReference<String> requestedType = new AtomicReference<>();
		AtomicReference<Map<String, Object>> requestedAction = new AtomicReference<>();
		AtomicReference<Boolean> requestedBreaks = new AtomicReference<>();
		GenericClientLuaHost host = GenericClientTestSupport
			.luaHost(temporaryFolder, "widget-action")
			.questAction((type, action, breaks) ->
			{
				requestedType.set(type);
				requestedAction.set(action);
				requestedBreaks.set(breaks.allowsBreaks());
				return CompletableFuture.completedFuture(receipt("dispatched"));
			})
			.build();
		try
		{
			host.saveScript(
				"widget-action",
				"Widget action",
				"Exercise generic widget click dispatch.",
				script("gc.await { action = { type = 'ui.click', widget_id = 1703941 }, " +
					"breaks = false }"))
				.get(2, TimeUnit.SECONDS);

			host.start("widget-action").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");

			assertEquals("ui.click", requestedType.get());
			assertEquals(1703941.0, requestedAction.get().get("widget_id"));
			assertEquals(Boolean.FALSE, requestedBreaks.get());
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void dispatchesCombatStyleSelection() throws Exception
	{
		AtomicReference<String> request = new AtomicReference<>();
		GenericClientLuaHost host = GenericClientTestSupport
			.luaHost(temporaryFolder, "combat-style")
			.combatMode((style, breaks) ->
			{
				request.set(style + ":" + breaks.allowsBreaks());
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "set");
				receipt.put("style_index", (long) style);
				return CompletableFuture.completedFuture(receipt);
			})
			.build();
		try
		{
			host.saveScript(
				"combat-style",
				"Combat style",
				"Exercise combat.set_style dispatch.",
				script(
					"local receipt = gc.await { action = { type = 'combat.set_style', style = 3 }, " +
					"breaks = false }\n" +
					"gc.log('info', 'style-result', receipt)"))
				.get(2, TimeUnit.SECONDS);

			host.start("combat-style").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");

			assertEquals("3:false", request.get());
			assertTrue(host.getRecentLogs().contains("style-result"));
			assertTrue(host.getRecentLogs().contains("style_index=3.0"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void dispatchesAutoRetaliateSelection() throws Exception
	{
		AtomicReference<String> request = new AtomicReference<>();
		GenericClientLuaHost host = GenericClientTestSupport
			.luaHost(temporaryFolder, "auto-retaliate")
			.combatMode((mode, breaks) ->
			{
				request.set(mode + ":" + breaks.allowsBreaks());
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "set");
				receipt.put("enabled", mode == 5);
				return CompletableFuture.completedFuture(receipt);
			})
			.build();
		try
		{
			host.saveScript(
				"auto-retaliate",
				"Auto retaliate",
				"Exercise combat.set_auto_retaliate dispatch.",
				script(
					"local receipt = gc.await { action = { type = 'combat.set_auto_retaliate', " +
					"enabled = false }, breaks = false }\n" +
					"gc.log('info', 'retaliate-result', receipt)"))
				.get(2, TimeUnit.SECONDS);

			host.start("auto-retaliate").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");

			assertEquals("4:false", request.get());
			assertTrue(host.getRecentLogs().contains("retaliate-result"));
			assertTrue(host.getRecentLogs().contains("enabled=false"));
		}
		finally
		{
			host.close();
		}
	}
}
