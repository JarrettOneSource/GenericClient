package com.genericclient;

import static com.genericclient.GenericClientTestSupport.luaSnapshot;
import static com.genericclient.GenericClientTestSupport.receipt;
import static com.genericclient.GenericClientTestSupport.script;
import static com.genericclient.GenericClientTestSupport.waitForStatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
			"{\"schema\":\"genericclient_scripts\",\"scripts\":[{" +
			"\"id\":\"example\",\"name\":\"Example\",\"description\":\"Module test\"," +
			"\"file\":\"example.lua\",\"modules\":{\"maths\":\"example/maths.lua\"}}]}\n");
		Files.writeString(directory.resolve("example.lua"),
			"local maths = gc.require('maths')\n" +
			"return { run = function() return maths.answer end }\n");
		Files.writeString(directory.resolve("example/maths.lua"),
			"return { answer = 42 }\n");
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(directory, GenericClientTestSupport.behavior(temporaryFolder.newFolder("module-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			host.start("example").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");
			assertEquals(42.0, host.getActiveScriptView().toMap().get("result"));
			host.catalog.saveScript("example", "Updated", "Use an intent from a declared module",
				"local maths = gc.require('maths')\n" +
				"return {run=function() return gc.intent('module', function()\n" +
				"  return {answer=maths.answer+1, api=gc.read('runtime').api_version}\n" +
				"end) end}\n").get(2, TimeUnit.SECONDS);
			host.catalog.reloadManifest().get(2, TimeUnit.SECONDS);
			host.publishGameTick(luaSnapshot(1));
			host.start("example").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");
			assertEquals(Map.of("answer", 43.0, "api", 3.0), host.getActiveScriptView().toMap().get("result"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void checkpointsSurviveScriptAndHostRestartUntilCleared() throws Exception
	{
		Path directory = temporaryFolder.newFolder("checkpoint-lua").toPath();
		GenericClientLuaHost first = GenericClientTestSupport.luaHost(directory, GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("checkpoint-behavior-one").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			first.catalog.saveScript(
				"checkpoint-test",
				"Checkpoint test",
				"Persist and clear one route cursor.",
				script(
				"  gc.await { event = 'game.tick' }\n" +
				"  local cursor = gc.checkpoint('route.cursor')\n" +
				"  if cursor == nil then\n" +
				"    gc.checkpoint('route.cursor', 29)\n" +
				"    return { status = 'saved' }\n" +
				"  end\n" +
				"  gc.clear_checkpoint('route.cursor')\n" +
				"  return { status = 'loaded', cursor = cursor }"))
				.get(2, TimeUnit.SECONDS);
			first.start("checkpoint-test").get(2, TimeUnit.SECONDS);
			first.publishGameTick(luaSnapshot(1));
			waitForStatus(first, "COMPLETED");
			assertEquals("saved", ((Map<String, Object>)
				first.getActiveScriptView().toMap().get("result")).get("status"));
		}
		finally
		{
			first.close();
		}

		GenericClientLuaHost second = GenericClientTestSupport.luaHost(directory, GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("checkpoint-behavior-two").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			second.start("checkpoint-test").get(2, TimeUnit.SECONDS);
			second.publishGameTick(luaSnapshot(2));
			waitForStatus(second, "COMPLETED");
			Map<String, Object> result = (Map<String, Object>)
				second.getActiveScriptView().toMap().get("result");
			assertEquals("loaded", result.get("status"));
			assertEquals(29.0, result.get("cursor"));
		}
		finally
		{
			second.close();
		}
	}

	@Test
	public void startsWithoutStandaloneScripts() throws Exception
	{
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("idle-start-scripts").toPath(), GenericClientTestSupport.behavior(temporaryFolder.newFolder("idle-start-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			assertEquals("none", host.getActiveScript());
			assertEquals("IDLE", host.getStatus());
			assertFalse(host.getActiveScriptView().isPresent());
			assertTrue(host.catalog.listScripts().isEmpty());
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void failedScriptFallsIntoStickySafetyNetOnTheNextTick() throws Exception
	{
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("safety-net-scripts").toPath(), GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("safety-net-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			host.catalog.saveScript(
				"safety-net",
				"Safety Net",
				"Remain active after recovery.",
				script("while true do gc.await { event = 'game.tick' } end"))
				.get(2, TimeUnit.SECONDS);
			host.catalog.saveScript(
				"failure",
				"Failure",
				"Return a failed terminal receipt.",
				script("return { status = 'action_failed' }"))
				.get(2, TimeUnit.SECONDS);

			host.start("failure").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");
			assertTrue(host.hasPendingFailureFallback());

			host.publishGameTick(luaSnapshot(1));
			waitForScript(host, "safety-net");
			assertTrue(host.getRunState().isRunning());
			assertEquals("safety_net", host.getRunState().getOwner());
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void completedLocalRecoveryDoesNotStartStickySafetyNet() throws Exception
	{
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("completed-recovery-scripts").toPath(), GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("completed-recovery-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(
				GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			host.catalog.saveScript(
				"failure",
				"Failure",
				"Return a failed receipt after a completed local retreat.",
				script("return { status = 'action_failed', recovery_completed = true }"))
				.get(2, TimeUnit.SECONDS);

			host.start("failure").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");

			assertFalse(host.hasPendingFailureFallback());
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void scriptBehaviorLifecycleResetsOnCompletionAndManualStop() throws Exception
	{
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("script-behavior-lifecycle").toPath(), GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("script-behavior-lifecycle-profile").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(
				GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		List<String> starts = new ArrayList<>();
		List<String> ends = new ArrayList<>();
		List<String> lifecycle = new ArrayList<>();
		host.setScriptStartListener((script, owner) ->
		{
			starts.add(script + ":" + owner);
			lifecycle.add("start:" + script);
		});
		host.setScriptEndListener((script, owner) ->
		{
			ends.add(script + ":" + owner);
			lifecycle.add("end:" + script);
		});
		try
		{
			host.catalog.saveScript(
				"complete-now", "Complete", "Complete immediately.",
				script("return { status = 'complete' }")).get(2, TimeUnit.SECONDS);
			host.catalog.saveScript(
				"wait", "Wait", "Wait for ticks.",
				script("while true do gc.await { event = 'game.tick' } end"))
				.get(2, TimeUnit.SECONDS);
			host.catalog.saveScript(
				"replacement", "Replacement", "Replace an active script.",
				script("while true do gc.await { event = 'game.tick' } end"))
				.get(2, TimeUnit.SECONDS);

			host.start("complete-now").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");
			host.start("wait").get(2, TimeUnit.SECONDS);
			host.stop().get(2, TimeUnit.SECONDS);
			host.start("wait").get(2, TimeUnit.SECONDS);
			host.start("replacement").get(2, TimeUnit.SECONDS);
			host.stop().get(2, TimeUnit.SECONDS);

			assertEquals(java.util.Arrays.asList(
				"complete-now:manual", "wait:manual", "wait:manual",
				"replacement:manual"), starts);
			assertEquals(java.util.Arrays.asList(
				"complete-now:manual", "wait:manual", "wait:manual",
				"replacement:manual"), ends);
			assertEquals(java.util.Arrays.asList(
				"start:complete-now", "end:complete-now",
				"start:wait", "end:wait",
				"start:wait", "end:wait",
				"start:replacement", "end:replacement"), lifecycle);
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void preservesManualAndScheduledRunOwnership() throws Exception
	{
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("owned-run-scripts").toPath(), GenericClientTestSupport.behavior(temporaryFolder.newFolder("owned-run-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		AtomicInteger manualStops = new AtomicInteger();
		host.setManualStopListener(manualStops::incrementAndGet);
		try
		{
			host.catalog.saveScript(
				"waiter",
				"Waiter",
				"Wait for game ticks.",
				script("while true do gc.await { event = 'game.tick' } end"))
				.get(2, TimeUnit.SECONDS);

			assertTrue(host.startScheduled("first", "waiter", Collections.emptyMap())
				.get(2, TimeUnit.SECONDS).contains("owner=rule:first"));
			GenericClientLuaRun.State first = host.getRunState();
			assertEquals("first", first.getRuleId());
			assertTrue(first.isRunning());
			assertTrue(host.startScheduled("second", "waiter", Collections.emptyMap())
				.get(2, TimeUnit.SECONDS).contains("LUA_START_SKIPPED"));

			host.start("waiter").get(2, TimeUnit.SECONDS);
			GenericClientLuaRun.State manual = host.getRunState();
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
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("scripts").toPath(), GenericClientTestSupport.behavior(temporaryFolder.newFolder("behavior").toPath()))
			.walkRandom(breaks ->
			{
				walkRequests.incrementAndGet();
				return CompletableFuture.completedFuture(
					GenericClientTestSupport.interaction("WALK_CLICK_EXECUTED test"));
			})
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			host.catalog.saveScript(
				"test",
				"Test",
				"Exercise reads, logs, and actions.",
				script(
				"  gc.await { event = 'game.tick' }\n" +
				"  local npcs = gc.read('npcs', { within = 15, limit = 1 })\n" +
				"  gc.log('info', 'npc-count', { count = #npcs })\n" +
				"  local receipt = gc.await { action = { type = 'walk.random' }, policy = { breaks = false, cursor_release = 'none', fidget = 'none' } }\n" +
				"  gc.log('info', 'walk-result', receipt)"))
				.get(2, TimeUnit.SECONDS);

			host.start("test").get(2, TimeUnit.SECONDS);
			host.publishGameTick(luaSnapshot(1));
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
	public void emergencyPauseRetriesTheInterruptedLuaActionAfterFoodInputFinishes() throws Exception
	{
		List<CompletableFuture<Map<String, Object>>> attempts =
			Collections.synchronizedList(new ArrayList<>());
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("emergency-pause-scripts").toPath(), GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("emergency-pause-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap()))
			.npcInteract((id, name, action, within, breaks) ->
				CompletableFuture.completedFuture(Collections.emptyMap()))
			.combatMode((mode, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()))
			.questAction((type, action, breaks) ->
			{
				CompletableFuture<Map<String, Object>> attempt = new CompletableFuture<>();
				attempts.add(attempt);
				return attempt;
			}).build();
		try
		{
			host.catalog.saveScript(
				"emergency-pause",
				"Emergency pause",
				"Retry an interrupted client action.",
				script("return gc.await { action = { type = 'ui.close' }, policy = { breaks = false, cursor_release = 'none', fidget = 'none' } }")).get(
				2, TimeUnit.SECONDS);
			host.start("emergency-pause").get(2, TimeUnit.SECONDS);
			waitForAttempts(attempts, 1);

			host.actions.pauseForEmergency("emergency_consumable").get(2, TimeUnit.SECONDS);
			Map<String, Object> cancelled = receipt("rejected");
			cancelled.put("result", "cancelled: emergency_consumable");
			attempts.get(0).complete(cancelled);
			Thread.sleep(50L);
			assertEquals("WAITING", host.getStatus());
			assertEquals(Boolean.TRUE, host.controlState().get("emergency_paused"));

			host.actions.resumeAfterEmergency("emergency_consumable").get(2, TimeUnit.SECONDS);
			waitForAttempts(attempts, 2);
			Map<String, Object> completed = receipt("dispatched");
			completed.put("result", "menu_action_executed");
			attempts.get(1).complete(completed);
			waitForStatus(host, "COMPLETED");

			assertEquals(2, attempts.size());
			assertEquals(Boolean.FALSE, host.controlState().get("emergency_paused"));
			assertEquals("dispatched",
				((Map<?, ?>) host.getActiveScriptView().toMap().get("result")).get("status"));
		}
		finally
		{
			host.close();
		}
	}






	@Test
	public void pinsOneSnapshotForTheEntireCoroutineResume() throws Exception
	{
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("pinned-scripts").toPath(), GenericClientTestSupport.behavior(temporaryFolder.newFolder("pinned-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			host.catalog.saveScript(
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
			host.publishGameTick(luaSnapshot(1));
			host.publishGameTick(luaSnapshot(2));
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
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("fault-scripts").toPath(), GenericClientTestSupport.behavior(temporaryFolder.newFolder("fault-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			host.catalog.saveScript(
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
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("invalid-break-scripts").toPath(), GenericClientTestSupport.behavior(temporaryFolder.newFolder("invalid-break-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			host.catalog.saveScript(
				"invalid-breaks",
				"Invalid breaks",
				"Exercise break policy validation.",
				script("gc.await { action = { type = 'walk.random' }, policy = { breaks = 'no' } }"))
				.get(2, TimeUnit.SECONDS);
			try
			{
				host.start("invalid-breaks").get(2, TimeUnit.SECONDS);
				throw new AssertionError("Expected invalid breaks value to fail");
			}
			catch (ExecutionException expected)
			{
				assertTrue(expected.getCause().getMessage().contains("initialization"));
				assertTrue(host.getRecentLogs().contains("policy.breaks must be true or false"));
			}
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void operatorAwaitsArePlainUnlessExplicitlyHumanized() throws Exception
	{
		List<GenericClientActivityContext> contexts = new java.util.concurrent.CopyOnWriteArrayList<>();
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("operator-scripts").toPath(), GenericClientTestSupport.behavior(temporaryFolder.newFolder("operator-behavior").toPath()))
			.walkRandom(context -> {
				contexts.add(context);
				return CompletableFuture.completedFuture(GenericClientTestSupport.interaction("walked"));
			})
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			host.publishGameTick(luaSnapshot(1));
			host.evaluate("gc.activity('combat'); return gc.await { action = { type = 'walk.random' }, }")
				.get(2, TimeUnit.SECONDS);
			assertFalse(contexts.get(0).allowsBreaks());
			assertFalse(contexts.get(0).allowsCursorRelease());
			assertEquals(550, contexts.get(0).mouseMoveDurationMillis(550));
			assertEquals("bypassed", host.evaluate("return gc.phase('operator.phase').status")
				.get(2, TimeUnit.SECONDS).get("value"));
			host.evaluate("return gc.await { action = { type = 'walk.random' }, humanize = true }")
				.get(2, TimeUnit.SECONDS);
			assertTrue(contexts.get(1).allowsBreaks());
			host.catalog.saveScript("owned", "Owned", "Standalone behavior remains enabled",
				script("return gc.await { action = { type = 'walk.random' } }")).get(2, TimeUnit.SECONDS);
			host.start("owned").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");
			assertTrue(contexts.get(2).allowsBreaks());
		}
		finally { host.close(); }
	}

	@Test
	@SuppressWarnings("unchecked")
	public void replKeepsGlobalsAndReturnsStructuredValues() throws Exception
	{
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("repl-scripts").toPath(), GenericClientTestSupport.behavior(temporaryFolder.newFolder("repl-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			host.publishGameTick(luaSnapshot(7));
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
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("quest-read-scripts").toPath(), GenericClientTestSupport.behavior(temporaryFolder.newFolder("quest-read-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
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
				new GenericClientWorldSnapshot.PlayerSnapshot("Player", 2511, 3480, 0, 0),
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
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("phase-scripts").toPath(), GenericClientTestSupport.behavior(temporaryFolder.newFolder("phase-behavior").toPath()))
			.walkRandom(breaks ->
			{
				walkRequests.incrementAndGet();
				return CompletableFuture.completedFuture(
					GenericClientTestSupport.interaction("WALK_CLICK_EXECUTED phase-test"));
			})
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			host.catalog.saveScript(
				"phase-test",
				"Phase test",
				"Exercise phase and action bypasses.",
				script(
					"  local first = gc.phase('banking.complete', { policy = { breaks = false, cursor_release = 'none', fidget = 'none' } })\n" +
					"  local second = gc.phase('banking.complete', { policy = { breaks = false, cursor_release = 'none', fidget = 'none' } })\n" +
					"  local action = gc.await { action = { type = 'walk.random' }, policy = { breaks = false, cursor_release = 'none', fidget = 'none' } }\n" +
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
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("input-scripts").toPath(), GenericClientTestSupport.behavior(temporaryFolder.newFolder("input-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			host.catalog.saveScript(
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

			List<GenericClientScriptInput> inputs = host.catalog.describe("input-test").get(2, TimeUnit.SECONDS);
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
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("invalid-input-scripts").toPath(), GenericClientTestSupport.behavior(temporaryFolder.newFolder("invalid-input-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			host.catalog.saveScript(
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
	public void completesAPendingReplCallWhenTheHostCloses() throws Exception
	{
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("closing-repl-scripts").toPath(), GenericClientTestSupport.behavior(temporaryFolder.newFolder("closing-repl-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
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
	@SuppressWarnings("unchecked")
	public void timedOutLuaActionCancelsItsUnderlyingClientInput() throws Exception
	{
		CompletableFuture<Map<String, Object>> pendingWalk = new CompletableFuture<>();
		List<String> cancellations = new java.util.concurrent.CopyOnWriteArrayList<>();
		List<String> publishedStatuses = new java.util.concurrent.CopyOnWriteArrayList<>();
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("action-timeout-scripts").toPath(), GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("action-timeout-behavior").toPath()))
			.walkRandom(context -> CompletableFuture.completedFuture(
				GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> pendingWalk)
			.cancel(reason ->
			{
				cancellations.add(reason);
				Map<String, Object> receipt = new java.util.LinkedHashMap<>();
				receipt.put("status", "cancelled");
				receipt.put("reason", reason);
				pendingWalk.complete(receipt);
			})
			.report(publishedStatuses::add).build();
		try
		{
			host.catalog.saveScript(
				"action-timeout",
				"Action timeout",
				"Cancel the underlying input when an awaited action times out.",
				script("return gc.await { action = { type = 'walk.to', destination = " +
					"{ x = 3210, y = 3200, plane = 0 } }, timeout = { game_ticks = 2 } }"))
				.get(2, TimeUnit.SECONDS);

			host.start("action-timeout").get(2, TimeUnit.SECONDS);
			host.publishGameTick(luaSnapshot(1));
			host.publishGameTick(luaSnapshot(2));
			waitForStatus(host, "COMPLETED");

			Map<String, Object> result = (Map<String, Object>)
				host.getActiveScriptView().toMap().get("result");
			assertEquals("timed_out", result.get("status"));
			assertEquals("lua_action_timeout_walk_to", cancellations.get(0));
			assertTrue(pendingWalk.isDone());
			assertTrue(publishedStatuses.stream().anyMatch(value ->
				value.contains("LUA_ACTION_TIMEOUT_CANCELLED")));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void manualEscapeInterruptsAPendingReplCall() throws Exception
	{
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("manual-takeover-repl-scripts").toPath(), GenericClientTestSupport.behavior(
				temporaryFolder.newFolder("manual-takeover-repl-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(
				GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap())).build();
		try
		{
			CompletableFuture<Map<String, Object>> pending = host.evaluate(
				"gc.await { ticks = 100 }\nreturn true");
			assertEquals(
				"LUA_STOPPED reason=manual_escape",
				host.stopForManualEscape().get(2, TimeUnit.SECONDS));
			try
			{
				pending.get(2, TimeUnit.SECONDS);
				throw new AssertionError("Expected manual escape to interrupt the REPL");
			}
			catch (ExecutionException expected)
			{
				assertEquals(
					"Lua REPL interrupted by manual escape",
					expected.getCause().getMessage());
			}
			assertFalse(host.isReplBusy());
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
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporaryFolder.newFolder("presentation-scripts").toPath(), GenericClientTestSupport.behavior(temporaryFolder.newFolder("presentation-behavior").toPath()))
			.walkRandom(breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap()))
			.clock(clock::get).build();
		try
		{
			host.catalog.saveScript(
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
			host.publishGameTick(luaSnapshot(1));
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

	private static void waitForAttempts(
		List<CompletableFuture<Map<String, Object>>> attempts,
		int expected) throws InterruptedException
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (attempts.size() < expected && System.nanoTime() < deadline)
		{
			Thread.sleep(10L);
		}
		assertEquals(expected, attempts.size());
	}

	private static void waitForScript(GenericClientLuaHost host, String expected)
		throws InterruptedException
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (!expected.equals(host.getRunState().getScriptId()) &&
			System.nanoTime() < deadline)
		{
			Thread.sleep(10L);
		}
		assertEquals(expected, host.getRunState().getScriptId());
	}
}
