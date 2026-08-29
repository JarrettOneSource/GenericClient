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
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

@Slf4j
final class GenericClientLuaHost implements AutoCloseable
{
	private static final String REPL_NAME = "Lua REPL";
	private static final String MANUAL_OWNER = "manual";

	private static final int LOG_HISTORY_SIZE = 80;

	private final GenericClientScriptRegistry registry;
	private final WalkRandomAction walkRandomAction;
	private final WalkToAction walkToAction;
	private final NpcInteractAction npcInteractAction;
	private final CombatModeAction combatModeAction;
	private final QuestAction questAction;
	private final Consumer<String> cancelAction;
	private final GenericClientBehaviorController behavior;
	private final Consumer<String> statusSink;
	private final LongSupplier clock;
	private final ExecutorService scheduler;
	private final ArrayDeque<String> recentLogs = new ArrayDeque<>();

	private volatile String activeScript = "none";
	private volatile String status = "IDLE";
	private volatile Map<String, Object> activeInputs = Collections.emptyMap();
	private volatile ActiveRun activeRun;
	private volatile Runnable manualStopListener = () -> { };
	private volatile Supplier<Map<String, Object>> randomEventStateSupplier = Collections::emptyMap;
	private volatile RandomEventSolverListener randomEventSolverListener = (key, state, error) -> { };
	private volatile String randomEventKey;
	private volatile boolean randomEventCleanupComplete;
	private volatile boolean closed;
	private long nextRunId;
	private GenericClientSnapshot currentSnapshot;
	private GenericClientLuaScript session;
	private SuspendedRun suspendedRun;
	private GenericClientLuaScript repl;
	private volatile CompletableFuture<Map<String, Object>> replCompletion;
	private final List<String> replLogs = new ArrayList<>();

	GenericClientLuaHost(
		Path scriptsDirectory,
		WalkRandomAction walkRandomAction,
		WalkToAction walkToAction,
		Consumer<String> cancelAction,
		GenericClientBehaviorController behavior,
		Consumer<String> statusSink) throws IOException
	{
		this(
			scriptsDirectory,
			walkRandomAction,
			walkToAction,
			unsupportedNpcInteractAction(),
			unsupportedCombatModeAction(),
			unsupportedQuestAction(),
			cancelAction,
			behavior,
			statusSink,
			System::nanoTime);
	}

	GenericClientLuaHost(
		Path scriptsDirectory,
		WalkRandomAction walkRandomAction,
		WalkToAction walkToAction,
		NpcInteractAction npcInteractAction,
		Consumer<String> cancelAction,
		GenericClientBehaviorController behavior,
		Consumer<String> statusSink) throws IOException
	{
		this(
			scriptsDirectory,
			walkRandomAction,
			walkToAction,
			npcInteractAction,
			unsupportedCombatModeAction(),
			unsupportedQuestAction(),
			cancelAction,
			behavior,
			statusSink,
			System::nanoTime);
	}

	GenericClientLuaHost(
		Path scriptsDirectory,
		WalkRandomAction walkRandomAction,
		WalkToAction walkToAction,
		NpcInteractAction npcInteractAction,
		CombatModeAction combatModeAction,
		Consumer<String> cancelAction,
		GenericClientBehaviorController behavior,
		Consumer<String> statusSink) throws IOException
	{
		this(
			scriptsDirectory,
			walkRandomAction,
			walkToAction,
			npcInteractAction,
			combatModeAction,
			unsupportedQuestAction(),
			cancelAction,
			behavior,
			statusSink,
			System::nanoTime);
	}

	GenericClientLuaHost(
		Path scriptsDirectory,
		WalkRandomAction walkRandomAction,
		WalkToAction walkToAction,
		Consumer<String> cancelAction,
		GenericClientBehaviorController behavior,
		Consumer<String> statusSink,
		LongSupplier clock) throws IOException
	{
		this(
			scriptsDirectory,
			walkRandomAction,
			walkToAction,
			unsupportedNpcInteractAction(),
			unsupportedCombatModeAction(),
			unsupportedQuestAction(),
			cancelAction,
			behavior,
			statusSink,
			clock);
	}

	GenericClientLuaHost(
		Path scriptsDirectory,
		WalkRandomAction walkRandomAction,
		WalkToAction walkToAction,
		NpcInteractAction npcInteractAction,
		Consumer<String> cancelAction,
		GenericClientBehaviorController behavior,
		Consumer<String> statusSink,
		LongSupplier clock) throws IOException
	{
		this(
			scriptsDirectory,
			walkRandomAction,
			walkToAction,
			npcInteractAction,
			unsupportedCombatModeAction(),
			unsupportedQuestAction(),
			cancelAction,
			behavior,
			statusSink,
			clock);
	}

