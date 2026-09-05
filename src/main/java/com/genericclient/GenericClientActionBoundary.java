package com.genericclient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/** Owns the discretionary boundary of one semantic action, including its verified result. */
final class GenericClientActionBoundary
{
	private final Function<GenericClientActivityContext, CompletableFuture<Map<String, Object>>> before;
	private final Function<GenericClientActivityContext, CompletableFuture<Map<String, Object>>> after;

	GenericClientActionBoundary(GenericClientBehaviorController behavior)
	{
		this(behavior::beforeAction, behavior::afterAction);
	}

	GenericClientActionBoundary(
		Function<GenericClientActivityContext, CompletableFuture<Map<String, Object>>> before,
		Function<GenericClientActivityContext, CompletableFuture<Map<String, Object>>> after)
	{
		this.before = before;
		this.after = after;
	}

	CompletableFuture<Map<String, Object>> execute(
		Ticket ticket,
		GenericClientActivityContext context,
		Supplier<CompletableFuture<Map<String, Object>>> action,
		boolean rollAfter)
	{
		if (ticket.isCancelled())
		{
			ticket.cancel();
			return ticket.result;
		}
		try
		{
			before.apply(context).thenCompose(beforeReceipt ->
			{
				synchronized (ticket)
				{
					if (!ticket.isActive()) return CompletableFuture.completedFuture(cancelled());
					return action.get().thenCompose(receipt ->
					{
						synchronized (ticket)
						{
							if (ticket.isCancelled()) return CompletableFuture.completedFuture(cancelled());
							if (receipt == null) throw new IllegalStateException("Action returned no receipt");
							if (!rollAfter || !completedInput(receipt))
							{
								return CompletableFuture.completedFuture(attach(receipt, beforeReceipt,
									Collections.singletonMap("status", "bypassed")));
							}
							return CompletableFuture.completedFuture(context).thenCompose(after).handle((afterReceipt, error) ->
								attachAfter(receipt, beforeReceipt, afterReceipt, error));
						}
					});
				}
			}).whenComplete((receipt, error) ->
			{
				synchronized (ticket)
				{
					if (ticket.isCancelled()) return;
				}
				if (error == null) ticket.result.complete(receipt);
				else ticket.result.completeExceptionally(error);
			});
		}
		catch (RuntimeException error)
		{
			ticket.result.completeExceptionally(error);
		}
		return ticket.result;
	}

	private static Map<String, Object> attachAfter(Map<String, Object> receipt, Map<String, Object> beforeReceipt,
		Map<String, Object> afterReceipt, Throwable error)
	{
		if (error != null)
		{
			afterReceipt = new LinkedHashMap<>();
			afterReceipt.put("status", "failed");
			afterReceipt.put("reason", error.getMessage());
		}
		return attach(receipt, beforeReceipt, afterReceipt);
	}

	private static boolean completedInput(Map<String, Object> receipt)
	{
		String status = String.valueOf(receipt.get("status"));
		return "dispatched".equals(status) || "set".equals(status) || "complete".equals(status) ||
			"completed".equals(status) || "arrived".equals(status) || "cast".equals(status);
	}

	private static Map<String, Object> attach(Map<String, Object> receipt,
		Map<String, Object> before, Map<String, Object> after)
	{
		Map<String, Object> result = new LinkedHashMap<>(receipt);
		result.put("behavior_before", before);
		result.put("behavior_after", after);
		return result;
	}

	private static Map<String, Object> cancelled()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("status", "cancelled");
		value.put("reason", "action_cancelled");
		return value;
	}

	static final class Ticket
	{
		private final Ticket parent;
		private final CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		private volatile boolean cancelled;
		private volatile boolean inputSuspended;
		private Ticket child;

		Ticket() { this(null); }
		private Ticket(Ticket parent) { this.parent = parent; }

		boolean isActive() { return !cancelled && !inputSuspended && (parent == null || parent.isActive()); }
		private boolean isCancelled() { return cancelled || parent != null && parent.isCancelled(); }

		synchronized boolean applyIfCurrent(Runnable update)
		{
			if (isCancelled()) return false;
			update.run();
			return true;
		}

		void suspendInput(boolean suspended)
		{
			Ticket pending;
			synchronized (this)
			{
				inputSuspended = suspended;
				pending = child;
			}
			if (pending != null) pending.suspendInput(suspended);
		}

		void cancel()
		{
			Ticket pending;
			synchronized (this)
			{
				cancelled = true;
				pending = child;
			}
			if (pending != null) pending.cancel();
			result.complete(GenericClientActionBoundary.cancelled());
		}

		Ticket child()
		{
			Ticket previous;
			Ticket next = new Ticket(this);
			synchronized (this)
			{
				previous = child;
				child = next;
				next.inputSuspended = inputSuspended;
			}
			if (previous != null) previous.cancel();
			if (next.isCancelled()) next.cancel();
			return next;
		}

		Ticket branch()
		{
			Ticket branch = new Ticket(this);
			result.whenComplete((receipt, error) -> branch.cancel());
			return branch;
		}
	}
}
