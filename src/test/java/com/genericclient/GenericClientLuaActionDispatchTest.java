package com.genericclient;

import static com.genericclient.GenericClientTestSupport.luaSnapshot;
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
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.coords.WorldPoint;
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
			host.catalog.saveScript(
				"npc-action",
				"NPC action",
				"Exercise npc.interact dispatch.",
				script(
					"local receipt = gc.await { action = { type = 'npc.interact', " +
					"id = 3996, name = \"Witch's experiment\", action = 'Attack', within = 7 }, " +
					"policy = { breaks = false, cursor_release = 'none', fidget = 'none' } }\n" +
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
			host.catalog.saveScript(
				"quest-action",
				"Quest action",
				"Exercise semantic quest action dispatch.",
				script(
					"local receipt = gc.await { action = { type = 'item.use_on_object', " +
					"item_id = 1985, object_id = 2870, " +
					"world = { x = 2903, y = 3466, plane = 0 }, within = 8 }, policy = { breaks = false, cursor_release = 'none', fidget = 'none' } }\n" +
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
	public void capturesAnImmutableDeclaredActivityForEveryAwaitedAction() throws Exception
	{
		List<String> activities = new CopyOnWriteArrayList<>();
		GenericClientLuaHost host = GenericClientTestSupport
			.luaHost(temporaryFolder, "activity")
			.walkTo((request, clickBoundary) ->
			{
				activities.add("walk:" + request.activityContext.getActivity().getValue());
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
			host.catalog.saveScript(
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
				"walk:skilling",
				"Talk-to:skilling",
				"Attack:skilling",
				"Bank:skilling",
				"Exchange:skilling",
				"bank.loadout:skilling",
				"ge.buy:skilling"), activities);
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
			host.catalog.saveScript(
				"equipment-action",
				"Equipment action",
				"Exercise equipped-item interaction dispatch.",
				script("gc.await { action = { type = 'equipment.interact', " +
					"id = 2560, action = 'Rub' }, policy = { breaks = false, cursor_release = 'none', fidget = 'none' } }"))
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
			host.catalog.saveScript(
				"widget-action",
				"Widget action",
				"Exercise generic widget click dispatch.",
				script("gc.await { action = { type = 'ui.click', widget_id = 1703941 }, " +
					"policy = { breaks = false, cursor_release = 'none', fidget = 'none' } }"))
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
			host.catalog.saveScript(
				"combat-style",
				"Combat style",
				"Exercise combat.set_style dispatch.",
				script(
					"local receipt = gc.await { action = { type = 'combat.set_style', style = 3 }, " +
					"policy = { breaks = false, cursor_release = 'none', fidget = 'none' } }\n" +
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
			host.catalog.saveScript(
				"auto-retaliate",
				"Auto retaliate",
				"Exercise combat.set_auto_retaliate dispatch.",
				script(
					"local receipt = gc.await { action = { type = 'combat.set_auto_retaliate', " +
					"enabled = false }, policy = { breaks = false, cursor_release = 'none', fidget = 'none' } }\n" +
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
	@Test
	public void dispatchesHomeTeleportThroughTheLuaSurface() throws Exception
	{
		AtomicReference<String> requestedType = new AtomicReference<>();
		AtomicReference<Boolean> requestedBreaks = new AtomicReference<>();
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("home-teleport-scripts").toPath(), GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("home-teleport-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap()))
			.npcInteract((id, name, action, within, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()))
			.combatMode((mode, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()))
			.questAction((type, action, breaks) ->
			{
				requestedType.set(type);
				requestedBreaks.set(breaks.allowsBreaks());
				return CompletableFuture.completedFuture(receipt("complete"));
			}).build();
		try
		{
			host.catalog.saveScript(
				"home-teleport",
				"Home teleport",
				"Exercise semantic home teleport dispatch.",
				script("gc.await { action = { type = 'travel.home_teleport' }, " +
					"}"))
				.get(2, TimeUnit.SECONDS);

			host.start("home-teleport").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");

			assertEquals("travel.home_teleport", requestedType.get());
			assertEquals(Boolean.TRUE, requestedBreaks.get());
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void dispatchesGenericPoisonCureThroughLua() throws Exception
	{
		AtomicReference<String> requestedType = new AtomicReference<>();
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("poison-cure-scripts").toPath(), GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("poison-cure-profile").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(
				GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap()))
			.npcInteract((id, name, action, within, breaks) ->
				CompletableFuture.completedFuture(Collections.emptyMap()))
			.combatMode((mode, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()))
			.questAction((type, action, breaks) ->
			{
				requestedType.set(type);
				return CompletableFuture.completedFuture(receipt("complete"));
			}).build();
		try
		{
			host.catalog.saveScript(
				"poison-cure",
				"Poison cure",
				"Exercise the generic poison cure action.",
				script("return gc.await { action = { " +
					"type = 'consumable.cure_poison' }, policy = { breaks = false, cursor_release = 'none', fidget = 'none' } }"))
				.get(2, TimeUnit.SECONDS);

			host.start("poison-cure").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");

			assertEquals("consumable.cure_poison", requestedType.get());
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void dispatchesPerScriptClientBehaviorPolicyThroughLua() throws Exception
	{
		AtomicReference<String> requestedType = new AtomicReference<>();
		AtomicReference<Map<String, Object>> requestedAction = new AtomicReference<>();
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("client-behavior-policy-scripts").toPath(), GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("client-behavior-policy-profile").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(
				GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap()))
			.npcInteract((id, name, action, within, breaks) ->
				CompletableFuture.completedFuture(Collections.emptyMap()))
			.combatMode((mode, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()))
			.questAction((type, action, breaks) ->
			{
				requestedType.set(type);
				requestedAction.set(action);
				return CompletableFuture.completedFuture(receipt("complete"));
			}).build();
		try
		{
			host.catalog.saveScript(
				"client-behaviors",
				"Client behaviors",
				"Exercise per-script GenericClient behavior ownership.",
				script("return gc.await { action = { " +
					"type = 'client.behaviors.configure', " +
					"emergency_consumables = false, emergency_escape = false, " +
					"combat_prayer = false, auto_retaliate = false }, policy = { breaks = false, cursor_release = 'none', fidget = 'none' } }"))
				.get(2, TimeUnit.SECONDS);

			host.start("client-behaviors").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");

			assertEquals("client.behaviors.configure", requestedType.get());
			assertEquals(Boolean.FALSE, requestedAction.get().get("emergency_consumables"));
			assertEquals(Boolean.FALSE, requestedAction.get().get("emergency_escape"));
			assertEquals(Boolean.FALSE, requestedAction.get().get("combat_prayer"));
			assertEquals(Boolean.FALSE, requestedAction.get().get("auto_retaliate"));
		}
		finally
		{
			host.close();
		}
	}



	@Test
	public void externalScriptMapsDestinationAndAvoidTilesIntoTheCoreWalkAction() throws Exception
	{
		AtomicReference<WorldPoint> requestedDestination = new AtomicReference<>();
		AtomicReference<List<WorldPoint>> requestedAvoidTiles = new AtomicReference<>();
		AtomicInteger requestedWithin = new AtomicInteger();
		AtomicInteger requestedTimeout = new AtomicInteger();
		AtomicReference<Boolean> requestedUseRun = new AtomicReference<>();
		AtomicReference<Boolean> requestedInterruptOnDialogue = new AtomicReference<>();
		AtomicInteger offscreenMoves = new AtomicInteger();
		AtomicReference<GenericClientBehaviorProfile.Edge> offscreenEdge = new AtomicReference<>();
		GenericClientBehaviorController behavior = GenericClientTestSupport.behavior(
			temporaryFolder.newFolder("walker-behavior").toPath(),
			edge ->
			{
				offscreenEdge.set(edge);
				offscreenMoves.incrementAndGet();
			});
		long behaviorHash = 0L;
		while (GenericClientBehaviorProfile.fromAccountHash(behaviorHash)
			.getMicroBreakProbability() > 0.10)
		{
			behaviorHash++;
		}
		behavior.activateAccount(behaviorHash);
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("walker-scripts").toPath(), behavior)
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> {
				requestedDestination.set(journeyRequest.destination);
				requestedAvoidTiles.set(journeyRequest.avoidTiles);
				requestedWithin.set(journeyRequest.within);
				requestedTimeout.set(journeyRequest.timeoutTicks);
				requestedUseRun.set(journeyRequest.useRun);
				requestedInterruptOnDialogue.set(journeyRequest.interrupts.dialogue);
				Map<String, Object> receipt = new java.util.LinkedHashMap<>();
				receipt.put("status", "arrived");
				return CompletableFuture.completedFuture(receipt);
			}).build();
		try
		{
			host.catalog.saveScript(
				"travel",
				"Travel",
				"Exercise a parameterized walk and cursor release.",
				"return { inputs = {{ id = 'destination', label = 'Destination', " +
					"type = 'choice', choices = {{ value = 'edgeville_bank', " +
					"label = 'Edgeville Bank' }} }}, run = function(input) " +
					"local receipt = gc.await { action = { type = 'walk.to', destination = " +
					"{ x = 3094, y = 3492, plane = 0 }, within = 3, run = true, " +
					"interrupt_on = { dialogue = true }, avoid_tiles = {" +
					"{ x = 3091, y = 3491, plane = 0 }, { x = 3092, y = 3491, plane = 0 }} }, " +
					"timeout = { game_ticks = 600 } }; local mouse = gc.await { action = { " +
					"type = 'mouse.offscreen' } }; gc.log('info', 'done', { mouse = mouse.status }); " +
					"return receipt end }\n")
				.get(2, TimeUnit.SECONDS);
			host.start("travel", Collections.singletonMap("destination", "edgeville_bank"))
				.get(2, TimeUnit.SECONDS);
			host.publishGameTick(luaSnapshot(1));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (requestedDestination.get() == null && System.nanoTime() < deadline)
			{
				Thread.sleep(10);
			}

			assertEquals(new WorldPoint(3094, 3492, 0), requestedDestination.get());
			assertEquals(java.util.Arrays.asList(
				new WorldPoint(3091, 3491, 0),
				new WorldPoint(3092, 3491, 0)), requestedAvoidTiles.get());
			assertEquals(3, requestedWithin.get());
			assertEquals(600, requestedTimeout.get());
			assertEquals(Boolean.TRUE, requestedUseRun.get());
			assertEquals(Boolean.TRUE, requestedInterruptOnDialogue.get());
			waitForStatus(host, "COMPLETED");
			assertEquals(1, offscreenMoves.get());
			assertEquals(GenericClientBehaviorProfile.fromAccountHash(behaviorHash).getIdleEdge(),
				offscreenEdge.get());
			assertTrue(host.getRecentLogs().contains("mouse=moved"));
		}
		finally
		{
			host.close();
			behavior.close();
		}
	}

	@Test
	public void walkClickDispatchesOneFixedTileClickWithoutStartingTheWalker() throws Exception
	{
		AtomicReference<WorldPoint> clicked = new AtomicReference<>();
		AtomicInteger walkerCalls = new AtomicInteger();
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("one-shot-walk-scripts").toPath(), GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("one-shot-walk-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkClick((destination, activity) ->
			{
				clicked.set(destination);
				return CompletableFuture.completedFuture(
					GenericClientTestSupport.interaction("one_shot_tile_click"));
			})
			.walkTo((journeyRequest, routeClickBoundary) -> {
				walkerCalls.incrementAndGet();
				return CompletableFuture.completedFuture(Collections.emptyMap());
			}).build();
		try
		{
			host.catalog.saveScript(
				"one-shot-walk",
				"One-shot walk",
				"Click one exact world tile once.",
				script("return gc.await { action = { type = 'walk.click', destination = " +
					"{ x = 2746, y = 2799, plane = 0 } }, policy = { breaks = false, cursor_release = 'none', fidget = 'none' } }"))
				.get(2, TimeUnit.SECONDS);
			host.start("one-shot-walk").get(2, TimeUnit.SECONDS);
			host.publishGameTick(luaSnapshot(1));
			waitForStatus(host, "COMPLETED");

			assertEquals(new WorldPoint(2746, 2799, 0), clicked.get());
			assertEquals(0, walkerCalls.get());
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void walkToCanExplicitlyConserveRunEnergy() throws Exception
	{
		AtomicReference<Boolean> requestedUseRun = new AtomicReference<>();
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("conserve-run-scripts").toPath(), GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("conserve-run-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> {
				requestedUseRun.set(journeyRequest.useRun);
				Map<String, Object> receipt = new java.util.LinkedHashMap<>();
				receipt.put("status", "arrived");
				return CompletableFuture.completedFuture(receipt);
			}).build();
		try
		{
			host.catalog.saveScript(
				"conserve-run",
				"Conserve run",
				"Verify walk run policy.",
				script("gc.await { action = { type = 'walk.to', destination = " +
					"{ x = 3201, y = 3201, plane = 0 }, run = false } }"))
				.get(2, TimeUnit.SECONDS);
			host.start("conserve-run").get(2, TimeUnit.SECONDS);
			host.publishGameTick(luaSnapshot(1));
			waitForStatus(host, "COMPLETED");

			assertEquals(Boolean.FALSE, requestedUseRun.get());
		}
		finally
		{
			host.close();
		}
	}

}
