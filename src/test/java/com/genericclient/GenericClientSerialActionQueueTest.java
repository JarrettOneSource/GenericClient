package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class GenericClientSerialActionQueueTest
{
	@Test
	public void runsActionsInOrderAndCancelsOnlyWorkThatHasNotStarted()
	{
		GenericClientSerialActionQueue queue = new GenericClientSerialActionQueue();
		CompletableFuture<String> firstAction = new CompletableFuture<>();
		AtomicInteger secondStarts = new AtomicInteger();

		CompletableFuture<String> first = queue.submit(
			() -> firstAction,
			() -> "first-cancelled");
		CompletableFuture<String> second = queue.submit(
			() ->
			{
				secondStarts.incrementAndGet();
				return CompletableFuture.completedFuture("second-complete");
			},
			() -> "second-cancelled");

		assertFalse(first.isDone());
		assertFalse(second.isDone());
		queue.cancelPending();
		firstAction.complete("first-complete");

		assertEquals("first-complete", first.join());
		assertEquals("second-cancelled", second.join());
		assertEquals(0, secondStarts.get());

		CompletableFuture<String> third = queue.submit(
			() -> CompletableFuture.completedFuture("third-complete"),
			() -> "third-cancelled");
		assertTrue(third.isDone());
		assertEquals("third-complete", third.join());
	}
}
