package com.genericclient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyManager;

final class GenericClientAutomationRuntime implements AutoCloseable
{
	private static final int[] PRAYER_POTION_IDS = {143, 141, 139, 2434};
	private final Client client;
	private final KeyManager keyManager;
	private final Consumer<String> reporter;
	private final GenericClientCursorBehavior cursor;
	private final GenericClientCursorAnchors cursorAnchors;
	final GenericClientNativeInputs inputs;
	final GenericClientManualTakeover manualTakeover;
	final GenericClientSyntheticMouse syntheticMouse;
	final GenericClientSyntheticKeyboard syntheticKeyboard;
	final GenericClientSessionController sessionController;
	final GenericClientBehaviorController behaviorController;
	final GenericClientCombatGuard combatGuard;
	final GenericClientWalker walker;
	final GenericClientEmergencyController emergencyController;
	final GenericClientQuestActions questActions;
	final GenericClientScriptHost scriptHost;
	final GenericClientAutomationScheduler automationScheduler;
	final GenericClientRandomEventController randomEventController;
	volatile GenericClientSnapshot latestSnapshot;
	private volatile GenericClientPolicyResolver.Signals capturedPolicySignals = GenericClientPolicyResolver.Signals.UNAVAILABLE;
	private volatile boolean running;
	private final AtomicBoolean interruptingBreak = new AtomicBoolean();

