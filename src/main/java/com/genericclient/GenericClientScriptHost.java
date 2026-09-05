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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

final class GenericClientScriptHost implements AutoCloseable
{
	private final GenericClientScriptRegistry registry;
	private final GenericClientScriptCheckpointStore checkpoints;
	private final GenericClientScriptActions actions;
	private final GenericClientBehaviorController behavior;
	private final Consumer<String> cancelInput;
	private final Consumer<String> reporter;
	final LongSupplier nanoClock;
	private final ExecutorService administration = Executors.newSingleThreadExecutor(task ->
	{
		Thread thread = new Thread(task, "GenericClient-Scripts");
		thread.setDaemon(true);
		return thread;
	});
	private final ArrayDeque<String> logs = new ArrayDeque<>();
	private volatile GenericClientSnapshot snapshot;
	private volatile Map<String,Object> localPlayerReference;
	private volatile long gameTick;
	private volatile GenericClientScriptRun active;
	private volatile boolean closed;
	private volatile boolean recoveryPending;
	private long nextRun;
	private String eventKey;
	private SuspendedRun interrupted;
	private Runnable manualStop = () -> {};
	private StartListener started = (id, owner, context) -> {};
	private BiConsumer<String, String> ended = (id, owner) -> {};
	private Supplier<Map<String, Object>> eventState = Collections::emptyMap;
	private RandomEventSolverListener eventFinished = (key, status, error) -> {};

	GenericClientScriptHost(Path directory, GenericClientScriptActions.WalkRandomAction random,
		GenericClientScriptActions.WalkClickAction click, GenericClientScriptActions.WalkToAction walk,
		GenericClientScriptActions.NpcInteractAction npc, GenericClientScriptActions.CombatModeAction combat,
		GenericClientScriptActions.QuestAction operations, Consumer<String> cancelInput,
		GenericClientBehaviorController behavior, LongSupplier nanoClock, Consumer<String> reporter) throws IOException
	{
		registry = new GenericClientScriptRegistry(directory);
		checkpoints = new GenericClientScriptCheckpointStore(directory.getParent().resolve("checkpoints"));
		actions = new GenericClientScriptActions(random, click, walk, npc, combat, operations, behavior);
		this.cancelInput = cancelInput;
		this.behavior = behavior;
		this.reporter = reporter;
		this.nanoClock = nanoClock;
	}

	List<GenericClientScriptRegistry.Script> listScripts() { return registry.list(); }
	long getCatalogRevision() { return registry.getRevision(); }
	CompletableFuture<List<GenericClientScriptInput>> describe(String id)
	{
		return CompletableFuture.completedFuture(registry.get(id).getInputs());
	}
	CompletableFuture<List<GenericClientScriptAction>> describeActions(String id)
	{
		return CompletableFuture.completedFuture(registry.get(id).getActions());
	}
	List<Map<String, Object>> listScriptValues()
	{
		return registry.list().stream().map(GenericClientScriptRegistry.Script::toMap).collect(Collectors.toList());
	}
	Map<String, Object> getScriptValue(String id) { return registry.get(id).toMap(); }

	CompletableFuture<String> compile(String className, String source)
	{
		return CompletableFuture.supplyAsync(() ->
		{
			try { registry.compile(className, source); }
			catch (IOException error) { throw new CompletionException(error); }
			return "SCRIPT_COMPILED class=" + className;
		}, administration);
	}

	CompletableFuture<String> reloadCatalog()
	{
		return CompletableFuture.supplyAsync(() ->
		{
			try { registry.reload(); }
			catch (IOException error) { throw new CompletionException(error); }
			return "SCRIPTS_RELOADED";
		}, administration);
	}

	CompletableFuture<String> reload()
	{
		GenericClientScriptRun previous = active;
		if (previous == null) return CompletableFuture.completedFuture("NO_ACTIVE_SCRIPT");
		return reloadCatalog().thenCompose(ignored -> start(previous.definition.getId(), previous.values));
	}

	CompletableFuture<String> start(String id) { return start(id, Collections.emptyMap()); }

