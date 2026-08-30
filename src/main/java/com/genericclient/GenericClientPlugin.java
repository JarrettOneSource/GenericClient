package com.genericclient;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.AccountHashChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.client.Notifier;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "GenericClient",
	description = "Popout Lua automation dashboard with seeded behavior profiles and synthetic client input",
	tags = {"genericclient", "diagnostics", "lua", "scripting", "mouse", "mcp", "behavior", "dashboard"},
	loadInSafeMode = false
)
public final class GenericClientPlugin extends Plugin
{
	private static final String LOG_PREFIX = "[GenericClient]";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GenericClientConfig config;

	@Inject
	private GenericClientMouseEffectOverlay mouseEffectOverlay;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ConfigManager configManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private Notifier notifier;

	private volatile String lifecycle = "CREATED";
	private volatile String gameStateName = "UNKNOWN";
	private volatile String lastStatus = "Plugin instance created";
	private volatile long tickCount;
	private volatile Instant startedAt;
	private boolean loginMessageShown;

	private GenericClientDashboard panel;
	private GenericClientBreakOverlay breakOverlay;
	private GenericClientScriptOverlay scriptOverlay;
	private GenericClientControlServer controlServer;
	private GenericClientGameInput gameInput;
	private GenericClientMenuInput menuInput;
	private GenericClientNpcInput npcInput;
	private GenericClientRunInput runInput;
	private GenericClientObjectInput objectInput;
	private GenericClientInventoryInput inventoryInput;
	private GenericClientEquipmentInput equipmentInput;
	private GenericClientGroundItemInput groundItemInput;
	private GenericClientDialogueInput dialogueInput;
	private GenericClientQuestActions questActions;
	private GenericClientCombatInput combatInput;
	private GenericClientSyntheticMouse syntheticMouse;
	private GenericClientSyntheticKeyboard syntheticKeyboard;
	private GenericClientBankInput bankInput;
	private GenericClientGrandExchangeInput grandExchangeInput;
	private GenericClientSpellInput spellInput;
	private GenericClientAutocastInput autocastInput;
	private GenericClientUiInput uiInput;
	private GenericClientEmergencyController emergencyController;
	private GenericClientSessionController sessionController;
	private GenericClientBehaviorController behaviorController;
	private GenericClientMouseRecorder mouseRecorder;
	private GenericClientWalker walker;
	private GenericClientLuaHost luaHost;
	private GenericClientAutomationScheduler automationScheduler;
	private GenericClientRandomEventController randomEventController;
	private GenericClientScreenshot screenshot;
	private NavigationButton navigationButton;
	private Path mouseProfilesDirectory;
	private volatile GenericClientMouseProfile mouseProfile;
	private volatile GenericClientSnapshot latestSnapshot;
	private final GenericClientBankCache bankCache = new GenericClientBankCache();
	private final GenericClientQuestCache questCache = new GenericClientQuestCache();
	private final GenericClientGameMessageBuffer gameMessages = new GenericClientGameMessageBuffer();
	private ScheduledFuture<?> panelRefreshFuture;

