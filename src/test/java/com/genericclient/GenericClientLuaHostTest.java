package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientLuaHostTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void runsAManifestDeclaredLuaModuleInsideTheSandbox() throws Exception
	{
		Path directory = temporaryFolder.newFolder("modular-lua").toPath();
		Files.createDirectories(directory.resolve("example"));
		Files.writeString(directory.resolve("manifest.json"),
			"{\"schema\":\"genericclient_scripts.v35\",\"scripts\":[{" +
			"\"id\":\"example\",\"name\":\"Example\",\"description\":\"Module test\"," +
			"\"file\":\"example.lua\",\"modules\":{\"maths\":\"example/maths.lua\"}}]}\n");
		Files.writeString(directory.resolve("example.lua"),
			"local maths = gc.require('maths')\n" +
			"return { run = function() return maths.answer end }\n");
		Files.writeString(directory.resolve("example/maths.lua"),
			"return { answer = 42 }\n");
		GenericClientLuaHost host = new GenericClientLuaHost(
			directory,
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) ->
				CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("module-behavior").toPath()),
			message -> { });
		try
		{
			host.start("example").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");
			assertEquals(42.0, host.getActiveScriptView().toMap().get("result"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void installsBundledScriptsWithoutStartingAnything() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("idle-start-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("idle-start-behavior").toPath()),
			message -> { });
		try
		{
			assertEquals("none", host.getActiveScript());
			assertEquals("IDLE", host.getStatus());
			assertFalse(host.getActiveScriptView().isPresent());
			List<GenericClientScriptInput> meleeInputs = host.describe("aio-melee")
				.get(2, TimeUnit.SECONDS);
			assertEquals(3, meleeInputs.size());
			assertEquals("skill", meleeInputs.get(0).getId());
			assertEquals("target_level", meleeInputs.get(1).getId());
			assertEquals("stop_after_kill", host.describeActions("aio-melee")
				.get(2, TimeUnit.SECONDS).get(0).getId());
			assertEquals(3, host.describe("aio-magic").get(2, TimeUnit.SECONDS).size());
			assertEquals("stop_after_cast", host.describeActions("aio-magic")
				.get(2, TimeUnit.SECONDS).get(0).getId());
			List<GenericClientScriptInput> questInputs = host.describe("quest-runner")
				.get(2, TimeUnit.SECONDS);
			assertEquals(3, questInputs.size());
			assertEquals("quest", questInputs.get(0).getId());
			assertEquals("restock", questInputs.get(1).getId());
			assertEquals("combat", questInputs.get(2).getId());
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void preservesManualAndScheduledRunOwnership() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("owned-run-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("owned-run-behavior").toPath()),
			message -> { });
		AtomicInteger manualStops = new AtomicInteger();
		host.setManualStopListener(manualStops::incrementAndGet);
		try
		{
			host.saveScript(
				"waiter",
				"Waiter",
				"Wait for game ticks.",
				script("while true do gc.await { event = 'game.tick' } end"))
				.get(2, TimeUnit.SECONDS);

			assertTrue(host.startScheduled("first", "waiter", Collections.emptyMap())
				.get(2, TimeUnit.SECONDS).contains("owner=rule:first"));
			GenericClientLuaHost.RunState first = host.getRunState();
			assertEquals("first", first.getRuleId());
			assertTrue(first.isRunning());
			assertTrue(host.startScheduled("second", "waiter", Collections.emptyMap())
				.get(2, TimeUnit.SECONDS).contains("LUA_START_SKIPPED"));

			host.start("waiter").get(2, TimeUnit.SECONDS);
			GenericClientLuaHost.RunState manual = host.getRunState();
			assertTrue(manual.isManual());
			assertTrue(manual.getRunId() > first.getRunId());
			host.stop().get(2, TimeUnit.SECONDS);
			assertEquals(1, manualStops.get());
			assertEquals(-1L, host.getRunState().getRunId());

			host.startScheduled("first", "waiter", Collections.emptyMap()).get(2, TimeUnit.SECONDS);
			assertTrue(host.stopScheduled("second", "test").get(2, TimeUnit.SECONDS)
				.contains("LUA_STOP_SKIPPED"));
			assertTrue(host.getRunState().isRunning());
			assertTrue(host.stopScheduled("first", "test").get(2, TimeUnit.SECONDS)
				.contains("owner=rule:first"));
			assertEquals(-1L, host.getRunState().getRunId());
			assertEquals(1, manualStops.get());
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void runsTickReadLogAndRealActionShapeEndToEnd() throws Exception
	{
		AtomicInteger walkRequests = new AtomicInteger();
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("scripts").toPath(),
			breaks ->
			{
				walkRequests.incrementAndGet();
				return CompletableFuture.completedFuture(
					GenericClientTestSupport.interaction("WALK_CLICK_EXECUTED test"));
			},
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("behavior").toPath()),
			message -> { });
		try
		{
			host.saveScript(
				"test",
				"Test",
				"Exercise reads, logs, and actions.",
				script(
				"  gc.await { event = 'game.tick' }\n" +
				"  local npcs = gc.read('npcs', { within = 15, limit = 1 })\n" +
				"  gc.log('info', 'npc-count', { count = #npcs })\n" +
				"  local receipt = gc.await { action = { type = 'walk.random' }, breaks = false }\n" +
				"  gc.log('info', 'walk-result', receipt)"))
				.get(2, TimeUnit.SECONDS);

			host.start("test").get(2, TimeUnit.SECONDS);
			host.publishGameTick(snapshot(1));
			waitForStatus(host, "COMPLETED");

			assertEquals(1, walkRequests.get());
			assertTrue(host.getRecentLogs().contains("npc-count"));
			assertTrue(host.getRecentLogs().contains("walk-result"));
			assertTrue(host.getRecentLogs().contains("dispatched"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void dispatchesNpcInteractionWithExplicitTargetAndBreakPolicy() throws Exception
	{
		AtomicReference<String> request = new AtomicReference<>();
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("npc-action-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(id, name, action, within, breaks) ->
			{
				request.set(id + ":" + name + ":" + action + ":" + within + ":" + breaks);
				Map<String, Object> receipt = new java.util.LinkedHashMap<>();
				receipt.put("status", "dispatched");
				receipt.put("result", "menu_action_executed");
				return CompletableFuture.completedFuture(receipt);
			},
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("npc-action-behavior").toPath()),
			message -> { });
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
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("quest-action-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(id, name, action, within, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(mode, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(type, action, breaks) ->
			{
				requestedType.set(type);
				requestedAction.set(action);
				requestedBreaks.set(breaks);
				Map<String, Object> receipt = new java.util.LinkedHashMap<>();
				receipt.put("status", "dispatched");
				receipt.put("result", "item_used_on_object");
				return CompletableFuture.completedFuture(receipt);
			},
			reason -> { },
			GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("quest-action-behavior").toPath()),
			message -> { });
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
	public void dispatchesCombatStyleSelection() throws Exception
	{
		AtomicReference<String> request = new AtomicReference<>();
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("combat-style-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(id, name, action, within, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(style, breaks) ->
			{
				request.set(style + ":" + breaks);
				Map<String, Object> receipt = new java.util.LinkedHashMap<>();
				receipt.put("status", "set");
				receipt.put("style_index", (long) style);
				return CompletableFuture.completedFuture(receipt);
			},
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("combat-style-behavior").toPath()),
			message -> { });
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
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("auto-retaliate-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(id, name, action, within, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(mode, breaks) ->
			{
				request.set(mode + ":" + breaks);
				Map<String, Object> receipt = new java.util.LinkedHashMap<>();
				receipt.put("status", "set");
				receipt.put("enabled", mode == 5);
				return CompletableFuture.completedFuture(receipt);
			},
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("auto-retaliate-behavior").toPath()),
			message -> { });
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

	@Test
	public void pinsOneSnapshotForTheEntireCoroutineResume() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("pinned-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("pinned-behavior").toPath()),
			message -> { });
		try
		{
			host.saveScript(
				"pinned",
				"Pinned frame",
				"Verify reads remain pinned for one resume.",
				script(
				"  gc.await { event = 'game.tick' }\n" +
				"  local first = gc.read('runtime').game_tick\n" +
				"  local second = gc.read('runtime').game_tick\n" +
				"  gc.log('info', 'pinned-frame', { first = first, second = second })"))
				.get(2, TimeUnit.SECONDS);

			host.start("pinned").get(2, TimeUnit.SECONDS);
			host.publishGameTick(snapshot(1));
			host.publishGameTick(snapshot(2));
			waitForStatus(host, "COMPLETED");

			assertTrue(host.getRecentLogs().contains("first=1.0"));
			assertTrue(host.getRecentLogs().contains("second=1.0"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void stopsAnInfiniteScriptWithTheInstructionHook() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("fault-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("fault-behavior").toPath()),
			message -> { });
		try
		{
			host.saveScript(
				"infinite",
				"Infinite",
				"Exercise the instruction hook.",
				script("while true do end")).get(2, TimeUnit.SECONDS);

			try
			{
				host.start("infinite").get(2, TimeUnit.SECONDS);
				throw new AssertionError("Expected infinite script to fail");
			}
			catch (ExecutionException expected)
			{
				assertTrue(expected.getCause().getMessage().contains("initialization"));
			}
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void rejectsANonBooleanBreakPolicyDuringInitialization() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("invalid-break-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("invalid-break-behavior").toPath()),
			message -> { });
		try
		{
			host.saveScript(
				"invalid-breaks",
				"Invalid breaks",
				"Exercise break policy validation.",
				script("gc.await { action = { type = 'walk.random' }, breaks = 'no' }"))
				.get(2, TimeUnit.SECONDS);
			try
			{
				host.start("invalid-breaks").get(2, TimeUnit.SECONDS);
				throw new AssertionError("Expected invalid breaks value to fail");
			}
			catch (ExecutionException expected)
			{
				assertTrue(expected.getCause().getMessage().contains("initialization"));
				assertTrue(host.getRecentLogs().contains("breaks must be true or false"));
			}
		}
		finally
		{
			host.close();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void replKeepsGlobalsAndReturnsStructuredValues() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("repl-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("repl-behavior").toPath()),
			message -> { });
		try
		{
			host.publishGameTick(snapshot(7));
			Map<String, Object> first = host.evaluate(
				"counter = (counter or 0) + 1\n" +
					"return { counter = counter, player = gc.read('player'), " +
					"npcs = gc.read('npcs'), empty = {} }")
				.get(2, TimeUnit.SECONDS);
			Map<String, Object> second = host.evaluate(
				"counter = counter + 1\nreturn { counter = counter }")
				.get(2, TimeUnit.SECONDS);

			assertEquals("completed", first.get("status"));
			Map<String, Object> firstValue = (Map<String, Object>) first.get("value");
			assertEquals(1.0, firstValue.get("counter"));
			assertTrue(firstValue.get("player") instanceof Map);
			assertTrue(firstValue.get("npcs") instanceof List);
			Map<String, Object> firstNpc = (Map<String, Object>) ((List<?>) firstValue.get("npcs")).get(0);
			assertTrue(firstNpc.get("actions") instanceof List);
			assertTrue(firstValue.get("empty") instanceof Map);
			assertEquals(2.0, ((Map<String, Object>) second.get("value")).get("counter"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void replReadsQuestVarsObjectsAndDialogueFromOnePinnedFrame() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("quest-read-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("quest-read-behavior").toPath()),
			message -> { });
		try
		{
			int[] varps = new int[100];
			varps[65] = 2;
			GenericClientQuestSnapshot quest = new GenericClientQuestSnapshot(
				true,
				varps,
				Collections.singletonList(new GenericClientQuestSnapshot.ObjectSnapshot(
					1989,
					"Bookcase",
					"game",
					2520,
					3427,
					1,
					3,
					Collections.singletonList("Search"))),
				GenericClientQuestSnapshot.DialogueSnapshot.continueDialogue(
					"Hudon", "Maybe I could help."));
			GenericClientSnapshot snapshot = new GenericClientSnapshot(
				77,
				"LOGGED_IN",
				240,
				new GenericClientSnapshot.PlayerSnapshot("Player", 2511, 3480, 0, 0),
				Collections.emptyList(),
				GenericClientAccountSnapshot.empty(),
				quest);
			host.publishGameTick(snapshot);

			Map<String, Object> result = host.evaluate(
				"local vars = gc.read('vars', { varps = { 65 } })\n" +
					"return { stage = vars.varps[65], " +
					"objects = gc.read('objects', { id = 1989, action = 'Search' }), " +
					"dialogue = gc.read('dialogue') }")
				.get(2, TimeUnit.SECONDS);
			Map<String, Object> value = (Map<String, Object>) result.get("value");

			assertEquals(2L, ((Number) value.get("stage")).longValue());
			assertEquals(1, ((List<?>) value.get("objects")).size());
			assertEquals("continue", ((Map<?, ?>) value.get("dialogue")).get("type"));
			assertEquals("Hudon", ((Map<?, ?>) value.get("dialogue")).get("speaker"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void phaseAndActionCanBypassBreaksForATimeSensitiveSequence() throws Exception
	{
		AtomicInteger walkRequests = new AtomicInteger();
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("phase-scripts").toPath(),
			breaks ->
			{
				walkRequests.incrementAndGet();
				return CompletableFuture.completedFuture(
					GenericClientTestSupport.interaction("WALK_CLICK_EXECUTED phase-test"));
			},
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("phase-behavior").toPath()),
			message -> { });
		try
		{
			host.saveScript(
				"phase-test",
				"Phase test",
				"Exercise phase and action bypasses.",
				script(
					"  local first = gc.phase('banking.complete', { breaks = false })\n" +
					"  local second = gc.phase('banking.complete', { breaks = false })\n" +
					"  local action = gc.await { action = { type = 'walk.random' }, breaks = false }\n" +
					"  gc.log('info', 'phase-bypass', { first = first.status, second = second.status, " +
					"action = action.status })"))
				.get(2, TimeUnit.SECONDS);

			host.start("phase-test").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");

			assertEquals(1, walkRequests.get());
			assertTrue(host.getRecentLogs().contains("first=bypassed"));
			assertTrue(host.getRecentLogs().contains("second=unchanged"));
			assertTrue(host.getRecentLogs().contains("action=dispatched"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void describesChoiceInputsAndPassesTheSelectedValueToRun() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("input-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("input-behavior").toPath()),
			message -> { });
		try
		{
			host.saveScript(
				"input-test",
				"Input test",
				"Exercise descriptor inputs.",
				"return {\n" +
				"  inputs = {{ id = 'destination', label = 'Destination', type = 'choice',\n" +
				"    default = 'grand_exchange', choices = {\n" +
				"      { value = 'grand_exchange', label = 'Grand Exchange' },\n" +
				"      { value = 'varrock_center', label = 'Varrock Center' },\n" +
				"    } }},\n" +
				"  run = function(input) gc.log('info', 'selected', input) end,\n" +
				"}\n").get(2, TimeUnit.SECONDS);

			List<GenericClientScriptInput> inputs = host.describe("input-test").get(2, TimeUnit.SECONDS);
			assertEquals(1, inputs.size());
			assertEquals("Destination", inputs.get(0).getLabel());
			assertEquals("grand_exchange", inputs.get(0).getDefaultValue());

			host.start("input-test", Collections.singletonMap("destination", "varrock_center"))
				.get(2, TimeUnit.SECONDS);

			assertEquals("COMPLETED", host.getStatus());
			assertTrue(host.getRecentLogs().contains("destination=varrock_center"));
			assertEquals("varrock_center",
				((Map<?, ?>) host.controlState().get("active_inputs")).get("destination"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void rejectsValuesOutsideTheDeclaredChoices() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("invalid-input-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("invalid-input-behavior").toPath()),
			message -> { });
		try
		{
			host.saveScript(
				"invalid-input",
				"Invalid input",
				"Exercise choice validation.",
				"return { inputs = {{ id = 'place', label = 'Place', type = 'choice', " +
					"choices = {{ value = 'varrock', label = 'Varrock' }} }}, " +
					"run = function(input) return input.place end }\n")
				.get(2, TimeUnit.SECONDS);

			try
			{
				host.start("invalid-input", Collections.singletonMap("place", "lumbridge"))
					.get(2, TimeUnit.SECONDS);
				throw new AssertionError("Expected an invalid script input to fail");
			}
			catch (ExecutionException expected)
			{
				assertTrue(expected.getCause().getMessage().contains("Invalid value for script input place"));
			}
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void walkerMapsTheSelectedLuaDestinationIntoTheCoreWalkAction() throws Exception
	{
		AtomicReference<WorldPoint> requestedDestination = new AtomicReference<>();
		AtomicInteger requestedWithin = new AtomicInteger();
		AtomicInteger requestedTimeout = new AtomicInteger();
		AtomicReference<Boolean> requestedUseRun = new AtomicReference<>();
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
			.getShortReleaseProbability() > 0.10)
		{
			behaviorHash++;
		}
		behavior.activateAccount(behaviorHash);
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("walker-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) ->
			{
				requestedDestination.set(destination);
				requestedWithin.set(within);
				requestedTimeout.set(timeout);
				requestedUseRun.set(useRun);
				Map<String, Object> receipt = new java.util.LinkedHashMap<>();
				receipt.put("status", "arrived");
				return CompletableFuture.completedFuture(receipt);
			},
			reason -> { },
			behavior,
			message -> { });
		try
		{
			host.start("walker", Collections.singletonMap("destination", "edgeville_bank"))
				.get(2, TimeUnit.SECONDS);
			host.publishGameTick(snapshot(1));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (requestedDestination.get() == null && System.nanoTime() < deadline)
			{
				Thread.sleep(10);
			}

			assertEquals(new WorldPoint(3094, 3492, 0), requestedDestination.get());
			assertEquals(3, requestedWithin.get());
			assertEquals(600, requestedTimeout.get());
			assertEquals(Boolean.TRUE, requestedUseRun.get());
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
	public void walkToCanExplicitlyConserveRunEnergy() throws Exception
	{
		AtomicReference<Boolean> requestedUseRun = new AtomicReference<>();
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("conserve-run-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) ->
			{
				requestedUseRun.set(useRun);
				Map<String, Object> receipt = new java.util.LinkedHashMap<>();
				receipt.put("status", "arrived");
				return CompletableFuture.completedFuture(receipt);
			},
			reason -> { },
			GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("conserve-run-behavior").toPath()),
			message -> { });
		try
		{
			host.saveScript(
				"conserve-run",
				"Conserve run",
				"Verify walk run policy.",
				script("gc.await { action = { type = 'walk.to', destination = " +
					"{ x = 3201, y = 3201, plane = 0 }, run = false } }"))
				.get(2, TimeUnit.SECONDS);
			host.start("conserve-run").get(2, TimeUnit.SECONDS);
			host.publishGameTick(snapshot(1));
			waitForStatus(host, "COMPLETED");

			assertEquals(Boolean.FALSE, requestedUseRun.get());
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void completesAPendingReplCallWhenTheHostCloses() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("closing-repl-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("closing-repl-behavior").toPath()),
			message -> { });
		try
		{
			CompletableFuture<Map<String, Object>> pending = host.evaluate(
				"gc.await { ticks = 100 }\nreturn true");
			host.close();
			try
			{
				pending.get(2, TimeUnit.SECONDS);
				throw new AssertionError("Expected the pending REPL call to fail during shutdown");
			}
			catch (ExecutionException expected)
			{
				assertEquals("Lua host stopped", expected.getCause().getMessage());
			}
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void exposesRuntimeOverlayAndCooperativeScriptActions() throws Exception
	{
		AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(10));
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("presentation-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("presentation-behavior").toPath()),
			message -> { },
			clock::get);
		try
		{
			host.saveScript(
				"presentation",
				"Presentation",
				"Exercise active script presentation.",
				"return {\n" +
				"  actions = {{ id = 'refresh', label = 'Refresh' }},\n" +
				"  run = function(input)\n" +
				"    gc.overlay {{ label = 'State', value = 'Waiting' }}\n" +
				"    while true do\n" +
				"      gc.await { event = 'game.tick' }\n" +
				"      gc.log('info', 'script-runtime', " +
				"        { millis = gc.read('runtime').script_runtime_millis })\n" +
				"      local action = gc.next_action()\n" +
				"      if action then\n" +
				"        gc.overlay {{ label = 'Action', value = action }}\n" +
				"        gc.log('info', 'action-consumed', { id = action })\n" +
				"        return action\n" +
				"      end\n" +
				"    end\n" +
				"  end,\n" +
				"}\n").get(2, TimeUnit.SECONDS);

			host.start("presentation").get(2, TimeUnit.SECONDS);
			clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(5_500));
			GenericClientActiveScript running = host.getActiveScriptView();
			assertEquals("Presentation", running.getName());
			assertEquals(5_500L, running.getRuntimeMillis());
			assertEquals("refresh", running.getActions().get(0).getId());
			assertEquals("Waiting", running.getOverlayRows().get(0).getValue());

			assertTrue(host.triggerAction("refresh").get(2, TimeUnit.SECONDS).contains("QUEUED"));
			host.publishGameTick(snapshot(1));
			waitForStatus(host, "COMPLETED");

			GenericClientActiveScript completed = host.getActiveScriptView();
			assertEquals(5_500L, completed.getRuntimeMillis());
			assertEquals("refresh", completed.getOverlayRows().get(0).getValue());
			assertEquals("refresh", completed.toMap().get("result"));
			assertTrue(host.getRecentLogs().contains("action-consumed"));
			assertTrue(host.getRecentLogs().contains("script-runtime"));
			assertTrue(host.getRecentLogs().contains("millis=5500.0"));
			assertEquals("presentation", ((Map<?, ?>) host.controlState().get("active")).get("id"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void runsTheBundledAccountAuditorAgainstOnePinnedFrame() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("account-auditor-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("account-auditor-behavior").toPath()),
			message -> { });
		try
		{
			host.start("account-auditor").get(2, TimeUnit.SECONDS);
			host.publishGameTick(accountSnapshot(91));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (!host.getRecentLogs().contains("account-audit") && System.nanoTime() < deadline)
			{
				Thread.sleep(10);
			}

			GenericClientActiveScript active = host.getActiveScriptView();
			assertEquals("Account Auditor", active.getName());
			assertEquals("refresh", active.getActions().get(0).getId());
			assertEquals("Audited", active.getOverlayRows().get(0).getValue());
			assertTrue(host.getRecentLogs().contains("INFO account-audit"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void completesAioMeleeWithoutTrainingWhenTheExactTargetIsAlreadyMet() throws Exception
	{
		GenericClientBehaviorController behavior = GenericClientTestSupport.behavior(
			temporaryFolder.newFolder("aio-melee-behavior").toPath());
		behavior.activateAccount(123L);
		behavior.setLoggedIn(true);
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("aio-melee-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(id, name, action, within, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(mode, breaks) ->
			{
				Map<String, Object> receipt = new java.util.LinkedHashMap<>();
				receipt.put("status", "unchanged");
				return CompletableFuture.completedFuture(receipt);
			},
			reason -> { },
			behavior,
			message -> { });
		try
		{
			Map<String, Object> inputs = new java.util.LinkedHashMap<>();
			inputs.put("skill", "attack");
			inputs.put("target_level", "2");
			inputs.put("method", "auto");
			host.start("aio-melee", inputs).get(2, TimeUnit.SECONDS);
			host.publishGameTick(accountSnapshot(92, 2, 83));
			waitForStatus(host, "COMPLETED");

			assertTrue(host.getRecentLogs().contains("target_already_met"));
			assertEquals("Target already met", host.getActiveScriptView().getOverlayRows().get(2).getValue());
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void questRunnerStrictlyBlocksWitchsHouseAtTenHitpoints() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("quest-runner-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("quest-runner-behavior").toPath()),
			message -> { });
		try
		{
			host.start("quest-runner", Collections.singletonMap("quest", "witchs_house"))
				.get(2, TimeUnit.SECONDS);
			host.publishGameTick(questSnapshot(1, 1, 10, 0));
			waitForStatus(host, "COMPLETED");

			assertTrue(host.getRecentLogs().contains("strict_stats_block"));
			assertEquals("strict stats block",
				host.getActiveScriptView().getOverlayRows().get(1).getValue());
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void questRunnerStrictlyBlocksWaterfallAtTenHitpoints() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("waterfall-runner-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("waterfall-runner-behavior").toPath()),
			message -> { });
		try
		{
			host.start("quest-runner", Collections.singletonMap("quest", "waterfall"))
				.get(2, TimeUnit.SECONDS);
			host.publishGameTick(questSnapshot(1, 1, 10, 0));
			waitForStatus(host, "COMPLETED");

			assertTrue(host.getRecentLogs().contains("strict_hitpoints_block"));
			assertEquals("strict hitpoints block",
				host.getActiveScriptView().getOverlayRows().get(1).getValue());
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void questRunnerStopsAtThePreparedShedCheckpoint() throws Exception
	{
		GenericClientBehaviorController behavior = GenericClientTestSupport.behavior(
			temporaryFolder.newFolder("garden-key-behavior").toPath());
		behavior.activateAccount(1234L);
		behavior.setLoggedIn(true);
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("garden-key-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) ->
				CompletableFuture.completedFuture(Collections.emptyMap()),
			(id, name, action, within, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(mode, breaks) -> CompletableFuture.completedFuture(receipt("unchanged")),
			(type, action, breaks) -> CompletableFuture.completedFuture(receipt("complete")),
			reason -> { },
			behavior,
			message -> { });
		try
		{
			host.start("quest-runner", Collections.singletonMap("quest", "witchs_house"))
				.get(2, TimeUnit.SECONDS);
			for (int tick = 1; tick <= 8 && !"COMPLETED".equals(host.getStatus()); tick++)
			{
				host.publishGameTick(questSnapshot(tick, 13, 12, 5, true));
				Thread.sleep(20L);
			}
			waitForStatus(host, "COMPLETED");

			assertTrue(host.getRecentLogs().contains("shed_ready_checkpoint"));
			assertEquals("shed ready checkpoint",
				host.getActiveScriptView().getOverlayRows().get(1).getValue());
		}
		finally
		{
			host.close();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void questRunnerRecognizesCompletedWitchsHouse() throws Exception
	{
		GenericClientBehaviorController behavior = GenericClientTestSupport.behavior(
			temporaryFolder.newFolder("completed-witch-behavior").toPath());
		behavior.activateAccount(1234L);
		behavior.setLoggedIn(true);
		AtomicReference<String> completedAction = new AtomicReference<>();
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("completed-witch-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("parked")),
			(destination, within, timeout, breaks, useRun) ->
				CompletableFuture.completedFuture(Collections.emptyMap()),
			(id, name, action, within, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(mode, breaks) -> CompletableFuture.completedFuture(receipt("unchanged")),
			(type, action, breaks) ->
			{
				completedAction.set(type);
				return CompletableFuture.completedFuture(receipt("complete"));
			},
			reason -> { },
			behavior,
			message -> { });
		try
		{
			host.start("quest-runner", Collections.singletonMap("quest", "witchs_house"))
				.get(2, TimeUnit.SECONDS);
			host.publishGameTick(questSnapshot(1, 16, 25, 7, true));
			waitForStatus(host, "COMPLETED");

			Map<String, Object> result = (Map<String, Object>)
				host.getActiveScriptView().toMap().get("result");
			assertEquals("complete", result.get("status"));
			assertEquals(7.0, ((Number) result.get("varp")).doubleValue(), 0.0);
			assertEquals("safety.clear", completedAction.get());
		}
		finally
		{
			host.close();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void questRunnerConservesRunOnTheCombatApproach() throws Exception
	{
		GenericClientBehaviorController behavior = GenericClientTestSupport.behavior(
			temporaryFolder.newFolder("shed-combat-behavior").toPath());
		behavior.activateAccount(1234L);
		behavior.setLoggedIn(true);
		AtomicInteger walkRequests = new AtomicInteger();
		java.util.concurrent.atomic.AtomicReference<Boolean> requestedRun =
			new java.util.concurrent.atomic.AtomicReference<>();
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("shed-combat-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, useRun) ->
			{
				walkRequests.incrementAndGet();
				requestedRun.set(useRun);
				return CompletableFuture.completedFuture(receipt("timed_out"));
			},
			(id, name, action, within, breaks) ->
				CompletableFuture.completedFuture(Collections.emptyMap()),
			(mode, breaks) -> CompletableFuture.completedFuture(receipt("unchanged")),
			(type, action, breaks) -> CompletableFuture.completedFuture(
				receipt("combat.set_autocast".equals(type) ? "set" : "complete")),
			reason -> { },
			behavior,
			message -> { });
		try
		{
			Map<String, Object> inputs = new java.util.LinkedHashMap<>();
			inputs.put("quest", "witchs_house");
			inputs.put("combat", "continue");
			host.start("quest-runner", inputs).get(2, TimeUnit.SECONDS);
			for (int tick = 1; tick <= 4 && !"COMPLETED".equals(host.getStatus()); tick++)
			{
				host.publishGameTick(questSnapshot(tick, 13, 12, 5, true, 100, true));
				Thread.sleep(20L);
			}
			waitForStatus(host, "COMPLETED");

			Map<String, Object> result = (Map<String, Object>)
				host.getActiveScriptView().toMap().get("result");
			assertEquals(1, walkRequests.get());
			assertEquals(Boolean.FALSE, requestedRun.get());
			assertEquals("garden_stage_failed", result.get("status"));
		}
		finally
		{
			host.close();
		}
	}

	private static GenericClientSnapshot accountSnapshot(long tick)
	{
		return accountSnapshot(tick, 1, 12);
	}

	private static GenericClientSnapshot accountSnapshot(long tick, int attackLevel, int attackXp)
	{
		GenericClientAccountSnapshot.ContainerSnapshot inventory =
			new GenericClientAccountSnapshot.ContainerSnapshot(
				true,
				28,
				Collections.singletonList(new GenericClientAccountSnapshot.ItemSnapshot(
					0,
					null,
					net.runelite.api.gameval.ItemID.COINS,
					1_000,
					"Coins",
					true,
					true,
					true,
					Collections.emptyList())));
		GenericClientAccountSnapshot account = new GenericClientAccountSnapshot(
			true,
			36,
			Collections.singletonList(new GenericClientAccountSnapshot.SkillSnapshot(
				"attack", attackLevel, attackLevel, attackXp)),
			inventory,
			GenericClientAccountSnapshot.ContainerSnapshot.unavailable(),
			GenericClientAccountSnapshot.BankSnapshot.unknown(),
			GenericClientAccountSnapshot.QuestListSnapshot.unavailable(),
			GenericClientAccountSnapshot.GrandExchangeSnapshot.unavailable(),
			GenericClientAccountSnapshot.CashSnapshot.from(
				inventory,
				GenericClientAccountSnapshot.BankSnapshot.unknown()));
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			231,
			new GenericClientSnapshot.PlayerSnapshot("Player", 3200, 3200, 0, -1),
			Collections.emptyList(),
			account);
	}

	private static GenericClientSnapshot snapshot(long tick)
	{
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			231,
			new GenericClientSnapshot.PlayerSnapshot("Player", 3200, 3200, 0, -1),
			Collections.singletonList(new GenericClientSnapshot.NpcSnapshot(
				1,
				100,
				"Banker",
				3201,
				3200,
				0,
				1,
				0,
				-1,
				null,
				Collections.singletonList("Bank"))));
	}

	private static GenericClientSnapshot questSnapshot(
		long tick,
		int magicLevel,
		int maxHitpoints,
		int witchVarp)
	{
		return questSnapshot(tick, magicLevel, maxHitpoints, witchVarp, false);
	}

	private static GenericClientSnapshot questSnapshot(
		long tick,
		int magicLevel,
		int maxHitpoints,
		int witchVarp,
		boolean shedKey)
	{
		return questSnapshot(tick, magicLevel, maxHitpoints, witchVarp, shedKey, 100, false);
	}

	private static GenericClientSnapshot questSnapshot(
		long tick,
		int magicLevel,
		int maxHitpoints,
		int witchVarp,
		boolean shedKey,
		int runEnergy,
		boolean combatEquipped)
	{
		GenericClientAccountSnapshot.ContainerSnapshot inventory =
			new GenericClientAccountSnapshot.ContainerSnapshot(
				true,
				28,
				shedKey ? shedLoadout() : Collections.emptyList());
		List<GenericClientAccountSnapshot.ItemSnapshot> equippedItems = new java.util.ArrayList<>();
		if (combatEquipped)
		{
			equippedItems.add(itemSnapshot(0, 1387, 1, "Staff of fire"));
			equippedItems.add(itemSnapshot(1, 2550, 1, "Ring of recoil"));
		}
		GenericClientAccountSnapshot.ContainerSnapshot equipment =
			new GenericClientAccountSnapshot.ContainerSnapshot(true, 14, equippedItems);
		List<GenericClientAccountSnapshot.SkillSnapshot> skills = new java.util.ArrayList<>();
		skills.add(new GenericClientAccountSnapshot.SkillSnapshot(
			"magic", magicLevel, magicLevel, 9));
		skills.add(new GenericClientAccountSnapshot.SkillSnapshot(
			"hitpoints", maxHitpoints, maxHitpoints, 1180));
		GenericClientAccountSnapshot.QuestListSnapshot quests =
			new GenericClientAccountSnapshot.QuestListSnapshot(
				true,
				tick,
				java.util.Arrays.asList(
					new GenericClientAccountSnapshot.QuestSnapshot(
						"witchs_house", 160, "Witch's House", "not_started"),
					new GenericClientAccountSnapshot.QuestSnapshot(
						"waterfall_quest", 158, "Waterfall Quest", "not_started")));
		GenericClientAccountSnapshot account = new GenericClientAccountSnapshot(
			true,
			magicLevel + maxHitpoints,
			skills,
			inventory,
			equipment,
			GenericClientAccountSnapshot.BankSnapshot.unknown(),
			quests);
		int[] varps = new int[227];
		varps[226] = witchVarp;
		GenericClientQuestSnapshot quest = new GenericClientQuestSnapshot(
			true,
			varps,
			Collections.emptyList(),
			GenericClientQuestSnapshot.DialogueSnapshot.closed());
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			231,
			new GenericClientSnapshot.PlayerSnapshot(
				"Player", 3168, 3492, 0, -1, -1, null,
				maxHitpoints, maxHitpoints, runEnergy, true, null),
			Collections.emptyList(),
			account,
			quest);
	}

	private static List<GenericClientAccountSnapshot.ItemSnapshot> shedLoadout()
	{
		List<GenericClientAccountSnapshot.ItemSnapshot> items = new java.util.ArrayList<>();
		items.add(itemSnapshot(0, 2411, 1, "Key"));
		items.add(itemSnapshot(1, 2409, 1, "Door key"));
		items.add(itemSnapshot(2, 1387, 1, "Staff of fire"));
		items.add(itemSnapshot(3, 556, 300, "Air rune"));
		items.add(itemSnapshot(4, 558, 150, "Mind rune"));
		items.add(itemSnapshot(5, 2550, 4, "Ring of recoil"));
		items.add(itemSnapshot(6, 3855, 1, "Games necklace(7)"));
		items.add(itemSnapshot(7, 1993, 6, "Jug of wine"));
		return items;
	}

	private static GenericClientAccountSnapshot.ItemSnapshot itemSnapshot(
		int slot,
		int id,
		int quantity,
		String name)
	{
		return new GenericClientAccountSnapshot.ItemSnapshot(
			slot, null, id, quantity, name, false, true, true, Collections.emptyList());
	}

	private static String script(String body)
	{
		return "return { run = function(input)\n" + body + "\nend }\n";
	}

	private static Map<String, Object> receipt(String status)
	{
		Map<String, Object> receipt = new java.util.LinkedHashMap<>();
		receipt.put("status", status);
		receipt.put("click_count", 0L);
		return receipt;
	}

	private static void waitForStatus(GenericClientLuaHost host, String expected) throws InterruptedException
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (!expected.equals(host.getStatus()) && System.nanoTime() < deadline)
		{
			Thread.sleep(10);
		}
		assertEquals(expected, host.getStatus());
	}
}
