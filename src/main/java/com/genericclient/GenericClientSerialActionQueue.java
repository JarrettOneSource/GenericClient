package com.genericclient;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Runs short client-input actions in order and can invalidate work that has not started. */
final class GenericClientSerialActionQueue
{
	private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
	private long generation;

	synchronized <T> CompletableFuture<T> submit(
		Supplier<CompletableFuture<T>> action,
		Supplier<T> cancelled)
	{
		CompletableFuture<T> result = new CompletableFuture<>();
		long submittedGeneration = generation;
		tail = tail.handle((ignored, error) -> null)
			.thenCompose(ignored ->
			{
				synchronized (GenericClientSerialActionQueue.this)
				{
					if (submittedGeneration != generation)
					{
						return CompletableFuture.completedFuture(cancelled.get());
					}
				}
				return action.get();
			})
			.handle((value, error) ->
			{
				if (error == null)
				{
					result.complete(value);
				}
				else
				{
					result.completeExceptionally(error);
				}
				return null;
			});
		return result;
	}

	synchronized void cancelPending()
	{
		generation++;
	}
}