	CompletableFuture<Map<String, Object>> evaluate(String code)
	{
		return CompletableFuture.supplyAsync(() ->
		{
			try
			{
				GenericClientJavaConsole console = new GenericClientJavaConsole(code);
				GenericClientScriptRun run;
				synchronized (this)
				{
					if (closed || active != null && active.isRunning())
					{
						console.close();
						throw new IllegalStateException("Stop the active script before running Java diagnostics");
					}
					try { run = launch(console.definition, Collections.emptyMap(), "operator"); }
					catch (RuntimeException error) { console.close(); throw error; }
				}
				return run.completion.handle((value, error) ->
				{
					try { console.close(); }
					catch (IOException failure) { throw new CompletionException(failure); }
					if (error != null) throw new CompletionException(error);
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("value", value);
					return result;
				});
			}
			catch (IOException error) { throw new CompletionException(error); }
		}, administration).thenCompose(result -> result);
	}
	CompletableFuture<String> start(String id, Map<String, Object> inputs)
	{
		return start(id, inputs, "manual", true);
	}
	CompletableFuture<String> startScheduled(String rule, String id, Map<String, Object> inputs)
	{
		return start(id, inputs, "rule:" + rule, false);
	}

	private CompletableFuture<String> start(String id, Map<String, Object> inputs, String owner, boolean replace)
	{
		return CompletableFuture.supplyAsync(() ->
		{
			synchronized (this)
			{
				if (closed) throw new IllegalStateException("Script host is closed");
				if (eventKey != null && !owner.equals("random_event:" + eventKey))
					throw new IllegalStateException("A random event owns script execution");
				if (!replace && active != null && active.isRunning()) return "SCRIPT_START_SKIPPED active_owner=" + active.owner;
				GenericClientScriptRegistry.Script definition = registry.get(id);
				Map<String, Object> values = definition.getInputs().isEmpty()
					? Collections.unmodifiableMap(new LinkedHashMap<>(inputs))
					: GenericClientScriptInput.resolve(definition.getInputs(), inputs);
				launch(definition, values, owner);
				return "SCRIPT_STARTED script=" + id + " owner=" + owner;
			}
		}, administration);
	}

	private GenericClientScriptRun launch(GenericClientScriptRegistry.Script definition,
		Map<String, Object> values, String owner)
	{
		GenericClientScriptRun next = new GenericClientScriptRun(this, ++nextRun, owner, definition, values);
		if (active != null && active.isRunning()) stopRun(active, "replaced");
		recoveryPending = false;
		active = next;

		next.start();
		reporter.accept("SCRIPT_STARTED script=" + definition.getId() + " owner=" + owner);
		return next;
	}

	void initialize(GenericClientScriptRun run, GenericClientActivityContext context)
	{
		if (active != run || !run.isRunning()) throw new java.util.concurrent.CancellationException("Script stopped before startup");
		if (run.owner.equals("operator")) return;
		behavior.beginSession();
		started.start(run.definition.getId(),run.owner,context);
	}

	@FunctionalInterface interface StartListener
	{
		void start(String id, String owner, GenericClientActivityContext context);
	}

	CompletableFuture<String> stop()
	{
		manualStop.run();
		synchronized (this)
		{
			recoveryPending = false;
			interrupted = null;
			if (active != null) stopRun(active, "manual");
		}
		return CompletableFuture.completedFuture("SCRIPT_STOPPED");
	}
	CompletableFuture<String> stopForManualEscape() { return stop(); }

	CompletableFuture<String> stopScheduled(String rule, String reason)
	{
		synchronized (this)
		{
			if (active != null && active.owner.equals("rule:" + rule)) return active.scheduleStop(reason);
		}
		return CompletableFuture.completedFuture("SCRIPT_STOPPED owner=rule:" + rule);
	}

	synchronized void stopRun(GenericClientScriptRun run, String reason)
	{
		run.requestStop();
		if (active == run)
		{
			cancelInput.accept(reason);
			notifyEnded(run);
		}
	}

