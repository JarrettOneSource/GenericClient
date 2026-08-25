package com.genericclient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

@Slf4j
final class GenericClientLuaHost implements AutoCloseable
{
	static final String DIAGNOSTIC_SCRIPT = "npc-diagnostics.lua";
	static final String WALK_STRESS_SCRIPT = "walk-stress.lua";
	static final String LUMBRIDGE_VARROCK_SCRIPT = "lumbridge-varrock.lua";

	private static final int LOG_HISTORY_SIZE = 80;

	private final Path scriptsDirectory;
	private final Supplier<CompletableFuture<String>> walkRandomAction;
	private final WalkToAction walkToAction;
	private final Consumer<String> cancelWalkAction;
	private final Consumer<String> statusSink;
	private final ExecutorService scheduler;
	private final ArrayDeque<String> recentLogs = new ArrayDeque<>();

	private volatile String activeScript = "none";
	private volatile String status = "IDLE";
	private volatile boolean closed;
	private GenericClientSnapshot currentSnapshot;
	private GenericClientLuaScript session;

	GenericClientLuaHost(
		Path scriptsDirectory,
		Supplier<CompletableFuture<String>> walkRandomAction,
		WalkToAction walkToAction,
		Consumer<String> cancelWalkAction,
		Consumer<String> statusSink) throws IOException
	{
		this.scriptsDirectory = scriptsDirectory;
		this.walkRandomAction = walkRandomAction;
		this.walkToAction = walkToAction;
		this.cancelWalkAction = cancelWalkAction;
		this.statusSink = statusSink;
		this.scheduler = Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "GenericClient-Lua");
			thread.setDaemon(true);
			return thread;
		});

		Files.createDirectories(scriptsDirectory);
		installExample(DIAGNOSTIC_SCRIPT);
		installExample(WALK_STRESS_SCRIPT);
		installExample(LUMBRIDGE_VARROCK_SCRIPT);
	}

	List<String> listScripts()
	{
		try (Stream<Path> paths = Files.list(scriptsDirectory))
		{
			return paths
				.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".lua"))
				.map(path -> path.getFileName().toString())
				.sorted()
				.collect(Collectors.toList());
		}
		catch (IOException exception)
		{
			throw new IllegalStateException("Unable to list Lua scripts", exception);
		}
	}

	CompletableFuture<String> start(String scriptName)
	{
		CompletableFuture<String> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			try
			{
				Path script = resolveScript(scriptName);
				GenericClientLuaScript candidate = new GenericClientLuaScript(
					this,
					script);
				if (candidate.isFinished() && "FAULTED".equals(candidate.getTerminalStatus()))
				{
					candidate.close();
					throw new IllegalArgumentException("Script faulted during initialization");
				}

				GenericClientLuaScript previous = session;
				session = candidate;
				activeScript = scriptName;
				status = candidate.isFinished() ? candidate.getTerminalStatus() : "WAITING";
				if (previous != null)
				{
					cancelWalkAction.accept("script_replaced");
					previous.close();
				}
				candidate.activate();
				String result;
				if (candidate.isFinished())
				{
					reconcileSession(candidate);
					result = "LUA_" + candidate.getTerminalStatus() + " script=" + scriptName;
				}
				else
				{
					result = "LUA_STARTED script=" + scriptName;
					publishStatus(result);
				}
				completion.complete(result);
			}
			catch (IOException | RuntimeException exception)
			{
				String result = "LUA_START_FAILED script=" + scriptName + " message=" + exception.getMessage();
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
		return start(scriptName);
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
			if (current == null)
			{
				return;
			}
			current.onGameTick(snapshot);
			reconcileSession(current);
		});
	}

	void submitWalkRandom(GenericClientLuaScript script, long requestId)
	{
		walkRandomAction.get().whenComplete((result, error) ->
		{
			if (closed)
			{
				return;
			}
			try
			{
				scheduler.execute(() ->
				{
					Map<String, Object> receipt = new LinkedHashMap<>();
					if (error != null)
					{
						receipt.put("status", "rejected");
						receipt.put("result", error.getMessage());
					}
					else
					{
						receipt.put("status", result.startsWith("WALK_CLICK_EXECUTED") ? "dispatched" : "rejected");
						receipt.put("result", result);
					}
					script.completeAction(requestId, receipt, currentSnapshot);
					reconcileSession(script);
				});
			}
			catch (java.util.concurrent.RejectedExecutionException ignored)
			{
				// The host completed shutdown between the closed check and queue submission.
			}
		});
	}

	void submitWalkTo(
		GenericClientLuaScript script,
		long requestId,
		WorldPoint destination,
		int within,
		int timeoutTicks)
	{
		walkToAction.walkTo(destination, within, timeoutTicks).whenComplete((receipt, error) ->
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
						result.put("reason", error.getMessage());
					}
					script.completeAction(requestId, result, currentSnapshot);
					reconcileSession(script);
				});
			}
			catch (java.util.concurrent.RejectedExecutionException ignored)
			{
				// The host completed shutdown between the closed check and queue submission.
			}
		});
	}

	void scriptLog(String scriptName, String level, String event, Object fields)
	{
		String line = level.toUpperCase() + " " + event + (fields == null ? "" : " " + fields);
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

	private Path resolveScript(String scriptName)
	{
		Path name = Path.of(scriptName).getFileName();
		if (!name.toString().equals(scriptName) || !scriptName.endsWith(".lua"))
		{
			throw new IllegalArgumentException("Invalid script name: " + scriptName);
		}
		Path script = scriptsDirectory.resolve(name);
		if (!Files.isRegularFile(script))
		{
			throw new IllegalArgumentException("Script does not exist: " + scriptName);
		}
		return script;
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
		status = "IDLE";
	}

	private void publishStatus(String message)
	{
		statusSink.accept(message);
	}

	private void installExample(String name) throws IOException
	{
		Path target = scriptsDirectory.resolve(name);
		if (Files.exists(target))
		{
			return;
		}

		try (InputStream input = GenericClientLuaHost.class.getResourceAsStream("/com/genericclient/scripts/" + name))
		{
			if (input == null)
			{
				throw new IOException("Missing bundled Lua script: " + name);
			}
			Files.copy(input, target);
		}
	}

	@Override
	public void close()
	{
		if (closed)
		{
			return;
		}
		try
		{
			scheduler.submit(this::stopOnScheduler).get(5, TimeUnit.SECONDS);
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
			closed = true;
			scheduler.shutdownNow();
		}
	}

	@FunctionalInterface
	interface WalkToAction
	{
		CompletableFuture<Map<String, Object>> walkTo(
			WorldPoint destination,
			int within,
			int timeoutTicks);
	}
}