	GenericClientAutomationRuntime(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		KeyManager keyManager,
		Path directory,
		GenericClientCollisionMap collisionMap,
		Supplier<GenericClientMouseProfile> mouseProfile,
		GenericClientMouseEffectOverlay mouseEffectOverlay,
		Consumer<String> notifyRandomEvent,
		Consumer<String> reporter, GenericClientEntityIds entityIds) throws IOException
	{
		this.client = client;
		this.keyManager = keyManager;
		this.reporter = reporter;
		GenericClientBehaviorStore behaviorStore = new GenericClientBehaviorStore(directory.resolve("behavior"));
		GenericClientEdgeMemory edgeMemory = new GenericClientEdgeMemory(directory.resolve("navigation"), System::currentTimeMillis, reporter);
		net.runelite.api.Point mousePosition = client.getMouseCanvasPosition();
		manualTakeover = new GenericClientManualTakeover(
			new GenericClientManualTakeover.Runtime()
			{
				@Override
				public boolean isAutomationActive()
				{
					return automationInputOwned();
				}

				@Override
				public boolean hasActiveInput()
				{
					return activeClientInput();
				}

				@Override
				public void cancelActiveActions(String reason)
				{
					GenericClientAutomationRuntime.this.cancelActiveActions(reason);
				}

				@Override
				public CompletableFuture<String> pauseAutomation()
				{
					if (emergencyRecoveryActive())
					{
						return CompletableFuture.completedFuture(
							"SCRIPT_PAUSE_SKIPPED reason=emergency_recovery");
					}
					return pauseInput("manual_mouse_preemption", false)
						.thenApply(ignored -> "SCRIPT_PAUSED reason=manual_mouse_preemption");
				}

				@Override
				public CompletableFuture<String> resumeAutomation()
				{
					if (emergencyRecoveryActive())
					{
						return CompletableFuture.completedFuture(
							"SCRIPT_RESUME_DEFERRED reason=emergency_recovery");
					}
					return resumeInput("manual_mouse_preemption", false)
						.thenApply(ignored -> "SCRIPT_RESUMED reason=manual_mouse_preemption");
				}

				@Override
				public GenericClientManualTakeover.Cancellable schedule(
					Runnable action,
					long delayMillis)
				{
					ScheduledFuture<?> future = executor.schedule(
						action, delayMillis, TimeUnit.MILLISECONDS);
					return () -> future.cancel(false);
				}

				@Override
				public CompletableFuture<String> stopAutomation()
				{
					emergencyController.disarmForManualEscape();
					combatGuard.reset();
					return scriptHost.stopForManualEscape();
				}
			},
			reporter);
		syntheticMouse = new GenericClientSyntheticMouse(
			client.getCanvas(),
			executor,
			mouseProfile,
			this::mouseMoveDurationMillis,
			new java.awt.Point(mousePosition.getX(), mousePosition.getY()),
			mouseEffectOverlay,
			reporter,
			point -> { if (running) manualTakeover.onPhysicalMouseMovement(point); });
		syntheticKeyboard = new GenericClientSyntheticKeyboard(
			client.getCanvas(), executor, reporter, this::typingWordsPerMinute);
		sessionController = new GenericClientSessionController(
			GenericClientSessionController.runeliteView(client, clientThread),
			GenericClientSessionController.syntheticInput(syntheticMouse, client.getCanvas()),
			executor,
			reporter);
		behaviorController = new GenericClientBehaviorController(
			behaviorStore,
			new GenericClientBehaviorController.BreakEffects()
			{
				@Override
				public CompletableFuture<String> moveOffscreen(
					GenericClientBehaviorProfile.Edge edge, GenericClientActivityContext context)
				{
					return syntheticMouse.moveOffscreen(edge, context);
				}

				@Override
				public CompletableFuture<String> logout()
				{
					return sessionController.logout();
				}

				@Override
				public CompletableFuture<String> ensureLoggedIn()
				{
					return sessionController.ensureLoggedIn();
				}
			},
			GenericClientBehaviorController.scheduledTimer(executor),
			GenericClientBehaviorController.systemClock(),
			new java.security.SecureRandom(),
			this::policySignals,
			reporter);
		inputs = new GenericClientNativeInputs(client, clientThread, executor,
			syntheticMouse, syntheticKeyboard, behaviorController, () -> latestSnapshot, reporter, entityIds);
		combatGuard = new GenericClientCombatGuard(
			new GenericClientCombatGuard.Runtime()
			{
				@Override
				public void cancelInput(GenericClientActivityContext context)
				{
					inputs.menuInput.cancel("combat_guard_reset", context);
				}

				@Override
				public boolean isPrayerActive(String prayer)
				{
					return inputs.prayerInput.isActive(prayer);
				}

				@Override
				public boolean isInputBlocked()
				{
					return manualTakeover.isActive() || emergencyRecoveryActive();
				}

				@Override
				public CompletableFuture<Map<String, Object>> setPrayer(
					String prayer,
					boolean enabled,
					GenericClientActivityContext context)
				{
					return inputs.prayerInput.set(
						prayer, enabled, context);
				}

				@Override
				public CompletableFuture<Map<String, Object>> restorePrayer(GenericClientActivityContext context)
				{
					return restorePrayerForCombatGuard(context);
				}
			},
			reporter);
		walker = new GenericClientWalker(
			inputs.gameInput, inputs.objectInput, inputs.runInput,
			(step, frame, context) -> step.execute(inputs, frame, context), collisionMap,
			edgeMemory, reporter);
		GenericClientEmergencyEscapeInput emergencyEscapeInput =
			new GenericClientEmergencyEscapeInput(
				client,
				clientThread,
				executor,
				inputs.inventoryInput,
				inputs.dialogueInput,
				walker,
				reporter);
		emergencyController = new GenericClientEmergencyController(
			(itemId, action) -> inputs.inventoryInput.interact(
				itemId, null, action, GenericClientActivityContext.none()),
			emergencyEscapeInput::escape,
			behaviorController::endActiveBreak,
			new GenericClientEmergencyController.InputControl()
			{
				@Override
				public CompletableFuture<?> pause(String reason)
				{
					return pauseInput(reason, true);
				}

				@Override
				public CompletableFuture<?> resume(String reason)
				{
					return resumeAfterEmergency(reason);
				}
			},
			this::stopForEmergency,
			reporter);
		questActions = new GenericClientQuestActions(
			inputs.objectInput,
			inputs.inventoryInput,
			inputs.equipmentInput,
			inputs.npcInput,
			inputs.groundItemInput,
			inputs.dialogueInput,
			inputs.bankInput,
			inputs.grandExchangeInput,
			inputs.spellInput,
			inputs.autocastInput,
			inputs.prayerInput,
			inputs.uiInput,
			inputs.worldInput,
			inputs.poisonInput,
			inputs.combatInput,
			emergencyController,
			combatGuard);
		GenericClientScriptHost openedHost = null;
		try
		{
			scriptHost = new GenericClientScriptHost(
				directory.resolve("scripts"),
				inputs.gameInput::walkToRandomTile,
				(destination, activity) -> inputs.gameInput.walkToFarthest(
					Collections.singletonList(destination), activity),
				walker::walkTo,
				inputs.npcInput::interact,
				inputs.combatInput::setMode,
				(type,arguments,context) -> type.equals("player.interact")
					? inputs.playerInput.interact(arguments,context) : questActions.execute(type,arguments,context),
				this::cancelActiveActions,
				behaviorController,
				System::nanoTime,
				reporter);
			openedHost = scriptHost;
			scriptHost.setScriptStartListener((scriptId, owner, context) ->
			{
				if (!context.isInputAllowed()) return;
				manualTakeover.resetForAutomationStart();
				resetScriptBehaviors("start", scriptId, context);
				if (!"safety-net".equals(scriptId))
				{
					emergencyController.disarmForScriptStart(scriptId);
				}
			});
			scriptHost.setScriptEndListener((scriptId, owner) ->
				resetScriptBehaviors("end", scriptId, GenericClientActivityContext.none()));
			automationScheduler = new GenericClientAutomationScheduler(
				directory.resolve("automation"),
				scriptHost,
				reporter);
			randomEventController = createRandomEventController(notifyRandomEvent);
			scriptHost.setRandomEventHooks(
				randomEventController::status,
				randomEventController::solverFinished);
			cursor = new GenericClientCursorBehavior(syntheticMouse, () -> System.nanoTime() / 1_000_000,
				new java.security.SecureRandom(), reporter);
			cursorAnchors = new GenericClientCursorAnchors(client);
		}
		catch (IOException | RuntimeException exception)
		{
			if (openedHost != null) openedHost.close();
			closeInputs();
			throw exception;
		}
		running = true;
		keyManager.registerKeyListener(manualTakeover);
	}

