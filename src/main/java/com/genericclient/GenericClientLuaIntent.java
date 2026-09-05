package com.genericclient;

import static com.genericclient.GenericClientErrors.rootMessage;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** One coroutine's nested semantic scope and the input authority that ends with it. */
final class GenericClientLuaIntent
{
	private static final long LONG_INTENT_MILLIS = 30_000L;
	private final LongSupplier clock;
	private final BiConsumer<String, String> reporter;
	private Scope active;
	private Map<String, Object> last = Map.of();

	GenericClientLuaIntent(LongSupplier clock, BiConsumer<String, String> reporter)
	{
		this.clock = clock;
		this.reporter = reporter;
	}

	static boolean isControl(String action) { return "intent.begin".equals(action) || "intent.end".equals(action); }

	CompletableFuture<Map<String, Object>> begin(String name, GenericClientActivityContext context,
		GenericClientActionBoundary boundary)
	{
		Scope scope;
		synchronized (this)
		{
			if (active != null)
			{
				active.names.push(name);
				return CompletableFuture.completedFuture(receipt("started", active.name));
			}
			scope = new Scope(name);
			active = scope;
		}
		boundary.execute(scope.ticket, context.withTicket(scope.ticket), () ->
		{
			scope.startedNanos = clock.getAsLong();
			scope.started = true;
			reporter.accept("info", "INTENT_STARTED name=" + name);
			scope.entered.complete(receipt("started", name));
			return scope.body;
		}, true).whenComplete((result, error) -> finish(scope, result, error));
		return scope.entered;
	}

	CompletableFuture<Map<String, Object>> end(String name, boolean failed)
	{
		Scope scope;
		synchronized (this)
		{
			scope = active;
			if (scope == null || !name.equals(scope.names.peek()))
				throw new IllegalStateException("Intent exit does not match its entry: " + name);
			scope.names.pop();
			if (!scope.names.isEmpty()) return CompletableFuture.completedFuture(receipt("complete", scope.name));
		}
		Map<String, Object> result = receipt(failed ? "failed" : "complete", name);
		result.put("elapsed_millis", elapsedMillis(scope));
		scope.body.complete(result);
		return scope.finished;
	}

	synchronized GenericClientActivityContext context(GenericClientActivityContext context)
	{
		return active == null ? context : context.inIntent();
	}

	synchronized GenericClientActionBoundary.Ticket newActionTicket()
	{
		return active == null ? new GenericClientActionBoundary.Ticket() : active.ticket.child();
	}

	void suspendInput(boolean suspended)
	{
		Scope scope;
		synchronized (this) { scope = active; }
		if (scope != null) scope.ticket.suspendInput(suspended);
	}

	void cancel(String reason)
	{
		Scope scope;
		synchronized (this)
		{
			scope = active;
			if (scope == null) return;
			scope.cancelReason = reason;
		}
		scope.ticket.cancel();
		scope.body.complete(receipt("cancelled", scope.name));
	}

	synchronized void onGameTick()
	{
		if (active == null || !active.started || active.names.isEmpty() || active.warned) return;
		long elapsed = elapsedMillis(active);
		if (elapsed <= LONG_INTENT_MILLIS) return;
		active.warned = true;
		reporter.accept("warn", "INTENT_LONG name=" + active.name + " elapsedMillis=" + elapsed);
	}

	synchronized Map<String, Object> decorate(Map<String, Object> value)
	{
		if (active == null) return value;
		Map<String, Object> result = new LinkedHashMap<>(value);
		result.put("intent", active.name);
		return result;
	}

	synchronized Map<String, Object> status(Map<String, Object> behavior)
	{
		behavior.put("intent", active == null ? "none" : active.name);
		behavior.put("intent_depth", active == null ? 0 : active.names.size());
		behavior.put("intent_elapsed_millis", active == null ? 0L : elapsedMillis(active));
		behavior.put("last_intent", last);
		return behavior;
	}

	private void finish(Scope scope, Map<String, Object> receipt, Throwable error)
	{
		Map<String, Object> result = error == null ? new LinkedHashMap<>(receipt) : receipt("rejected", scope.name);
		result.put("intent", scope.name);
		if (error != null) result.put("reason", rootMessage(error));
		if (scope.cancelReason != null) result.put("reason", scope.cancelReason);
		synchronized (this)
		{
			active = null;
			last = result;
		}
		reporter.accept("info", "INTENT_ENDED name=" + scope.name + " status=" + result.get("status"));
		scope.ticket.cancel();
		scope.entered.complete(result);
		scope.finished.complete(result);
	}

	private long elapsedMillis(Scope scope)
	{
		return !scope.started ? 0L : TimeUnit.NANOSECONDS.toMillis(Math.max(0L, clock.getAsLong() - scope.startedNanos));
	}

	private static Map<String, Object> receipt(String status, String name)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("status", status);
		value.put("intent", name);
		return value;
	}

	private static final class Scope
	{
		private final String name;
		private final ArrayDeque<String> names = new ArrayDeque<>();
		private final GenericClientActionBoundary.Ticket ticket = new GenericClientActionBoundary.Ticket();
		private final CompletableFuture<Map<String, Object>> entered = new CompletableFuture<>();
		private final CompletableFuture<Map<String, Object>> body = new CompletableFuture<>();
		private final CompletableFuture<Map<String, Object>> finished = new CompletableFuture<>();
		private volatile long startedNanos;
		private volatile boolean started;
		private boolean warned;
		private volatile String cancelReason;

		private Scope(String name)
		{
			this.name = name;
			names.push(name);
		}
	}
}
