package com.genericclient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

@Slf4j
final class GenericClientLuaHost implements AutoCloseable
{
	private static final String REPL_NAME = "Lua REPL";

	private static final int LOG_HISTORY_SIZE = 80;

	private final GenericClientScriptRegistry registry;
	private final WalkRandomAction walkRandomAction;
	private final WalkToAction walkToAction;
	private final Consumer<String> cancelWalkAction;
	private final GenericClientBehaviorController behavior;
	private final Consumer<String> statusSink;
	private final ExecutorService scheduler;
	private final ArrayDeque<String> recentLogs = new ArrayDeque<>();

	private volatile String activeScript = "none";
	private volatile String status = "IDLE";
	private volatile Map<String, Object> activeInputs = Collections.emptyMap();
	private volatile boolean closed;
	private GenericClientSnapshot currentSnapshot;
	private GenericClientLuaScript session;
	private GenericClientLuaScript repl;
	private volatile CompletableFuture<Map<String, Object>> replCompletion;
	private final List<String> replLogs = new ArrayList<>();

	GenericClientLuaHost(
		Path scriptsDirectory,
		WalkRandomAction walkRandomAction,
		WalkToAction walkToAction,
		Consumer<String> cancelWalkAction,
		GenericClientBehaviorController behavior,
		Consumer<String> statusSink) throws IOException
	{
		this.registry = new GenericClientScriptRegistry(scriptsDirectory);
		this.walkRandomAction = walkRandomAction;
		this.walkToAction = walkToAction;
		this.cancelWalkAction = cancelWalkAction;
		this.behavior = behavior;
		this.statusSink = statusSink;
		this.scheduler = Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "GenericClient-Lua");
			thread.setDaemon(true);
			return thread;
		});

	}

	List<GenericClientScriptRegistry.Script> listScripts()
	{
		return registry.list();
	}

	CompletableFuture<List<GenericClientScriptInput>> describe(String scriptId)
	{
		CompletableFuture<List<GenericClientScriptInput>> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			try
			{
				GenericClientLuaScript descriptor = new GenericClientLuaScript(
					this,
					scriptId,
					registry.readSource(scriptId));
				try
				{
					completion.complete(descriptor.getInputs());
				}
				finally
				{
					descriptor.close();
				}
			}
			catch (IOException | RuntimeException exception)
			{
				completion.completeExceptionally(exception);
			}
		});
		return completion;
	}

	long getManifestRevision()
	{
		return registry.getRevision();
	}

	Object readSnapshot(GenericClientSnapshot snapshot, String subject, Map<?, ?> query)
	{
		if ("behavior".equals(subject))
		{
			return behavior.status();
		}
		return snapshot == null ? null : snapshot.read(subject, query);
	}

	CompletableFuture<String> start(String scriptId)
	{
		return start(scriptId, Collections.emptyMap());
	}

	CompletableFuture<String> start(String scriptId, Map<String, Object> suppliedInputs)
	{
		CompletableFuture<String> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			GenericClientLuaScript candidate = null;
			try
			{
				GenericClientScriptRegistry.Script definition = registry.get(scriptId);
				candidate = new GenericClientLuaScript(
					this,
					definition.getId(),
					registry.readSource(definition.getId()));
				Map<String, Object> resolvedInputs = GenericClientScriptInput.resolve(
					candidate.getInputs(), suppliedInputs);
				candidate.pinSnapshot(currentSnapshot);
				candidate.activate(resolvedInputs);
				if (candidate.isFinished() && "FAULTED".equals(candidate.getTerminalStatus()))
				{
					throw new IllegalArgumentException(
						"Script faulted during initialization: " + candidate.getFaultMessage());
				}

				GenericClientLuaScript previous = session;
				session = candidate;
				activeScript = scriptId;
				activeInputs = resolvedInputs;
				status = candidate.isFinished() ? candidate.getTerminalStatus() : "WAITING";
				if (previous != null)
				{
					cancelWalkAction.accept("script_replaced");
					previous.close();
				}
				String result;
				if (candidate.isFinished())
				{
					reconcileSession(candidate);
					result = "LUA_" + candidate.getTerminalStatus() + " script=" + scriptId;
				}
				else
				{
					result = "LUA_STARTED script=" + scriptId;
					publishStatus(result);
				}
				completion.complete(result);
			}
			catch (IOException | RuntimeException exception)
			{
				if (candidate != null && candidate != session)
				{
					candidate.close();
				}
				String result = "LUA_START_FAILED script=" + scriptId + " message=" + exception.getMessage();
				publishStatus(result);
				completion.completeExceptionally(exception);
			}
		});
		return completion;
	}

	CompletableFuture<String> reload()
	{
		String scriptName = activeScript;
		if ("none".equals(scriptName))
		{
			return CompletableFuture.completedFuture("LUA_RELOAD_SKIPPED no_active_script");
		}
		return start(scriptName, activeInputs);
	}

	CompletableFuture<String> stop()
	{
		CompletableFuture<String> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			stopOnScheduler();
			String result = "LUA_STOPPED";
			publishStatus(result);
			completion.complete(result);
		});
		return completion;
	}

	void publishGameTick(GenericClientSnapshot snapshot)
	{
		if (closed)
		{
			return;
		}
		scheduler.execute(() ->
		{
			currentSnapshot = snapshot;
			GenericClientLuaScript current = session;
			if (current != null)
			{
				current.onGameTick(snapshot);
				reconcileScript(current);
			}
			GenericClientLuaScript currentRepl = repl;
			if (currentRepl != null)
			{
				currentRepl.onGameTick(snapshot);
				reconcileScript(currentRepl);
			}
		});
	}

	boolean isBehaviorPaused()
	{
		return behavior.isPaused();
	}

	void submitWalkRandom(GenericClientLuaScript script, long requestId, boolean breaksEnabled)
	{
		walkRandomAction.walk(breaksEnabled).handle((result, error) ->
		{
			if (error != null)
			{
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "rejected");
				receipt.put("result", rootMessage(error));
				return receipt;
			}
			return result.toReceipt();
		}).whenComplete((receipt, error) -> completeAction(script, requestId, receipt, error));
	}

	void submitWalkTo(
		GenericClientLuaScript script,
		long requestId,
		WorldPoint destination,
		int within,
		int timeoutTicks,
		boolean breaksEnabled)
	{
		walkToAction.walkTo(destination, within, timeoutTicks, breaksEnabled).handle((receipt, error) ->
		{
			Map<String, Object> result = receipt == null
				? new LinkedHashMap<>()
				: new LinkedHashMap<>(receipt);
			if (error != null)
			{
				result.put("status", "rejected");
				result.put("reason", rootMessage(error));
			}
			return result;
		}).whenComplete((receipt, error) -> completeAction(script, requestId, receipt, error));
	}

	void submitMouseOffscreen(GenericClientLuaScript script, long requestId)
	{
		behavior.moveMouseOffscreen().handle((result, error) ->
		{
			Map<String, Object> receipt = new LinkedHashMap<>();
			if (error == null)
			{
				receipt.put("status", "moved");
				receipt.put("result", result);
			}
			else
			{
				receipt.put("status", "rejected");
				receipt.put("reason", rootMessage(error));
			}
			return receipt;
		}).whenComplete((receipt, error) -> completeAction(script, requestId, receipt, error));
	}

	void submitPhase(
		GenericClientLuaScript script,
		long requestId,
		String phase,
		boolean breaksEnabled)
	{
		behavior.enterPhase(phase, breaksEnabled).whenComplete((receipt, error) ->
			completePhase(script, requestId, phase, receipt, error));
	}

	private void completeAction(
		GenericClientLuaScript script,
		long requestId,
		Map<String, Object> receipt,
		Throwable error)
	{
		if (closed)
		{
			return;
		}
		try
		{
			scheduler.execute(() ->
			{
				Map<String, Object> result = receipt;
				if (error != null)
				{
					result = new LinkedHashMap<>();
					result.put("status", "rejected");
					result.put("reason", rootMessage(error));
				}
				script.completeAction(requestId, result, currentSnapshot);
				reconcileScript(script);
			});
		}
		catch (java.util.concurrent.RejectedExecutionException ignored)
		{
			// The host completed shutdown between the closed check and queue submission.
		}
	}

	private void completePhase(
		GenericClientLuaScript script,
		long requestId,
		String phase,
		Map<String, Object> receipt,
		Throwable error)
	{
		if (closed)
		{
			return;
		}
		try
		{
			scheduler.execute(() ->
			{
				Map<String, Object> result = receipt == null
					? new LinkedHashMap<>()
					: new LinkedHashMap<>(receipt);
				result.put("phase", phase);
				if (error != null)
				{
					result.put("status", "rejected");
					result.put("reason", rootMessage(error));
				}
				script.completePhase(requestId, result, currentSnapshot);
				reconcileScript(script);
			});
		}
		catch (java.util.concurrent.RejectedExecutionException ignored)
		{
			// The host completed shutdown between the closed check and queue submission.
		}
	}

	private static String rootMessage(Throwable error)
	{
		Throwable current = error;
		while (current.getCause() != null)
		{
			current = current.getCause();
		}
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}

	void scriptLog(String scriptName, String level, String event, Object fields)
	{
		String line = level.toUpperCase() + " " + event + (fields == null ? "" : " " + fields);
		if (REPL_NAME.equals(scriptName) && replCompletion != null)
		{
			replLogs.add(line);
		}
		synchronized (recentLogs)
		{
			if (recentLogs.size() == LOG_HISTORY_SIZE)
			{
				recentLogs.removeFirst();
			}
			recentLogs.addLast(line);
		}
		log.info("[GenericClient][Lua][{}] {}", scriptName, line);
	}

	CompletableFuture<Map<String, Object>> evaluate(String code)
	{
		CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
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
					repl = new GenericClientLuaScript(this, REPL_NAME, descriptor(""));
					repl.activate(Collections.emptyMap());
				}
				repl.pinSnapshot(currentSnapshot);
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

	CompletableFuture<String> reloadManifest()
	{
		CompletableFuture<String> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			try
			{
				registry.reload();
				String result = "SCRIPT_MANIFEST_LOADED scripts=" + registry.list().size();
				publishStatus(result);
				completion.complete(result);
			}
			catch (IOException | RuntimeException exception)
			{
				publishStatus("SCRIPT_MANIFEST_FAILED message=" + exception.getMessage());
				completion.completeExceptionally(exception);
			}
		});
		return completion;
	}

	CompletableFuture<Map<String, Object>> saveScript(
		String id,
		String name,
		String description,
		String source)
	{
		CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			try
			{
				completion.complete(registry.save(id, name, description, source).toMap());
			}
			catch (IOException | RuntimeException exception)
			{
				completion.completeExceptionally(exception);
			}
		});
		return completion;
	}

	List<Map<String, Object>> listScriptValues()
	{
		List<Map<String, Object>> result = new ArrayList<>();
		for (GenericClientScriptRegistry.Script script : registry.list())
		{
			result.add(script.toMap());
		}
		return Collections.unmodifiableList(result);
	}

	Map<String, Object> getScriptValue(String id) throws IOException
	{
		Map<String, Object> result = new LinkedHashMap<>(registry.get(id).toMap());
		result.put("source", registry.readSource(id));
		return result;
	}

	Map<String, Object> controlState()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("active_script", activeScript);
		value.put("active_inputs", new LinkedHashMap<>(activeInputs));
		value.put("script_status", status);
		value.put("repl_busy", replCompletion != null);
		value.put("scripts", listScriptValues());
		value.put("recent_logs", getRecentLogLines(20));
		return value;
	}

	private List<String> getRecentLogLines(int limit)
	{
		synchronized (recentLogs)
		{
			List<String> lines = new ArrayList<>(Math.min(limit, recentLogs.size()));
			int skip = Math.max(0, recentLogs.size() - limit);
			int index = 0;
			for (String line : recentLogs)
			{
				if (index++ >= skip)
				{
					lines.add(line);
				}
			}
			return Collections.unmodifiableList(lines);
		}
	}

	String getStatus()
	{
		return status;
	}

	String getActiveScript()
	{
		return activeScript;
	}

	String getRecentLogs()
	{
		synchronized (recentLogs)
		{
			return String.join("\n", recentLogs);
		}
	}

	private void reconcileScript(GenericClientLuaScript expected)
	{
		if (expected == repl)
		{
			reconcileRepl();
			return;
		}
		reconcileSession(expected);
	}

	private void reconcileSession(GenericClientLuaScript expected)
	{
		if (session != expected || !expected.isFinished())
		{
			return;
		}
		status = expected.getTerminalStatus();
		publishStatus("LUA_" + status + " script=" + activeScript);
		cancelWalkAction.accept("script_finished");
		expected.close();
		session = null;
	}

	private void reconcileRepl()
	{
		if (repl == null || replCompletion == null || !repl.isFinished())
		{
			return;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("status", repl.getTerminalStatus().toLowerCase());
		result.put("value", repl.getReturnValue());
		result.put("logs", new ArrayList<>(replLogs));
		result.put("game_tick", currentSnapshot == null ? null : currentSnapshot.getGameTick());
		if (repl.getFaultMessage() != null)
		{
			result.put("error", repl.getFaultMessage());
		}
		CompletableFuture<Map<String, Object>> completion = replCompletion;
		replCompletion = null;
		completion.complete(result);
	}

	private void stopOnScheduler()
	{
		GenericClientLuaScript current = session;
		session = null;
		cancelWalkAction.accept("script_stopped");
		if (current != null)
		{
			current.close();
		}
		activeScript = "none";
		activeInputs = Collections.emptyMap();
		status = "IDLE";
	}

	private void publishStatus(String message)
	{
		statusSink.accept(message);
	}

	@Override
	public void close()
	{
		if (closed)
		{
			return;
		}
		closed = true;
		try
		{
			scheduler.submit(() ->
			{
				stopOnScheduler();
				CompletableFuture<Map<String, Object>> pendingRepl = replCompletion;
				replCompletion = null;
				if (pendingRepl != null)
				{
					pendingRepl.completeExceptionally(new IllegalStateException("Lua host stopped"));
				}
				if (repl != null)
				{
					repl.close();
					repl = null;
				}
			}).get(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException exception)
		{
			Thread.currentThread().interrupt();
		}
		catch (ExecutionException | java.util.concurrent.TimeoutException exception)
		{
			log.warn("Unable to stop Lua scheduler cleanly", exception);
		}
		finally
		{
			scheduler.shutdownNow();
		}
	}

	@FunctionalInterface
	interface WalkRandomAction
	{
		CompletableFuture<GenericClientInteractionResult> walk(boolean breaksEnabled);
	}

	@FunctionalInterface
	interface WalkToAction
	{
		CompletableFuture<Map<String, Object>> walkTo(
			WorldPoint destination,
			int within,
			int timeoutTicks,
			boolean breaksEnabled);
	}
}
