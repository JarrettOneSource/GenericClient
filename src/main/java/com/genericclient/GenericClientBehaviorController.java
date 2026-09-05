package com.genericclient;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class GenericClientBehaviorController implements AutoCloseable
{
	private static final long SAVE_INTERVAL_ACTIVE_MILLIS = 30_000L;
	private static final long MAX_ACTIVE_TICK_MILLIS = 5_000L;
	private static final long GLOBAL_PHASE_COOLDOWN_MILLIS = TimeUnit.MINUTES.toMillis(2);
	private static final long NAMED_PHASE_COOLDOWN_MILLIS = TimeUnit.MINUTES.toMillis(5);

	private final GenericClientBehaviorStore store;
	private final BreakEffects effects;
	private final Timer timer;
	private final Clock clock;
	private final Random random;
	private final Consumer<String> reporter;
	final GenericClientPolicyResolver policies;

	private GenericClientBehaviorProfile profile;
	private GenericClientBehaviorProfile generatedProfile;
	private GenericClientBehaviorState state;
	private CompletableFuture<Map<String, Object>> activeBreak;
	private CompletableFuture<Void> activeBreakEffects = CompletableFuture.completedFuture(null);
	private Cancellable breakTimer;
	private long lastTickNanos;
	private long activeMillisAtLastSave;
	private boolean loggedIn;
	private boolean inputOwned;
	private long sessionStartedActiveMillis;
	private long lastBoundaryActiveMillis = -1L;
	private final java.util.ArrayDeque<Long> boundaryGaps = new java.util.ArrayDeque<>();
	private boolean closed;

	GenericClientBehaviorController(
		GenericClientBehaviorStore store,
		BreakEffects effects,
		Timer timer,
		Clock clock,
		Random random,
		Supplier<GenericClientPolicyResolver.Signals> policySignals,
		Consumer<String> reporter)
	{
		this.store = store;
		this.effects = effects;
		this.timer = timer;
		this.clock = clock;
		this.random = random;
		this.reporter = reporter;
		this.policies = new GenericClientPolicyResolver(policySignals, reporter);
	}

	static Timer scheduledTimer(ScheduledExecutorService executor)
	{
		return (task, delayMillis) ->
		{
			java.util.concurrent.ScheduledFuture<?> future = executor.schedule(
				task,
				Math.max(0L, delayMillis),
				TimeUnit.MILLISECONDS);
			return () -> future.cancel(false);
		};
	}

	static Clock systemClock()
	{
		return new Clock()
		{
			@Override
			public long epochMillis()
			{
				return System.currentTimeMillis();
			}

			@Override
			public long nanoTime()
			{
				return System.nanoTime();
			}
		};
	}

	synchronized void activateAccount(long accountHash) throws IOException
	{
		ensureOpen();
		GenericClientBehaviorProfile nextProfile = GenericClientBehaviorProfile.fromAccountHash(accountHash);
		if (profile != null && profile.getId().equals(nextProfile.getId()))
		{
			return;
		}
		if (state != null)
		{
			saveStateQuietly();
		}
		cancelCurrentBreak("account_changed");
		generatedProfile = null;
		profile = null;
		state = null;
		inputOwned = false;
		GenericClientBehaviorOverrides overrides = store.loadOverrides(nextProfile.getId());
		GenericClientBehaviorProfile loadedProfile = overrides == null ? nextProfile : nextProfile.withOverrides(overrides);
		GenericClientBehaviorState loadedState = store.load(loadedProfile.getId(),
			() -> GenericClientBehaviorProfile.sampleExponentialBudget(random));
		if (loadedState == null)
		{
			loadedState = new GenericClientBehaviorState(loadedProfile.getId(), GenericClientBehaviorProfile.sampleExponentialBudget(random),
				GenericClientBehaviorProfile.sampleExponentialBudget(random));
		}
		store.save(loadedState, clock.epochMillis());
		generatedProfile = nextProfile;
		profile = loadedProfile;
		state = loadedState;
		activeMillisAtLastSave = state.getTotalActiveMillis();
		beginSession();
		lastTickNanos = clock.nanoTime();
		reporter.accept("BEHAVIOR_PROFILE_ACTIVATED id=" + profile.getId() +
			" title=" + profile.getTitle() + " edge=" + profile.getIdleEdge());
		restorePersistedBreak();
	}

	synchronized Map<String, Object> saveOverrides(GenericClientBehaviorOverrides overrides) throws IOException
	{
		ensureProfile();
		overrides.validate();
		store.saveOverrides(generatedProfile.getId(), overrides);
		profile = generatedProfile.withOverrides(overrides);
		reporter.accept("BEHAVIOR_OVERRIDES_SAVED title=" + profile.getTitle());
		return status();
	}

	synchronized Map<String, Object> resetOverrides() throws IOException
	{
		ensureProfile();
		store.deleteOverrides(generatedProfile.getId());
		profile = generatedProfile;
		reporter.accept("BEHAVIOR_OVERRIDES_RESET title=" + profile.getTitle());
		return status();
	}

	synchronized int mouseMoveDurationMillis()
	{
		return profile == null
			? GenericClientBehaviorProfile.DEFAULT_MOUSE_MOVE_DURATION_MILLIS
			: profile.getMouseMoveDurationMillis();
	}

	synchronized int typingWordsPerMinute()
	{
		return profile == null
			? GenericClientBehaviorProfile.DEFAULT_TYPING_WORDS_PER_MINUTE
			: profile.getTypingWordsPerMinute();
	}

	synchronized int dialogueReadingPercent()
	{
		return profile == null ? 50 : profile.getDialogueReadingPercent();
	}

	synchronized GenericClientBehaviorProfile.DialogueInputMode dialogueInputMode()
	{
		return profile == null
			? GenericClientBehaviorProfile.DialogueInputMode.MOUSE
			: profile.getDialogueInputMode();
	}

	synchronized GenericClientBehaviorProfile currentProfile() { return profile; }

	CompletableFuture<String> moveMouseOffscreen(GenericClientActivityContext context)
	{
		final GenericClientBehaviorProfile.Edge edge;
		synchronized (this)
		{
			ensureProfile();
			edge = profile.getIdleEdge();
		}
		return effects.moveOffscreen(edge, context);
	}

	synchronized void setLoggedIn(boolean loggedIn)
	{
		this.loggedIn = loggedIn;
		lastTickNanos = clock.nanoTime();
	}

	synchronized long totalActiveMillis() { return state == null ? 0L : state.getTotalActiveMillis(); }

	synchronized int nextWalkClickDelayTicks()
	{
		return profile == null ? 6 : profile.sampleWalkClickDelayTicks(random);
	}

	synchronized double nextWalkReachFraction()
	{
		return profile == null ? 1.0 : profile.sampleWalkReachFraction(random);
	}

	synchronized void beginSession()
	{
		sessionStartedActiveMillis = state == null ? 0L : state.getTotalActiveMillis();
		lastBoundaryActiveMillis = -1L;
		boundaryGaps.clear();
	}

	synchronized void publishActiveTick(boolean automationInputOwned, GenericClientActivityContext context)
	{
		inputOwned = automationInputOwned && context.getActivity() != GenericClientActivityContext.Activity.MANUAL &&
			context.isInputAllowed();
		if (closed)
		{
			return;
		}
		long now = clock.nanoTime();
		if (lastTickNanos == 0L)
		{
			lastTickNanos = now;
			return;
		}
		long elapsedMillis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(now - lastTickNanos));
		lastTickNanos = now;
		if (profile == null || state == null || !loggedIn || !inputOwned || activeBreak != null)
		{
			return;
		}
		long activeMillis = Math.min(elapsedMillis, MAX_ACTIVE_TICK_MILLIS);
		state.addActiveMillis(activeMillis);
		state.addMicroPressure(activeMillis * profile.microPressurePerMinute(context.getActivity()) / 60_000.0);
		if (state.getTotalActiveMillis() - activeMillisAtLastSave >= SAVE_INTERVAL_ACTIVE_MILLIS)
		{
			saveStateQuietly();
			activeMillisAtLastSave = state.getTotalActiveMillis();
		}
	}

	CompletableFuture<Map<String, Object>> beforeAction(GenericClientActivityContext activityContext)
	{
		synchronized (this)
		{
			ensureOpen();
			if (!activityContext.allowsBreaks() || !activityContext.isInputAllowed())
			{
				return completed("bypassed", "action", activityContext);
			}
			if (profile == null || state == null)
			{
				return failed(new IllegalStateException("Behavior profile is unavailable until account login"));
			}
			if (activeBreak != null)
			{
				return activeBreak.thenApply(ignored ->
					receipt("resumed", "existing_break", activityContext));
			}
			return completed("ready", "action", activityContext);
		}
	}

	CompletableFuture<Map<String, Object>> afterAction(GenericClientActivityContext activityContext)
	{
		synchronized (this)
		{
			ensureOpen();
			if (!activityContext.allowsBreaks() || !activityContext.isInputAllowed())
			{
				return completed("bypassed", "action_complete", activityContext);
			}
			if (profile == null || state == null)
			{
				return failed(new IllegalStateException("Behavior profile is unavailable until account login"));
			}
			if (activeBreak != null)
			{
				return activeBreak;
			}
			if (state.consumeMicroSuppression())
			{
				saveStateQuietly();
				return completed("suppressed_after_long_break", "action_complete", activityContext);
			}
			recordBoundary();
			if (longMayStart(false, 0.0))
			{
				return startLongBreak("action_complete_due", 0.0);
			}
			if (state.getMicroPressure() >= state.getMicroBudget())
			{
				return startMicroBreak(
					"action_complete", false, activityContext);
			}
			return completed("no_break", "action_complete", activityContext);
		}
	}

	private CompletableFuture<Map<String, Object>> microCursorRelease(
		GenericClientActivityContext activityContext)
	{
		if (!activityContext.allowsCursorRelease() || !activityContext.isInputAllowed())
		{
			return completed("bypassed", "cursor_release", activityContext);
		}
		double probability = profile.getCursorReleaseProbability();
		double roll = random.nextDouble();
		if (roll >= probability)
		{
			Map<String, Object> skipped = receipt("not_selected", "cursor_release", activityContext);
			skipped.put("probability", probability);
			skipped.put("roll", roll);
			return CompletableFuture.completedFuture(skipped);
		}
		GenericClientBehaviorProfile.Edge edge = profile.getIdleEdge();
		reporter.accept("BEHAVIOR_CURSOR_RELEASE_STARTED activity=" +
			activityContext.getActivity().getValue() + " edge=" + edge.name().toLowerCase(Locale.ROOT));
		GenericClientBehaviorState owner = state;
		return effects.moveOffscreen(edge, activityContext).handle((value, error) ->
		{
			Map<String, Object> result = receipt(
				error == null ? "moved" : "failed",
				"cursor_release",
				activityContext);
			result.put("probability", probability);
			result.put("roll", roll);
			result.put("edge", edge.name().toLowerCase(Locale.ROOT));
			if (error == null)
			{
				synchronized (GenericClientBehaviorController.this)
				{
					if (state == owner && activityContext.isInputAllowed())
					{
						state.recordCursorRelease();
						saveStateQuietly();
					}
				}
				reporter.accept("BEHAVIOR_CURSOR_RELEASE_COMPLETED activity=" +
					activityContext.getActivity().getValue() + " edge=" +
					edge.name().toLowerCase(Locale.ROOT));
				result.put("result", value);
			}
			else
			{
				String message = error.getMessage() == null
					? error.getClass().getSimpleName()
					: error.getMessage();
				reporter.accept("BEHAVIOR_CURSOR_RELEASE_FAILED message=" + message);
				result.put("message", message);
			}
			return result;
		});
	}

	CompletableFuture<Map<String, Object>> enterPhase(
		String phase,
		GenericClientActivityContext activityContext)
	{
		synchronized (this)
		{
			ensureOpen();
			if (!activityContext.allowsBreaks() || !activityContext.isInputAllowed())
			{
				return completed("bypassed", "phase:" + phase, activityContext);
			}
			if (profile == null || state == null)
			{
				return failed(new IllegalStateException("Behavior profile is unavailable until account login"));
			}
			if (activeBreak != null)
			{
				return activeBreak.thenApply(ignored ->
					receipt("resumed", "phase:" + phase, activityContext));
			}

			recordBoundary();
			long activeMillis = state.getTotalActiveMillis();
			Long lastNamed = state.getLastPhaseActiveMillis(phase);
			boolean globalReady = state.getLastGlobalPhaseActiveMillis() == Long.MIN_VALUE ||
				activeMillis - state.getLastGlobalPhaseActiveMillis() >= GLOBAL_PHASE_COOLDOWN_MILLIS;
			boolean namedReady = lastNamed == null || activeMillis - lastNamed >= NAMED_PHASE_COOLDOWN_MILLIS;
			if (!globalReady || !namedReady)
			{
				if (longMayStart(true, 0.0))
				{
					return startLongBreak("phase_due:" + phase, 0.0);
				}
				return completed("phase_cooldown", phase, activityContext);
			}

			state.recordPhase(phase);
			double maturity = Math.min(1.0,
				state.getActiveMillisSinceLongBreak() /
					(profile.getLongCadenceMinutes() * TimeUnit.MINUTES.toMillis(1)));
			double longBonus = profile.getPhaseLongBonusMaximum() * maturity * maturity;
			if (longMayStart(true, longBonus))
			{
				return startLongBreak("phase:" + phase, longBonus);
			}
			if (state.consumeMicroSuppression())
			{
				saveStateQuietly();
				return completed("suppressed_after_long_break", "phase:" + phase, activityContext);
			}
			if (inputOwned) state.addMicroPressure(profile.phaseMicroPressure());
			if (state.getMicroPressure() >= state.getMicroBudget())
			{
				return startMicroBreak("phase:" + phase, true, activityContext);
			}
			saveStateQuietly();
			return completed("no_break", "phase:" + phase, activityContext);
		}
	}

	synchronized Map<String, Object> status()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.putAll(policies.status());
		value.put("available", profile != null && state != null);
		if (profile == null || state == null)
		{
			value.put("state", "waiting_for_account");
			return value;
		}
		value.put("profile", profile.toMap());
		value.put("generated_profile", generatedProfile.toMap());
		value.put("state", state.getBreakType().equals("none")
			? state.isLongBreakDeferred() ? "long_break_deferred" : "ready"
			: state.getBreakType() + "_break");
		value.put("long_break_mode", state.getLongBreakMode());
		value.put("break_remaining_millis", Math.max(0L, state.getBreakEndEpochMillis() - clock.epochMillis()));
		value.put("active_millis_since_long_break", state.getActiveMillisSinceLongBreak());
		double hazard = profile.cumulativeLongHazard(
			state.getActiveMillisSinceLongBreak() / (double) TimeUnit.MINUTES.toMillis(1));
		value.put("long_hazard", hazard);
		value.put("long_hazard_budget", state.getLongHazardBudget());
		value.put("long_due", hazard >= state.getLongHazardBudget());
		value.put("input_owned", inputOwned);
		value.put("session_active_millis", state.getTotalActiveMillis() - sessionStartedActiveMillis);
		value.put("long_break_deferred", state.isLongBreakDeferred());
		double deferredMinutes = state.isLongBreakDeferred()
			? Math.max(0.0, (state.getLongDeferredUntilActiveMillis() - state.getTotalActiveMillis()) / 60_000.0) : 0.0;
		value.put("long_break_due_in_active_minutes", Math.max(deferredMinutes,
			Math.max(0.0,
			profile.activeMinutesAtLongHazard(state.getLongHazardBudget()) -
				state.getActiveMillisSinceLongBreak() / 60_000.0)));
		value.put("micro_pressure", state.getMicroPressure());
		value.put("micro_budget", state.getMicroBudget());
		value.put("total_active_millis", state.getTotalActiveMillis());
		value.put("micro_break_count", state.getMicroBreakCount());
		value.put("long_break_count", state.getLongBreakCount());
		value.put("cursor_release_count", state.getCursorReleaseCount());
		return value;
	}

	CompletableFuture<Map<String, Object>> endLongBreak()
	{
		return endBreak("long", "manual");
	}

	CompletableFuture<Map<String, Object>> endActiveBreak()
	{
		return endBreak(null, "interrupted");
	}

	private CompletableFuture<Map<String, Object>> endBreak(String requiredType, String reason)
	{
		final CompletableFuture<Map<String, Object>> completion;
		final String type;
		synchronized (this)
		{
			ensureOpen();
			if (state == null || activeBreak == null ||
				(requiredType != null && !requiredType.equals(state.getBreakType())))
			{
				return completed("not_active", requiredType == null ? "break" : requiredType);
			}
			type = state.getBreakType();
			if (breakTimer != null)
			{
				breakTimer.cancel();
				breakTimer = null;
			}
			completion = activeBreak;
			state.interruptBreak(reason);
			saveStateQuietly();
			reporter.accept("BEHAVIOR_BREAK_END_REQUESTED type=" + type + " reason=" + reason);
		}

		finishActiveBreak();
		return completion.thenApply(outcome ->
		{
			Map<String, Object> result = new LinkedHashMap<>(outcome);
			result.put("status", "ended");
			return result;
		});
	}

	synchronized boolean isPaused()
	{
		return activeBreak != null;
	}

	private void recordBoundary()
	{
		long now = state.getTotalActiveMillis();
		if (lastBoundaryActiveMillis >= 0L && now > lastBoundaryActiveMillis)
		{
			if (boundaryGaps.size() == 21) boundaryGaps.removeFirst();
			boundaryGaps.addLast(now - lastBoundaryActiveMillis);
		}
		lastBoundaryActiveMillis = now;
	}

	private boolean longMayStart(boolean phase, double bonus)
	{
		if (!inputOwned || (!phase && state.getTotalActiveMillis() - sessionStartedActiveMillis <
			profile.getSessionGraceMinutes() * 60_000.0)) return false;
		if (state.isLongBreakDeferred())
		{
			return phase && state.getTotalActiveMillis() >= state.getLongDeferredUntilActiveMillis();
		}
		double gapMinutes = 0.0;
		if (!boundaryGaps.isEmpty())
		{
			java.util.List<Long> gaps = new java.util.ArrayList<>(boundaryGaps);
			java.util.Collections.sort(gaps);
			gapMinutes = gaps.get(gaps.size() / 2) / 60_000.0;
		}
		double activeMinutes = state.getActiveMillisSinceLongBreak() /
			(double) TimeUnit.MINUTES.toMillis(1);
		return profile.cumulativeLongHazard(activeMinutes + gapMinutes) + Math.max(0.0, bonus) >=
			state.getLongHazardBudget();
	}

	private CompletableFuture<Map<String, Object>> startMicroBreak(
		String reason,
		boolean phase,
		GenericClientActivityContext activityContext)
	{
		double pressure = state.getMicroPressure();
		state.resetMicroPressure(GenericClientBehaviorProfile.sampleExponentialBudget(random));
		double seconds = profile.sampleMicroSeconds(random, phase);
		CompletableFuture<Map<String, Object>> pause = startBreak(
			"micro", "none", secondsToMillis(seconds), reason, pressure);
		if (!activityContext.allowsCursorRelease())
		{
			return pause;
		}

		CompletableFuture<Map<String, Object>> cursor = microCursorRelease(activityContext);
		activeBreakEffects = CompletableFuture.allOf(
			activeBreakEffects,
			cursor.thenApply(ignored -> null));
		return pause.thenCombine(cursor, (breakReceipt, cursorReceipt) ->
		{
			Map<String, Object> result = new LinkedHashMap<>(breakReceipt);
			result.put("cursor_release", cursorReceipt);
			return result;
		});
	}

	private CompletableFuture<Map<String, Object>> startLongBreak(String reason, double bonus)
	{
		double minutes = profile.sampleLongMinutes(random);
		GenericClientBehaviorProfile.LongBreakMode mode = profile.sampleLongBreakMode(random);
		return startBreak(
			"long",
			mode.name().toLowerCase(Locale.ROOT),
			minutesToMillis(minutes),
			reason,
			bonus);
	}

	private CompletableFuture<Map<String, Object>> startBreak(
		String type,
		String mode,
		long durationMillis,
		String reason,
		double triggerValue)
	{
		long endEpochMillis = Math.addExact(clock.epochMillis(), durationMillis);
		state.startBreak(type, mode, clock.epochMillis(), endEpochMillis);
		saveStateQuietly();
		Map<String, Object> started = receipt("started", type);
		started.put("reason", reason);
		started.put("duration_millis", durationMillis);
		started.put("long_break_mode", mode);
		started.put("micro".equals(type) ? "micro_pressure" : "phase_long_bonus", triggerValue);
		activeBreak = new CompletableFuture<>();
		reporter.accept("BEHAVIOR_BREAK_STARTED type=" + type + " mode=" + mode +
			" durationMillis=" + durationMillis + " reason=" + reason);

		activeBreakEffects = applyBreakEffects(type, mode);
		breakTimer = timer.schedule(this::finishActiveBreak, durationMillis);
		return activeBreak.thenApply(completed ->
		{
			Map<String, Object> result = new LinkedHashMap<>(started);
			result.putAll(completed);
			return result;
		});
	}

	private void finishActiveBreak()
	{
		final String type;
		final CompletableFuture<Map<String, Object>> completion;
		final CompletableFuture<Void> effectsCompletion;
		synchronized (this)
		{
			if (activeBreak == null || state == null)
			{
				return;
			}
			type = state.getBreakType();
			completion = activeBreak;
			effectsCompletion = activeBreakEffects;
			breakTimer = null;
		}

		CompletableFuture<String> ready = effectsCompletion.thenCompose(ignored -> "long".equals(type)
			? effects.ensureLoggedIn()
			: CompletableFuture.completedFuture("not_required"));
		ready.whenComplete((ignored, error) ->
		{
			synchronized (GenericClientBehaviorController.this)
			{
				if (activeBreak != completion || state == null)
				{
					return;
				}
				if (error != null)
				{
					reporter.accept("BEHAVIOR_RELOGIN_FAILED message=" + error.getMessage());
					breakTimer = timer.schedule(GenericClientBehaviorController.this::finishActiveBreak, 5_000L);
					return;
				}
				long elapsedMillis = Math.max(0L, clock.epochMillis() - state.getBreakStartedEpochMillis());
				String endReason = state.getBreakEndReason();
				boolean deferred = "long".equals(type) && !"completed".equals(endReason);
				if (deferred)
				{
					state.deferLongBreak(minutesToMillis(profile.getLongRefractoryMinutes()));
					if (elapsedMillis >= secondsToMillis(profile.getShortBodyMedianSeconds())) suppressMicroAfterLongBreak();
				}
				else if ("long".equals(type))
				{
					state.resetLongClock(GenericClientBehaviorProfile.sampleExponentialBudget(random));
					suppressMicroAfterLongBreak();
				}
				state.clearBreak();
				activeBreak = null;
				lastTickNanos = clock.nanoTime();
				activeBreakEffects = CompletableFuture.completedFuture(null);
				saveStateQuietly();
				reporter.accept("BEHAVIOR_BREAK_" + (deferred ? "DEFERRED" : "COMPLETED") +
					" type=" + type + " reason=" + endReason + " elapsedMillis=" + elapsedMillis);
				Map<String, Object> outcome = receipt(deferred ? "deferred" : "completed", type);
				outcome.put("end_reason", endReason);
				outcome.put("elapsed_millis", elapsedMillis);
				outcome.put("long_break_deferred", deferred);
				completion.complete(outcome);
			}
		});
	}

	private void suppressMicroAfterLongBreak()
	{
		state.resetMicroPressure(GenericClientBehaviorProfile.sampleExponentialBudget(random));
		state.suppressNextMicro();
	}

	private void restorePersistedBreak()
	{
		if ("none".equals(state.getBreakType()))
		{
			return;
		}
		long remaining = state.getBreakEndEpochMillis() - clock.epochMillis();
		if (!"completed".equals(state.getBreakEndReason()))
		{
			activeBreak = new CompletableFuture<>();
			activeBreakEffects = CompletableFuture.completedFuture(null);
			breakTimer = timer.schedule(this::finishActiveBreak, 0L);
			reporter.accept("BEHAVIOR_BREAK_RESTORED type=" + state.getBreakType() + " interrupted=true");
			return;
		}
		if (remaining <= 0L)
		{
			if ("long".equals(state.getBreakType()))
			{
				state.resetLongClock(GenericClientBehaviorProfile.sampleExponentialBudget(random));
				suppressMicroAfterLongBreak();
			}
			state.clearBreak();
			saveStateQuietly();
			return;
		}
		activeBreak = new CompletableFuture<>();
		breakTimer = timer.schedule(this::finishActiveBreak, remaining);
		activeBreakEffects = applyBreakEffects(state.getBreakType(), state.getLongBreakMode());
		reporter.accept("BEHAVIOR_BREAK_RESTORED type=" + state.getBreakType() +
			" remainingMillis=" + remaining);
	}

	private CompletableFuture<Void> applyBreakEffects(String type, String mode)
	{
		return ("long".equals(type) && "logout".equals(mode)
			? effects.logout()
			: CompletableFuture.completedFuture("not_required"))
			.handle((ignored, error) ->
			{
				if (error != null)
				{
					reporter.accept("BEHAVIOR_LOGOUT_FAILED message=" + error.getMessage());
				}
				return null;
			});
	}

	private void saveStateQuietly()
	{
		try
		{
			store.save(state, clock.epochMillis());
			activeMillisAtLastSave = state.getTotalActiveMillis();
		}
		catch (IOException exception)
		{
			reporter.accept("BEHAVIOR_STATE_SAVE_FAILED message=" + exception.getMessage());
		}
	}

	private void cancelCurrentBreak(String reason)
	{
		if (breakTimer != null)
		{
			breakTimer.cancel();
			breakTimer = null;
		}
		if (activeBreak != null)
		{
			activeBreak.completeExceptionally(new IllegalStateException("Behavior break cancelled: " + reason));
			activeBreak = null;
		}
		activeBreakEffects = CompletableFuture.completedFuture(null);
	}

	private void ensureOpen()
	{
		if (closed)
		{
			throw new IllegalStateException("Behavior controller is closed");
		}
	}

	private void ensureProfile()
	{
		ensureOpen();
		if (profile == null || generatedProfile == null || state == null)
		{
			throw new IllegalStateException("Behavior profile is unavailable until account login");
		}
	}

	private static CompletableFuture<Map<String, Object>> completed(String status, String kind)
	{
		return CompletableFuture.completedFuture(receipt(status, kind));
	}

	private static CompletableFuture<Map<String, Object>> completed(
		String status,
		String kind,
		GenericClientActivityContext activityContext)
	{
		return CompletableFuture.completedFuture(receipt(status, kind, activityContext));
	}

	private static <T> CompletableFuture<T> failed(Throwable error)
	{
		CompletableFuture<T> result = new CompletableFuture<>();
		result.completeExceptionally(error);
		return result;
	}

	private static Map<String, Object> receipt(String status, String kind)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("status", status);
		value.put("kind", kind);
		return value;
	}

	private static Map<String, Object> receipt(
		String status,
		String kind,
		GenericClientActivityContext activityContext)
	{
		Map<String, Object> value = receipt(status, kind);
		GenericClientPolicyResolver.Resolution resolved = activityContext.resolve();
		value.put("activity", resolved.activity.getValue());
		value.put("policy", resolved.policy.toMap());
		value.put("policy_reasons", resolved.reasons);
		return value;
	}

	private static long secondsToMillis(double seconds)
	{
		return Math.max(1_000L, Math.min(119_999L, Math.round(seconds * 1_000.0)));
	}

	private static long minutesToMillis(double minutes)
	{
		return Math.max(TimeUnit.MINUTES.toMillis(3),
			Math.min(TimeUnit.MINUTES.toMillis(60), Math.round(minutes * TimeUnit.MINUTES.toMillis(1))));
	}

	@Override
	public synchronized void close()
	{
		if (closed)
		{
			return;
		}
		closed = true;
		if (state != null)
		{
			saveStateQuietly();
		}
		cancelCurrentBreak("controller_closed");
	}

	interface BreakEffects
	{
		CompletableFuture<String> moveOffscreen(GenericClientBehaviorProfile.Edge edge, GenericClientActivityContext context);

		CompletableFuture<String> logout();

		CompletableFuture<String> ensureLoggedIn();
	}

	interface Timer
	{
		Cancellable schedule(Runnable task, long delayMillis);
	}

	interface Cancellable
	{
		void cancel();
	}

	interface Clock
	{
		long epochMillis();

		long nanoTime();
	}


}
