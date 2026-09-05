package com.genericclient;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientActionLifetimeTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void luaWalkHelperPassesViaAndTimeoutWithoutChangingTheOptionsTable() throws Exception
	{
		AtomicReference<GenericClientWalkRequest> request = new AtomicReference<>();
		GenericClientLuaHost host = host((journeyRequest, routeClickBoundary) ->
		{
			request.set(journeyRequest);
			return CompletableFuture.completedFuture(Collections.singletonMap("status", "arrived"));
		});
		try
		{
			host.publishGameTick(snapshot(1));
			Map<String, Object> result = host.evaluate("local options = { destination={x=3210,y=3200,plane=0}, " +
				"via={{x=3200,y=3210,plane=0}}, arrival_tiles={{x=3209,y=3200,plane=0}}, ticks=123 }; local receipt=gc.walk.to(options); " +
				"return options.type == nil and receipt.status == 'arrived'").get(2, TimeUnit.SECONDS);
			assertEquals(true, result.get("value"));
			assertEquals(123, request.get().timeoutTicks);
			assertEquals(1, request.get().via.size());
			assertEquals(1, request.get().arrivalTiles.size());
		}
		finally { host.close(); }
	}

	@Test
	public void invalidationDiscardsSnapshotsAlreadyQueuedBeforeLogout() throws Exception
	{
		GenericClientLuaHost host = host((journeyRequest, routeClickBoundary) -> CompletableFuture.completedFuture(Collections.emptyMap()));
		CompletableFuture<Void> release = new CompletableFuture<>();
		try
		{
			host.publishGameTick(snapshot(1));
			assertNotNull(host.readCurrentSnapshot("player").get(2, TimeUnit.SECONDS));
			host.catalog.saveScript("waiting", "Waiting", "Snapshot test", script("gc.await { event = 'game.tick' }"))
				.get(2, TimeUnit.SECONDS);
			CountDownLatch entered = new CountDownLatch(1);
			host.setScriptStartListener((id, owner) -> { entered.countDown(); release.join(); });
			CompletableFuture<String> started = host.start("waiting");
			assertTrue(entered.await(2, TimeUnit.SECONDS));
			host.publishGameTick(snapshot(2));
			host.clearSnapshot();
			release.complete(null);
			started.get(2, TimeUnit.SECONDS);
			assertNull(host.readCurrentSnapshot("account").get(2, TimeUnit.SECONDS));
			host.publishGameTick(snapshot(3));
			assertNotNull(host.readCurrentSnapshot("player").get(2, TimeUnit.SECONDS));
		}
		finally { release.complete(null); host.close(); }
	}

	@Test
	public void lateCompletionFromTimedOutReplCannotCompleteItsNextAwait() throws Exception
	{
		CompletableFuture<Map<String, Object>> first = new CompletableFuture<>();
		CompletableFuture<Map<String, Object>> second = new CompletableFuture<>();
		AtomicInteger dispatches = new AtomicInteger();
		GenericClientLuaHost host = host((journeyRequest, routeClickBoundary) -> dispatches.incrementAndGet() == 1 ? first : second);
		try
		{
			host.publishGameTick(snapshot(1));
			CompletableFuture<Map<String, Object>> oldEval = host.evaluate(walk(1));
			host.readCurrentSnapshot("player").get(2, TimeUnit.SECONDS);
			host.publishGameTick(snapshot(2));
			assertEquals("timed_out", ((Map<?, ?>) oldEval.get(2, TimeUnit.SECONDS).get("value")).get("status"));
			CompletableFuture<Map<String, Object>> newEval = host.evaluate(walk(20));
			host.readCurrentSnapshot("player").get(2, TimeUnit.SECONDS);
			first.complete(Collections.singletonMap("status", "arrived"));
			host.readCurrentSnapshot("player").get(2, TimeUnit.SECONDS);
			assertFalse(newEval.isDone());
			second.complete(Collections.singletonMap("status", "arrived"));
			assertEquals("arrived", ((Map<?, ?>) newEval.get(2, TimeUnit.SECONDS).get("value")).get("status"));
		}
		finally { host.close(); }
	}

	@Test
	public void pauseRevokesInputAuthorityWithoutChangingTheCapturedPolicy() throws Exception
	{
		CompletableFuture<Map<String, Object>> action = new CompletableFuture<>();
		AtomicReference<GenericClientActivityContext> captured = new AtomicReference<>();
		GenericClientLuaHost host = host((journeyRequest, routeClickBoundary) -> {
			captured.set(journeyRequest.activityContext);
			return action;
		});
		try
		{
			host.publishGameTick(snapshot(1));
			host.catalog.saveScript("owned", "Owned", "Authority test", script(walk(20))).get(2, TimeUnit.SECONDS);
			host.start("owned").get(2, TimeUnit.SECONDS);
			assertTrue(captured.get().isInputAllowed());
			host.actions.pauseForEmergency("manual_mouse_preemption").get(2, TimeUnit.SECONDS);
			assertFalse(captured.get().isInputAllowed());
			assertTrue(captured.get().allowsBreaks());
			host.actions.resumeAfterEmergency("manual_mouse_idle").get(2, TimeUnit.SECONDS);
			assertTrue(captured.get().isInputAllowed());
			action.complete(Collections.singletonMap("status", "arrived"));
			host.readCurrentSnapshot("player").get(2, TimeUnit.SECONDS);
			assertEquals("COMPLETED", host.getStatus());
			assertFalse(captured.get().isInputAllowed());
		}
		finally { host.close(); }
	}

	private GenericClientLuaHost host(GenericClientLuaActions.WalkToAction walk) throws Exception
	{
		return GenericClientTestSupport.luaHost(temporary.newFolder("scripts").toPath(), GenericClientTestSupport.behavior(temporary.newFolder("behavior").toPath()))
			.walkRandom(context -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")))
			.walkTo(walk).build();
	}

	private static String script(String body) { return "return { run = function() " + body + " end }"; }
	private static String walk(int timeout)
	{
		return "return gc.await { action = { type = 'walk.to', destination = { x=3210, y=3200, plane=0 } }, " +
			"timeout = { game_ticks = " + timeout + " } }";
	}
	private static GenericClientSnapshot snapshot(long tick)
	{
		return new GenericClientSnapshot(tick, "LOGGED_IN", 240,
			new GenericClientWorldSnapshot.PlayerSnapshot("lifetime-test", 3200, 3200, 0, -1), Collections.emptyList());
	}
}