	synchronized void finished(GenericClientScriptRun run)
	{
		if (active != run) { run.publishTerminalStatus(); return; }
		run.publishTerminalStatus();
		reporter.accept("SCRIPT_" + run.status() + " script=" + run.definition.getId());
		if (run.owner.startsWith("random_event:"))
			eventFinished.finished(run.owner.substring("random_event:".length()), run.status(), run.error());
		else if (run.status().equals("FAULTED") && !run.owner.equals("operator") && !run.definition.getId().equals("safety-net"))
			recoveryPending = registry.list().stream().anyMatch(script -> script.getId().equals("safety-net"));
	}

	synchronized void releaseInput(GenericClientScriptRun run, String reason)
	{
		boolean pendingInput = run.revoke();
		if (active == run)
		{
			if (pendingInput || !run.owner.equals("operator")) cancelInput.accept(reason);
			notifyEnded(run);
		}
	}

	synchronized void cancelAction(GenericClientScriptRun run, GenericClientActionBoundary.Ticket ticket, String reason)
	{
		boolean owned = run.clearPending(ticket);
		// A pause or stop may transfer native input while the worker reports its timeout.
		if (active == run && owned && run.isRunning() && !run.isPaused()) cancelInput.accept(reason);
	}

	void paint(java.awt.Graphics2D graphics)
	{
		GenericClientScriptRun run = active;
		if (run != null && snapshot != null) run.paint(graphics);
	}

	private void notifyEnded(GenericClientScriptRun run)
	{
		if (run.endNotified || run.owner.equals("operator")) return;
		run.endNotified = true;
		ended.accept(run.definition.getId(), run.owner);
	}

	synchronized CompletableFuture<Map<String, Object>> execute(GenericClientScriptRun run, String type,
		Map<String, Object> arguments, GenericClientActionBoundary.Ticket ticket)
	{
		if (active != run) throw new java.util.concurrent.CancellationException("Script no longer owns input");
		if (run.isPaused()) return CompletableFuture.completedFuture(Map.of("status", "cancelled", "reason", "script_paused"));
		GenericClientActivityContext context = run.context(type, arguments).withResolver(behavior.policies);
		run.own(ticket, type, context);
		if (GenericClientScriptCheckpointStore.supports(type))
		{
			GenericClientSnapshot current = snapshot;
			if (current == null || current.getPlayer() == null) throw new IllegalStateException("Checkpoint requires a logged-in player");
			try
			{
				return CompletableFuture.completedFuture(checkpoints.execute(current.getPlayer().getName(),
					run.definition.getId(), type, arguments));
			}
			catch (IOException error) { throw new CompletionException(error); }
		}
		return actions.execute(type, arguments, context, ticket);
	}

	synchronized CompletableFuture<Map<String, Object>> enterPhase(GenericClientScriptRun run, String name,
		GenericClientActivityContext context)
	{
		if (active != run || !run.isRunning()) throw new java.util.concurrent.CancellationException("Script stopped");
		if (run.isPaused()) return CompletableFuture.completedFuture(Map.of("status", "cancelled", "reason", "script_paused"));
		run.own(context.inputTicket(), "phase", context);
		return behavior.enterPhase(name, context.withResolver(behavior.policies));
	}

	synchronized CompletableFuture<Map<String, Object>> enterIntent(GenericClientScriptRun run,
		GenericClientActivityContext context, Supplier<CompletableFuture<Map<String, Object>>> body)
	{
		if (active != run || !run.isRunning()) throw new java.util.concurrent.CancellationException("Script stopped");
		return new GenericClientActionBoundary(behavior).execute(context.inputTicket(), context.withResolver(behavior.policies), body, true);
	}

