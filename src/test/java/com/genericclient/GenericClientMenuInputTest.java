package com.genericclient;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GenericClientMenuInputTest
{
	@Test
	public void stoppingAWalkDoesNotCancelInputOwnedByTheGuard() throws Exception
	{
		GenericClientActivityContext walk = GenericClientActivityContext.none()
			.withTicket(new GenericClientActionBoundary.Ticket());
		GenericClientActivityContext guard = GenericClientActivityContext.none()
			.withTicket(new GenericClientActionBoundary.Ticket());
		net.runelite.client.callback.ClientThread deferred = new net.runelite.client.callback.ClientThread()
		{
			@Override public void invoke(Runnable action) { }
		};
		GenericClientMenuInput menu = new GenericClientMenuInput(null, deferred, null, null, ignored -> { });
		try
		{
			java.util.concurrent.CompletableFuture<java.util.Map<String, Object>> input = menu.interact(
				() -> { throw new AssertionError("Deferred input resolved unexpectedly"); }, guard);
			menu.cancel("walk_interrupted", walk);
			assertTrue(menu.isRunning());
			assertFalse(input.isDone());
			menu.cancel("test_complete", guard);
			org.junit.Assert.assertEquals("rejected", input.get(2, java.util.concurrent.TimeUnit.SECONDS).get("status"));
		}
		finally { menu.close(); }
	}

	@Test
	public void lateCompositeMemberCannotResolveOrClickAfterItsAwaitWasCancelled() throws Exception
	{
		GenericClientActionBoundary.Ticket ticket = new GenericClientActionBoundary.Ticket();
		GenericClientActivityContext context = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.GENERAL).withTicket(ticket);
		ticket.cancel();
		GenericClientMenuInput menu = new GenericClientMenuInput(null, null, null, null, ignored -> { });
		try
		{
			java.util.Map<String, Object> receipt = menu.interact(() -> {
				throw new AssertionError("Cancelled action resolved a new target");
			}, context).get();
			org.junit.Assert.assertEquals("action_cancelled", receipt.get("result"));
		}
		finally { menu.close(); }
	}

	@Test
	public void settlesOnlyAnActiveSelectedWidgetWhoseMenuHasNotUpdated()
	{
		assertTrue(GenericClientMenuInput.shouldSettleSelectedWidgetTarget(true, true, 0));
		assertTrue(GenericClientMenuInput.shouldSettleSelectedWidgetTarget(true, true, 9));
		assertFalse(GenericClientMenuInput.shouldSettleSelectedWidgetTarget(true, true, 10));
		assertFalse(GenericClientMenuInput.shouldSettleSelectedWidgetTarget(false, true, 0));
		assertFalse(GenericClientMenuInput.shouldSettleSelectedWidgetTarget(true, false, 0));
	}
}
