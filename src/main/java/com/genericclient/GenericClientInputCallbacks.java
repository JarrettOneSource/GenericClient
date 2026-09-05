package com.genericclient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Binds queued input work to the operation that scheduled it, under that input's lock. */
final class GenericClientInputCallbacks
{
	private final Object lock;
	private final Supplier<? extends CompletableFuture<?>> current;
	private final ScheduledExecutorService executor;
	private final List<ScheduledFuture<?>> pending = new ArrayList<>();

	GenericClientInputCallbacks(Object lock, Supplier<? extends CompletableFuture<?>> current, ScheduledExecutorService executor)
	{
		this.lock = lock;
		this.current = current;
		this.executor = executor;
	}

	void schedule(Runnable action, long delayMillis)
	{
		synchronized (lock)
		{
			pending.add(executor.schedule(bind(action), delayMillis, TimeUnit.MILLISECONDS));
		}
	}

	void cancelPending()
	{
		synchronized (lock)
		{
			for (ScheduledFuture<?> future : pending) future.cancel(false);
			pending.clear();
		}
	}

	Runnable bind(Runnable action)
	{
		CompletableFuture<?> owner = current.get();
		return () -> {
			synchronized (lock)
			{
				if (owner != null && owner == current.get() && !owner.isDone()) action.run();
			}
		};
	}

	<T> BiConsumer<T, Throwable> bind(BiConsumer<T, Throwable> action)
	{
		CompletableFuture<?> owner = current.get();
		return (value, error) -> {
			synchronized (lock)
			{
				if (owner != null && owner == current.get() && !owner.isDone()) action.accept(value, error);
			}
		};
	}

	<T, R> Function<T, CompletableFuture<R>> bind(Function<T, CompletableFuture<R>> action)
	{
		CompletableFuture<?> owner = current.get();
		return value -> {
			synchronized (lock)
			{
				return owner != null && owner == current.get() && !owner.isDone()
					? action.apply(value)
					: CompletableFuture.failedFuture(new CancellationException("Input operation replaced"));
			}
		};
	}
}