	void onGameStateChanged(GameState state)
	{
		sessionController.onGameStateChanged(state);
		if (state != GameState.LOGGED_IN)
		{
			cursor.cancel();
			latestSnapshot = null;
			scriptHost.clearSnapshot();
			walker.clearSnapshot();
			combatGuard.reset();
			automationScheduler.clearSnapshot();
		}
		behaviorController.setLoggedIn(state == GameState.LOGGED_IN);
		if (state == GameState.LOGGED_IN) activateBehaviorProfile();
	}

	void onAccountHashChanged()
	{
		latestSnapshot = null;
		scriptHost.clearSnapshot();
		walker.clearAccount();
		automationScheduler.clearSnapshot();
		if (client.getGameState() == GameState.LOGGED_IN) activateBehaviorProfile();
		combatGuard.reset();
		cursor.cancel();
	}

	void publishGameTick(GenericClientSnapshot snapshot)
	{
		latestSnapshot = snapshot;
		GenericClientActivityContext context = scriptHost.getBehaviorContext();
		boolean inputOwned = automationInputOwned();
		emergencyController.publishGameTick(snapshot, inputOwned);
		combatGuard.publishGameTick(snapshot, context.declaredPolicy,
			inputOwned && context.getActivity() != GenericClientActivityContext.Activity.MANUAL);
		refreshPolicySignals();
		GenericClientPolicyResolver.Resolution resolution = behaviorController.policies.resolve(context);
		if (resolution.unexpectedCombat && behaviorController.isPaused() && interruptingBreak.compareAndSet(false, true))
		{
			behaviorController.endActiveBreak().whenComplete((receipt, error) ->
			{
				interruptingBreak.set(false);
				if (error != null) reporter.accept("BEHAVIOR_BREAK_INTERRUPTION_FAILED message=" + error.getMessage());
			});
		}
		behaviorController.publishActiveTick(scriptHost.getRunState().isRunning(), scriptHost.ownedBehaviorContext());
		walker.publishGameTick(snapshot);
		scriptHost.publishGameTick(snapshot);
		automationScheduler.publishGameTick(snapshot);
		publishCursor();
	}

	void recordHitsplat(net.runelite.api.events.HitsplatApplied event)
	{
		net.runelite.api.Player player = client.getLocalPlayer();
		if (player == null || event.getActor() != player) return;
		net.runelite.api.Hitsplat hit = event.getHitsplat();
		combatGuard.recordHitsplat(hit.getHitsplatType(), hit.getAmount());
	}

