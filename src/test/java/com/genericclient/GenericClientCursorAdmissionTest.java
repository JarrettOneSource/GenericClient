package com.genericclient;

import static org.junit.Assert.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientCursorAdmissionTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void anExplicitTickWaitPolicyControlsRestWithoutOpeningAnActionBoundary() throws Exception
	{
		try (GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporary, "cursor-wait-policy").build())
		{
			host.publishGameTick(GenericClientDamageTrackerTest.snapshot(10, 80, 0));
			CompletableFuture<Map<String, Object>> evaluation = host.evaluate(
				"gc.activity('combat'); gc.await {ticks=4, activity='travel', humanize=true, " +
				"policy={fidget='drift', walk_refresh=true}}");
			host.readCurrentSnapshot("player").get(2, TimeUnit.SECONDS);
			GenericClientActivityContext context = host.getBehaviorContext();
			assertEquals(GenericClientActivityContext.Activity.TRAVEL, context.getActivity());
			assertEquals(GenericClientBehaviorPolicy.Fidget.DRIFT, context.policy().fidget);
			assertTrue(context.refreshesWalkClicks());
			assertEquals(1800, host.quietMillis(null, 0));
			assertFalse(host.isBehaviorPaused());
			assertFalse(evaluation.isDone());
		}
	}

	@Test
	public void walkerWindowsBelongOnlyToTheAwaitThatStartedTheJourney() throws Exception
	{
		CompletableFuture<GenericClientWalkRequest> started = new CompletableFuture<>();
		CompletableFuture<Map<String, Object>> walking = new CompletableFuture<>();
		try (GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporary, "cursor-walk")
			.walkTo((request, clicks) -> { started.complete(request); return walking; }).build())
		{
			host.publishGameTick(GenericClientDamageTrackerTest.snapshot(10, 80, 0));
			CompletableFuture<Map<String, Object>> evaluation = host.evaluate(
				"return gc.walk.to {destination={x=3250,y=3250,plane=0}} ");
			GenericClientWalkRequest request = started.get(2, TimeUnit.SECONDS);
			assertEquals(0, host.quietMillis(new GenericClientActionBoundary.Ticket(), 9000));
			assertEquals(9000, host.quietMillis(request.activityContext.inputTicket(), 9000));
			assertEquals(0, host.quietMillis(request.activityContext.inputTicket(), 0));
			walking.complete(Map.of("status", "arrived"));
			assertEquals("completed", evaluation.get(2, TimeUnit.SECONDS).get("status"));
			assertEquals(Long.MAX_VALUE, host.quietMillis(null, 0));
		}
	}

	@Test
	public void theEarliestCoroutineWaitClosesTheFidgetWindowBeforeItsNextTick() throws Exception
	{
		try (GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporary, "cursor-wait").build())
		{
			host.publishGameTick(GenericClientDamageTrackerTest.snapshot(10, 80, 0));
			host.catalog.saveScript("rest", "Rest", "Wait for a tick boundary",
				GenericClientTestSupport.script("gc.activity('skilling'); gc.await {ticks=10}"))
				.get(2, TimeUnit.SECONDS);
			host.start("rest").get(2, TimeUnit.SECONDS);
			assertEquals(5400, host.quietMillis(null, 0));
			CompletableFuture<Map<String, Object>> evaluation = host.evaluate("gc.await {ticks=2}");
			host.readCurrentSnapshot("player").get(2, TimeUnit.SECONDS);
			assertEquals(600, host.quietMillis(null, 0));
			host.publishGameTick(GenericClientDamageTrackerTest.snapshot(11, 80, 0));
			host.readCurrentSnapshot("player").get(2, TimeUnit.SECONDS);
			assertEquals(0, host.quietMillis(null, 0));
			host.publishGameTick(GenericClientDamageTrackerTest.snapshot(12, 80, 0));
			assertEquals("completed", evaluation.get(2, TimeUnit.SECONDS).get("status"));
			assertEquals(4200, host.quietMillis(null, 0));
		}
	}
}