	Object read(String subject, Map<String, Object> query)
	{
		if (subject.equals("local_player")) return localPlayerReference;
		if (subject.equals("behavior"))
		{
			behavior.policies.resolve(getBehaviorContext());
			GenericClientScriptRun run = active;
			return run == null ? behavior.status() : run.behaviorStatus(behavior.status());
		}
		if (subject.equals("random_event")) return eventState.get();
		GenericClientSnapshot current = snapshot;
		return current == null ? null : current.read(subject, query);
	}
	CompletableFuture<Object> readCurrentSnapshot(String subject)
	{
		return CompletableFuture.completedFuture(read(subject, Collections.emptyMap()));
	}
	void clearSnapshot() { snapshot = null; }
	void publishGameTick(GenericClientSnapshot current)
	{
		if (closed) return;
		gameTick = current.getGameTick();
		if (current.getPlayer() != null) localPlayerReference = Map.of("identity",current.getPlayer().identity);
		snapshot = current;
		if (recoveryPending && current.getPlayer() != null)
		{
			recoveryPending = false;
			start("safety-net", Collections.emptyMap(), "safety_net", false)
				.exceptionally(error -> { reporter.accept("RECOVERY_START_FAILED " + error.getMessage()); return null; });
		}
	}
	boolean hasPendingFailureFallback() { return recoveryPending; }
	boolean behaviorPaused() { return behavior.isPaused(); }
	long tick() { return gameTick; }

	CompletableFuture<String> pauseForEmergency(String reason) { return pause(false, true, reason); }
	CompletableFuture<String> resumeAfterEmergency(String reason) { return pause(false, false, reason); }
	CompletableFuture<String> pauseForManualInput(String reason) { return pause(true, true, reason); }
	CompletableFuture<String> resumeAfterManualInput(String reason) { return pause(true, false, reason); }
	private synchronized CompletableFuture<String> pause(boolean manual, boolean paused, String reason)
	{
		if (active != null) active.pause(manual, paused);
		return CompletableFuture.completedFuture((paused ? "SCRIPT_PAUSED reason=" : "SCRIPT_RESUMED reason=") + reason);
	}

	boolean isInputPaused() { GenericClientScriptRun run = active; return run != null && run.isPaused(); }

	void setManualStopListener(Runnable callback) { manualStop = callback; }
	void setScriptStartListener(StartListener callback) { started = callback; }
	void setScriptEndListener(BiConsumer<String, String> callback) { ended = callback; }
	void setRandomEventHooks(Supplier<Map<String, Object>> state, RandomEventSolverListener callback)
	{
		eventState = state == null ? Collections::emptyMap : state;
		eventFinished = callback == null ? (key, status, error) -> {} : callback;
	}
	String findRandomEventSolver(int npcId)
	{
		GenericClientScriptRegistry.Script solver = registry.findRandomEventSolver(npcId);
		return solver == null ? null : solver.getId();
	}
	synchronized boolean isRandomEventBlocked() { return eventKey != null; }

	synchronized CompletableFuture<String> interruptForRandomEvent(String key)
	{
		if (eventKey != null && !eventKey.equals(key)) throw new IllegalStateException("Another random event owns execution");
		eventKey = key;
		if (active != null && active.isRunning())
		{
			if (active.owner.equals("manual")) interrupted = new SuspendedRun(active.definition.getId(), active.values);
			stopRun(active, "random_event");
		}
		return CompletableFuture.completedFuture("RANDOM_EVENT_BLOCKED event=" + key);
	}
	CompletableFuture<String> startRandomEventSolver(String key, String id)
	{
		synchronized (this)
		{
			if (!key.equals(eventKey)) throw new IllegalArgumentException("Random event no longer owns execution");
			int npcId = Integer.parseInt(key.split(":")[1]);
			if (!registry.get(id).getRandomEvents().contains(npcId)) throw new IllegalArgumentException("Script is not this event's solver");
		}
		return start(id, Collections.emptyMap(), "random_event:" + key, false);
	}
	CompletableFuture<String> releaseRandomEvent(String key, boolean resume)
	{
		SuspendedRun previous;
		synchronized (this)
		{
			if (!key.equals(eventKey)) throw new IllegalArgumentException("Random event no longer owns execution");
			if (active != null && active.isRunning()) stopRun(active, "random_event_released");
			eventKey = null;
			previous = interrupted;
			interrupted = null;
		}
		return resume && previous != null ? start(previous.id, previous.inputs)
			: CompletableFuture.completedFuture("RANDOM_EVENT_RELEASED");
	}

	CompletableFuture<String> triggerAction(String id)
	{
		GenericClientScriptRun run = active;
		if (run == null) throw new IllegalStateException("No active script");
		return CompletableFuture.completedFuture(run.queueAction(id));
	}