	GenericClientLuaHost(
		Path scriptsDirectory,
		WalkRandomAction walkRandomAction,
		WalkToAction walkToAction,
		NpcInteractAction npcInteractAction,
		CombatModeAction combatModeAction,
		QuestAction questAction,
		Consumer<String> cancelAction,
		GenericClientBehaviorController behavior,
		Consumer<String> statusSink) throws IOException
	{
		this(
			scriptsDirectory,
			walkRandomAction,
			walkToAction,
			npcInteractAction,
			combatModeAction,
			questAction,
			cancelAction,
			behavior,
			statusSink,
			System::nanoTime);
	}

	GenericClientLuaHost(
		Path scriptsDirectory,
		WalkRandomAction walkRandomAction,
		WalkToAction walkToAction,
		NpcInteractAction npcInteractAction,
		CombatModeAction combatModeAction,
		QuestAction questAction,
		Consumer<String> cancelAction,
		GenericClientBehaviorController behavior,
		Consumer<String> statusSink,
		LongSupplier clock) throws IOException
	{
		this.registry = new GenericClientScriptRegistry(scriptsDirectory);
		this.walkRandomAction = walkRandomAction;
		this.walkToAction = walkToAction;
		this.npcInteractAction = npcInteractAction;
		this.combatModeAction = combatModeAction;
		this.questAction = questAction;
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

	}

	List<GenericClientScriptRegistry.Script> listScripts()
	{
		return registry.list();
	}

	CompletableFuture<List<GenericClientScriptInput>> describe(String scriptId)
	{
		return inspect(scriptId, GenericClientLuaScript::getInputs);
	}

	CompletableFuture<List<GenericClientScriptAction>> describeActions(String scriptId)
	{
		return inspect(scriptId, GenericClientLuaScript::getActions);
	}

