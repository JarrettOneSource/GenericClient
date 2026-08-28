package com.genericclient;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class GenericClientBehaviorController implements AutoCloseable
{
	private static final long SAVE_INTERVAL_ACTIVE_MILLIS = 30_000L;
	private static final long MAX_ACTIVE_TICK_MILLIS = 5_000L;
	private static final long GLOBAL_PHASE_COOLDOWN_MILLIS = TimeUnit.MINUTES.toMillis(2);
	private static final long NAMED_PHASE_COOLDOWN_MILLIS = TimeUnit.MINUTES.toMillis(5);
	private static final double SHORT_TAIL_MIN_SECONDS = 12.0;

	private final GenericClientBehaviorStore store;
	private final BreakEffects effects;
	private final Timer timer;
	private final Clock clock;
	private final RandomSource random;
	private final Consumer<String> reporter;

	private GenericClientBehaviorProfile profile;
	private GenericClientBehaviorProfile generatedProfile;
	private GenericClientBehaviorState state;
	private CompletableFuture<Map<String, Object>> activeBreak;
	private CompletableFuture<Void> activeBreakEffects = CompletableFuture.completedFuture(null);
	private Cancellable breakTimer;
	private long lastTickNanos;
	private long activeMillisAtLastSave;
	private boolean loggedIn;
	private boolean closed;

	GenericClientBehaviorController(
		GenericClientBehaviorStore store,
		BreakEffects effects,
		Timer timer,
		Clock clock,
		RandomSource random,
		Consumer<String> reporter)
	{
		this.store = store;
		this.effects = effects;
		this.timer = timer;
		this.clock = clock;
		this.random = random;
		this.reporter = reporter;
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

	static RandomSource secureRandom()
	{
		SecureRandom source = new SecureRandom();
		return new RandomSource()
		{
			@Override
			public double nextDouble()
			{
				return source.nextDouble();
			}

			@Override
			public double nextGaussian()
			{
				return source.nextGaussian();
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
		generatedProfile = nextProfile;
		GenericClientBehaviorOverrides overrides = store.loadOverrides(nextProfile.getId());
		profile = overrides == null ? nextProfile : nextProfile.withOverrides(overrides);
		state = store.load(profile.getId());
		if (state == null)
		{
			state = new GenericClientBehaviorState(profile.getId(), nextExponentialBudget());
			saveState();
		}
		activeMillisAtLastSave = state.getTotalActiveMillis();
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

	CompletableFuture<String> moveMouseOffscreen()
	{
		final GenericClientBehaviorProfile.Edge edge;
		synchronized (this)
		{
			ensureProfile();
			edge = profile.getIdleEdge();
		}
		return effects.moveOffscreen(edge);
	}

	synchronized void setLoggedIn(boolean loggedIn)
	{
		this.loggedIn = loggedIn;
		lastTickNanos = clock.nanoTime();
	}

	synchronized void publishActiveTick()
	{
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
		if (profile == null || state == null || !loggedIn || activeBreak != null)
		{
			return;
		}
		state.addActiveMillis(Math.min(elapsedMillis, MAX_ACTIVE_TICK_MILLIS));
		if (state.getTotalActiveMillis() - activeMillisAtLastSave >= SAVE_INTERVAL_ACTIVE_MILLIS)
		{
			saveStateQuietly();
			activeMillisAtLastSave = state.getTotalActiveMillis();
		}
	}

	CompletableFuture<Map<String, Object>> beforeAction(boolean breaksEnabled)
	{
		synchronized (this)
		{
			ensureOpen();
			if (!breaksEnabled)
			{
				return completed("bypassed", "action");
			}
			if (profile == null || state == null)
			{
				return failed(new IllegalStateException("Behavior profile is unavailable until account login"));
			}
			if (activeBreak != null)
			{
				return activeBreak.thenApply(ignored -> receipt("resumed", "existing_break"));
			}
			if (longDue(0.0))
			{
				return startLongBreak("action", 0.0);
			}
			return completed("ready", "action");
		}
	}

	CompletableFuture<Map<String, Object>> afterAction(boolean breaksEnabled)
	{
		synchronized (this)
		{
			ensureOpen();
			if (!breaksEnabled)
			{
				return completed("bypassed", "action_complete");
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
				return completed("suppressed_after_long_break", "action_complete");
			}
			if (longDue(0.0))
			{
				return startLongBreak("action_complete_due", 0.0);
			}
			if (random.nextDouble() < profile.getShortReleaseProbability())
			{
				return startMicroBreak("action_complete", profile.getShortReleaseProbability(), false);
			}
			return completed("no_break", "action_complete");
		}
	}

	CompletableFuture<Map<String, Object>> enterPhase(String phase, boolean breaksEnabled)
	{
		synchronized (this)
		{
			ensureOpen();
			if (!breaksEnabled)
			{
				return completed("bypassed", "phase:" + phase);
			}
			if (profile == null || state == null)
			{
				return failed(new IllegalStateException("Behavior profile is unavailable until account login"));
			}
			if (activeBreak != null)
			{
				return activeBreak.thenApply(ignored -> receipt("resumed", "phase:" + phase));
			}

			long activeMillis = state.getTotalActiveMillis();
			Long lastNamed = state.getLastPhaseActiveMillis(phase);
			boolean globalReady = state.getLastGlobalPhaseActiveMillis() == Long.MIN_VALUE ||
				activeMillis - state.getLastGlobalPhaseActiveMillis() >= GLOBAL_PHASE_COOLDOWN_MILLIS;
			boolean namedReady = lastNamed == null || activeMillis - lastNamed >= NAMED_PHASE_COOLDOWN_MILLIS;
			if (!globalReady || !namedReady)
			{
				if (longDue(0.0))
				{
					return startLongBreak("phase_due:" + phase, 0.0);
				}
				return completed("phase_cooldown", phase);
			}

			state.recordPhase(phase);
			double maturity = Math.min(1.0,
				state.getActiveMillisSinceLongBreak() /
					(profile.getLongCadenceMinutes() * TimeUnit.MINUTES.toMillis(1)));
			double longBonus = profile.getPhaseLongBonusMaximum() * maturity * maturity;
			if (longDue(longBonus))
			{
				return startLongBreak("phase:" + phase, longBonus);
			}
			double shortChance = 1.0 - Math.pow(
				1.0 - profile.getShortReleaseProbability(),
				profile.getPhaseShortChances());
			if (random.nextDouble() < shortChance)
			{
				return startMicroBreak("phase:" + phase, shortChance, true);
			}
			saveStateQuietly();
			return completed("no_break", "phase:" + phase);
		}
	}

	synchronized Map<String, Object> status()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("available", profile != null && state != null);
		if (profile == null || state == null)
		{
			value.put("state", "waiting_for_account");
			return value;
		}
		value.put("profile", profile.toMap());
		value.put("generated_profile", generatedProfile.toMap());
		value.put("state", state.getBreakType().equals("none") ? "ready" : state.getBreakType() + "_break");
		value.put("long_break_mode", state.getLongBreakMode());
		value.put("break_remaining_millis", Math.max(0L, state.getBreakEndEpochMillis() - clock.epochMillis()));
		value.put("active_millis_since_long_break", state.getActiveMillisSinceLongBreak());
		double hazard = profile.cumulativeLongHazard(
			state.getActiveMillisSinceLongBreak() / (double) TimeUnit.MINUTES.toMillis(1));
		value.put("long_hazard", hazard);
		value.put("long_hazard_budget", state.getLongHazardBudget());
		value.put("long_due", hazard >= state.getLongHazardBudget());
		value.put("micro_break_count", state.getMicroBreakCount());
		value.put("long_break_count", state.getLongBreakCount());
		return value;
	}

	CompletableFuture<Map<String, Object>> endLongBreak()
	{
		return endBreak("long");
	}

	CompletableFuture<Map<String, Object>> endActiveBreak()
	{
		return endBreak(null);
	}

	private CompletableFuture<Map<String, Object>> endBreak(String requiredType)
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
			reporter.accept("BEHAVIOR_BREAK_END_REQUESTED type=" + type + " reason=manual");
		}

		finishActiveBreak();
		return completion.thenApply(ignored -> receipt("ended", type));
	}

	synchronized boolean isPaused()
	{
		return activeBreak != null;
	}

	private boolean longDue(double bonus)
	{
		double activeMinutes = state.getActiveMillisSinceLongBreak() /
			(double) TimeUnit.MINUTES.toMillis(1);
		return profile.cumulativeLongHazard(activeMinutes) + Math.max(0.0, bonus) >=
			state.getLongHazardBudget();
	}

	private CompletableFuture<Map<String, Object>> startMicroBreak(
		String reason,
		double chance,
		boolean phase)
	{
		double seconds = sampleMicroSeconds(phase);
		return startBreak("micro", "none", secondsToMillis(seconds), reason, chance);
	}

	private CompletableFuture<Map<String, Object>> startLongBreak(String reason, double bonus)
	{
		double minutes = sampleLongMinutes();
		GenericClientBehaviorProfile.LongBreakMode mode = profile.getFavoredLongBreakMode();
		if (random.nextDouble() < profile.getOppositeLongBreakProbability())
		{
			mode = mode == GenericClientBehaviorProfile.LongBreakMode.AFK
				? GenericClientBehaviorProfile.LongBreakMode.LOGOUT
				: GenericClientBehaviorProfile.LongBreakMode.AFK;
		}
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
		double rollValue)
	{
		long endEpochMillis = Math.addExact(clock.epochMillis(), durationMillis);
		state.startBreak(type, mode, endEpochMillis);
		saveStateQuietly();
		Map<String, Object> started = receipt("started", type);
		started.put("reason", reason);
		started.put("duration_millis", durationMillis);
		started.put("long_break_mode", mode);
		started.put("roll_value", rollValue);
		activeBreak = new CompletableFuture<>();
		reporter.accept("BEHAVIOR_BREAK_STARTED type=" + type + " mode=" + mode +
			" durationMillis=" + durationMillis + " reason=" + reason);

		activeBreakEffects = applyBreakEffects(type, mode);
		breakTimer = timer.schedule(this::finishActiveBreak, durationMillis);
		return activeBreak.thenApply(completed ->
		{
			Map<String, Object> result = new LinkedHashMap<>(started);
			result.put("status", "completed");
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
				if ("long".equals(type))
				{
					state.resetLongClock(nextExponentialBudget());
					state.suppressNextMicro();
				}
				state.clearBreak();
				activeBreak = null;
				activeBreakEffects = CompletableFuture.completedFuture(null);
				saveStateQuietly();
				reporter.accept("BEHAVIOR_BREAK_COMPLETED type=" + type);
				completion.complete(receipt("completed", type));
			}
		});
	}

	private void restorePersistedBreak()
	{
		if ("none".equals(state.getBreakType()))
		{
			return;
		}
		long remaining = state.getBreakEndEpochMillis() - clock.epochMillis();
		if (remaining <= 0L)
		{
			if ("long".equals(state.getBreakType()))
			{
				state.resetLongClock(nextExponentialBudget());
				state.suppressNextMicro();
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
		return effects.moveOffscreen(profile.getIdleEdge())
			.handle((ignored, error) ->
			{
				if (error != null)
				{
					reporter.accept("BEHAVIOR_OFFSCREEN_FAILED message=" + error.getMessage());
				}
				return null;
			})
			.thenCompose(ignored -> "long".equals(type) && "logout".equals(mode)
				? effects.logout()
				: CompletableFuture.completedFuture("not_required"))
			.thenCompose(ignored -> "long".equals(type) && "logout".equals(mode)
				? effects.moveOffscreen(profile.getIdleEdge())
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

	private double sampleMicroSeconds(boolean phase)
	{
		double tailChance = Math.min(0.30,
			phase ? profile.getShortTailProbability() * 2.0 : profile.getShortTailProbability());
		if (random.nextDouble() < tailChance)
		{
			return Math.min(Math.nextDown(GenericClientBehaviorProfile.SHORT_DURATION_MAX_SECONDS),
				SHORT_TAIL_MIN_SECONDS * Math.pow(10.0, random.nextDouble()));
		}
		double body = 1.0 + (profile.getShortBodyMedianSeconds() - 1.0) *
			Math.exp(0.5 * random.nextGaussian());
		return clamp(body,
			GenericClientBehaviorProfile.SHORT_DURATION_MIN_SECONDS,
			Math.nextDown(GenericClientBehaviorProfile.SHORT_DURATION_MAX_SECONDS));
	}

	private double sampleLongMinutes()
	{
		double duration = 3.0 + (profile.getLongMedianMinutes() - 3.0) *
			Math.exp(0.5 * random.nextGaussian());
		return clamp(duration,
			GenericClientBehaviorProfile.LONG_DURATION_MIN_MINUTES,
			GenericClientBehaviorProfile.LONG_DURATION_MAX_MINUTES);
	}

	private double nextExponentialBudget()
	{
		double unit = clamp(random.nextDouble(), 0.000000000001, Math.nextDown(1.0));
		return -Math.log(1.0 - unit);
	}

	private void saveState()
	{
		try
		{
			store.save(state, clock.epochMillis());
			activeMillisAtLastSave = state.getTotalActiveMillis();
		}
		catch (IOException exception)
		{
			throw new IllegalStateException("Unable to save behavior state", exception);
		}
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

	private static long secondsToMillis(double seconds)
	{
		return Math.max(1_000L, Math.min(119_999L, Math.round(seconds * 1_000.0)));
	}

	private static long minutesToMillis(double minutes)
	{
		return Math.max(TimeUnit.MINUTES.toMillis(3),
			Math.min(TimeUnit.MINUTES.toMillis(60), Math.round(minutes * TimeUnit.MINUTES.toMillis(1))));
	}

	private static double clamp(double value, double minimum, double maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
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
		CompletableFuture<String> moveOffscreen(GenericClientBehaviorProfile.Edge edge);

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

	interface RandomSource
	{
		double nextDouble();

		double nextGaussian();
	}
}
