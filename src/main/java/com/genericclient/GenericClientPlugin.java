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
import javax.inject.Provider;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.gameval.VarPlayerID;
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
import net.runelite.client.input.KeyManager;
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
	private Provider<OverlayManager> overlayManagerProvider;

	@Inject
	private Provider<ClientToolbar> clientToolbarProvider;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ConfigManager configManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private Provider<Notifier> notifierProvider;

	private volatile String lifecycle = "CREATED";
	private volatile String gameStateName = "UNKNOWN";
	private volatile String lastStatus = "Plugin instance created";
	private volatile long tickCount;
	private volatile Instant startedAt;
	private boolean loginMessageShown;

	private GenericClientDashboard panel;
	private GenericClientBreakOverlay breakOverlay;
	private GenericClientScriptOverlay scriptOverlay;
	private GenericClientSceneOverlay sceneOverlay;
	private GenericClientSceneHighlights sceneHighlights;
	private GenericClientControlServer controlServer;
	private GenericClientMouseRecorder mouseRecorder;
	private GenericClientScreenshot screenshot;
	private GenericClientDeathForensics deathForensics;
	private GenericClientRuntimeOptions runtimeOptions;
	private GenericClientInstanceRegistration instanceRegistration;
	private OverlayManager presentationOverlayManager;
	private ClientToolbar presentationToolbar;
	private NavigationButton navigationButton;
	private Path mouseProfilesDirectory;
	private volatile GenericClientMouseProfile mouseProfile;
	private volatile GenericClientAutomationRuntime runtime;
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
		runtimeOptions = GenericClientRuntimeOptions.load(
			config.controlPort(), net.runelite.client.RuneLite.RUNELITE_DIR.toPath());
		instanceRegistration = GenericClientInstanceRegistration.create(runtimeOptions);
		lastStatus = runtimeOptions.isDense()
			? "PLUGIN_STARTED dense RuneLite loaded GenericClient"
			: "PLUGIN_STARTED stock RuneLite loaded GenericClient";
		mouseProfilesDirectory = net.runelite.client.RuneLite.RUNELITE_DIR.toPath()
			.resolve("genericclient")
			.resolve("mouse-profiles");
		GenericClientMouseProfile.installDefault(mouseProfilesDirectory);
		loadConfiguredMouseProfile();
		GenericClientCollisionMap collisionMap = GenericClientCollisionMap.loadBundled();
		if (runtimeOptions.isDense())
		{
			client.changeMemoryMode(true);
			client.setUnlockedFps(true);
			client.setUnlockedFpsTarget(1);
		}
		runtime = new GenericClientAutomationRuntime(
			client, clientThread, executor, keyManager,
			net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("genericclient"),
			collisionMap, () -> mouseProfile, mouseEffectOverlay,
			message ->
			{
				if (runtimeOptions.isPresentationEnabled()) notifierProvider.get().notify(message);
				postChat(message);
			}, this::publishResult);
		mouseRecorder = new GenericClientMouseRecorder(client.getCanvas(), runtime::activeClientInput);
		sceneHighlights = new GenericClientSceneHighlights(
			runtime.luaHost::getSceneMarkers,
			runtime.syntheticMouse::isMoving);
		sceneHighlights.setShowMouseTile(config.showMouseTile());
		screenshot = new GenericClientScreenshot(drawManager, executor);
		deathForensics = new GenericClientDeathForensics(
			net.runelite.client.RuneLite.RUNELITE_DIR.toPath()
				.resolve("genericclient")
				.resolve("forensics"),
			screenshot::capture,
			this::publishResult);
		controlServer = new GenericClientControlServer(
			runtimeOptions.getControlPort(),
			runtime.luaHost,
			runtime.automationScheduler,
			runtime.randomEventController,
			runtime.sessionController::logout,
			runtime.sessionController::ensureLoggedIn,
			this::controlStatus,
			this::accountNote,
			this::setAccountNote,
			screenshot::capture,
			runtime.behaviorController::endActiveBreak,
			sceneHighlights,
			this::publishResult);
		controlServer.setHealthSupplier(this::instanceHealth);
		controlServer.start();
		publishInitialInstanceDescriptor();
		if (runtimeOptions.isPresentationEnabled())
		{
			breakOverlay = new GenericClientBreakOverlay(
				runtime.behaviorController::status,
				runtime.behaviorController::endActiveBreak);
			scriptOverlay = new GenericClientScriptOverlay(
				runtime.luaHost::getActiveScriptView,
				runtime.luaHost::getActivity,
				runtime.luaHost::getScriptState);
			panel = new GenericClientDashboard(
				javax.swing.SwingUtilities.getWindowAncestor(client.getCanvas()),
				dashboardActions(),
				runtime.luaHost,
				runtime.automationScheduler);
			navigationButton = NavigationButton.builder()
				.tooltip("GenericClient")
				.icon(createIcon())
				.priority(1)
				.onClick(panel::open)
				.build();

			presentationOverlayManager = overlayManagerProvider.get();
			presentationToolbar = clientToolbarProvider.get();
			presentationOverlayManager.add(mouseEffectOverlay);
			presentationOverlayManager.add(breakOverlay);
			mouseManager.registerMouseListener(breakOverlay.getMouseListener());
			presentationOverlayManager.add(scriptOverlay);
			sceneOverlay = new GenericClientSceneOverlay(client, sceneHighlights::visibleMarkers);
			presentationOverlayManager.add(sceneOverlay);
			presentationToolbar.addNavigation(navigationButton);
			refreshPanel();
			panelRefreshFuture = executor.scheduleAtFixedRate(
				this::refreshPanel, 1L, 1L, TimeUnit.SECONDS);
		}

		log.info("{} PLUGIN_STARTED runeliteVersion={} classLoader={} thread={}",
			LOG_PREFIX,
			RuneLiteProperties.getVersion(),
			getClass().getClassLoader().getClass().getName(),
			Thread.currentThread().getName());
		log.info("{} COLLISION_MAP_LOADED regions={} revision={} sha256={} cache={} gameRevision={} " +
			"doorRegions={} doorDumperRevision={} doorRuneLiteRevision={} " +
			"doorCache={} doorGameRevision={} doorSha256={}",
			LOG_PREFIX,
			collisionMap.getRegionCount(),
			GenericClientCollisionMap.SOURCE_REVISION,
			GenericClientCollisionMap.SOURCE_SHA256,
			GenericClientCollisionMap.SOURCE_CACHE_ID,
			GenericClientCollisionMap.SOURCE_GAME_REVISION,
			collisionMap.getDoorRegionCount(),
			GenericClientCollisionMap.DOOR_DUMPER_REVISION,
			GenericClientCollisionMap.DOOR_RUNELITE_REVISION,
			GenericClientCollisionMap.DOOR_SOURCE_CACHE_ID,
			GenericClientCollisionMap.DOOR_SOURCE_GAME_REVISION,
			GenericClientCollisionMap.DOOR_SOURCE_SHA256);
		GenericClientCollisionMap.reportRevisionDrift(client.getRevision(), message -> log.warn("{} {}", LOG_PREFIX, message));
		log.info("{} MOUSE_PROFILE_LOADED file={} profile={} templates={}",
			LOG_PREFIX,
			config.mouseProfileFile(),
			mouseProfile.getProfileId(),
			mouseProfile.getTemplateCount());
		printDiagnostics();
		runtime.behaviorController.setLoggedIn(client.getGameState() == GameState.LOGGED_IN);
		if (client.getGameState() == GameState.LOGGED_IN) runtime.activateBehaviorProfile();
	}

	@Override
	protected void shutDown()
	{
		lifecycle = "STOPPING";
		closeRuntimeServices();
		removePluginUi();
		lifecycle = "STOPPED";
		log.info("{} PLUGIN_STOPPED ticks={} uptime={}", LOG_PREFIX, tickCount, getUptimeText());
	}

	private void closeRuntimeServices()
	{
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
		if (instanceRegistration != null)
		{
			try
			{
				instanceRegistration.close();
			}
			catch (IOException exception)
			{
				log.warn("Unable to remove GenericClient instance descriptor", exception);
			}
			instanceRegistration = null;
		}
		if (screenshot != null)
		{
			screenshot.close();
			screenshot = null;
		}
		deathForensics = null;
		if (runtime != null)
		{
			runtime.close();
			runtime = null;
		}
		if (mouseRecorder != null)
		{
			mouseRecorder.close();
			mouseRecorder = null;
		}
	}

	private void removePluginUi()
	{
		if (presentationOverlayManager != null)
		{
			presentationOverlayManager.remove(mouseEffectOverlay);
		}
		if (breakOverlay != null)
		{
			mouseManager.unregisterMouseListener(breakOverlay.getMouseListener());
			if (presentationOverlayManager != null)
			{
				presentationOverlayManager.remove(breakOverlay);
			}
			breakOverlay = null;
		}
		if (scriptOverlay != null)
		{
			if (presentationOverlayManager != null)
			{
				presentationOverlayManager.remove(scriptOverlay);
			}
			scriptOverlay = null;
		}
		if (sceneOverlay != null)
		{
			if (presentationOverlayManager != null) presentationOverlayManager.remove(sceneOverlay);
			sceneOverlay = null;
		}
		sceneHighlights = null;
		if (navigationButton != null)
		{
			if (presentationToolbar != null)
			{
				presentationToolbar.removeNavigation(navigationButton);
			}
			navigationButton = null;
		}
		presentationOverlayManager = null;
		presentationToolbar = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		gameStateName = event.getGameState().name();
		GenericClientAutomationRuntime active = runtime;
		if (active != null) active.onGameStateChanged(event.getGameState());
		if (event.getGameState() == GameState.LOGIN_SCREEN) loginMessageShown = false;
		publishResult("GAME_STATE_CHANGED state=" + gameStateName);
		publishInstanceDescriptor();
		if (event.getGameState() == GameState.LOGGED_IN && !loginMessageShown)
		{
			loginMessageShown = true;
			postChat("GenericClient loaded");
		}
	}

	@Subscribe
	public void onAccountHashChanged(AccountHashChanged event)
	{
		bankCache.clear();
		questCache.clear();
		gameMessages.clear();
		GenericClientAutomationRuntime active = runtime;
		if (active != null) active.onAccountHashChanged();
		if (deathForensics != null) deathForensics.reset();
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		gameMessages.add(tickCount, event);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		GenericClientAutomationRuntime active = runtime;
		if (active == null) return;
		tickCount++;
		GenericClientSnapshot snapshot = GenericClientSnapshot.capture(
			client, tickCount, bankCache, questCache, gameMessages.snapshot());
		active.publishGameTick(snapshot);
		GenericClientDeathForensics forensics = deathForensics;
		if (forensics != null)
		{
			forensics.record(
				snapshot,
				deathForensicContext(),
				client.getVarpValue(VarPlayerID.POISON));
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
	public void onHitsplatApplied(net.runelite.api.events.HitsplatApplied event)
	{
		GenericClientAutomationRuntime active = runtime;
		if (active != null) active.recordHitsplat(event);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		GenericClientAutomationRuntime active = runtime;
		if (active != null) active.inputs.onMenuOptionClicked(event);
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		GenericClientAutomationRuntime active = runtime;
		if (active != null)
		{
			active.randomEventController.onInteractingChanged(client.getLocalPlayer(), event, tickCount);
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		GenericClientAutomationRuntime active = runtime;
		if (active != null)
		{
			active.randomEventController.onNpcDespawned(event, tickCount);
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
		if (GenericClientConfig.GROUP.equals(event.getGroup()) &&
			"showMouseTile".equals(event.getKey()) && sceneHighlights != null)
		{
			sceneHighlights.setShowMouseTile(config.showMouseTile());
			refreshPanel();
		}
	}

	void printDiagnostics()
	{
		GenericClientAutomationRuntime active = runtime;
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
				active == null ? 0 : active.mouseMoveDurationMillis(),
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

	private GenericClientDashboardActions dashboardActions()
	{
		GenericClientAutomationRuntime active = runtime;
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
				active.inputs.gameInput.walkToRandomTile(GenericClientActivityContext.none());
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
			public void setShowMouseTile(boolean enabled)
			{
				configManager.setConfiguration(GenericClientConfig.GROUP, "showMouseTile", enabled);
				if (sceneHighlights != null)
				{
					sceneHighlights.setShowMouseTile(enabled);
				}
				refreshPanel();
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
					active.behaviorController.saveOverrides(overrides);
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
					active.behaviorController.resetOverrides();
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
				return active.behaviorController.endLongBreak().thenApply(receipt ->
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

	private Map<String, Object> deathForensicContext()
	{
		GenericClientAutomationRuntime active = runtime;
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("last_status", lastStatus);
		GenericClientLuaHost host = active == null ? null : active.luaHost;
		if (host != null)
		{
			GenericClientActiveScript script = host.getActiveScriptView();
			value.put("active_script", script.isPresent() ? script.getId() : null);
			value.put("script_status", script.isPresent() ? script.getStatus() : "IDLE");
			value.put("script_state", host.getScriptState());
			value.put("activity", host.getActivity());
			value.put("declared_activity", host.getActivity());
		}
		GenericClientCombatGuard guard = active == null ? null : active.combatGuard;
		value.put("combat_guard", guard == null ? null : guard.status());
		GenericClientEmergencyController emergency = active == null ? null : active.emergencyController;
		value.put("safety", emergency == null ? null : emergency.status());
		GenericClientBehaviorController behaviors = active == null ? null : active.behaviorController;
		if (behaviors != null)
		{
			Map<String, Object> status = behaviors.status();
			Map<String, Object> behavior = new LinkedHashMap<>();
			behavior.put("state", status.get("state"));
			behavior.put("long_break_mode", status.get("long_break_mode"));
			behavior.put("break_remaining_millis", status.get("break_remaining_millis"));
			behavior.put("effective_policy", status.get("effective_policy"));
			behavior.put("policy_reasons", status.get("policy_reasons"));
			value.put("behavior", behavior);
		}
		GenericClientRandomEventController randomEvents = active == null ? null : active.randomEventController;
		if (randomEvents != null)
		{
			Map<String, Object> status = randomEvents.status();
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("state", status.get("state"));
			event.put("active", status.get("active"));
			event.put("npc_id", status.get("npc_id"));
			event.put("npc_name", status.get("npc_name"));
			value.put("random_event", event);
		}
		return value;
	}

	private Map<String, Object> controlStatus()
	{
		GenericClientAutomationRuntime active = runtime;
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("protocol", 1L);
		value.put("lifecycle", lifecycle);
		value.put("game_state", gameStateName);
		value.put("last_status", lastStatus);
		value.put("instance", instanceHealth());
		GenericClientSnapshot snapshot = active == null ? null : active.latestSnapshot;
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
			mouse.put("duration_ms", active == null ? 0L : (long) active.mouseMoveDurationMillis());
			GenericClientManualTakeover takeover = active == null ? null : active.manualTakeover;
			mouse.put("manual_takeover", takeover != null && takeover.isActive());
			value.put("mouse", mouse);
		}
		for (String subject : List.of("lua", "behavior", "automation", "safety", "combat_guard", "random_event"))
		{
			value.put(subject, null);
		}
		if (active != null) value.putAll(active.status());
		GenericClientDeathForensics forensics = deathForensics;
		value.put("death_forensics", forensics == null ? null : forensics.status());
		GenericClientControlServer bridge = controlServer;
		value.put("control_url", bridge == null ? null : bridge.getUrl());
		return value;
	}

	private Map<String, Object> instanceHealth()
	{
		GenericClientInstanceRegistration registration = instanceRegistration;
		Map<String, Object> value = registration == null
			? new LinkedHashMap<>()
			: registration.metadata();
		value.put("lifecycle", descriptorLifecycle());
		value.put("game_state", gameStateName);
		value.put("dense", runtimeOptions != null && runtimeOptions.isDense());
		GenericClientControlServer bridge = controlServer;
		value.put("control_url", bridge == null ? null : bridge.getUrl());
		return value;
	}

	private void publishInstanceDescriptor()
	{
		GenericClientInstanceRegistration registration = instanceRegistration;
		GenericClientControlServer bridge = controlServer;
		if (registration == null || bridge == null)
		{
			return;
		}
		try
		{
			registration.publish(
				bridge.getUrl(),
				descriptorLifecycle(),
				client.getLauncherDisplayName(),
				activeAccountProfileId());
		}
		catch (IOException | RuntimeException exception)
		{
			log.warn("Unable to publish GenericClient instance descriptor", exception);
		}
	}

	private void publishInitialInstanceDescriptor() throws IOException
	{
		instanceRegistration.publish(
			controlServer.getUrl(),
			descriptorLifecycle(),
			client.getLauncherDisplayName(),
			activeAccountProfileId());
	}

	private String descriptorLifecycle()
	{
		if ("STOPPING".equals(lifecycle) || "STOPPED".equals(lifecycle))
		{
			return lifecycle.toLowerCase(java.util.Locale.ROOT);
		}
		return gameStateName == null
			? lifecycle.toLowerCase(java.util.Locale.ROOT)
			: gameStateName.toLowerCase(java.util.Locale.ROOT);
	}

	@SuppressWarnings("unchecked")
	private String activeAccountProfileId()
	{
		GenericClientAutomationRuntime active = runtime;
		GenericClientBehaviorController behaviors = active == null ? null : active.behaviorController;
		if (behaviors == null)
		{
			return null;
		}
		Object profile = behaviors.status().get("profile");
		if (!(profile instanceof Map))
		{
			return null;
		}
		Object id = ((Map<String, Object>) profile).get("id");
		return id == null ? null : String.valueOf(id);
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
		GenericClientAutomationRuntime active = runtime;
		GenericClientDashboard currentPanel = panel;
		if (currentPanel == null)
		{
			return;
		}
		GenericClientBehaviorController behaviors = active == null ? null : active.behaviorController;
		currentPanel.updateBehaviorState(behaviors == null ? null : behaviors.status());
		GenericClientAutomationScheduler automations = active == null ? null : active.automationScheduler;
		currentPanel.updateAutomationState(automations == null ? null : automations.status());
		GenericClientLuaHost scripts = active == null ? null : active.luaHost;
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
			recorder == null ? 0 : recorder.getTemplateCount(),
			config.showMouseTile());
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
