package com.genericclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Persistent operator coroutine and its one outstanding evaluation. Runs on the host scheduler. */
final class GenericClientLuaRepl
{
	static final String NAME = "Lua REPL";
	private final GenericClientLuaHost host;
	private final Executor scheduler;
	private final BooleanSupplier blocked;
	private volatile GenericClientLuaScript repl;
	private volatile CompletableFuture<Map<String, Object>> replCompletion;
	private final List<String> replLogs = new ArrayList<>();

	GenericClientLuaRepl(GenericClientLuaHost host, Executor scheduler, BooleanSupplier blocked)
	{
		this.host = host;
		this.scheduler = scheduler;
		this.blocked = blocked;
	}

	boolean owns(GenericClientLuaScript candidate) { return candidate == repl; }
	boolean isBusy() { return replCompletion != null; }
	long quietMillis(GenericClientActionBoundary.Ticket walkOwner, long walkQuietMillis)
	{
		GenericClientLuaScript current = repl;
		return current == null ? 0 : current.quietMillis(walkOwner, walkQuietMillis);
	}
	String activity()
	{
		GenericClientLuaScript current = repl;
		return current == null ? null : current.getActivity();
	}

	GenericClientActivityContext behaviorContext()
	{
		GenericClientLuaScript current = repl;
		return current == null ? GenericClientActivityContext.none() : current.getActivityContext();
	}

	void captureLog(String name, String line)
	{
		if (NAME.equals(name) && replCompletion != null) replLogs.add(line);
	}

	void advance(GenericClientSnapshot snapshot)
	{
		if (repl == null) return;
		repl.onGameTick(snapshot);
		reconcileRepl();
	}

	CompletableFuture<Map<String, Object>> evaluate(String code)
	{
		CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			if (blocked.getAsBoolean())
			{
				completion.completeExceptionally(
					new IllegalStateException("Lua REPL interrupted for random-event cleanup"));
				return;
			}
			if (code == null || code.trim().isEmpty())
			{
				completion.completeExceptionally(new IllegalArgumentException("Lua code cannot be empty"));
				return;
			}
			if (replCompletion != null)
			{
				completion.completeExceptionally(new IllegalStateException("The Lua REPL is already executing code"));
				return;
			}

			try
			{
				if (repl == null)
				{
					repl = new GenericClientLuaScript(host, NAME, descriptor(""));
					repl.activate(Collections.emptyMap());
				}
				repl.pinSnapshot(host.currentSnapshot);
				replLogs.clear();
				replCompletion = completion;
				repl.startSource(descriptor(code));
				repl.activate(Collections.emptyMap());
				reconcileRepl();
			}
			catch (RuntimeException exception)
			{
				replCompletion = null;
				completion.completeExceptionally(exception);
			}
		});
		return completion;
	}

	private static String descriptor(String body)
	{
		return "return { run = function(input)\n" + body + "\nend }\n";
	}

	CompletableFuture<String> resetRepl()
	{
		CompletableFuture<String> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			if (replCompletion != null)
			{
				completion.completeExceptionally(
					new IllegalStateException("Stop or wait for the current Lua REPL execution before resetting"));
				return;
			}
			if (repl != null)
			{
				repl.close();
				repl = null;
			}
			replLogs.clear();
			completion.complete("LUA_REPL_RESET");
		});
		return completion;
	}

	void reconcileRepl()
	{
		if (repl == null || replCompletion == null || !repl.isFinished())
		{
			return;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("status", repl.getTerminalStatus().toLowerCase());
		result.put("value", repl.getReturnValue());
		result.put("logs", new ArrayList<>(replLogs));
		result.put("game_tick", host.currentSnapshot == null ? null : host.currentSnapshot.getGameTick());
		if (repl.getFaultMessage() != null)
		{
			result.put("error", repl.getFaultMessage());
		}
		CompletableFuture<Map<String, Object>> completion = replCompletion;
		replCompletion = null;
		completion.complete(result);
	}

	void interrupt(String reason)
	{
		CompletableFuture<Map<String, Object>> pending = replCompletion;
		replCompletion = null;
		if (repl != null)
		{
			repl.close();
			repl = null;
		}
		replLogs.clear();
		if (pending != null)
		{
			pending.completeExceptionally(new IllegalStateException(reason));
		}
	}

}