	Map<String, Object> status()
	{
		behaviorController.policies.resolve(scriptHost.getBehaviorContext());
		Map<String, Object> value = new LinkedHashMap<>();
		Map<String, Object> scripts = new LinkedHashMap<>(scriptHost.controlState());
		scripts.put("declared_activity", scriptHost.getActivity());
		scripts.put("activity", scriptHost.getActivity());
		value.put("scripts", scripts);
		value.put("behavior", behaviorController.status());
		value.put("automation", automationScheduler.status());
		value.put("safety", emergencyController.status());
		value.put("combat_guard", combatGuard.status());
		value.put("random_event", randomEventController.status());
		return value;
	}

	private void publishCursor()
	{
		GenericClientSnapshot snapshot = latestSnapshot;
		GenericClientBehaviorProfile profile = behaviorController.currentProfile();
		GenericClientActivityContext context = scriptHost.getBehaviorContext();
		boolean idle = !automationInputOwned();
		boolean blocked = snapshot == null || !snapshot.isLoggedIn() || snapshot.getPlayer() == null ||
			profile == null || behaviorController.isPaused() || inputs.isActive() || syntheticKeyboard.isTyping() ||
			syntheticMouse.isActionActive() || combatGuard.hasPendingInput() || manualTakeover.isActive() ||
			randomEventActive() || emergencyRecoveryActive() || client.isMenuOpen();
		GenericClientWalkJourney.InputWindow window = walker.inputWindow();
		long walkQuiet = snapshot == null ? 0 : Math.max(0, window.nextTick - snapshot.getGameTick()) * 600;
		boolean readAnchors = !blocked && !idle && context.policy().fidget != GenericClientBehaviorPolicy.Fidget.NONE;
		cursor.publish(new GenericClientCursorBehavior.Frame(context, profile, behaviorController.totalActiveMillis(), scriptHost.quietMillis(window.owner, walkQuiet),
			idle, blocked, readAnchors ? cursorAnchors.read(context.getActivity(), syntheticMouse.getLastClick()) : java.util.List.of(),
			readAnchors ? cursorAnchors.anticipate(window.waypoint) : null,
			new java.awt.Rectangle(0, 0, client.getCanvas().getWidth(), client.getCanvas().getHeight())));
	}

	boolean activeClientInput()
	{
		return inputs.isActive() || syntheticKeyboard.isTyping() || syntheticMouse.isMoving();
	}

	private void cancelActiveActions(String reason)
	{
		walker.cancelActive(reason);
		inputs.cancel(reason);
		syntheticKeyboard.cancel(reason);
		syntheticMouse.cancel(reason);
	}

	private CompletableFuture<?> stopForEmergency(String reason)
	{
		cancelActiveActions(reason);
		return scriptHost.stop();
	}

	private CompletableFuture<?> pauseInput(String reason, boolean pressEscape)
	{
		combatGuard.reset();
		return (pressEscape ? scriptHost.pauseForEmergency(reason) : scriptHost.pauseForManualInput(reason)).thenCompose(ignored ->
		{
			walker.pauseActiveInput(reason);
			inputs.pause(reason);
			syntheticKeyboard.cancel(reason);
			syntheticMouse.cancel(reason);
			return pressEscape
				? syntheticKeyboard.pressEscape().handle((result, error) -> null)
				: CompletableFuture.completedFuture(null);
		});
	}

	private CompletableFuture<?> resumeAfterEmergency(String reason)
	{
		return resumeInput(reason, true);
	}

	private CompletableFuture<?> resumeInput(String reason, boolean emergency)
	{
		CompletableFuture<?> resumed = emergency ? scriptHost.resumeAfterEmergency(reason) : scriptHost.resumeAfterManualInput(reason);
		return resumed.thenRun(() -> { if (!scriptHost.isInputPaused()) walker.resumeActiveInput(reason); });
	}

	@Override
	public void close()
	{
		automationScheduler.close();
		scriptHost.setRandomEventHooks(null, null);
		scriptHost.close();
		closeInputs();
	}

	private void closeInputs()
	{
		running = false;
		if (cursor != null) cursor.cancel();
		combatGuard.reset();
		keyManager.unregisterKeyListener(manualTakeover);
		walker.close();
		sessionController.close();
		inputs.close();
		behaviorController.close();
		syntheticMouse.close();
		manualTakeover.close();
		syntheticKeyboard.close();
	}