	private <T> CompletableFuture<T> inspect(
		String scriptId,
		Function<GenericClientLuaScript, T> reader)
	{
		CompletableFuture<T> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			try (GenericClientLuaScript descriptor = new GenericClientLuaScript(
				this,
				scriptId,
				registry.readExecutableSource(scriptId)))
			{
				completion.complete(reader.apply(descriptor));
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
			return behavior.status();
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

	String findRandomEventSolver(int npcId)
	{
		GenericClientScriptRegistry.Script solver = registry.findRandomEventSolver(npcId);
		return solver == null ? null : solver.getId();
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
			ActiveRun run = activeRun;
			if (session != null && run != null && MANUAL_OWNER.equals(run.owner))
			{
				suspendedRun = new SuspendedRun(run.definition.getId(), run.values);
			}
			stopOnScheduler("random_event_detected");
			interruptReplForRandomEvent();
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
		GenericClientScriptRegistry.Script solver = registry.get(solverScript);
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
		scheduler.execute(() ->
		{
			GenericClientLuaScript candidate = null;
			try
			{
				String blockedBy = randomEventKey;
				if (blockedBy != null && !randomEventOwner(blockedBy).equals(owner))
				{
					throw new IllegalStateException(
						"A random event requires completion before another standalone script can start");
				}
				if (!replaceActive && session != null)
				{
					ActiveRun running = activeRun;
					String activeOwner = running == null ? "unknown" : running.owner;
					completion.complete("LUA_START_SKIPPED active_owner=" + activeOwner);
					return;
				}
				GenericClientScriptRegistry.Script definition = registry.get(scriptId);
				candidate = new GenericClientLuaScript(
					this,
					definition.getId(),
					registry.readExecutableSource(definition.getId()));
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
				activeRun = new ActiveRun(
					++nextRunId,
					owner,
					definition,
					candidate,
					resolvedInputs,
					status,
					candidate.getStartedNanos());
				if (previous != null)
				{
					cancelAction.accept("script_replaced");
					previous.close();
				}
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

	CompletableFuture<String> stopScheduled(String ruleId, String reason)
	{
		String owner = "rule:" + ruleId;
		CompletableFuture<String> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			ActiveRun run = activeRun;
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
				if (current != null && canAdvance(current))
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
		boolean breaksEnabled,
		boolean useRun)
	{
		walkToAction.walkTo(destination, within, timeoutTicks, breaksEnabled, useRun).handle((receipt, error) ->
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

	void submitNpcInteract(
		GenericClientLuaScript script,
		long requestId,
		Integer id,
		String name,
		String action,
		int within,
		boolean breaksEnabled)
	{
		npcInteractAction.interact(id, name, action, within, breaksEnabled).handle((receipt, error) ->
		{
			Map<String, Object> result = receipt == null
				? new LinkedHashMap<>()
				: new LinkedHashMap<>(receipt);
			if (error != null)
			{
				result.put("status", "rejected");
				result.put("result", rootMessage(error));
			}
			return result;
		}).whenComplete((receipt, error) -> completeAction(script, requestId, receipt, error));
	}

	void submitQuestAction(
		GenericClientLuaScript script,
		long requestId,
		String type,
		Map<String, Object> action,
		boolean breaksEnabled)
	{
		CompletableFuture<Map<String, Object>> execution;
		try
		{
			execution = questAction.execute(type, action, breaksEnabled);
		}
		catch (RuntimeException exception)
		{
			Map<String, Object> receipt = new LinkedHashMap<>();
			receipt.put("status", "rejected");
			receipt.put("result", exception.getMessage());
			completeAction(script, requestId, receipt, null);
			return;
		}
		execution.handle((receipt, error) ->
		{
			Map<String, Object> result = receipt == null
				? new LinkedHashMap<>()
				: new LinkedHashMap<>(receipt);
			if (error != null)
			{
				result.put("status", "rejected");
				result.put("result", rootMessage(error));
			}
			return result;
		}).whenComplete((receipt, error) -> completeAction(script, requestId, receipt, error));
	}

	void submitCombatSetStyle(
		GenericClientLuaScript script,
		long requestId,
		int styleIndex,
		boolean breaksEnabled)
	{
		combatModeAction.setMode(styleIndex, breaksEnabled).handle((receipt, error) ->
		{
			Map<String, Object> result = receipt == null
				? new LinkedHashMap<>()
				: new LinkedHashMap<>(receipt);
			if (error != null)
			{
				result.put("status", "rejected");
				result.put("result", rootMessage(error));
			}
			return result;
		}).whenComplete((receipt, error) -> completeAction(script, requestId, receipt, error));
	}

	void submitCombatSetAutoRetaliate(
		GenericClientLuaScript script,
		long requestId,
		boolean enabled,
		boolean breaksEnabled)
	{
		int mode = enabled ? 5 : 4;
		combatModeAction.setMode(mode, breaksEnabled).handle((receipt, error) ->
		{
			Map<String, Object> result = receipt == null
				? new LinkedHashMap<>()
				: new LinkedHashMap<>(receipt);
			if (error != null)
			{
				result.put("status", "rejected");
				result.put("result", rootMessage(error));
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
					if (!canAdvance(script))
					{
						return;
					}
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
					if (!canAdvance(script))
					{
						return;
					}
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

	private static NpcInteractAction unsupportedNpcInteractAction()
	{
		return (id, name, action, within, breaksEnabled) ->
		{
			Map<String, Object> receipt = new LinkedHashMap<>();
			receipt.put("status", "rejected");
			receipt.put("result", "npc.interact is unavailable in this host");
			return CompletableFuture.completedFuture(receipt);
		};
	}

	private static CombatModeAction unsupportedCombatModeAction()
	{
		return (styleIndex, breaksEnabled) ->
		{
			Map<String, Object> receipt = new LinkedHashMap<>();
			receipt.put("status", "rejected");
			receipt.put("result", "combat.set_style is unavailable in this host");
			return CompletableFuture.completedFuture(receipt);
		};
	}

	private static QuestAction unsupportedQuestAction()
	{
		return (type, action, breaksEnabled) ->
		{
			Map<String, Object> receipt = new LinkedHashMap<>();
			receipt.put("status", "rejected");
			receipt.put("result", type + " is unavailable in this host");
			return CompletableFuture.completedFuture(receipt);
		};
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
			if (randomEventKey != null && !randomEventCleanupComplete)
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
		return saveScript(id, name, description, source, Collections.emptyList());
	}

	CompletableFuture<Map<String, Object>> saveScript(
		String id,
		String name,
		String description,
		String source,
		List<Integer> randomEvents)
	{
		CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			try
			{
				completion.complete(registry.save(
					id, name, description, source, randomEvents).toMap());
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
		result.put("module_sources", registry.readModuleSources(id));
		return result;
	}

	Map<String, Object> controlState()
	{
		RunState run = getRunState();
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("active", getActiveScriptView().toMap());
		value.put("active_script", activeScript);
		value.put("active_inputs", new LinkedHashMap<>(activeInputs));
		value.put("script_status", status);
		value.put("run_id", run.getRunId() < 0L ? null : run.getRunId());
		value.put("run_owner", run.getOwner());
		value.put("repl_busy", replCompletion != null);
		value.put("random_event_blocked", randomEventKey != null);
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

	GenericClientActiveScript getActiveScriptView()
	{
		ActiveRun run = activeRun;
		return run == null
			? GenericClientActiveScript.none()
			: run.snapshot(clock.getAsLong());
	}

	RunState getRunState()
	{
		ActiveRun run = activeRun;
		if (run == null)
		{
			return RunState.none();
		}
		GenericClientActiveScript view = run.snapshot(clock.getAsLong());
		return new RunState(run.id, run.owner, view.getId(), view.getStatus(), view.isRunning());
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
		ActiveRun run = activeRun;
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
		if (run != null && run.script == expected && run.owner.startsWith("random_event:"))
		{
			randomEventSolverListener.finished(
				run.owner.substring("random_event:".length()),
				status,
				expected.getFaultMessage());
		}
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
		stopOnScheduler("host_closed");
	}

	private void stopOnScheduler(String reason)
	{
		GenericClientLuaScript current = session;
		ActiveRun run = activeRun;
		session = null;
		cancelAction.accept("script_stopped_" + reason);
		if (current != null)
		{
			current.close();
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

	private void interruptReplForRandomEvent()
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
			pending.completeExceptionally(
				new IllegalStateException("Lua REPL interrupted by random event"));
		}
	}

	private boolean canAdvance(GenericClientLuaScript script)
	{
		String blockedBy = randomEventKey;
		if (blockedBy == null || script == repl)
		{
			return true;
		}
		ActiveRun run = activeRun;
		return session == script && run != null && randomEventOwner(blockedBy).equals(run.owner);
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

	private static final class ActiveRun
	{
		private final long id;
		private final String owner;
		private final GenericClientScriptRegistry.Script definition;
		private final GenericClientLuaScript script;
		private final Map<String, Object> values;
		private final long startedNanos;
		private volatile String status;
		private volatile long finishedNanos = -1L;
		private volatile List<GenericClientOverlayRow> terminalOverlay = Collections.emptyList();
		private volatile Object terminalValue;
		private volatile String terminalError;

		private ActiveRun(
			long id,
			String owner,
			GenericClientScriptRegistry.Script definition,
			GenericClientLuaScript script,
			Map<String, Object> values,
			String status,
			long startedNanos)
		{
			this.id = id;
			this.owner = owner;
			this.definition = definition;
			this.script = script;
			this.values = values;
			this.status = status;
			this.startedNanos = startedNanos;
		}

		private void finish(
			String terminalStatus,
			List<GenericClientOverlayRow> overlay,
			Object value,
			String error,
			long nowNanos)
		{
			status = terminalStatus;
			terminalOverlay = overlay;
			terminalValue = value;
			terminalError = error;
			finishedNanos = nowNanos;
		}

		private GenericClientActiveScript snapshot(long nowNanos)
		{
			long end = finishedNanos < 0L ? nowNanos : finishedNanos;
			long runtimeMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, end - startedNanos));
			List<GenericClientOverlayRow> overlay = finishedNanos < 0L
				? script.getOverlayRows()
				: terminalOverlay;
			return new GenericClientActiveScript(
				definition.getId(),
				definition.getName(),
				definition.getDescription(),
				status,
				runtimeMillis,
				script.getInputs(),
				values,
				script.getActions(),
				overlay,
				terminalValue,
				terminalError);
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

	static final class RunState
	{
		private static final RunState NONE = new RunState(-1L, null, null, "IDLE", false);
		private final long runId;
		private final String owner;
		private final String scriptId;
		private final String status;
		private final boolean running;

		RunState(long runId, String owner, String scriptId, String status, boolean running)
		{
			this.runId = runId;
			this.owner = owner;
			this.scriptId = scriptId;
			this.status = status;
			this.running = running;
		}

		static RunState none()
		{
			return NONE;
		}

		long getRunId()
		{
			return runId;
		}

		String getOwner()
		{
			return owner;
		}

		String getScriptId()
		{
			return scriptId;
		}

		String getStatus()
		{
			return status;
		}

		boolean isRunning()
		{
			return running;
		}

		boolean isManual()
		{
			return MANUAL_OWNER.equals(owner);
		}

		String getRuleId()
		{
			return owner != null && owner.startsWith("rule:") ? owner.substring(5) : null;
		}
	}

	@FunctionalInterface
	interface WalkRandomAction
	{
		CompletableFuture<GenericClientInteractionResult> walk(boolean breaksEnabled);
	}

	@FunctionalInterface
	interface RandomEventSolverListener
	{
		void finished(String eventKey, String terminalStatus, String error);
	}

	@FunctionalInterface
	interface WalkToAction
	{
		CompletableFuture<Map<String, Object>> walkTo(
			WorldPoint destination,
			int within,
			int timeoutTicks,
			boolean breaksEnabled,
			boolean useRun);
	}

	@FunctionalInterface
	interface NpcInteractAction
	{
		CompletableFuture<Map<String, Object>> interact(
			Integer id,
			String name,
			String action,
			int within,
			boolean breaksEnabled);
	}

	@FunctionalInterface
	interface CombatModeAction
	{
		CompletableFuture<Map<String, Object>> setMode(int mode, boolean breaksEnabled);
	}

	@FunctionalInterface
	interface QuestAction
	{
		CompletableFuture<Map<String, Object>> execute(
			String type,
			Map<String, Object> action,
			boolean breaksEnabled);
	}
}
