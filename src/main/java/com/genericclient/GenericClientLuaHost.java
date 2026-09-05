package com.genericclient;

import static com.genericclient.GenericClientErrors.rootMessage;

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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class GenericClientLuaHost implements AutoCloseable
{
	private static final String MANUAL_OWNER = "manual";
	private static final String SAFETY_NET_SCRIPT_ID = "safety-net";
	private static final String SAFETY_NET_OWNER = "safety_net";

	private static final int LOG_HISTORY_SIZE = 80;

	final GenericClientLuaActions actions;
	final GenericClientLuaCatalog catalog;
	private final Consumer<String> cancelAction;
	private final GenericClientBehaviorController behavior;
	private final Consumer<String> statusSink;
	private final LongSupplier clock;
	final ExecutorService scheduler;
	private final ArrayDeque<String> recentLogs = new ArrayDeque<>();

	private volatile String activeScript = "none";
	private volatile String status = "IDLE";
	private volatile Map<String, Object> activeInputs = Collections.emptyMap();
	private volatile GenericClientLuaRun activeRun;
	private volatile Runnable manualStopListener = () -> { };
	private volatile BiConsumer<String, String> scriptStartListener = (script, owner) -> { };
	private volatile BiConsumer<String, String> scriptEndListener = (script, owner) -> { };
	private volatile Supplier<Map<String, Object>> randomEventStateSupplier = Collections::emptyMap;
	private volatile RandomEventSolverListener randomEventSolverListener = (key, state, error) -> { };
	private volatile String randomEventKey;
	private volatile boolean randomEventCleanupComplete;
	volatile boolean closed;
	private long nextRunId;
	volatile GenericClientSnapshot currentSnapshot;
	private final java.util.concurrent.atomic.AtomicLong snapshotGeneration = new java.util.concurrent.atomic.AtomicLong();
	volatile GenericClientLuaScript session;
	private SuspendedRun suspendedRun;
	final GenericClientLuaRepl repl;
	private volatile PendingFailureFallback pendingFailureFallback;

	GenericClientLuaHost(
		Path scriptsDirectory,
		GenericClientLuaActions.WalkRandomAction walkRandomAction,
		GenericClientLuaActions.WalkClickAction walkClickAction,
		GenericClientLuaActions.WalkToAction walkToAction,
		GenericClientLuaActions.NpcInteractAction npcInteractAction,
		GenericClientLuaActions.CombatModeAction combatModeAction,
		GenericClientLuaActions.QuestAction questAction,
		Consumer<String> cancelAction,
		GenericClientBehaviorController behavior,
		Consumer<String> statusSink,
		LongSupplier clock) throws IOException
	{
		this.actions = new GenericClientLuaActions(this, scriptsDirectory, walkRandomAction, walkClickAction,
			walkToAction, npcInteractAction, combatModeAction, questAction, behavior);
		this.cancelAction = cancelAction;
		this.behavior = behavior;
		this.statusSink = statusSink;
		this.clock = clock;
		this.scheduler = Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "GenericClient-Lua");
			thread.setDaemon(true);
			return thread;
		});

		this.catalog = new GenericClientLuaCatalog(scriptsDirectory, this, scheduler, this::publishStatus);
		this.repl = new GenericClientLuaRepl(this, scheduler, () -> randomEventKey != null && !randomEventCleanupComplete);

	}

	Object readSnapshot(GenericClientSnapshot snapshot, String subject, Map<?, ?> query)
	{
		return readSnapshot(null, snapshot, subject, query);
	}

	Object readSnapshot(
		GenericClientLuaScript script,
		GenericClientSnapshot snapshot,
		String subject,
		Map<?, ?> query)
	{
		if ("behavior".equals(subject))
		{
			behavior.policies.resolve(script == null ? getBehaviorContext() : script.getActivityContext());
			return script == null ? behavior.status() : script.intents.status(behavior.status());
		}
		if ("random_event".equals(subject))
		{
			Map<String, Object> value = randomEventStateSupplier.get();
			return value == null ? Collections.emptyMap() : new LinkedHashMap<>(value);
		}
		Object value = snapshot == null ? null : snapshot.read(subject, query);
		if (script != null && "runtime".equals(subject) && value instanceof Map)
		{
			Map<String, Object> runtime = new LinkedHashMap<>();
			((Map<?, ?>) value).forEach((key, item) -> runtime.put(String.valueOf(key), item));
			runtime.put("script_runtime_millis", script.getRuntimeMillis(clock.getAsLong()));
			return runtime;
		}
		return value;
	}

	long nowNanos()
	{
		return clock.getAsLong();
	}

	CompletableFuture<Object> readCurrentSnapshot(String subject)
	{
		CompletableFuture<Object> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			try
			{
				completion.complete(readSnapshot(currentSnapshot, subject, Collections.emptyMap()));
			}
			catch (RuntimeException exception)
			{
				completion.completeExceptionally(exception);
			}
		});
		return completion;
	}

	CompletableFuture<String> start(String scriptId)
	{
		return start(scriptId, Collections.emptyMap());
	}

	CompletableFuture<String> start(String scriptId, Map<String, Object> suppliedInputs)
	{
		return start(scriptId, suppliedInputs, MANUAL_OWNER, true);
	}

	void setRandomEventHooks(
		Supplier<Map<String, Object>> stateSupplier,
		RandomEventSolverListener solverListener)
	{
		randomEventStateSupplier = stateSupplier == null ? Collections::emptyMap : stateSupplier;
		randomEventSolverListener = solverListener == null
			? (key, state, error) -> { }
			: solverListener;
	}

	void setScriptStartListener(BiConsumer<String, String> listener)
	{
		scriptStartListener = listener == null ? (script, owner) -> { } : listener;
	}

	void setScriptEndListener(BiConsumer<String, String> listener)
	{
		scriptEndListener = listener == null ? (script, owner) -> { } : listener;
	}

	boolean isRandomEventBlocked()
	{
		return randomEventKey != null;
	}

	CompletableFuture<String> interruptForRandomEvent(String eventKey)
	{
		if (eventKey == null || eventKey.trim().isEmpty())
		{
			throw new IllegalArgumentException("Random-event key cannot be empty");
		}
		synchronized (this)
		{
			if (randomEventKey != null && !randomEventKey.equals(eventKey))
			{
				throw new IllegalStateException("Another random event already owns the Lua runtime");
			}
			randomEventKey = eventKey;
			randomEventCleanupComplete = false;
		}
		cancelAction.accept("random_event_detected");

		CompletableFuture<String> interrupted = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			GenericClientLuaRun run = activeRun;
			if (session != null && run != null && MANUAL_OWNER.equals(run.owner))
			{
				suspendedRun = new SuspendedRun(run.definition.getId(), run.values);
			}
			stopOnScheduler("random_event_detected");
			repl.interrupt("Lua REPL interrupted by random event");
			status = "ATTENTION_REQUIRED";
			randomEventCleanupComplete = true;
			interrupted.complete("RANDOM_EVENT_BLOCKED event=" + eventKey);
		});
		return interrupted;
	}

	CompletableFuture<String> startRandomEventSolver(String eventKey, String solverScript)
	{
		if (solverScript == null || solverScript.trim().isEmpty())
		{
			throw new IllegalArgumentException("Random-event solver script cannot be empty");
		}
		if (!eventKey.equals(randomEventKey) || !randomEventCleanupComplete)
		{
			throw new IllegalStateException(
				"Random-event cleanup must complete before its solver starts");
		}
		GenericClientScriptRegistry.Script solver = catalog.definition(solverScript);
		int npcId = randomEventNpcId(eventKey);
		if (!solver.getRandomEvents().contains(npcId))
		{
			throw new IllegalArgumentException(
				"Script " + solverScript + " is not registered for random-event NPC " + npcId);
		}
		return start(
			solverScript,
			Collections.emptyMap(),
			randomEventOwner(eventKey),
			false).thenApply(result ->
			"RANDOM_EVENT_SOLVER_STARTED event=" + eventKey +
				" script=" + solverScript + " result=" + result);
	}

	CompletableFuture<String> releaseRandomEvent(String eventKey, boolean resumeInterrupted)
	{
		CompletableFuture<SuspendedRun> released = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			if (randomEventKey == null || !randomEventKey.equals(eventKey))
			{
				released.completeExceptionally(
					new IllegalStateException("Random-event key does not own the Lua runtime"));
				return;
			}
			stopOnScheduler("random_event_release");
			SuspendedRun resume = resumeInterrupted ? suspendedRun : null;
			suspendedRun = null;
			randomEventKey = null;
			randomEventCleanupComplete = false;
			released.complete(resume);
		});

		return released.thenCompose(resume ->
		{
			if (resume == null)
			{
				return CompletableFuture.completedFuture(
					"RANDOM_EVENT_RELEASED event=" + eventKey + " resumed=false");
			}
			return start(resume.scriptId, resume.inputs, MANUAL_OWNER, true).handle((result, error) ->
				error == null
					? "RANDOM_EVENT_RELEASED event=" + eventKey +
						" resumed=true result=" + result
					: "RANDOM_EVENT_RELEASED event=" + eventKey +
						" resumed=false resume_error=" + rootMessage(error));
		});
	}

	CompletableFuture<String> startScheduled(
		String ruleId,
		String scriptId,
		Map<String, Object> suppliedInputs)
	{
		if (ruleId == null || !ruleId.matches("[a-z0-9][a-z0-9_-]{0,63}"))
		{
			throw new IllegalArgumentException("Invalid automation rule id: " + ruleId);
		}
		return start(scriptId, suppliedInputs, "rule:" + ruleId, false);
	}

	private CompletableFuture<String> start(
		String scriptId,
		Map<String, Object> suppliedInputs,
		String owner,
		boolean replaceActive)
	{
		CompletableFuture<String> completion = new CompletableFuture<>();
		scheduler.execute(() -> startOnScheduler(
			scriptId, suppliedInputs, owner, replaceActive, completion));
		return completion;
	}

	private void startOnScheduler(
		String scriptId,
		Map<String, Object> suppliedInputs,
		String owner,
		boolean replaceActive,
		CompletableFuture<String> completion)
	{
		GenericClientLuaScript candidate = null;
		boolean candidateStarted = false;
		try
		{
			if (!SAFETY_NET_OWNER.equals(owner)) pendingFailureFallback = null;
			String blockedBy = randomEventKey;
			if (blockedBy != null && !randomEventOwner(blockedBy).equals(owner))
			{
				throw new IllegalStateException(
					"A random event requires completion before another standalone script can start");
			}
			if (!replaceActive && session != null)
			{
				GenericClientLuaRun running = activeRun;
				String activeOwner = running == null ? "unknown" : running.owner;
				completion.complete("LUA_START_SKIPPED active_owner=" + activeOwner);
				return;
			}
			GenericClientScriptRegistry.Script definition = catalog.definition(scriptId);
			candidate = catalog.open(definition.getId());
			Map<String, Object> resolvedInputs = GenericClientScriptInput.resolve(
				candidate.getInputs(), suppliedInputs);
			replaceActiveScript();
			behavior.beginSession();
			scriptStartListener.accept(scriptId, owner);
			candidateStarted = true;
			candidate.pinSnapshot(currentSnapshot);
			candidate.activate(resolvedInputs);
			if (candidate.isFinished() && "FAULTED".equals(candidate.getTerminalStatus()))
			{
				throw new IllegalArgumentException(
					"Script faulted during initialization: " + candidate.getFaultMessage());
			}

			session = candidate;
			activeScript = scriptId;
			activeInputs = resolvedInputs;
			status = candidate.isFinished() ? candidate.getTerminalStatus() : "WAITING";
			activeRun = new GenericClientLuaRun(
				++nextRunId,
				owner,
				definition,
				candidate,
				resolvedInputs,
				status,
				candidate.getStartedNanos());
			String result;
			if (candidate.isFinished())
			{
				reconcileSession(candidate);
				result = "LUA_" + candidate.getTerminalStatus() + " script=" + scriptId +
					" owner=" + owner;
			}
			else
			{
				result = "LUA_STARTED script=" + scriptId + " owner=" + owner;
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
			if (candidateStarted) scriptEndListener.accept(scriptId, owner);
			String result = "LUA_START_FAILED script=" + scriptId + " message=" + exception.getMessage();
			publishStatus(result);
			completion.completeExceptionally(exception);
		}
	}

	private void replaceActiveScript()
	{
		GenericClientLuaScript previous = session;
		if (previous == null) return;
		GenericClientLuaRun previousRun = activeRun;
		session = null;
		activeRun = null;
		activeScript = "none";
		activeInputs = Collections.emptyMap();
		status = "IDLE";
		cancelAction.accept("script_replaced");
		previous.close();
		if (previousRun != null && previousRun.script == previous)
		{
			previousRun.finish("STOPPED", previous.getOverlayRows(), previous.getReturnValue(), "Script replaced", clock.getAsLong());
			scriptEndListener.accept(previousRun.definition.getId(), previousRun.owner);
		}
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
		manualStopListener.run();
		CompletableFuture<String> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			stopOnScheduler("manual");
			String result = "LUA_STOPPED";
			publishStatus(result);
			completion.complete(result);
		});
		return completion;
	}

	CompletableFuture<String> stopForManualEscape()
	{
		manualStopListener.run();
		CompletableFuture<String> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			stopOnScheduler("manual_escape");
			repl.interrupt("Lua REPL interrupted by manual escape");
			String result = "LUA_STOPPED reason=manual_escape";
			publishStatus(result);
			completion.complete(result);
		});
		return completion;
	}

	CompletableFuture<String> stopScheduled(String ruleId, String reason)
	{
		String owner = "rule:" + ruleId;
		CompletableFuture<String> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			GenericClientLuaRun run = activeRun;
			if (session == null || run == null || !owner.equals(run.owner))
			{
				completion.complete("LUA_STOP_SKIPPED owner=" + owner);
				return;
			}
			stopOnScheduler("automation_" + reason);
			String result = "LUA_STOPPED owner=" + owner + " reason=" + reason;
			publishStatus(result);
			completion.complete(result);
		});
		return completion;
	}

	void setManualStopListener(Runnable listener)
	{
		manualStopListener = listener == null ? () -> { } : listener;
	}

	CompletableFuture<String> triggerAction(String actionId)
	{
		CompletableFuture<String> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			try
			{
				GenericClientLuaScript current = session;
				if (current == null)
				{
					throw new IllegalStateException("No script is running");
				}
				String result = current.queueAction(actionId);
				String receipt = "SCRIPT_ACTION_" + result.toUpperCase(java.util.Locale.ROOT) +
					" script=" + activeScript + " action=" + actionId;
				publishStatus(receipt);
				completion.complete(receipt);
			}
			catch (RuntimeException exception)
			{
				completion.completeExceptionally(exception);
			}
		});
		return completion;
	}

	void clearSnapshot()
	{
		synchronized (snapshotGeneration)
		{
			snapshotGeneration.incrementAndGet();
			currentSnapshot = null;
		}
	}

	void publishGameTick(GenericClientSnapshot snapshot)
	{
		if (closed)
		{
			return;
		}
		long generation = snapshotGeneration.get();
		scheduler.execute(() ->
			{
				synchronized (snapshotGeneration)
				{
					if (snapshotGeneration.get() != generation) return;
					currentSnapshot = snapshot;
				}
				startPendingFailureFallback();
				GenericClientLuaScript current = session;
				if (current != null && canAdvance(current) && !actions.isPaused())
				{
				current.onGameTick(snapshot);
				reconcileScript(current);
			}
			repl.advance(snapshot);
		});
	}

	boolean hasPendingFailureFallback()
	{
		return pendingFailureFallback != null;
	}

	boolean isBehaviorPaused()
	{
		return behavior.isPaused();
	}

	boolean isOperator(GenericClientLuaScript script)
	{
		return repl.owns(script);
	}

	void cancelTimedOutAction(
		GenericClientLuaScript script,
		long requestId,
		String actionType)
	{
		String normalizedAction = actionType == null
			? "unknown"
			: actionType.replace('.', '_');
		cancelAction.accept("lua_action_timeout_" + normalizedAction);
		publishStatus("LUA_ACTION_TIMEOUT_CANCELLED script=" + script.getName() +
			" request=" + requestId + " action=" + actionType);
	}


	void scriptLog(String scriptName, String level, String event, Object fields)
	{
		String line = level.toUpperCase() + " " + event + (fields == null ? "" : " " + fields);
		repl.captureLog(scriptName, line);
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

	CompletableFuture<Map<String, Object>> evaluate(String code) { return repl.evaluate(code); }
	CompletableFuture<String> resetRepl() { return repl.resetRepl(); }

	Map<String, Object> controlState()
	{
		GenericClientLuaRun.State run = getRunState();
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("active", getActiveScriptView().toMap());
		value.put("active_script", activeScript);
		value.put("active_inputs", new LinkedHashMap<>(activeInputs));
		GenericClientLuaScript currentSession = session;
		value.put("activity", currentSession == null ? "idle" : currentSession.getActivity());
		value.put("script_state", getScriptState());
		value.put("repl_activity", repl.activity());
		value.put("script_status", status);
		value.put("run_id", run.getRunId() < 0L ? null : run.getRunId());
		value.put("run_owner", run.getOwner());
		value.put("repl_busy", repl.isBusy());
		value.put("emergency_paused", actions.isPaused());
		value.put("random_event_blocked", randomEventKey != null);
		value.put("scripts", catalog.listScriptValues());
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

	GenericClientActiveScript getActiveScriptView()
	{
		GenericClientLuaRun run = activeRun;
		return run == null
			? GenericClientActiveScript.none()
			: run.snapshot(clock.getAsLong());
	}

	String getActivity()
	{
		if (repl.isBusy()) return repl.behaviorContext().getActivity().getValue();
		GenericClientLuaScript currentSession = session;
		return currentSession == null ? "idle" : currentSession.getActivity();
	}

	GenericClientActivityContext getBehaviorContext()
	{
		if (repl.isBusy()) return repl.behaviorContext().withResolver(behavior.policies);
		GenericClientLuaScript current = session;
		return (current == null ? GenericClientActivityContext.none() : current.getActivityContext()).withResolver(behavior.policies);
	}

	GenericClientActivityContext ownedBehaviorContext()
	{
		GenericClientLuaScript current = session;
		return current == null ? GenericClientActivityContext.none() : current.getActivityContext();
	}

	long quietMillis(GenericClientActionBoundary.Ticket walkOwner, long walkQuietMillis)
	{
		GenericClientLuaScript current = session;
		long sessionQuiet = current == null ? Long.MAX_VALUE : current.quietMillis(walkOwner, walkQuietMillis);
		return repl.isBusy() ? Math.min(sessionQuiet, repl.quietMillis(walkOwner, walkQuietMillis)) : sessionQuiet;
	}

	boolean isReplBusy()
	{
		return repl.isBusy();
	}

	String getScriptState()
	{
		GenericClientLuaRun run = activeRun;
		return run == null ? "idle" : run.script.getScriptState();
	}

	List<GenericClientSceneMarker> getSceneMarkers()
	{
		GenericClientLuaRun run = activeRun;
		return run == null || run.script.isFinished()
			? Collections.emptyList()
			: run.script.getSceneMarkers();
	}

	GenericClientLuaRun.State getRunState()
	{
		GenericClientLuaRun run = activeRun;
		if (run == null)
		{
			return GenericClientLuaRun.State.none();
		}
		GenericClientActiveScript view = run.snapshot(clock.getAsLong());
		return new GenericClientLuaRun.State(run.id, run.owner, view.getId(), view.getStatus(), view.isRunning());
	}

	void reconcileScript(GenericClientLuaScript expected)
	{
		if (repl.owns(expected))
		{
			repl.reconcileRepl();
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
		GenericClientLuaRun run = activeRun;
		boolean startFailureFallback = run != null && run.script == expected &&
			shouldStartFailureFallback(
				run.definition.getId(), run.owner, status, expected.getReturnValue());
		if (run != null && run.script == expected)
		{
			run.finish(
				status,
				expected.getOverlayRows(),
				expected.getReturnValue(),
				expected.getFaultMessage(),
				clock.getAsLong());
		}
		publishStatus("LUA_" + status + " script=" + activeScript);
		cancelAction.accept("script_finished");
		expected.close();
		session = null;
		if (run != null && run.script == expected)
		{
			scriptEndListener.accept(run.definition.getId(), run.owner);
		}
		if (startFailureFallback)
		{
			pendingFailureFallback = new PendingFailureFallback(
				run.id, run.definition.getId());
			publishStatus("LUA_SAFETY_NET_PENDING failed_script=" +
				run.definition.getId() + " run_id=" + run.id);
		}
		if (run != null && run.script == expected && run.owner.startsWith("random_event:"))
		{
			randomEventSolverListener.finished(
				run.owner.substring("random_event:".length()),
				status,
				expected.getFaultMessage());
		}
	}

	private void stopOnScheduler()
	{
		stopOnScheduler("host_closed");
	}

	private void stopOnScheduler(String reason)
	{
		actions.resetPauseState();
		pendingFailureFallback = null;
		GenericClientLuaScript current = session;
		GenericClientLuaRun run = activeRun;
		session = null;
		cancelAction.accept("script_stopped_" + reason);
		if (current != null)
		{
			current.close();
			if (run != null && run.script == current)
			{
				scriptEndListener.accept(run.definition.getId(), run.owner);
			}
		}
		activeScript = "none";
		activeInputs = Collections.emptyMap();
		activeRun = null;
		status = "IDLE";
		if (current != null && run != null && run.owner.startsWith("random_event:") &&
			!"random_event_release".equals(reason))
		{
			randomEventSolverListener.finished(
				run.owner.substring("random_event:".length()),
				"STOPPED",
				"Random-event solver was stopped: " + reason);
		}
	}

	private void startPendingFailureFallback()
	{
		PendingFailureFallback pending = pendingFailureFallback;
		if (pending == null || session != null || randomEventKey != null)
		{
			return;
		}
		pendingFailureFallback = null;
		start(SAFETY_NET_SCRIPT_ID, Collections.emptyMap(), SAFETY_NET_OWNER, false)
			.whenComplete((result, error) -> publishStatus(error == null
				? "LUA_SAFETY_NET_STARTED failed_script=" + pending.scriptId +
					" failed_run_id=" + pending.runId + " result=" + result
				: "LUA_SAFETY_NET_START_FAILED failed_script=" + pending.scriptId +
					" failed_run_id=" + pending.runId + " message=" + rootMessage(error)));
	}

	private static boolean shouldStartFailureFallback(
		String scriptId,
		String owner,
		String terminalStatus,
		Object returnedValue)
	{
		if (SAFETY_NET_SCRIPT_ID.equals(scriptId) ||
			SAFETY_NET_OWNER.equals(owner) ||
			(owner != null && owner.startsWith("random_event:")))
		{
			return false;
		}
		if ("FAULTED".equals(terminalStatus))
		{
			return true;
		}
		if (!(returnedValue instanceof Map))
		{
			return false;
		}
		Map<?, ?> result = (Map<?, ?>) returnedValue;
		if (Boolean.TRUE.equals(result.get("recovery_completed")))
		{
			return false;
		}
		Object rawStatus = result.get("status");
		if (rawStatus == null)
		{
			return false;
		}
		String resultStatus = String.valueOf(rawStatus).toLowerCase(java.util.Locale.ROOT);
		return resultStatus.matches(
			"(^|.*_)(failed|failure|faulted|rejected|timeout|timed_out)$");
	}

	private static String randomEventOwner(String eventKey)
	{
		return "random_event:" + eventKey;
	}

	private static int randomEventNpcId(String eventKey)
	{
		String[] parts = eventKey.split(":", -1);
		if (parts.length != 3)
		{
			throw new IllegalArgumentException("Invalid random-event key: " + eventKey);
		}
		try
		{
			return Integer.parseInt(parts[1]);
		}
		catch (NumberFormatException exception)
		{
			throw new IllegalArgumentException("Invalid random-event key: " + eventKey, exception);
		}
	}

	boolean canAdvance(GenericClientLuaScript script)
	{
		String blockedBy = randomEventKey;
		if (blockedBy == null || repl.owns(script))
		{
			return true;
		}
		GenericClientLuaRun run = activeRun;
		return session == script && run != null && randomEventOwner(blockedBy).equals(run.owner);
	}

	void publishStatus(String message)
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
				repl.interrupt("Lua host stopped");
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

	private static final class SuspendedRun
	{
		private final String scriptId;
		private final Map<String, Object> inputs;

		private SuspendedRun(String scriptId, Map<String, Object> inputs)
		{
			this.scriptId = scriptId;
			this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
		}
	}

	private static final class PendingFailureFallback
	{
		private final long runId;
		private final String scriptId;

		private PendingFailureFallback(long runId, String scriptId)
		{
			this.runId = runId;
			this.scriptId = scriptId;
		}
	}

	@FunctionalInterface
	interface RandomEventSolverListener
	{
		void finished(String eventKey, String terminalStatus, String error);
	}
}
