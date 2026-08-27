package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
	public void installsBundledScriptsWithoutStartingDiagnostics() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("idle-start-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("idle-start-behavior").toPath()),
			message -> { });
		try
		{
			assertTrue(host.listScripts().stream()
				.anyMatch(script -> "npc-diagnostics".equals(script.getId())));
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
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
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
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(name, action, within, breaks) ->
			{
				request.set(name + ":" + action + ":" + within + ":" + breaks);
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
					"name = 'Banker', action = 'Bank', within = 7 }, breaks = false }\n" +
					"gc.log('info', 'npc-result', receipt)"))
				.get(2, TimeUnit.SECONDS);

			host.start("npc-action").get(2, TimeUnit.SECONDS);
			waitForStatus(host, "COMPLETED");

			assertEquals("Banker:Bank:7:false", request.get());
			assertTrue(host.getRecentLogs().contains("npc-result"));
			assertTrue(host.getRecentLogs().contains("menu_action_executed"));
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
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(name, action, within, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
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
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(name, action, within, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
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
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
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
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
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
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
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
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
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
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
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
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
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
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
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
			(destination, within, timeout, breaks) ->
			{
				requestedDestination.set(destination);
				requestedWithin.set(within);
				requestedTimeout.set(timeout);
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
	public void completesAPendingReplCallWhenTheHostCloses() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("closing-repl-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
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
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
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
			assertTrue(host.getRecentLogs().contains("action-consumed"));
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
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
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
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			(name, action, within, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
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

	private static String script(String body)
	{
		return "return { run = function(input)\n" + body + "\nend }\n";
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