	private GenericClientRandomEventController createRandomEventController(Consumer<String> notifyRandomEvent)
	{
		return new GenericClientRandomEventController(
			scriptHost::findRandomEventSolver,
			new GenericClientRandomEventController.Runtime()
			{
				@Override
				public String randomEventDeferralActivity()
				{
					String activity = scriptHost.getActivity();
					if (behaviorController.policies.resolve(scriptHost.getBehaviorContext()).unexpectedCombat)
						return "unexpected_combat";
					return "combat".equalsIgnoreCase(activity) ||
						"hazardous_travel".equalsIgnoreCase(activity)
						? activity
						: null;
				}

				@Override
				public boolean isAutomationActive()
				{
					return automationInputOwned();
				}

				@Override
				public CompletableFuture<String> interrupt(
					String eventKey,
					String solverScript)
				{
					automationScheduler.setAttentionRequired(true, "random_event:" + eventKey);
					CompletableFuture<String> interrupted =
						scriptHost.interruptForRandomEvent(eventKey);
					syntheticMouse.cancel("random_event_detected");
					syntheticKeyboard.cancel("random_event_detected");
					CompletableFuture<String> breakEnded;
					try
					{
						breakEnded = behaviorController.endActiveBreak().handle((result, error) ->
						{
							if (error != null)
							{
								reporter.accept(
									"RANDOM_EVENT_BREAK_END_FAILED message=" + error.getMessage());
							}
							return "break_ready";
						});
					}
					catch (RuntimeException exception)
					{
						reporter.accept("RANDOM_EVENT_BREAK_END_FAILED message=" + exception.getMessage());
						breakEnded = CompletableFuture.completedFuture("break_ready");
					}
					return interrupted.thenCombine(breakEnded, (result, ignored) -> result)
						.thenCompose(result -> solverScript == null
							? CompletableFuture.completedFuture(result)
							: scriptHost.startRandomEventSolver(eventKey, solverScript));
				}

				@Override
				public CompletableFuture<String> release(
					String eventKey,
					boolean resumeInterrupted)
				{
					return scriptHost.releaseRandomEvent(eventKey, resumeInterrupted).whenComplete(
						(result, error) ->
						{
							if (error == null)
							{
								automationScheduler.setAttentionRequired(
									false, "random_event_completed");
							}
						});
				}
			},
			npcId -> inputs.npcInput.interact(
				npcId, null, null,
				null,
				"Talk-to",
				12,
				GenericClientActivityContext.none()),
			notifyRandomEvent,
			reporter);
	}

	void activateBehaviorProfile()
	{
		long accountHash = client.getAccountHash();
		if (accountHash == -1L)
		{
			walker.clearAccount();
			reporter.accept("BEHAVIOR_PROFILE_WAITING account_hash_unavailable");
			return;
		}
		try
		{
			walker.activateAccount(accountHash);
		}
		catch (IOException | RuntimeException exception)
		{
			reporter.accept("WALK_EDGE_MEMORY_LOAD_FAILED message=" + exception.getMessage());
		}
		try
		{
			behaviorController.activateAccount(accountHash);
		}
		catch (IOException | RuntimeException exception)
		{
			reporter.accept("BEHAVIOR_PROFILE_FAILED message=" + exception.getMessage());
		}
		automationScheduler.activateProfile(GenericClientBehaviorProfile.fromAccountHash(accountHash).getId());
	}

