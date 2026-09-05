package com.genericclient;

import static com.genericclient.GenericClientErrors.rootMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class GenericClientAutomationScheduler implements AutoCloseable
{
	private final GenericClientAutomationStore store;
	private final Runtime runtime;
	private final GenericClientRuleEngine ruleEngine;
	private final Clock clock;
	private final ScheduledExecutorService executor;
	private final Consumer<String> reporter;

	private volatile Map<String, Object> publishedStatus = waitingStatus();
	private volatile boolean closed;

	private String profileId;
	private GenericClientAutomationConfig config;
	private GenericClientSchedule schedules;
	private GenericClientAutomationStore.State state;
	private GenericClientSnapshot latestSnapshot;
	private ScheduledFuture<?> boundaryFuture;
	private Instant scheduledBoundary;
	private boolean actionPending;
	private String fault;
	private volatile boolean manualPauseRequested;
	private volatile boolean attentionRequired;
	private volatile String attentionReason;

	GenericClientAutomationScheduler(
		Path directory,
		GenericClientScriptHost scriptHost,
		Consumer<String> reporter) throws IOException
	{
		this(
			new GenericClientAutomationStore(directory, ZoneId.systemDefault().getId()),
			new ScriptRuntimeAdapter(scriptHost),
			new GenericClientRuleEngine(),
			Clock.systemUTC(),
			newExecutor(),
			reporter);
	}

	GenericClientAutomationScheduler(
		GenericClientAutomationStore store,
		Runtime runtime,
		GenericClientRuleEngine ruleEngine,
		Clock clock,
		ScheduledExecutorService executor,
		Consumer<String> reporter)
	{
		this.store = store;
		this.runtime = runtime;
		this.ruleEngine = ruleEngine;
		this.clock = clock;
		this.executor = executor;
		this.reporter = reporter;
		runtime.setManualStopListener(() -> setPaused(true, "manual_script_stop"));
	}

	private static ScheduledExecutorService newExecutor()
	{
		return Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "GenericClient-Automation");
			thread.setDaemon(true);
			return thread;
		});
	}

	CompletableFuture<Map<String, Object>> activateProfile(String profileId)
	{
		return submit(() ->
		{
			if (profileId.equals(this.profileId) && config != null)
			{
				evaluate("profile_unchanged");
				return status();
			}
			stopOwnedRun("account_changed");
			this.profileId = profileId;
			latestSnapshot = null;
			manualPauseRequested = false;
			loadProfile();
			evaluate("profile_activated");
			return status();
		});
	}

	void publishGameTick(GenericClientSnapshot snapshot)
	{
		if (closed)
		{
			return;
		}
		executeIfOpen(() ->
		{
			latestSnapshot = snapshot;
			evaluate("game_tick");
		});
	}

	void clearSnapshot()
	{
		if (closed)
		{
			return;
		}
		executeIfOpen(() ->
		{
			latestSnapshot = null;
			evaluate("game_state_unavailable");
		});
	}

	Map<String, Object> status()
	{
		return new LinkedHashMap<>(publishedStatus);
	}

	CompletableFuture<Map<String, Object>> getConfig()
	{
		return submit(() ->
		{
			ensureProfile();
			return config.toMap();
		});
	}

	CompletableFuture<Map<String, Object>> configure(Map<String, Object> value)
	{
		return submit(() ->
		{
			ensureProfile();
			GenericClientAutomationConfig candidate = GenericClientAutomationConfig.fromMap(value);
			validateScripts(candidate);
			store.saveConfig(profileId, candidate);
			config = candidate;
			schedules = GenericClientSchedule.compile(candidate);
			fault = null;
			state.setLastEvent("config_saved");
			saveState();
			evaluate("config_saved");
			return status();
		});
	}

	CompletableFuture<Map<String, Object>> setEnabled(boolean enabled)
	{
		return submit(() ->
		{
			ensureProfile();
			if (fault != null)
			{
				throw new IllegalStateException(
					"Replace or repair the invalid automation configuration before enabling it");
			}
			GenericClientAutomationConfig candidate = config.withEnabled(enabled);
			store.saveConfig(profileId, candidate);
			config = candidate;
			schedules = GenericClientSchedule.compile(candidate);
			state.setLastEvent(enabled ? "enabled" : "disabled");
			saveState();
			evaluate(enabled ? "enabled" : "disabled");
			return status();
		});
	}

	CompletableFuture<Map<String, Object>> setPaused(boolean paused, String reason)
	{
		if (paused)
		{
			manualPauseRequested = true;
		}
		return submit(() ->
		{
			ensureProfile();
			state.setPaused(paused);
			manualPauseRequested = false;
			state.setLastEvent(paused ? "paused:" + reason : "resumed:" + reason);
			saveState();
			evaluate(paused ? "paused" : "resumed");
			return status();
		});
	}

	void setAttentionRequired(boolean required, String reason)
	{
		attentionRequired = required;
		attentionReason = required ? reason : null;
		executeIfOpen(() -> evaluate(required ? "attention_required" : "attention_released"));
	}

	CompletableFuture<Map<String, Object>> reload()
	{
		return submit(() ->
		{
			ensureProfile();
			loadProfile();
			evaluate("reloaded");
			return status();
		});
	}

	private void loadProfile()
	{
		try
		{
			GenericClientAutomationConfig loadedConfig = store.loadConfig(profileId);
			validateScripts(loadedConfig);
			GenericClientAutomationStore.State loadedState = store.loadState(profileId);
			loadedState.setHandledRunId(-1L);
			loadedState.clearExpiredCooldowns(clock.millis());
			config = loadedConfig;
			schedules = GenericClientSchedule.compile(loadedConfig);
			state = loadedState;
			fault = null;
			saveState();
		}
		catch (IOException | RuntimeException exception)
		{
			fault = rootMessage(exception);
			config = GenericClientAutomationConfig.empty(ZoneId.systemDefault().getId());
			schedules = GenericClientSchedule.compile(config);
			state = GenericClientAutomationStore.State.empty();
			state.setPaused(true);
			state.setLastEvent("load_failed:" + fault);
			reporter.accept("AUTOMATION_LOAD_FAILED profile=" + profileId + " message=" + fault);
		}
	}

	private void validateScripts(GenericClientAutomationConfig candidate)
	{
		Set<String> scripts = new HashSet<>();
		for (Map<String, Object> script : runtime.listScriptValues())
		{
			scripts.add(String.valueOf(script.get("id")));
		}
		for (GenericClientAutomationConfig.RuleSpec rule : candidate.getRules())
		{
			String scriptId = rule.getRun().getScript();
			if (!scripts.contains(scriptId))
			{
				throw new IllegalArgumentException(
					"Rule " + rule.getId() + " references unknown script: " + scriptId);
			}
			try
			{
				GenericClientScriptInput.resolve(
					runtime.describe(scriptId).get(10, TimeUnit.SECONDS),
					rule.getRun().getInputs());
			}
			catch (InterruptedException exception)
			{
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while validating script inputs", exception);
			}
			catch (ExecutionException | java.util.concurrent.TimeoutException exception)
			{
				throw new IllegalArgumentException(
					"Unable to validate inputs for script " + scriptId, exception);
			}
		}
	}

	private void evaluate(String trigger)
	{
		if (closed)
		{
			return;
		}
		if (profileId == null || config == null || state == null)
		{
			publishedStatus = waitingStatus();
			return;
		}
		long now = clock.millis();
		state.clearExpiredCooldowns(now);
		GenericClientSchedule.Snapshot scheduleSnapshot = schedules.evaluate(clock.instant());
		Map<String, Object> account = accountSnapshot();
		GenericClientRuleEngine.Evaluation decision = ruleEngine.evaluate(
			config, scheduleSnapshot, account, state.getCooldowns(), now);
		GenericClientScriptHost.RunState run = runtime.getRunState();
		boolean attentionPending = attentionRequired;
		String attentionDetail = attentionReason;
		boolean pausePending = state.isPaused() || manualPauseRequested;

		if (run.getRunId() >= 0L && !run.isRunning() && run.getRuleId() != null &&
			run.getRunId() != state.getHandledRunId())
		{
			handleTerminalRun(run, now);
			decision = ruleEngine.evaluate(config, scheduleSnapshot, account, state.getCooldowns(), now);
		}

		AutomationMode mode = selectMode(
			run, decision, attentionPending, attentionDetail, pausePending);
		publishStatus(
			mode.name, mode.detail, trigger, scheduleSnapshot, decision, run,
			pausePending, attentionPending);
		rescheduleBoundary(scheduleSnapshot.getNextTransition());
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> accountSnapshot()
	{
		if (latestSnapshot == null)
		{
			return Collections.emptyMap();
		}
		Object value = latestSnapshot.read("account", Collections.emptyMap());
		return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
	}

	private AutomationMode selectMode(
		GenericClientScriptHost.RunState run,
		GenericClientRuleEngine.Evaluation decision,
		boolean attentionPending,
		String attentionDetail,
		boolean pausePending)
	{
		if (fault != null)
		{
			return new AutomationMode("faulted", fault);
		}
		if (!config.isEnabled())
		{
			stopIfOwned(run, "disabled");
			return new AutomationMode("disabled", "automation is disabled");
		}
		if (attentionPending)
		{
			stopIfOwned(run, "attention_required");
			return new AutomationMode(
				"attention_required",
				attentionDetail == null ? "external attention is required" : attentionDetail);
		}
		if (pausePending)
		{
			stopIfOwned(run, "paused");
			return new AutomationMode("paused", state.getLastEvent());
		}
		if (actionPending)
		{
			return new AutomationMode("transitioning", state.getLastEvent());
		}
		if (run.isRunning() && run.isManual())
		{
			clearActiveRuleIfNeeded();
			return new AutomationMode("manual", "manual script owns the runtime");
		}
		if (run.isRunning() && run.getRuleId() != null)
		{
			return activeRuleMode(run.getRuleId(), decision);
		}
		if (decision.getSelected() != null)
		{
			GenericClientRuleEngine.RuleEvaluation selected = decision.getSelected();
			requestStart(selected.getRule());
			return new AutomationMode("starting", selected.getReason());
		}
		clearActiveRuleIfNeeded();
		return new AutomationMode("idle", "no rule is eligible");
	}

	private AutomationMode activeRuleMode(
		String owner,
		GenericClientRuleEngine.Evaluation decision)
	{
		GenericClientRuleEngine.RuleEvaluation ownerDecision = decision.get(owner);
		state.setActiveRule(owner);
		if (ownerDecision == null || ownerDecision.getTruth() == GenericClientRuleEngine.Truth.FALSE)
		{
			requestStop(owner, "ineligible");
			return new AutomationMode(
				"stopping",
				ownerDecision == null ? "owning rule was removed" : ownerDecision.getReason());
		}
		if (ownerDecision.getTruth() == GenericClientRuleEngine.Truth.UNKNOWN)
		{
			return new AutomationMode("holding", ownerDecision.getReason());
		}
		return new AutomationMode("running", "rule " + owner + " retains its script lease");
	}

	private void handleTerminalRun(GenericClientScriptHost.RunState run, long now)
	{
		String ruleId = run.getRuleId();
		GenericClientAutomationConfig.RuleSpec rule = config.getRule(ruleId);
		state.setHandledRunId(run.getRunId());
		state.setActiveRule(null);
		if (rule != null)
		{
			state.setCooldown(ruleId, Math.addExact(now, rule.getRetryAfterMillis()));
		}
		state.setLastEvent("run_" + run.getStatus().toLowerCase() + ":" + ruleId);
		saveStateQuietly();
		reporter.accept("AUTOMATION_RUN_TERMINAL rule=" + ruleId + " status=" + run.getStatus());
	}

	private void requestStart(GenericClientAutomationConfig.RuleSpec rule)
	{
		actionPending = true;
		state.setActiveRule(rule.getId());
		state.setLastEvent("starting:" + rule.getId());
		saveStateQuietly();
		runtime.startScheduled(rule.getId(), rule.getRun().getScript(), rule.getRun().getInputs())
			.whenComplete((result, error) -> executeIfOpen(() ->
			{
				actionPending = false;
				if (error != null)
				{
					long retry = Math.addExact(clock.millis(), rule.getRetryAfterMillis());
					state.setCooldown(rule.getId(), retry);
					state.setActiveRule(null);
					state.setLastEvent("start_failed:" + rule.getId() + ":" + rootMessage(error));
					reporter.accept("AUTOMATION_START_FAILED rule=" + rule.getId() +
						" message=" + rootMessage(error));
				}
				else if (result.contains("SCRIPT_START_SKIPPED"))
				{
					state.setActiveRule(null);
					state.setLastEvent("start_skipped:" + rule.getId());
				}
				else
				{
					state.setLastEvent("started:" + rule.getId());
					reporter.accept("AUTOMATION_STARTED rule=" + rule.getId() +
						" script=" + rule.getRun().getScript());
				}
				saveStateQuietly();
				evaluate("start_result");
			}));
	}

	private void requestStop(String ruleId, String reason)
	{
		if (actionPending)
		{
			return;
		}
		actionPending = true;
		state.setLastEvent("stopping:" + ruleId + ":" + reason);
		saveStateQuietly();
		runtime.stopScheduled(ruleId, reason).whenComplete((result, error) -> executeIfOpen(() ->
		{
			actionPending = false;
			state.setActiveRule(null);
			state.setLastEvent(error == null
				? "stopped:" + ruleId + ":" + reason
				: "stop_failed:" + ruleId + ":" + rootMessage(error));
			saveStateQuietly();
			evaluate("stop_result");
		}));
	}

	private void stopIfOwned(GenericClientScriptHost.RunState run, String reason)
	{
		if (run.isRunning() && run.getRuleId() != null)
		{
			requestStop(run.getRuleId(), reason);
		}
	}

	private void stopOwnedRun(String reason)
	{
		GenericClientScriptHost.RunState run = runtime.getRunState();
		if (run.isRunning() && run.getRuleId() != null)
		{
			try
			{
				runtime.stopScheduled(run.getRuleId(), reason).get(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException exception)
			{
				Thread.currentThread().interrupt();
			}
			catch (ExecutionException | java.util.concurrent.TimeoutException exception)
			{
				log.warn("Unable to stop old automation-owned script", exception);
			}
		}
	}

	private void clearActiveRuleIfNeeded()
	{
		if (state.getActiveRule() != null)
		{
			state.setActiveRule(null);
			saveStateQuietly();
		}
	}

	private void publishStatus(
		String mode,
		String detail,
		String trigger,
		GenericClientSchedule.Snapshot scheduleSnapshot,
		GenericClientRuleEngine.Evaluation decision,
		GenericClientScriptHost.RunState run,
		boolean pausePending,
		boolean attentionPending)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("available", true);
		value.put("profile", profileId);
		value.put("config_path", store.configPath(profileId).toString());
		value.put("enabled", config.isEnabled());
		value.put("paused", pausePending);
		value.put("attention_required", attentionPending);
		value.put("mode", mode);
		value.put("detail", detail);
		value.put("trigger", trigger);
		value.put("active_rule", state.getActiveRule());
		value.put("last_event", state.getLastEvent());
		Map<String, Object> runValue = new LinkedHashMap<>();
		runValue.put("id", run.getRunId() < 0L ? null : run.getRunId());
		runValue.put("owner", run.getOwner());
		runValue.put("script", run.getScriptId());
		runValue.put("status", run.getStatus());
		runValue.put("running", run.isRunning());
		value.put("run", runValue);
		value.putAll(scheduleSnapshot.toMap());
		value.putAll(decision.toMap());
		publishedStatus = Collections.unmodifiableMap(value);
	}

	private void rescheduleBoundary(Instant nextTransition)
	{
		if (nextTransition != null && nextTransition.equals(scheduledBoundary) &&
			boundaryFuture != null && !boundaryFuture.isDone())
		{
			return;
		}
		if (boundaryFuture != null)
		{
			boundaryFuture.cancel(false);
		}
		scheduledBoundary = nextTransition;
		if (nextTransition == null || closed)
		{
			boundaryFuture = null;
			return;
		}
		long delay = Math.max(1L, nextTransition.toEpochMilli() - clock.millis() + 5L);
		boundaryFuture = executor.schedule(() ->
		{
			boundaryFuture = null;
			scheduledBoundary = null;
			evaluate("schedule_transition");
		}, delay, TimeUnit.MILLISECONDS);
	}

	private void saveState()
	{
		try
		{
			store.saveState(profileId, state, clock.millis());
		}
		catch (IOException exception)
		{
			throw new IllegalStateException("Unable to save automation state", exception);
		}
	}

	private void saveStateQuietly()
	{
		try
		{
			store.saveState(profileId, state, clock.millis());
		}
		catch (IOException exception)
		{
			fault = rootMessage(exception);
			reporter.accept("AUTOMATION_STATE_SAVE_FAILED message=" + fault);
		}
	}

	private <T> CompletableFuture<T> submit(ThrowingSupplier<T> supplier)
	{
		CompletableFuture<T> completion = new CompletableFuture<>();
		if (closed)
		{
			completion.completeExceptionally(new IllegalStateException("Automation scheduler is closed"));
			return completion;
		}
		if (!executeIfOpen(() ->
		{
			try
			{
				completion.complete(supplier.get());
			}
			catch (Exception exception)
			{
				completion.completeExceptionally(exception);
			}
		}))
		{
			completion.completeExceptionally(new IllegalStateException("Automation scheduler is closed"));
		}
		return completion;
	}

	private boolean executeIfOpen(Runnable task)
	{
		if (closed)
		{
			return false;
		}
		try
		{
			executor.execute(task);
			return true;
		}
		catch (RejectedExecutionException exception)
		{
			if (!closed)
			{
				throw exception;
			}
			return false;
		}
	}

	private void ensureProfile()
	{
		if (profileId == null || config == null || state == null)
		{
			throw new IllegalStateException("Automation is waiting for an account profile");
		}
	}

	private static Map<String, Object> waitingStatus()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("available", false);
		value.put("mode", "waiting_for_account");
		return Collections.unmodifiableMap(value);
	}


	@Override
	public void close()
	{
		if (closed)
		{
			return;
		}
		closed = true;
		runtime.setManualStopListener(null);
		if (boundaryFuture != null)
		{
			boundaryFuture.cancel(false);
			boundaryFuture = null;
		}
		scheduledBoundary = null;
		stopOwnedRun("scheduler_closed");
		executor.shutdownNow();
	}

	@FunctionalInterface
	private interface ThrowingSupplier<T>
	{
		T get() throws Exception;
	}

	private static final class AutomationMode
	{
		private final String name;
		private final String detail;

		private AutomationMode(String name, String detail)
		{
			this.name = name;
			this.detail = detail;
		}
	}

	interface Runtime
	{
		List<Map<String, Object>> listScriptValues();

		CompletableFuture<List<GenericClientScriptInput>> describe(String scriptId);

		CompletableFuture<String> startScheduled(
			String ruleId,
			String scriptId,
			Map<String, Object> inputs);

		CompletableFuture<String> stopScheduled(String ruleId, String reason);

		GenericClientScriptHost.RunState getRunState();

		void setManualStopListener(Runnable listener);
	}

	private static final class ScriptRuntimeAdapter implements Runtime
	{
		private final GenericClientScriptHost host;

		private ScriptRuntimeAdapter(GenericClientScriptHost host)
		{
			this.host = host;
		}

		@Override
		public List<Map<String, Object>> listScriptValues()
		{
			return host.listScriptValues();
		}

		@Override
		public CompletableFuture<List<GenericClientScriptInput>> describe(String scriptId)
		{
			return host.describe(scriptId);
		}

		@Override
		public CompletableFuture<String> startScheduled(
			String ruleId,
			String scriptId,
			Map<String, Object> inputs)
		{
			return host.startScheduled(ruleId, scriptId, inputs);
		}

		@Override
		public CompletableFuture<String> stopScheduled(String ruleId, String reason)
		{
			return host.stopScheduled(ruleId, reason);
		}

		@Override
		public GenericClientScriptHost.RunState getRunState()
		{
			return host.getRunState();
		}

		@Override
		public void setManualStopListener(Runnable listener)
		{
			host.setManualStopListener(listener);
		}
	}
}
