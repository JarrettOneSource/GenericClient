package com.genericclient;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class GenericClientActionBoundaryTest
{
	private static final GenericClientActivityContext CONTEXT = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.GENERAL);

	@Test
	public void verifiedCompletionClosesRestBranchesWithoutWaitingForAnotherTick() throws Exception
	{
		CompletableFuture<Map<String, Object>> action = new CompletableFuture<>();
		GenericClientActionBoundary boundary = new GenericClientActionBoundary(c -> receipt("ready"), c -> receipt("no_break"));
		GenericClientActionBoundary.Ticket ticket = new GenericClientActionBoundary.Ticket();
		CompletableFuture<Map<String, Object>> result = boundary.execute(ticket, CONTEXT, () -> action, true);
		GenericClientActivityContext rest = CONTEXT.withTicket(ticket).forkInputScope();
		assertTrue(rest.isInputAllowed());
		action.complete(Map.of("status", "arrived"));
		assertEquals("arrived", result.get().get("status"));
		assertFalse(rest.isInputAllowed());
	}

	@Test
	public void cancellationDuringBeforePreventsDispatch() throws Exception
	{
		CompletableFuture<Map<String, Object>> before = new CompletableFuture<>();
		AtomicInteger dispatches = new AtomicInteger();
		GenericClientActionBoundary boundary = new GenericClientActionBoundary(c -> before,
			c -> { throw new AssertionError("after cancelled action"); });
		GenericClientActionBoundary.Ticket ticket = new GenericClientActionBoundary.Ticket();
		CompletableFuture<Map<String, Object>> result = boundary.execute(ticket, CONTEXT,
			() -> { dispatches.incrementAndGet(); return receipt("set"); }, true);
		ticket.cancel();
		before.complete(Collections.singletonMap("status", "ready"));
		assertEquals("cancelled", result.get().get("status"));
		assertEquals(0, dispatches.get());
	}

	@Test
	public void afterWaitsForVerifiedCompletionAndSkipsRejectedActions() throws Exception
	{
		CompletableFuture<Map<String, Object>> action = new CompletableFuture<>();
		AtomicInteger after = new AtomicInteger();
		GenericClientActionBoundary boundary = new GenericClientActionBoundary(c -> receipt("ready"),
			c -> { after.incrementAndGet(); return receipt("no_break"); });
		CompletableFuture<Map<String, Object>> result = boundary.execute(
			new GenericClientActionBoundary.Ticket(), CONTEXT, () -> action, true);
		assertFalse(result.isDone());
		assertEquals(0, after.get());
		action.complete(Collections.singletonMap("status", "set"));
		assertEquals("set", result.get().get("status"));
		assertEquals(1, after.get());
		boundary.execute(new GenericClientActionBoundary.Ticket(), CONTEXT, () -> receipt("rejected"), true).get();
		assertEquals(1, after.get());
	}

	@Test
	public void oldCompletionCannotStartAfterBehaviorOrAlterReplacement() throws Exception
	{
		CompletableFuture<Map<String, Object>> old = new CompletableFuture<>();
		AtomicInteger after = new AtomicInteger();
		GenericClientActionBoundary boundary = new GenericClientActionBoundary(c -> receipt("ready"),
			c -> { after.incrementAndGet(); return receipt("no_break"); });
		GenericClientActionBoundary.Ticket first = new GenericClientActionBoundary.Ticket();
		boundary.execute(first, CONTEXT, () -> old, true);
		first.cancel();
		Map<String, Object> replacement = boundary.execute(new GenericClientActionBoundary.Ticket(),
			CONTEXT, () -> receipt("set"), true).get();
		old.complete(Collections.singletonMap("status", "set"));
		assertEquals(1, after.get());
		assertEquals("set", replacement.get("status"));
	}

	@Test
	public void afterFailurePreservesTheVerifiedActionOutcome() throws Exception
	{
		GenericClientActionBoundary boundary = new GenericClientActionBoundary(c -> receipt("ready"),
			c -> CompletableFuture.failedFuture(new IllegalStateException("cursor unavailable")));
		Map<String, Object> result = boundary.execute(new GenericClientActionBoundary.Ticket(),
			CONTEXT, () -> receipt("set"), true).get();
		assertEquals("set", result.get("status"));
		assertEquals("failed", ((Map<?, ?>) result.get("behavior_after")).get("status"));
	}

	private static CompletableFuture<Map<String, Object>> receipt(String status)
	{
		return CompletableFuture.completedFuture(Collections.singletonMap("status", status));
	}
}
