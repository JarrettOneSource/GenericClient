package com.genericclient;

import com.genericclient.script.ScriptEnvironment;
import com.genericclient.script.ScriptScope;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** One worker and its revocable authority to read state and request input. */
final class GenericClientScriptRun implements ScriptEnvironment
{
	final long id;
	final String owner;
	final GenericClientScriptRegistry.Script definition;
	final Map<String, Object> values;
	final CompletableFuture<Object> completion = new CompletableFuture<>();
	boolean endNotified;
	private final GenericClientScriptHost host;
	private GenericClientScriptRegistry.LoadedScript loaded;
	private final GenericClientActionBoundary.Ticket lifetime = new GenericClientActionBoundary.Ticket();
	private final Thread worker;
	private final long startedNanos;
	private final ArrayDeque<String> buttons = new ArrayDeque<>();
	private volatile boolean stopped;
	private volatile boolean cancellationRequested;
	private final Object paintLock = new Object();
	private volatile boolean initialized;
	private boolean paintFailed;
	private volatile boolean emergencyPaused;
	private volatile boolean manualPaused;
	private volatile String status = "RUNNING";
	private String terminalStatus = "COMPLETED";
	private volatile long pauseRevision;
	private long pauseStartedNanos;
	private long pausedNanos;
	private volatile String phase = "starting";
	private volatile GenericClientActivityContext declaredActivity;
	private volatile Long finishedNanos;
	private volatile Object value;
	private volatile String error;
	private volatile List<GenericClientOverlayRow> overlay = Collections.emptyList();
	private volatile List<GenericClientSceneMarker> markers = Collections.emptyList();
	private GenericClientActionBoundary.Ticket pending;
	private String pendingType;
	private GenericClientActivityContext pendingContext;
	private volatile Rest rest;
	private boolean pauseCallback;
	private volatile ScheduledStop scheduledStop;
	private boolean checkingScheduledStop;
	private volatile Intent currentIntent;
	private volatile Map<String, Object> lastIntent = Map.of();

	GenericClientScriptRun(GenericClientScriptHost host, long id, String owner,
		GenericClientScriptRegistry.Script definition, Map<String, Object> values)
	{
		this.host = host;
		startedNanos = host.nanoClock.getAsLong();
		this.id = id;
		this.owner = owner;
		this.definition = definition;
		this.values = values;
		worker = new Thread(this::run, "GenericClient-Script-" + id);
		worker.setDaemon(true);
	}

	void start() { worker.start(); }