	private void resetScriptBehaviors(String boundary, String scriptId, GenericClientActivityContext context)
	{
		emergencyController.resetScriptBehavior();
		combatGuard.resetScriptBehavior();
		if (client.getGameState() != GameState.LOGGED_IN) return;
		if ("end".equals(boundary) && manualTakeover.isActive())
		{
			reporter.accept("SCRIPT_BEHAVIOR_RESET_DEFERRED boundary=end script=" + scriptId +
				" reason=manual_escape");
			return;
		}
		try
		{
			Map<String, Object> prayerReceipt = questActions.releaseScriptPrayer(context)
				.get(3L, TimeUnit.SECONDS);
			String prayerStatus = String.valueOf(prayerReceipt.get("status"));
			if (!("set".equals(prayerStatus) || "unchanged".equals(prayerStatus)))
			{
				reporter.accept("SCRIPT_BEHAVIOR_RESET_FAILED boundary=" + boundary +
					" script=" + scriptId + " prayer_result=" +
					prayerReceipt.get("result"));
				if ("start".equals(boundary))
				{
					throw new IllegalStateException(
						"Could not release script-owned prayer: " +
							prayerReceipt.get("result"));
				}
			}
			Map<String, Object> receipt = inputs.combatInput.setAutoRetaliate(
				true, context).get(3L, TimeUnit.SECONDS);
			String status = String.valueOf(receipt.get("status"));
			if ("set".equals(status) || "unchanged".equals(status))
			{
				reporter.accept("SCRIPT_BEHAVIOR_RESET boundary=" + boundary +
					" script=" + scriptId + " auto_retaliate=true result=" +
					receipt.get("result"));
				return;
			}
			reporter.accept("SCRIPT_BEHAVIOR_RESET_FAILED boundary=" + boundary +
				" script=" + scriptId + " auto_retaliate=true result=" +
				receipt.get("result"));
			if ("start".equals(boundary))
			{
				throw new IllegalStateException(
					"Could not restore default auto-retaliate: " + receipt.get("result"));
			}
		}
		catch (InterruptedException exception)
		{
			Thread.currentThread().interrupt();
			reporter.accept("SCRIPT_BEHAVIOR_RESET_FAILED boundary=" + boundary +
				" script=" + scriptId + " message=interrupted");
			if ("start".equals(boundary))
			{
				throw new IllegalStateException("Script behavior reset was interrupted", exception);
			}
		}
		catch (ExecutionException | TimeoutException exception)
		{
			reporter.accept("SCRIPT_BEHAVIOR_RESET_FAILED boundary=" + boundary +
				" script=" + scriptId + " message=" +
				exception.getMessage());
			if ("start".equals(boundary))
			{
				throw new IllegalStateException("Could not restore default script behavior", exception);
			}
		}
	}

	private GenericClientPolicyResolver.Signals policySignals()
	{
		GenericClientSnapshot snapshot = latestSnapshot;
		if (snapshot == null || !snapshot.isLoggedIn() || snapshot.getPlayer() == null)
			return GenericClientPolicyResolver.Signals.UNAVAILABLE;
		GenericClientPolicyResolver.Signals observed = capturedPolicySignals;
		return new GenericClientPolicyResolver.Signals(observed.snapshotAvailable, observed.tick,
			observed.damageGraceUntilTick, observed.threatsPresent, observed.manualTakeover,
			observed.randomEvent, observed.emergencyRecovery, combatGuard.isAutomaticPrayerEnabled());
	}

	void refreshPolicySignals()
	{
		GenericClientCombatGuard.Observation observation = combatGuard.observation();
		GenericClientSnapshot snapshot = latestSnapshot;
		capturedPolicySignals = new GenericClientPolicyResolver.Signals(snapshot != null && snapshot.getPlayer() != null,
			observation.tick, observation.damageGraceUntilTick, observation.threatsPresent,
			manualTakeover.isActive(), randomEventActive(), emergencyRecoveryActive(), observation.automaticPrayerEnabled);
	}

	int mouseMoveDurationMillis()
	{
		return behaviorController.mouseMoveDurationMillis();
	}

	private int typingWordsPerMinute()
	{
		return behaviorController.typingWordsPerMinute();
	}

	boolean automationInputOwned()
	{
		return scriptHost.getRunState().isRunning() ||
			scriptHost.hasPendingFailureFallback() || emergencyRecoveryActive();
	}

	private boolean randomEventActive()
	{
		return Boolean.TRUE.equals(randomEventController.status().get("active"));
	}

	private boolean emergencyRecoveryActive()
	{
		return Boolean.TRUE.equals(emergencyController.status().get("recovering"));
	}

	private CompletableFuture<Map<String, Object>>
		restorePrayerForCombatGuard(GenericClientActivityContext context)
	{
		GenericClientSnapshot snapshot = latestSnapshot;
		GenericClientInventoryInput inventory = inputs.inventoryInput;
		if (snapshot != null)
		{
			for (int itemId : PRAYER_POTION_IDS)
			{
				if (snapshot.getInventoryQuantity(itemId) > 0L)
				{
					return inventory.interact(
						itemId, null, "Drink", context);
				}
			}
		}
		return CompletableFuture.completedFuture(
			GenericClientInteractionReceipts.rejected("no_prayer_restore_available"));
	}

}