	void scriptLog(String id, Object message)
	{
		String text = id + ": " + message;
		synchronized (logs)
		{
			logs.addLast(text);
			while (logs.size() > 80) logs.removeFirst();
		}
		reporter.accept(text);
	}
	String getRecentLogs() { synchronized (logs) { return String.join("\n", logs); } }
	String getStatus() { GenericClientScriptRun run = active; return run == null ? "IDLE" : run.status(); }
	String getActiveScript() { GenericClientScriptRun run = active; return run == null || !run.isRunning() ? "none" : run.definition.getId(); }
	GenericClientActivityContext getBehaviorContext()
	{
		GenericClientScriptRun run = active;
		return (run == null || !run.isRunning() ? GenericClientActivityContext.none() : run.behaviorContext())
			.withResolver(behavior.policies);
	}

	GenericClientActivityContext ownedBehaviorContext()
	{
		GenericClientScriptRun run = active;
		return run == null || !run.isRunning() || run.owner.equals("operator")
			? GenericClientActivityContext.preset(GenericClientActivityContext.Activity.MANUAL)
			: run.ownedBehaviorContext();
	}

	long quietMillis(GenericClientActionBoundary.Ticket walkOwner, long walkQuietMillis)
	{
		GenericClientScriptRun run = active;
		return run == null || !run.isRunning() ? Long.MAX_VALUE : run.quietMillis(walkOwner, walkQuietMillis);
	}

	String getActivity() { GenericClientScriptRun run = active; return run == null || !run.isRunning() ? "idle" : run.activity(); }
	String getScriptState() { GenericClientScriptRun run = active; return run == null ? "idle" : run.phase(); }
	GenericClientActiveScript getActiveScriptView() { GenericClientScriptRun run = active; return run == null ? GenericClientActiveScript.none() : run.snapshot(); }
	List<GenericClientSceneMarker> getSceneMarkers() { GenericClientScriptRun run = active; return run == null ? Collections.emptyList() : run.markers(); }

	Map<String, Object> controlState()
	{
		GenericClientScriptRun run = active;
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("active_script", getActiveScript());
		value.put("activity", getActivity());
		value.put("script_state", getScriptState());
		value.put("script_status", getStatus());
		value.put("active", getActiveScriptView().toMap());
		if (run != null)
		{
			value.put("run_id", run.id);
			value.put("run_owner", run.owner);
			value.put("paused", run.isPaused());
		}
		value.put("random_event_blocked", isRandomEventBlocked());
		value.put("scripts", listScriptValues());
		synchronized (logs) { value.put("recent_logs", new ArrayList<>(logs)); }
		return value;
	}
	RunState getRunState()
	{
		GenericClientScriptRun run = active;
		return run == null ? RunState.none()
			: new RunState(run.id, run.owner, run.definition.getId(), run.status(), run.isRunning());
	}

	@Override
	public synchronized void close()
	{
		closed = true;
		recoveryPending = false;
		if (active != null) stopRun(active, "host_closed");
		administration.shutdownNow();
	}

	@FunctionalInterface interface RandomEventSolverListener
	{
		void finished(String eventKey, String terminalStatus, String error);
	}
	private static final class SuspendedRun
	{
		final String id;
		final Map<String, Object> inputs;
		SuspendedRun(String id, Map<String, Object> inputs) { this.id = id; this.inputs = inputs; }
	}
	static final class RunState
	{
		private final long runId;
		private final String owner;
		private final String scriptId;
		private final String status;
		private final boolean running;
		RunState(long runId, String owner, String scriptId, String status, boolean running)
		{
			this.runId = runId; this.owner = owner; this.scriptId = scriptId; this.status = status; this.running = running;
		}
		static RunState none() { return new RunState(-1, null, null, "IDLE", false); }
		long getRunId() { return runId; }
		String getOwner() { return owner; }
		String getScriptId() { return scriptId; }
		String getStatus() { return status; }
		boolean isRunning() { return running; }
		boolean isManual() { return "manual".equals(owner); }
		String getRuleId() { return owner != null && owner.startsWith("rule:") ? owner.substring(5) : null; }
	}
}