	private void run()
	{
		ScriptScope scope = new ScriptScope(this);
		try (scope)
		{
			try
			{
				checkpoint();
				loaded = definition.load();
				initialize();
				loaded.instantiate();
				checkpoint();
				if (values.isEmpty()) loaded.script.onStart();
				else loaded.script.onStart(values.entrySet().stream()
					.map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
				initialized = true;
				loaded.script.run();
				terminalStatus = cancellationRequested ? "STOPPED" : "COMPLETED";
			}
			catch (Throwable failure)
			{
				terminalStatus = cancellationRequested ? "STOPPED" : "FAULTED";
				if (!cancellationRequested) error = failure.toString();
			}
			finally
			{
				host.releaseInput(this, "script_finished");
				synchronized (paintLock)
				{
					initialized = false;
					if (loaded != null && loaded.script != null)
					{
						try { loaded.script.onExit(); }
						catch (Throwable failure) { recordCleanupFailure(failure); }
					}
				}
				try { if (loaded != null) loaded.close(); }
				catch (IOException failure) { recordCleanupFailure(failure); }
			}
		}
		finally
		{
			finishedNanos = host.nanoClock.getAsLong();
			host.finished(this);
			completeScheduledStop();
			if (error == null) completion.complete(value);
			else completion.completeExceptionally(new IllegalStateException(error));
		}
	}

	private void initialize()
	{
		while (true)
		{
			checkpoint();
			long revision = pauseRevision;
			try { host.initialize(this,GenericClientActivityContext.none().withTicket(lifetime)); }
			catch (RuntimeException failure)
			{
				if (revision == pauseRevision || cancellationRequested) throw failure;
			}
			checkpoint();
			if (revision == pauseRevision) return;
		}
	}

	private void recordCleanupFailure(Throwable failure)
	{
		log("Script cleanup failed: " + failure);
		if (!cancellationRequested && error == null) { terminalStatus = "FAULTED"; error = failure.toString(); }
	}

	void paint(java.awt.Graphics2D graphics)
	{
		if (!initialized || stopped) return;
		synchronized (paintLock)
		{
			if (!initialized || stopped || paintFailed) return;
			ScriptScope scope = new ScriptScope(this);
			try (scope) { loaded.script.onPaint(graphics); }
			catch (Throwable failure)
			{
				paintFailed = true;
				log("Script paint failed: " + failure);
			}
		}
	}

	private void requireWorker()
	{
		if (Thread.currentThread() != worker) throw new IllegalStateException("Input and waits require the script worker");
	}

	synchronized boolean revoke()
	{
		stopped = true;
		lifetime.cancel();
		return pending != null;
	}

	void requestStop()
	{
		cancellationRequested = true;
		revoke();
		status = "STOPPED";
		worker.interrupt();
		completeScheduledStop();
	}

	synchronized void pause(boolean manual, boolean paused)
	{
		boolean previouslyPaused = isPaused();
		if (manual) manualPaused = paused;
		else emergencyPaused = paused;
		if (!previouslyPaused && isPaused()) pauseStartedNanos = host.nanoClock.getAsLong();
		if (previouslyPaused && !isPaused()) pausedNanos += host.nanoClock.getAsLong() - pauseStartedNanos;
		if (paused) pauseRevision++;
		if (!"safety.recover".equals(pendingType)) lifetime.suspendInput(isPaused());
	}

	@Override
	public boolean isRunning() { return !stopped; }
	@Override
	public boolean isPaused() { return emergencyPaused || manualPaused; }
	@Override
	public long tick() { return host.tick(); }
	@Override
	public synchronized long activeTimeNanos()
	{
		long now = host.nanoClock.getAsLong();
		return now - pausedNanos - (isPaused() ? now - pauseStartedNanos : 0);
	}
	@Override
	public Map<String, Object> inputs() { return values; }
	@Override
	public void log(Object message) { host.scriptLog(definition.getId(), message); }
	@Override
	public void result(Object result) { value = result; }
	@Override
	public void overlay(Map<String, String> rows)
	{
		if (rows.size() > 4) throw new IllegalArgumentException("Script overlays may contain at most 4 rows");
		overlay = rows.entrySet().stream().map(row -> new GenericClientOverlayRow(row.getKey(), row.getValue()))
			.collect(java.util.stream.Collectors.toList());
	}
	@Override
	public void markers(List<Map<String, Object>> values) { markers = GenericClientSceneMarker.parse(values); }
	List<GenericClientSceneMarker> markers() { return stopped ? Collections.emptyList() : markers; }

	@Override
	public void stop()
	{
		host.stopRun(this, "script");
	}

	@Override
	public void checkpoint()
	{
		requireWorker();
		while (true)
		{
			if (stopped) throw new CancellationException("Script stopped");
			boolean paused = isPaused();
			if (paused != pauseCallback && initialized)
			{
				pauseCallback = paused;
				if (paused) loaded.script.onPause();
				else loaded.script.onResume();
			}
			if (!paused)
			{
				checkScheduledStop();
				warnLongIntent();
				return;
			}
			pauseForPoll(50);
		}
	}

	synchronized CompletableFuture<String> scheduleStop(String reason)
	{
		if (scheduledStop == null) scheduledStop = new ScheduledStop(reason);
		if (stopped) completeScheduledStop();
		return scheduledStop.completion;
	}

	private void checkScheduledStop()
	{
		ScheduledStop request = scheduledStop;
		if (request == null || checkingScheduledStop || !initialized || activeTimeNanos() < request.nextCheck) return;
		checkingScheduledStop = true;
		try
		{
			if (loaded.script.onScheduledStop())
			{
				host.stopRun(this,request.reason);
				throw new CancellationException("Scheduled script stop");
			}
			request.nextCheck = activeTimeNanos() + TimeUnit.SECONDS.toNanos(1);
		}
		finally { checkingScheduledStop = false; }
	}

	private void completeScheduledStop()
	{
		ScheduledStop request = scheduledStop;
		if (request != null) request.completion.complete("SCRIPT_STOPPED owner=" + owner);
	}

	private static final class ScheduledStop
	{
		final String reason;
		final CompletableFuture<String> completion = new CompletableFuture<>();
		long nextCheck;
		ScheduledStop(String reason) { this.reason = reason; }
	}

	@Override
	public void sleep(long millis)
	{
		if (millis < 0) throw new IllegalArgumentException("Sleep cannot be negative");
		waitForRest(new Rest(context("sleep", Map.of()).withTicket(inputScope().branch()),
			activeTimeNanos() + TimeUnit.MILLISECONDS.toNanos(millis), -1));
	}

	@Override
	public void sleepTicks(int ticks, Map<String, Object> options)
	{
		if (ticks < 0) throw new IllegalArgumentException("Ticks cannot be negative");
		waitForRest(new Rest(context("sleep", options).withTicket(inputScope().branch()), 0, tick() + ticks));
	}

	private void waitForRest(Rest wait)
	{
		checkpoint();
		Rest previous = rest;
		rest = wait;
		try
		{
			while (true)
			{
				checkpoint();
				long remaining = wait.wakeTick < 0 ? wait.wakeNanos - activeTimeNanos() : wait.wakeTick - tick();
				if (remaining <= 0) return;
				pauseForPoll(wait.wakeTick < 0
					? Math.max(1, Math.min(50, TimeUnit.NANOSECONDS.toMillis(remaining))) : 50);
			}
		}
		finally
		{
			rest = previous;
			wait.context.cancelInput();
		}
	}

	synchronized long quietMillis(GenericClientActionBoundary.Ticket walkOwner, long walkQuietMillis)
	{
		if (stopped || isPaused()) return 0;
		if (pending != null)
			return "walk.to".equals(pendingType) && pending == walkOwner ? walkQuietMillis : 0;
		Rest wait = rest;
		if (wait == null || !wait.context.isInputAllowed()) return 0;
		return wait.wakeTick < 0
			? Math.max(0, TimeUnit.NANOSECONDS.toMillis(wait.wakeNanos - activeTimeNanos()))
			: Math.max(0, wait.wakeTick - tick() - 1) * 600;
	}

	private static final class Rest
	{
		final GenericClientActivityContext context;
		final long wakeNanos;
		final long wakeTick;
		Rest(GenericClientActivityContext context, long wakeNanos, long wakeTick)
		{
			this.context = context;
			this.wakeNanos = wakeNanos;
			this.wakeTick = wakeTick;
		}
	}

	private void pauseForPoll(long millis)
	{
		try { Thread.sleep(millis); }
		catch (InterruptedException interrupted)
		{
			Thread.currentThread().interrupt();
			throw new CancellationException("Script interrupted");
		}
	}

	@Override
	public Object read(String subject, Map<String, Object> query)
	{
		Object frame = host.read(subject, query);
		boolean requiresFrame = !List.of("player", "players", "local_player", "npcs", "objects", "entity", "runtime", "mouse_tile", "behavior", "random_event").contains(subject);
		while (frame == null && requiresFrame && !stopped && !owner.equals("operator") && Thread.currentThread() == worker)
		{
			sleep(50);
			frame = host.read(subject, query);
		}
		return frame;
	}

	@Override
	public Map<String, Object> execute(String type, Map<String, Object> arguments, long timeoutMillis)
	{
		while (true)
		{
			checkpoint();
			long revision = pauseRevision;
			GenericClientActionBoundary.Ticket ticket = inputScope().child();
			Map<String, Object> receipt;
			try { receipt = await(host.execute(this, type, arguments, ticket), timeoutMillis); }
			catch (TimeoutException expired)
			{
				host.cancelAction(this, ticket, "action_timeout");
				receipt = Map.of("status", "timed_out", "reason", expired.getMessage());
			}
			finally
			{
				clearPending(ticket);
			}
			checkpoint();
			if (!retryAfterPause(revision, receipt))
			{
				if (currentIntent == null) return receipt;
				Map<String, Object> result = new LinkedHashMap<>(receipt);
				result.put("intent", currentIntent.name);
				return result;
			}
		}
	}

	private boolean retryAfterPause(long revision, Map<String, Object> receipt)
	{
		String outcome = String.valueOf(receipt.get("status"));
		return pauseRevision != revision && (outcome.equals("cancelled") || outcome.equals("rejected"));
	}

	synchronized void own(GenericClientActionBoundary.Ticket ticket, String type, GenericClientActivityContext context)
	{
		if (stopped || isPaused()) throw new CancellationException("Script input unavailable");
		if (pending != null) throw new IllegalStateException("A script action is already pending");
		pending = ticket;
		pendingType = type;
		pendingContext = context.withTicket(ticket);
	}

	boolean clearPending(GenericClientActionBoundary.Ticket ticket)
	{
		boolean owned;
		synchronized (this)
		{
			owned = pending == ticket;
			if (owned) { pending = null; pendingType = null; pendingContext = null; }
		}
		ticket.cancel();
		return owned;
	}

	private Map<String, Object> await(CompletableFuture<Map<String, Object>> result, long timeoutMillis) throws TimeoutException
	{
		long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		while (true)
		{
			checkpoint();
			long before = activeTimeNanos();
			try { return result.get(Math.min(50, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))), TimeUnit.MILLISECONDS); }
			catch (TimeoutException pendingResult)
			{
				if (!host.behaviorPaused()) remaining -= activeTimeNanos() - before;
				if (remaining <= 0)
				{
					throw new TimeoutException("Script action timed out after " + timeoutMillis + "ms");
				}
			}
			catch (InterruptedException interrupted)
			{
				Thread.currentThread().interrupt();
				throw new CancellationException("Script interrupted");
			}
			catch (ExecutionException failure)
			{
				throw new CompletionException(failure.getCause());
			}
		}
	}

	GenericClientActivityContext context(String action, Map<String, Object> arguments)
	{
		GenericClientActivityContext context = GenericClientActivityContext.forOperation(action, arguments,
			declaredActivity, owner.equals("operator"));
		return currentIntent == null ? context : context.inIntent();
	}

	synchronized GenericClientActivityContext behaviorContext()
	{
		if (pendingContext != null) return pendingContext;
		Rest wait = rest;
		if (wait != null) return wait.context;
		return context("idle", Map.of()).withTicket(inputScope());
	}

	GenericClientActivityContext ownedBehaviorContext()
	{
		return context("idle", Map.of()).withTicket(lifetime);
	}

	@Override
	public Map<String, Object> phase(String name, Map<String, Object> options)
	{
		while (true)
		{
			checkpoint();
			GenericClientActivityContext phaseContext = context("phase", options);
			phase = name;
			long revision = pauseRevision;
			GenericClientActionBoundary.Ticket ticket = inputScope().child();
			Map<String, Object> receipt;
			try { receipt = awaitBoundary(host.enterPhase(this, name, phaseContext.withTicket(ticket))); }
			catch (RuntimeException | Error failure)
			{
				host.cancelAction(this, ticket, "phase_failed");
				throw failure;
			}
			finally { clearPending(ticket); }
			checkpoint();
			if (!retryAfterPause(revision, receipt)) return receipt;
		}
	}

	private GenericClientActionBoundary.Ticket inputScope()
	{
		return currentIntent == null ? lifetime : currentIntent.ticket;
	}

	private Map<String, Object> awaitBoundary(CompletableFuture<Map<String, Object>> result)
	{
		try { return await(result, 60_000); }
		catch (TimeoutException expired) { throw new IllegalStateException(expired.getMessage(), expired); }
	}

	@Override
	public <T> T intent(String name, Supplier<T> body)
	{
		checkpoint();
		if (name == null || name.isBlank()) throw new IllegalArgumentException("Intent name is required");
		if (currentIntent != null)
		{
			currentIntent.depth++;
			try { return body.get(); }
			finally { currentIntent.depth--; }
		}
		while (true)
		{
			checkpoint();
			long revision = pauseRevision;
			Intent scope = new Intent(name, lifetime.child());
			GenericClientActivityContext policy = context("intent", Map.of()).withTicket(scope.ticket);
			currentIntent = scope;
			try
			{
				CompletableFuture<Map<String, Object>> finished = beginIntent(scope, policy);
				T result;
				try
				{
					Map<String, Object> entry = awaitBoundary(scope.entered);
					if (retryAfterPause(revision, entry)) continue;
					if (!"started".equals(entry.get("status"))) throw new CancellationException("Intent did not start: " + name);
					checkpoint();
					scope.startedNanos = activeTimeNanos();
					log("INTENT_STARTED name=" + name);
					result = body.get();
				}
				catch (RuntimeException | Error failure)
				{
					finishIntent(scope, finished, false, failure);
					throw failure;
				}
				finishIntent(scope, finished, true, null);
				return result;
			}
			finally
			{
				scope.ticket.cancel();
				currentIntent = null;
			}
		}
	}

	private CompletableFuture<Map<String, Object>> beginIntent(Intent scope, GenericClientActivityContext policy)
	{
		CompletableFuture<Map<String, Object>> finished = host.enterIntent(this, policy, () ->
		{
			scope.entered.complete(Map.of("status", "started"));
			return scope.body;
		});
		finished.whenComplete((receipt, error) ->
		{
			if (error == null) scope.entered.complete(receipt);
			else scope.entered.completeExceptionally(error);
		});
		return finished;
	}

	private void finishIntent(Intent scope, CompletableFuture<Map<String, Object>> finished,
		boolean completed, Throwable failure)
	{
		scope.depth = 0;
		Map<String, Object> receipt = Map.of("status", stopped ? "cancelled" : completed ? "complete" : "failed",
			"intent", scope.name, "elapsed_millis", intentElapsedMillis(scope));
		scope.body.complete(receipt);
		try { lastIntent = stopped ? receipt : awaitBoundary(finished); }
		catch (RuntimeException | Error cleanup)
		{
			lastIntent = receipt;
			if (failure == null) throw cleanup;
			failure.addSuppressed(cleanup);
		}
		finally { log("INTENT_ENDED name=" + scope.name + " status=" + lastIntent.get("status")); }
	}

	Map<String, Object> behaviorStatus(Map<String, Object> behavior)
	{
		Intent scope = currentIntent;
		behavior.put("intent", scope == null ? "none" : scope.name);
		behavior.put("intent_depth", scope == null ? 0 : scope.depth);
		behavior.put("intent_elapsed_millis", scope == null ? 0L : intentElapsedMillis(scope));
		behavior.put("last_intent", lastIntent);
		return behavior;
	}

	private long intentElapsedMillis(Intent scope)
	{
		Long started = scope.startedNanos;
		return started == null ? 0 : TimeUnit.NANOSECONDS.toMillis(activeTimeNanos() - started);
	}

	private void warnLongIntent()
	{
		Intent scope = currentIntent;
		if (scope == null || scope.depth == 0 || scope.warned || intentElapsedMillis(scope) <= 30_000) return;
		scope.warned = true;
		log("INTENT_LONG name=" + scope.name + " elapsedMillis=" + intentElapsedMillis(scope));
	}

	private static final class Intent
	{
		final String name;
		final GenericClientActionBoundary.Ticket ticket;
		final CompletableFuture<Map<String, Object>> entered = new CompletableFuture<>();
		final CompletableFuture<Map<String, Object>> body = new CompletableFuture<>();
		volatile Long startedNanos;
		volatile int depth = 1;
		boolean warned;
		Intent(String name, GenericClientActionBoundary.Ticket ticket) { this.name = name; this.ticket = ticket; }
	}

	@Override
	public void activity(String name, Map<String, Object> policy)
	{
		checkpoint();
		declaredActivity = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.fromName(name)).withPolicy(policy);
	}

	synchronized String queueAction(String action)
	{
		if (definition.getActions().stream().noneMatch(button -> button.getId().equals(action)))
			throw new IllegalArgumentException("Unknown script action: " + action);
		if (stopped) throw new IllegalStateException("Script is stopped");
		if (buttons.contains(action)) return "already_queued";
		buttons.add(action);
		return "queued";
	}

	@Override
	public synchronized String nextAction() { return buttons.poll(); }
	String activity() { return behaviorContext().getActivity().getValue(); }
	String phase() { return phase; }
	String status() { return status; }
	void publishTerminalStatus() { status = cancellationRequested ? "STOPPED" : terminalStatus; }
	String error() { return error; }

	GenericClientActiveScript snapshot()
	{
		Long finished = finishedNanos;
		long end = finished == null ? host.nanoClock.getAsLong() : finished;
		return new GenericClientActiveScript(definition.getId(), definition.getName(), definition.getDescription(),
			status, TimeUnit.NANOSECONDS.toMillis(end - startedNanos), definition.getInputs(), values,
			definition.getActions(), overlay, value, error);
	}
}
