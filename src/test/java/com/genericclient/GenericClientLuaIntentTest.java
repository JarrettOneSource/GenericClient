package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientLuaIntentTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void cancellingBeforeAnExistingBreakEndsPreventsTheIntentBodyFromStarting() throws Exception
	{
		Path root = temporary.newFolder("waiting-intent").toPath();
		Path behaviorDirectory = root.resolve("behavior");
		GenericClientBehaviorState state = new GenericClientBehaviorState(
			GenericClientBehaviorProfile.fromAccountHash(1L).getId(), 100.0, 1.0);
		long now = System.currentTimeMillis();
		state.startBreak("micro", "none", now, now + 120_000L);
		new GenericClientBehaviorStore(behaviorDirectory).save(state, now);
		AtomicLong clock = new AtomicLong();
		AtomicInteger dispatched = new AtomicInteger();
		try (GenericClientBehaviorController behavior = GenericClientTestSupport.behavior(behaviorDirectory);
			GenericClientLuaHost host = GenericClientTestSupport.luaHost(root.resolve("scripts"), behavior)
				.clock(clock::get).npcInteract((id, name, action, within, context) -> {
					dispatched.incrementAndGet();
					return CompletableFuture.completedFuture(Map.of("status", "dispatched", "click_count", 1L));
				}).build())
		{
			host.catalog.saveScript("waiting", "Waiting", "Cancel an intent before entry", GenericClientTestSupport.script(
				"return gc.intent('waiting', function() return gc.await {action={type='npc.interact', id=1, action='Bank'}} end)"))
				.get(2, TimeUnit.SECONDS);
			host.start("waiting").get(2, TimeUnit.SECONDS);
			clock.set(TimeUnit.MINUTES.toNanos(1));
			host.publishGameTick(snapshot(1));
			host.readCurrentSnapshot("runtime").get(2, TimeUnit.SECONDS);
			assertEquals(0, dispatched.get());
			List<?> messages = (List<?>) host.controlState().get("recent_logs");
			assertFalse(messages.stream().anyMatch(message -> message.toString().contains("INTENT_LONG")));
			host.stop().get(2, TimeUnit.SECONDS);
			behavior.endActiveBreak().get(2, TimeUnit.SECONDS);
			host.readCurrentSnapshot("runtime").get(2, TimeUnit.SECONDS);
			assertEquals(0, dispatched.get());
			host.start("waiting").get(2, TimeUnit.SECONDS);
			GenericClientTestSupport.waitForStatus(host, "COMPLETED");
			assertEquals(1, dispatched.get());
		}
	}

	@Test
	public void anEmergencyPausePreservesTheIntentThroughPhaseAndTickAwaits() throws Exception
	{
		CountDownLatch dispatched = new CountDownLatch(1);
		CompletableFuture<Map<String, Object>> action = new CompletableFuture<>();
		AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
		try (GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporary, "intent-pause")
			.npcInteract((id, name, verb, within, context) -> {
				input.set(context);
				dispatched.countDown();
				return action;
			}).build())
		{
			host.catalog.saveScript("pause", "Pause", "Resume an intent after emergency input", GenericClientTestSupport.script(
				"return gc.intent('bank', function()\n" +
				"  local opened = gc.await {action={type='npc.interact', id=1, action='Bank'}}\n" +
				"  local phase = gc.phase('bank.opened')\n" +
				"  local tick = gc.await {ticks=2}\n" +
				"  return {opened=opened, phase=phase, tick=tick, intent=gc.read('behavior').intent}\nend)"))
				.get(2, TimeUnit.SECONDS);
			host.start("pause").get(2, TimeUnit.SECONDS);
			assertTrue(dispatched.await(2, TimeUnit.SECONDS));
			host.actions.pauseForEmergency("consumable").get(2, TimeUnit.SECONDS);
			assertFalse(input.get().isInputAllowed());
			action.complete(Map.of("status", "dispatched", "click_count", 1L));
			host.readCurrentSnapshot("runtime").get(2, TimeUnit.SECONDS);
			assertEquals("starting", host.controlState().get("script_state"));
			host.actions.resumeAfterEmergency("consumable").get(2, TimeUnit.SECONDS);
			host.readCurrentSnapshot("runtime").get(2, TimeUnit.SECONDS);
			host.publishGameTick(snapshot(1));
			host.readCurrentSnapshot("runtime").get(2, TimeUnit.SECONDS);
			assertEquals("WAITING", host.getStatus());
			host.publishGameTick(snapshot(2));
			GenericClientTestSupport.waitForStatus(host, "COMPLETED");
			Map<?, ?> result = (Map<?, ?>) host.getActiveScriptView().toMap().get("result");
			assertEquals("bank", result.get("intent"));
			assertEquals("dispatched", ((Map<?, ?>) result.get("opened")).get("status"));
			assertEquals("bank", ((Map<?, ?>) result.get("phase")).get("intent"));
			assertEquals("bypassed", ((Map<?, ?>) result.get("phase")).get("status"));
			assertEquals("bank", ((Map<?, ?>) result.get("tick")).get("intent"));
		}
	}

	@Test
	public void errorsUnwindNestedScopesAndAReusedReplStartsFresh() throws Exception
	{
		try (GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporary, "intent-error").build())
		{
			Map<String, Object> failed = host.evaluate(
				"return gc.intent('outer', function()\n" +
				"  return gc.intent('inner', function() error('intent exploded') end)\nend)")
				.get(2, TimeUnit.SECONDS);
			assertEquals("faulted", failed.get("status"));
			assertTrue(failed.get("error").toString().contains("intent exploded"));
			Map<String, Object> next = host.evaluate(
				"assert(gc.read('behavior').intent == 'none')\n" +
				"local values = table.pack(gc.intent('fresh', function() return nil, 42, nil end))\n" +
				"return {count=values.n, second=values[2], intent=gc.read('behavior').last_intent.intent}")
				.get(2, TimeUnit.SECONDS);
			assertEquals("completed", next.get("status"));
			Map<?, ?> value = (Map<?, ?>) next.get("value");
			assertEquals(3.0, value.get("count"));
			assertEquals(42.0, value.get("second"));
			assertEquals("fresh", value.get("intent"));
		}
	}

	@Test
	public void aTimedOutActionCannotCompleteInsideTheNextIntent() throws Exception
	{
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch nextStarted = new CountDownLatch(1);
		CompletableFuture<Map<String, Object>> first = new CompletableFuture<>();
		CompletableFuture<Map<String, Object>> next = new CompletableFuture<>();
		AtomicReference<GenericClientActivityContext> firstInput = new AtomicReference<>();
		AtomicReference<GenericClientActivityContext> nextInput = new AtomicReference<>();
		try (GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporary, "intent-timeout")
			.npcInteract((id, name, verb, within, context) -> {
				if (id == 1)
				{
					firstInput.set(context);
					firstStarted.countDown();
					return first;
				}
				nextInput.set(context);
				nextStarted.countDown();
				return next;
			}).build())
		{
			host.catalog.saveScript("timeout", "Timeout", "Continue after a timed-out intent", GenericClientTestSupport.script(
				"local expired = gc.intent('expired', function()\n" +
				"  return gc.await { action={type='npc.interact', id=1, action='Bank'}, timeout={game_ticks=1} }\nend)\n" +
				"local fresh = gc.intent('fresh', function()\n" +
				"  return gc.await { action={type='npc.interact', id=2, action='Bank'} }\nend)\n" +
				"return {expired=expired, fresh=fresh, intent=gc.read('behavior').intent}"))
				.get(2, TimeUnit.SECONDS);
			host.start("timeout").get(2, TimeUnit.SECONDS);
			assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
			host.publishGameTick(snapshot(1));
			assertTrue(nextStarted.await(2, TimeUnit.SECONDS));
			assertFalse(firstInput.get().isInputAllowed());
			assertTrue(nextInput.get().isInputAllowed());
			first.complete(Map.of("status", "dispatched", "click_count", 1L));
			host.readCurrentSnapshot("runtime").get(2, TimeUnit.SECONDS);
			assertTrue(nextInput.get().isInputAllowed());
			next.complete(Map.of("status", "dispatched", "click_count", 1L));
			GenericClientTestSupport.waitForStatus(host, "COMPLETED");
			Map<?, ?> result = (Map<?, ?>) host.getActiveScriptView().toMap().get("result");
			assertEquals("timed_out", ((Map<?, ?>) result.get("expired")).get("status"));
			assertEquals("expired", ((Map<?, ?>) result.get("expired")).get("intent"));
			assertEquals("fresh", ((Map<?, ?>) result.get("fresh")).get("intent"));
			assertEquals("none", result.get("intent"));
		}
	}

	@Test
	public void stoppingAnIntentRevokesItsInputBeforeAReplacementRunStarts() throws Exception
	{
		CountDownLatch dispatched = new CountDownLatch(1);
		CompletableFuture<Map<String, Object>> action = new CompletableFuture<>();
		AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
		try (GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporary, "intent-stop")
			.npcInteract((id, name, verb, within, context) -> {
				input.set(context);
				dispatched.countDown();
				return action;
			}).build())
		{
			host.catalog.saveScript("old", "Old", "Stop during an intent", GenericClientTestSupport.script(
				"return gc.intent('old', function() return gc.await {action={type='npc.interact', id=1, action='Bank'}} end)"))
				.get(2, TimeUnit.SECONDS);
			host.catalog.saveScript("fresh", "Fresh", "Start after a cancelled intent", GenericClientTestSupport.script(
				"return gc.intent('fresh', function() return {intent=gc.read('behavior').intent} end)"))
				.get(2, TimeUnit.SECONDS);
			host.start("old").get(2, TimeUnit.SECONDS);
			assertTrue(dispatched.await(2, TimeUnit.SECONDS));
			host.stop().get(2, TimeUnit.SECONDS);
			assertFalse(input.get().isInputAllowed());
			host.start("fresh").get(2, TimeUnit.SECONDS);
			GenericClientTestSupport.waitForStatus(host, "COMPLETED");
			action.complete(Map.of("status", "dispatched", "click_count", 1L));
			host.readCurrentSnapshot("runtime").get(2, TimeUnit.SECONDS);
			assertEquals("fresh", host.getActiveScript());
			Map<?, ?> result = (Map<?, ?>) host.getActiveScriptView().toMap().get("result");
			assertEquals("fresh", result.get("intent"));
		}
	}

	@Test
	public void aLongIntentWarnsOnceWithoutInterruptingItsVerifiedAction() throws Exception
	{
		AtomicLong clock = new AtomicLong();
		CountDownLatch dispatched = new CountDownLatch(1);
		CompletableFuture<Map<String, Object>> action = new CompletableFuture<>();
		try (GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporary, "long-intent")
			.clock(clock::get).npcInteract((id, name, verb, within, context) -> {
				dispatched.countDown();
				return action;
			}).build())
		{
			host.catalog.saveScript("long", "Long", "Observe a slow action inside an intent", GenericClientTestSupport.script(
				"gc.intent('slow', function()\n" +
				"  return gc.await { action={type='npc.interact', id=1, action='Bank'}, timeout={game_ticks=100} }\n" +
				"end)\nreturn gc.read('behavior').last_intent"))
				.get(2, TimeUnit.SECONDS);
			host.start("long").get(2, TimeUnit.SECONDS);
			assertTrue(dispatched.await(2, TimeUnit.SECONDS));
			clock.set(TimeUnit.SECONDS.toNanos(31));
			host.publishGameTick(snapshot(1));
			host.readCurrentSnapshot("runtime").get(2, TimeUnit.SECONDS);
			clock.set(TimeUnit.SECONDS.toNanos(40));
			host.publishGameTick(snapshot(2));
			host.readCurrentSnapshot("runtime").get(2, TimeUnit.SECONDS);
			assertFalse(action.isDone());
			List<?> messages = (List<?>) host.controlState().get("recent_logs");
			assertEquals(1, messages.stream().filter(message -> message.toString().contains("INTENT_LONG")).count());
			action.complete(Map.of("status", "dispatched", "click_count", 1L));
			GenericClientTestSupport.waitForStatus(host, "COMPLETED");
			Map<?, ?> result = (Map<?, ?>) host.getActiveScriptView().toMap().get("result");
			assertEquals(40_000.0, result.get("elapsed_millis"));
		}
	}

	@Test
	public void nestedIntentsPreserveReturnValuesAndSafetyWhileSuppressingInnerBoundaries() throws Exception
	{
		List<GenericClientActivityContext> contexts = new ArrayList<>();
		try (GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporary, "nested")
			.npcInteract((id, name, action, within, context) -> {
				contexts.add(context);
				return CompletableFuture.completedFuture(Map.of("status", "dispatched", "click_count", 1L));
			}).build())
		{
			host.catalog.saveScript("intent", "Intent", "Exercise a nested semantic scope", GenericClientTestSupport.script(
				"gc.activity('travel')\n" +
				"local first, second, third = gc.intent('bank', function()\n" +
				"  assert(gc.read('behavior').intent == 'bank')\n" +
				"  local opened = gc.await { action={type='npc.interact', id=1, action='Bank'} }\n" +
				"  return gc.intent('withdraw', function()\n" +
				"    gc.activity('hazardous_travel')\n" +
				"    local selected = gc.await { action={type='npc.interact', id=2, action='Attack'} }\n" +
				"    return opened, selected, 42\n" +
				"  end)\n" +
				"end)\n" +
				"local behavior = gc.read('behavior')\n" +
				"return {first=first, second=second, third=third, active=behavior.intent, last=behavior.last_intent}"))
				.get(2, TimeUnit.SECONDS);
			host.start("intent").get(2, TimeUnit.SECONDS);
			GenericClientTestSupport.waitForStatus(host, "COMPLETED");
			Map<?, ?> result = (Map<?, ?>) host.getActiveScriptView().toMap().get("result");
			assertEquals(42.0, result.get("third"));
			assertEquals("none", result.get("active"));
			assertEquals("bank", ((Map<?, ?>) result.get("first")).get("intent"));
			assertEquals("bank", ((Map<?, ?>) result.get("second")).get("intent"));
			Map<?, ?> last = (Map<?, ?>) result.get("last");
			assertEquals("bank", last.get("intent"));
			assertTrue(last.containsKey("behavior_before"));
			assertTrue(last.containsKey("behavior_after"));
			assertEquals(2, contexts.size());
			for (GenericClientActivityContext context : contexts)
			{
				assertFalse(context.allowsBreaks());
				assertFalse(context.allowsCursorRelease());
			}
			assertTrue(contexts.get(1).refreshesWalkClicks());
			assertEquals(GenericClientBehaviorPolicy.PrayerOwner.GUARD, contexts.get(1).policy().prayerOwner);
			assertEquals(180, contexts.get(1).mouseMoveDurationMillis(500));
			List<?> messages = (List<?>) host.controlState().get("recent_logs");
			assertEquals(1, messages.stream().filter(message -> message.toString().contains("INTENT_STARTED")).count());
			assertEquals(1, messages.stream().filter(message -> message.toString().contains("INTENT_ENDED")).count());
		}
	}

	private static GenericClientSnapshot snapshot(long tick)
	{
		return new GenericClientSnapshot(tick, "LOGGED_IN", 240,
			new GenericClientWorldSnapshot.PlayerSnapshot("intent", 3202, 3428, 0, -1), List.of());
	}
}