	@Override
	protected void startUp() throws Exception
	{
		startedAt = Instant.now();
		loginMessageShown = false;
		lifecycle = "RUNNING";
		gameStateName = client.getGameState().name();
		lastStatus = "PLUGIN_STARTED stock RuneLite loaded GenericClient";
		mouseProfilesDirectory = net.runelite.client.RuneLite.RUNELITE_DIR.toPath()
			.resolve("genericclient")
			.resolve("mouse-profiles");
		GenericClientMouseProfile.installDefault(mouseProfilesDirectory);
		loadConfiguredMouseProfile();
		GenericClientCollisionMap collisionMap = GenericClientCollisionMap.loadBundled();
		net.runelite.api.Point mousePosition = client.getMouseCanvasPosition();
		syntheticMouse = new GenericClientSyntheticMouse(
			client.getCanvas(),
			executor,
			() -> mouseProfile,
			this::mouseMoveDurationMillis,
			new java.awt.Point(mousePosition.getX(), mousePosition.getY()),
			mouseEffectOverlay,
			this::publishResult);
		syntheticKeyboard = new GenericClientSyntheticKeyboard(
			client.getCanvas(), executor, this::publishResult, this::typingWordsPerMinute);
		sessionController = new GenericClientSessionController(
			GenericClientSessionController.runeliteView(client, clientThread),
			GenericClientSessionController.syntheticInput(syntheticMouse, client.getCanvas()),
			executor,
			this::publishResult);
		behaviorController = new GenericClientBehaviorController(
			new GenericClientBehaviorStore(net.runelite.client.RuneLite.RUNELITE_DIR.toPath()
				.resolve("genericclient")
				.resolve("behavior")),
			new GenericClientBehaviorController.BreakEffects()
			{
				@Override
				public java.util.concurrent.CompletableFuture<String> moveOffscreen(
					GenericClientBehaviorProfile.Edge edge)
				{
					return syntheticMouse.moveOffscreen(edge);
				}

				@Override
				public java.util.concurrent.CompletableFuture<String> logout()
				{
					return sessionController.logout();
				}

				@Override
				public java.util.concurrent.CompletableFuture<String> ensureLoggedIn()
				{
					return sessionController.ensureLoggedIn();
				}
			},
			GenericClientBehaviorController.scheduledTimer(executor),
			GenericClientBehaviorController.systemClock(),
			GenericClientBehaviorController.secureRandom(),
			this::publishResult);
		breakOverlay = new GenericClientBreakOverlay(
			() ->
			{
				GenericClientBehaviorController behaviors = behaviorController;
				return behaviors == null ? null : behaviors.status();
			},
			behaviorController::endActiveBreak);
		gameInput = new GenericClientGameInput(
			client,
			clientThread,
			executor,
			syntheticMouse,
			behaviorController,
			this::publishResult);
		menuInput = new GenericClientMenuInput(
			client,
			clientThread,
			executor,
			syntheticMouse,
			behaviorController,
			this::publishResult);
		runInput = new GenericClientRunInput(client, clientThread, executor, menuInput);
		npcInput = new GenericClientNpcInput(
			client, clientThread, executor, menuInput, this::publishResult);
		spellInput = new GenericClientSpellInput(client, clientThread, executor, menuInput, npcInput);
		autocastInput = new GenericClientAutocastInput(
			client, clientThread, executor, menuInput, this::publishResult);
		uiInput = new GenericClientUiInput(
			client, menuInput, syntheticKeyboard, behaviorController);
		objectInput = new GenericClientObjectInput(client, clientThread, executor, menuInput);
		inventoryInput = new GenericClientInventoryInput(client, clientThread, executor, menuInput);
		equipmentInput = new GenericClientEquipmentInput(client, clientThread, executor, menuInput);
		groundItemInput = new GenericClientGroundItemInput(
			client, clientThread, executor, menuInput);
		dialogueInput = new GenericClientDialogueInput(
			client, menuInput, behaviorController, this::publishResult);
		emergencyController = new GenericClientEmergencyController(
			(itemId, action) -> inventoryInput.interact(
				itemId, null, action, GenericClientActivityContext.none()),
			this::startEmergencyEscape,
			behaviorController::endActiveBreak,
			new GenericClientEmergencyController.InputControl()
			{
				@Override
				public java.util.concurrent.CompletableFuture<?> pause(String reason)
				{
					return pauseForEmergency(reason);
				}

				@Override
				public java.util.concurrent.CompletableFuture<?> resume(String reason)
				{
					return resumeAfterEmergency(reason);
				}
			},
			this::stopForEmergency,
			this::publishResult);
		bankInput = new GenericClientBankInput(
			client,
			clientThread,
			executor,
			menuInput,
			syntheticKeyboard,
			behaviorController,
			this::publishResult);
		grandExchangeInput = new GenericClientGrandExchangeInput(
			client,
			clientThread,
			executor,
			menuInput,
			syntheticKeyboard,
			behaviorController,
			() -> latestSnapshot,
			this::publishResult);
		questActions = new GenericClientQuestActions(
			objectInput,
			inventoryInput,
			equipmentInput,
			npcInput,
			groundItemInput,
			dialogueInput,
			bankInput,
			grandExchangeInput,
			spellInput,
			autocastInput,
			uiInput,
			emergencyController);
		combatInput = new GenericClientCombatInput(
			client,
			clientThread,
			executor,
			syntheticMouse,
			behaviorController,
			this::publishResult);
		mouseRecorder = new GenericClientMouseRecorder(
			client.getCanvas(),
			() -> gameInput.isRunning() || menuInput.isRunning() || combatInput.isRunning() ||
				syntheticKeyboard.isTyping() ||
				syntheticMouse.isMoving());
		walker = new GenericClientWalker(
			gameInput, objectInput, runInput, collisionMap, this::publishResult);
		luaHost = new GenericClientLuaHost(
			net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("genericclient").resolve("scripts"),
			gameInput::walkToRandomTile,
			walker::walkTo,
			npcInput::interact,
			combatInput::setMode,
			questActions::execute,
			this::cancelActiveActions,
			behaviorController,
			this::publishResult);
		automationScheduler = new GenericClientAutomationScheduler(
			net.runelite.client.RuneLite.RUNELITE_DIR.toPath()
				.resolve("genericclient").resolve("automation"),
			luaHost,
			this::publishResult);
		randomEventController = new GenericClientRandomEventController(
			luaHost::findRandomEventSolver,
			new GenericClientRandomEventController.Runtime()
			{
				@Override
				public java.util.concurrent.CompletableFuture<String> interrupt(
					String eventKey,
					String solverScript)
				{
					automationScheduler.setAttentionRequired(true, "random_event:" + eventKey);
					java.util.concurrent.CompletableFuture<String> interrupted =
						luaHost.interruptForRandomEvent(eventKey);
					syntheticMouse.cancel("random_event_detected");
					syntheticKeyboard.cancel("random_event_detected");
					java.util.concurrent.CompletableFuture<String> breakEnded;
					try
					{
						breakEnded = behaviorController.endActiveBreak().handle((result, error) ->
						{
							if (error != null)
							{
								publishResult(
									"RANDOM_EVENT_BREAK_END_FAILED message=" + error.getMessage());
							}
							return "break_ready";
						});
					}
					catch (RuntimeException exception)
					{
						publishResult("RANDOM_EVENT_BREAK_END_FAILED message=" + exception.getMessage());
						breakEnded = java.util.concurrent.CompletableFuture.completedFuture("break_ready");
					}
					return interrupted.thenCombine(breakEnded, (result, ignored) -> result)
						.thenCompose(result -> solverScript == null
							? java.util.concurrent.CompletableFuture.completedFuture(result)
							: luaHost.startRandomEventSolver(eventKey, solverScript));
				}

				@Override
				public java.util.concurrent.CompletableFuture<String> release(
					String eventKey,
					boolean resumeInterrupted)
				{
					return luaHost.releaseRandomEvent(eventKey, resumeInterrupted).whenComplete(
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
			npcId -> npcInput.interact(
				npcId,
				null,
				"Talk-to",
				12,
				GenericClientActivityContext.none()),
			message ->
			{
				notifier.notify(message);
				postChat(message);
			},
			this::publishResult);
		luaHost.setRandomEventHooks(
			randomEventController::status,
			randomEventController::solverFinished);
		screenshot = new GenericClientScreenshot(drawManager, executor);
		scriptOverlay = new GenericClientScriptOverlay(
			luaHost::getActiveScriptView,
			luaHost::getActivity,
			luaHost::getScriptState);
		controlServer = new GenericClientControlServer(
			config.controlPort(),
			luaHost,
			automationScheduler,
			randomEventController,
			sessionController::logout,
			sessionController::ensureLoggedIn,
			this::controlStatus,
			this::accountNote,
			this::setAccountNote,
			screenshot::capture,
			behaviorController::endActiveBreak,
			this::publishResult);
		controlServer.start();
		panel = new GenericClientDashboard(
			javax.swing.SwingUtilities.getWindowAncestor(client.getCanvas()),
			dashboardActions(),
			luaHost,
			automationScheduler);
		navigationButton = NavigationButton.builder()
			.tooltip("GenericClient")
			.icon(createIcon())
			.priority(1)
			.onClick(panel::open)
			.build();

		overlayManager.add(mouseEffectOverlay);
		overlayManager.add(breakOverlay);
		mouseManager.registerMouseListener(breakOverlay.getMouseListener());
		overlayManager.add(scriptOverlay);
		clientToolbar.addNavigation(navigationButton);
		refreshPanel();
		panelRefreshFuture = executor.scheduleAtFixedRate(this::refreshPanel, 1L, 1L, TimeUnit.SECONDS);

		log.info("{} PLUGIN_STARTED runeliteVersion={} classLoader={} thread={}",
			LOG_PREFIX,
			RuneLiteProperties.getVersion(),
			getClass().getClassLoader().getClass().getName(),
			Thread.currentThread().getName());
		log.info("{} COLLISION_MAP_LOADED regions={} revision={} sha256={}",
			LOG_PREFIX,
			collisionMap.getRegionCount(),
			GenericClientCollisionMap.SOURCE_REVISION,
			GenericClientCollisionMap.SOURCE_SHA256);
		log.info("{} MOUSE_PROFILE_LOADED file={} profile={} templates={}",
			LOG_PREFIX,
			config.mouseProfileFile(),
			mouseProfile.getProfileId(),
			mouseProfile.getTemplateCount());
		printDiagnostics();
		behaviorController.setLoggedIn(client.getGameState() == GameState.LOGGED_IN);
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			activateBehaviorProfile();
		}
	}

	@Override
	protected void shutDown()
	{
		lifecycle = "STOPPING";
		if (panelRefreshFuture != null)
		{
			panelRefreshFuture.cancel(false);
			panelRefreshFuture = null;
		}
		if (panel != null)
		{
			panel.close();
			panel = null;
		}
		if (controlServer != null)
		{
			controlServer.close();
			controlServer = null;
		}
		if (screenshot != null)
		{
			screenshot.close();
			screenshot = null;
		}
		if (automationScheduler != null)
		{
			automationScheduler.close();
			automationScheduler = null;
		}
		if (luaHost != null)
		{
			luaHost.setRandomEventHooks(null, null);
			luaHost.close();
			luaHost = null;
		}
		randomEventController = null;
		if (walker != null)
		{
			walker.close();
			walker = null;
		}
		if (sessionController != null)
		{
			sessionController.close();
			sessionController = null;
		}
		if (mouseRecorder != null)
		{
			mouseRecorder.close();
			mouseRecorder = null;
		}
		if (gameInput != null)
		{
			gameInput.close();
			gameInput = null;
		}
		if (npcInput != null)
		{
			npcInput = null;
		}
		if (bankInput != null)
		{
			bankInput.close();
			bankInput = null;
		}
		if (grandExchangeInput != null)
		{
			grandExchangeInput.close();
			grandExchangeInput = null;
		}
		questActions = null;
		spellInput = null;
		autocastInput = null;
		uiInput = null;
		emergencyController = null;
		dialogueInput = null;
		groundItemInput = null;
		inventoryInput = null;
		equipmentInput = null;
		objectInput = null;
		runInput = null;
		if (menuInput != null)
		{
			menuInput.close();
			menuInput = null;
		}
		if (combatInput != null)
		{
			combatInput.close();
			combatInput = null;
		}
		if (behaviorController != null)
		{
			behaviorController.close();
			behaviorController = null;
		}
		if (syntheticMouse != null)
		{
			syntheticMouse.close();
			syntheticMouse = null;
		}
		if (syntheticKeyboard != null)
		{
			syntheticKeyboard.close();
			syntheticKeyboard = null;
		}
		overlayManager.remove(mouseEffectOverlay);
		if (breakOverlay != null)
		{
			mouseManager.unregisterMouseListener(breakOverlay.getMouseListener());
			overlayManager.remove(breakOverlay);
			breakOverlay = null;
		}
		if (scriptOverlay != null)
		{
			overlayManager.remove(scriptOverlay);
			scriptOverlay = null;
		}
		if (navigationButton != null)
		{
			clientToolbar.removeNavigation(navigationButton);
			navigationButton = null;
		}
		lifecycle = "STOPPED";
		log.info("{} PLUGIN_STOPPED ticks={} uptime={}", LOG_PREFIX, tickCount, getUptimeText());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		gameStateName = event.getGameState().name();
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			loginMessageShown = false;
		}
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			latestSnapshot = null;
			GenericClientAutomationScheduler automations = automationScheduler;
			if (automations != null)
			{
				automations.clearSnapshot();
			}
		}
		GenericClientBehaviorController behaviors = behaviorController;
		if (behaviors != null)
		{
			behaviors.setLoggedIn(event.getGameState() == GameState.LOGGED_IN);
		}
		publishResult("GAME_STATE_CHANGED state=" + gameStateName);
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			activateBehaviorProfile();
			if (!loginMessageShown)
			{
				loginMessageShown = true;
				postChat("GenericClient loaded");
			}
		}
	}

	@Subscribe
	public void onAccountHashChanged(AccountHashChanged event)
	{
		bankCache.clear();
		questCache.clear();
		gameMessages.clear();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			activateBehaviorProfile();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		gameMessages.add(tickCount, event);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickCount++;
		GenericClientSnapshot snapshot = GenericClientSnapshot.capture(
			client, tickCount, bankCache, questCache, gameMessages.snapshot());
		latestSnapshot = snapshot;
		GenericClientEmergencyController emergency = emergencyController;
		if (emergency != null)
		{
			emergency.publishGameTick(snapshot);
		}
		GenericClientBehaviorController behaviors = behaviorController;
		if (behaviors != null)
		{
			behaviors.publishActiveTick();
		}
		GenericClientWalker activeWalker = walker;
		if (activeWalker != null)
		{
			activeWalker.publishGameTick(snapshot);
		}
		GenericClientLuaHost scripts = luaHost;
		if (scripts != null)
		{
			scripts.publishGameTick(snapshot);
		}
		GenericClientAutomationScheduler automations = automationScheduler;
		if (automations != null)
		{
			automations.publishGameTick(snapshot);
		}
		if (tickCount == 1)
		{
			log.info("{} FIRST_GAME_TICK state={}", LOG_PREFIX, client.getGameState());
		}
		if (tickCount % 5 == 0)
		{
			refreshPanel();
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		GenericClientGameInput input = gameInput;
		if (input != null)
		{
			input.onMenuOptionClicked(event);
		}
		GenericClientMenuInput menu = menuInput;
		if (menu != null)
		{
			menu.onMenuOptionClicked(event);
		}
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		GenericClientRandomEventController randomEvents = randomEventController;
		if (randomEvents != null)
		{
			randomEvents.onInteractingChanged(client.getLocalPlayer(), event, tickCount);
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		GenericClientRandomEventController randomEvents = randomEventController;
		if (randomEvents != null)
		{
			randomEvents.onNpcDespawned(event, tickCount);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (GenericClientConfig.GROUP.equals(event.getGroup()) &&
			"mouseProfileFile".equals(event.getKey()))
		{
			reloadMouseProfile();
		}
		if (GenericClientConfig.GROUP.equals(event.getGroup()) &&
			"mouseEffect".equals(event.getKey()))
		{
			mouseEffectOverlay.clear();
			refreshPanel();
		}
	}

	void printDiagnostics()
	{
		clientThread.invoke(() ->
		{
			Player player = client.getLocalPlayer();
			GenericClientMouseProfile profile = mouseProfile;
			String playerLocation = player == null ? "unavailable" : String.valueOf(player.getWorldLocation());
			String codeSource = getClass().getProtectionDomain().getCodeSource() == null
				? "unknown"
				: String.valueOf(getClass().getProtectionDomain().getCodeSource().getLocation());
			log.info(
				"{} DIAGNOSTICS lifecycle={} gameState={} ticks={} playerLocation={} " +
					"runeliteVersion={} gameRevision={} mouseProfile={} mouseTemplates={} mouseDurationMs={} " +
					"classLoader={} codeSource={} clientThread={} uptime={}",
				LOG_PREFIX,
				lifecycle,
				client.getGameState(),
				tickCount,
				playerLocation,
				RuneLiteProperties.getVersion(),
				client.getRevision(),
				profile == null ? "unavailable" : profile.getProfileId(),
				profile == null ? 0 : profile.getTemplateCount(),
				mouseMoveDurationMillis(),
				getClass().getClassLoader().getClass().getName(),
				codeSource,
				Thread.currentThread().getName(),
				getUptimeText());
			publishResult("DIAGNOSTICS_PRINTED console_and_client_log");
			postChat("GenericClient diagnostics written");
		});
	}

	private void publishResult(String result)
	{
		lastStatus = result;
		log.info("{} {}", LOG_PREFIX, result);
		refreshPanel();
		if (result.startsWith("WALK_CLICK_EXECUTED"))
		{
			postChat("GenericClient clicked a ground tile");
		}
	}

	private void reloadMouseProfile()
	{
		executor.execute(() ->
		{
			try
			{
				loadConfiguredMouseProfile();
				publishResult("MOUSE_PROFILE_LOADED file=" + config.mouseProfileFile() +
					" profile=" + mouseProfile.getProfileId() +
					" templates=" + mouseProfile.getTemplateCount());
			}
			catch (IOException | RuntimeException exception)
			{
				publishResult("MOUSE_PROFILE_LOAD_FAILED file=" + config.mouseProfileFile() +
					" message=" + exception.getMessage());
			}
		});
	}

	private void loadConfiguredMouseProfile() throws IOException
	{
		String configured = config.mouseProfileFile().trim();
		Path name = Path.of(configured).getFileName();
		if (configured.isEmpty() || !name.toString().equals(configured))
		{
			throw new IOException("Mouse profile must be a filename inside " + mouseProfilesDirectory);
		}
		mouseProfile = GenericClientMouseProfile.load(mouseProfilesDirectory.resolve(name));
	}

	private void startMouseRecording()
	{
		try
		{
			mouseRecorder.start();
			publishResult("MOUSE_RECORDING_STARTED");
		}
		catch (RuntimeException exception)
		{
			publishResult("MOUSE_RECORDING_FAILED message=" + exception.getMessage());
		}
	}

	private void stopMouseRecording()
	{
		final GenericClientMouseProfile recorded;
		final String profileId = "recorded-" + Instant.now().toEpochMilli();
		try
		{
			recorded = mouseRecorder.stop(profileId);
			publishResult("MOUSE_RECORDING_STOPPED templates=" + recorded.getTemplateCount());
		}
		catch (RuntimeException exception)
		{
			publishResult("MOUSE_RECORDING_FAILED message=" + exception.getMessage());
			return;
		}

		executor.execute(() ->
		{
			String fileName = profileId + ".json";
			try
			{
				recorded.save(mouseProfilesDirectory.resolve(fileName));
				mouseProfile = recorded;
				configManager.setConfiguration(GenericClientConfig.GROUP, "mouseProfileFile", fileName);
				publishResult("MOUSE_PROFILE_RECORDED file=" + fileName +
					" templates=" + recorded.getTemplateCount());
			}
			catch (IOException exception)
			{
				publishResult("MOUSE_RECORDING_SAVE_FAILED message=" + exception.getMessage());
			}
		});
	}

	private void postChat(String message)
	{
		if (config.chatNotifications() &&
			client.getGameState() == GameState.LOGGED_IN &&
			client.getLocalPlayer() != null)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
		}
	}

	private void activateBehaviorProfile()
	{
		GenericClientBehaviorController behaviors = behaviorController;
		if (behaviors == null)
		{
			return;
		}
		long accountHash = client.getAccountHash();
		if (accountHash == -1L)
		{
			publishResult("BEHAVIOR_PROFILE_WAITING account_hash_unavailable");
			return;
		}
		try
		{
			behaviors.activateAccount(accountHash);
			GenericClientAutomationScheduler automations = automationScheduler;
			if (automations != null)
			{
				automations.activateProfile(
					GenericClientBehaviorProfile.fromAccountHash(accountHash).getId());
			}
		}
		catch (IOException | RuntimeException exception)
		{
			publishResult("BEHAVIOR_PROFILE_FAILED message=" + exception.getMessage());
		}
	}

	private GenericClientDashboardActions dashboardActions()
	{
		return new GenericClientDashboardActions()
		{
			@Override
			public void printDiagnostics()
			{
				GenericClientPlugin.this.printDiagnostics();
			}

			@Override
			public void walkToRandomTile()
			{
				gameInput.walkToRandomTile(GenericClientActivityContext.none());
			}

			@Override
			public void setMouseProfile(String file)
			{
				configManager.setConfiguration(GenericClientConfig.GROUP, "mouseProfileFile", file);
			}

			@Override
			public void setMouseEffect(GenericClientMouseEffect effect)
			{
				configManager.setConfiguration(GenericClientConfig.GROUP, "mouseEffect", effect);
				mouseEffectOverlay.clear();
			}

			@Override
			public void reloadMouseProfile()
			{
				GenericClientPlugin.this.reloadMouseProfile();
			}

			@Override
			public void startMouseRecording()
			{
				GenericClientPlugin.this.startMouseRecording();
			}

			@Override
			public void stopMouseRecording()
			{
				GenericClientPlugin.this.stopMouseRecording();
			}

			@Override
			public String saveBehaviorOverrides(GenericClientBehaviorOverrides overrides)
			{
				try
				{
					behaviorController.saveOverrides(overrides);
					refreshPanel();
					return "Saved";
				}
				catch (IOException | RuntimeException exception)
				{
					return exception.getMessage();
				}
			}

			@Override
			public String resetBehaviorOverrides()
			{
				try
				{
					behaviorController.resetOverrides();
					refreshPanel();
					return "Using seeded profile";
				}
				catch (IOException | RuntimeException exception)
				{
					return exception.getMessage();
				}
			}

			@Override
			public java.util.concurrent.CompletableFuture<String> endLongBreak()
			{
				return behaviorController.endLongBreak().thenApply(receipt ->
				{
					refreshPanel();
					return "ended".equals(receipt.get("status"))
						? "Long break ended"
						: "No long break is active";
				});
			}
		};
	}

	private List<String> listMouseProfiles()
	{
		if (mouseProfilesDirectory == null)
		{
			return Collections.emptyList();
		}
		List<String> files = new ArrayList<>();
		try (java.util.stream.Stream<Path> paths = java.nio.file.Files.list(mouseProfilesDirectory))
		{
			paths.filter(java.nio.file.Files::isRegularFile)
				.map(path -> path.getFileName().toString())
				.filter(name -> name.endsWith(".json"))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.forEach(files::add);
		}
		catch (IOException exception)
		{
			log.warn("Unable to list mouse profiles", exception);
		}
		return files;
	}

	private Map<String, Object> controlStatus()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("protocol", 1L);
		value.put("lifecycle", lifecycle);
		value.put("game_state", gameStateName);
		value.put("last_status", lastStatus);
		GenericClientSnapshot snapshot = latestSnapshot;
		value.put("runtime", snapshot == null ? null : snapshot.read("runtime", null));
		value.put("player", snapshot == null ? null : snapshot.read("player", null));
		value.put("recent_messages",
			snapshot == null
				? new ArrayList<>()
				: snapshot.read("messages", Collections.singletonMap("limit", 20L)));
		GenericClientMouseProfile profile = mouseProfile;
		if (profile != null)
		{
			Map<String, Object> mouse = new LinkedHashMap<>();
			mouse.put("profile", profile.getProfileId());
			mouse.put("templates", (long) profile.getTemplateCount());
			mouse.put("duration_ms", (long) mouseMoveDurationMillis());
			value.put("mouse", mouse);
		}
		GenericClientLuaHost host = luaHost;
		value.put("lua", host == null ? null : host.controlState());
		GenericClientBehaviorController behaviors = behaviorController;
		value.put("behavior", behaviors == null ? null : behaviors.status());
		GenericClientAutomationScheduler automations = automationScheduler;
		value.put("automation", automations == null ? null : automations.status());
		GenericClientEmergencyController emergency = emergencyController;
		value.put("safety", emergency == null ? null : emergency.status());
		GenericClientRandomEventController randomEvents = randomEventController;
		value.put("random_event", randomEvents == null ? null : randomEvents.status());
		GenericClientControlServer bridge = controlServer;
		value.put("control_url", bridge == null ? null : bridge.getUrl());
		return value;
	}

	private void cancelActiveActions(String reason)
	{
		GenericClientGameInput activeGameInput = gameInput;
		if (activeGameInput != null)
		{
			activeGameInput.cancel(reason);
		}
		GenericClientWalker activeWalker = walker;
		if (activeWalker != null)
		{
			activeWalker.cancelActive(reason);
		}
		GenericClientMenuInput activeMenuInput = menuInput;
		if (activeMenuInput != null)
		{
			activeMenuInput.cancel(reason);
		}
		GenericClientCombatInput activeCombatInput = combatInput;
		if (activeCombatInput != null)
		{
			activeCombatInput.cancel(reason);
		}
		GenericClientBankInput activeBankInput = bankInput;
		if (activeBankInput != null)
		{
			activeBankInput.cancel(reason);
		}
		GenericClientGrandExchangeInput activeGrandExchangeInput = grandExchangeInput;
		if (activeGrandExchangeInput != null)
		{
			activeGrandExchangeInput.cancel(reason);
		}
	}

	private java.util.concurrent.CompletableFuture<?> stopForEmergency(String reason)
	{
		cancelActiveActions(reason);
		GenericClientLuaHost host = luaHost;
		return host == null
			? java.util.concurrent.CompletableFuture.completedFuture(null)
			: host.stop();
	}

	private java.util.concurrent.CompletableFuture<?> pauseForEmergency(String reason)
	{
		GenericClientWalker activeWalker = walker;
		if (activeWalker != null)
		{
			activeWalker.pauseActiveInput(reason);
		}
		GenericClientGameInput activeGameInput = gameInput;
		if (activeGameInput != null)
		{
			activeGameInput.cancel(reason);
		}
		GenericClientMenuInput activeMenuInput = menuInput;
		if (activeMenuInput != null)
		{
			activeMenuInput.cancel(reason);
		}
		GenericClientCombatInput activeCombatInput = combatInput;
		if (activeCombatInput != null)
		{
			activeCombatInput.cancel(reason);
		}
		return java.util.concurrent.CompletableFuture.completedFuture(null);
	}

	private java.util.concurrent.CompletableFuture<?> resumeAfterEmergency(String reason)
	{
		GenericClientWalker activeWalker = walker;
		if (activeWalker != null)
		{
			activeWalker.resumeActiveInput(reason);
		}
		return java.util.concurrent.CompletableFuture.completedFuture(null);
	}

	private java.util.concurrent.CompletableFuture<Map<String, Object>> startEmergencyEscape(
		GenericClientEmergencyController.Escape escape)
	{
		GenericClientWalker activeWalker = walker;
		if (activeWalker == null)
		{
			Map<String, Object> receipt = new LinkedHashMap<>();
			receipt.put("status", "rejected");
			receipt.put("result", "walker_unavailable");
			receipt.put("click_count", 0L);
			return java.util.concurrent.CompletableFuture.completedFuture(receipt);
		}
		java.util.concurrent.CompletableFuture<Map<String, Object>> walk = activeWalker.walkTo(
			escape.getDestination(), escape.getWithin(), 300, GenericClientActivityContext.none());
		walk.whenComplete((receipt, error) -> publishResult(error == null
			? "EMERGENCY_ESCAPE_COMPLETED status=" + receipt.get("status")
			: "EMERGENCY_ESCAPE_FAILED message=" + error.getMessage()));
		return walk;
	}

	private String accountNote()
	{
		return configManager.getConfiguration("notes", "notesData");
	}

	private java.util.concurrent.CompletableFuture<String> setAccountNote(String note)
	{
		java.util.concurrent.CompletableFuture<String> completion = new java.util.concurrent.CompletableFuture<>();
		clientThread.invoke(() ->
		{
			configManager.setConfiguration("notes", "notesData", note);
			publishResult("ACCOUNT_NOTE_UPDATED characters=" + note.length());
			completion.complete("ACCOUNT_NOTE_UPDATED");
		});
		return completion;
	}

	private void refreshPanel()
	{
		GenericClientDashboard currentPanel = panel;
		if (currentPanel == null)
		{
			return;
		}
		GenericClientBehaviorController behaviors = behaviorController;
		currentPanel.updateBehaviorState(behaviors == null ? null : behaviors.status());
		GenericClientAutomationScheduler automations = automationScheduler;
		currentPanel.updateAutomationState(automations == null ? null : automations.status());
		GenericClientLuaHost scripts = luaHost;
		if (scripts != null)
		{
			currentPanel.updateLiveState(
				gameStateName,
				scripts.getActiveScript(),
				scripts.getStatus(),
				lastStatus);
			currentPanel.updateLuaState(scripts.getActiveScript(), scripts.getStatus(), scripts.getRecentLogs());
		}
		GenericClientMouseRecorder recorder = mouseRecorder;
		currentPanel.updateMouseState(
			config.mouseProfileFile(),
			listMouseProfiles(),
			config.mouseEffect(),
			recorder != null && recorder.isRecording(),
			recorder == null ? 0 : recorder.getTemplateCount());
	}

	private int mouseMoveDurationMillis()
	{
		GenericClientBehaviorController behaviors = behaviorController;
		return behaviors == null
			? GenericClientBehaviorProfile.DEFAULT_MOUSE_MOVE_DURATION_MILLIS
			: behaviors.mouseMoveDurationMillis();
	}

	private int typingWordsPerMinute()
	{
		GenericClientBehaviorController behaviors = behaviorController;
		return behaviors == null
			? GenericClientBehaviorProfile.DEFAULT_TYPING_WORDS_PER_MINUTE
			: behaviors.typingWordsPerMinute();
	}

	private static BufferedImage createIcon()
	{
		BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = icon.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setColor(new Color(68, 181, 126));
			graphics.fillRoundRect(0, 0, 16, 16, 6, 6);
			graphics.setColor(Color.BLACK);
			graphics.drawString("G", 4, 12);
		}
		finally
		{
			graphics.dispose();
		}
		return icon;
	}

	private String getUptimeText()
	{
		Instant start = startedAt;
		if (start == null)
		{
			return "0s";
		}
		long seconds = Math.max(0, Duration.between(start, Instant.now()).getSeconds());
		return seconds + "s";
	}

	@Provides
	GenericClientConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GenericClientConfig.class);
	}
}
