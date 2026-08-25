package com.genericclient;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "GenericClient",
	description = "Runs Lua scripts over client snapshots and native game actions",
	tags = {"genericclient", "diagnostics", "lua", "scripting"},
	loadInSafeMode = false
)
public final class GenericClientPlugin extends Plugin
{
	private static final String LOG_PREFIX = "[GenericClient]";
	private static final int NPC_LOG_LIMIT = 25;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GenericClientConfig config;

	@Inject
	private GenericClientOverlay overlay;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ScheduledExecutorService executor;

	private volatile String lifecycle = "CREATED";
	private volatile String gameStateName = "UNKNOWN";
	private volatile String lastStatus = "Plugin instance created";
	private volatile long tickCount;
	private volatile int nearbyNpcCount;
	private volatile Instant startedAt;
	private boolean initialNpcSnapshotLogged;

	private GenericClientPanel panel;
	private GenericClientGameInput gameInput;
	private GenericClientWalker walker;
	private GenericClientLuaHost luaHost;
	private NavigationButton navigationButton;

	@Override
	protected void startUp() throws Exception
	{
		startedAt = Instant.now();
		lifecycle = "RUNNING";
		gameStateName = client.getGameState().name();
		lastStatus = "PLUGIN_STARTED stock RuneLite loaded GenericClient";
		initialNpcSnapshotLogged = false;
		GenericClientCollisionMap collisionMap = GenericClientCollisionMap.loadBundled();
		gameInput = new GenericClientGameInput(client, clientThread, executor, this::publishResult);
		walker = new GenericClientWalker(gameInput, collisionMap, this::publishResult);
		luaHost = new GenericClientLuaHost(
			net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("genericclient").resolve("scripts"),
			gameInput::walkToRandomTile,
			walker::walkTo,
			walker::cancelActive,
			this::publishResult);
		panel = new GenericClientPanel(
			this::printDiagnostics,
			this::logNearbyNpcs,
			() -> gameInput.walkToRandomTile(),
			luaHost);
		navigationButton = NavigationButton.builder()
			.tooltip("GenericClient")
			.icon(createIcon())
			.priority(1)
			.panel(panel)
			.build();

		overlayManager.add(overlay);
		clientToolbar.addNavigation(navigationButton);
		refreshPanel();

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
		printDiagnostics();
		luaHost.start(GenericClientLuaHost.DIAGNOSTIC_SCRIPT);
	}

	@Override
	protected void shutDown()
	{
		lifecycle = "STOPPING";
		if (luaHost != null)
		{
			luaHost.close();
			luaHost = null;
		}
		if (walker != null)
		{
			walker.close();
			walker = null;
		}
		if (gameInput != null)
		{
			gameInput.close();
			gameInput = null;
		}
		overlayManager.remove(overlay);
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
		publishResult("GAME_STATE_CHANGED state=" + gameStateName);
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			postChat("GenericClient loaded");
			logNearbyNpcsOnClientThread();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickCount++;
		GenericClientSnapshot snapshot = GenericClientSnapshot.capture(client, tickCount);
		nearbyNpcCount = snapshot.countNearbyNpcs(config.npcLogRadius());
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
		if (!initialNpcSnapshotLogged && client.getLocalPlayer() != null)
		{
			logNearbyNpcsOnClientThread();
		}
		if (tickCount == 1)
		{
			log.info("{} FIRST_GAME_TICK state={} nearbyNpcCount={}",
				LOG_PREFIX, client.getGameState(), nearbyNpcCount);
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
	}

	void printDiagnostics()
	{
		clientThread.invoke(() ->
		{
			Player player = client.getLocalPlayer();
			String playerLocation = player == null ? "unavailable" : String.valueOf(player.getWorldLocation());
			String codeSource = getClass().getProtectionDomain().getCodeSource() == null
				? "unknown"
				: String.valueOf(getClass().getProtectionDomain().getCodeSource().getLocation());
			log.info(
				"{} DIAGNOSTICS lifecycle={} gameState={} ticks={} nearbyNpcs={} playerLocation={} " +
					"runeliteVersion={} gameRevision={} classLoader={} codeSource={} clientThread={} uptime={}",
				LOG_PREFIX,
				lifecycle,
				client.getGameState(),
				tickCount,
				nearbyNpcCount,
				playerLocation,
				RuneLiteProperties.getVersion(),
				client.getRevision(),
				getClass().getClassLoader().getClass().getName(),
				codeSource,
				Thread.currentThread().getName(),
				getUptimeText());
			publishResult("DIAGNOSTICS_PRINTED console_and_client_log");
			postChat("GenericClient diagnostics written");
		});
	}

	void logNearbyNpcs()
	{
		clientThread.invoke(this::logNearbyNpcsOnClientThread);
	}

	private void logNearbyNpcsOnClientThread()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			publishResult("NPC_SNAPSHOT_ABORTED player_not_logged_in");
			return;
		}

		GenericClientSnapshot snapshot = GenericClientSnapshot.capture(client, tickCount);
		initialNpcSnapshotLogged = true;
		nearbyNpcCount = snapshot.countNearbyNpcs(config.npcLogRadius());
		int logged = Math.min(nearbyNpcCount, NPC_LOG_LIMIT);
		String panelOutput = snapshot.formatNpcDiagnostics(config.npcLogRadius(), NPC_LOG_LIMIT);
		log.info("{} NPC_SNAPSHOT\n{}", LOG_PREFIX, panelOutput);
		GenericClientPanel currentPanel = panel;
		if (currentPanel != null)
		{
			currentPanel.updateNpcDiagnostics(panelOutput);
		}
		publishResult("NPC_SNAPSHOT_WRITTEN logged=" + logged + " total=" + nearbyNpcCount);
		postChat("GenericClient logged " + logged + " nearby NPCs");
	}

	private void publishResult(String result)
	{
		lastStatus = result;
		log.info("{} {}", LOG_PREFIX, result);
		refreshPanel();
		if (result.startsWith("WALK_CLICK_EXECUTED"))
		{
			postChat("GenericClient clicked a ground tile");
			logNearbyNpcs();
		}
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

	private void refreshPanel()
	{
		GenericClientPanel currentPanel = panel;
		if (currentPanel == null)
		{
			return;
		}
		currentPanel.updateLiveState(lifecycle, gameStateName, tickCount, nearbyNpcCount, lastStatus);
		GenericClientLuaHost scripts = luaHost;
		if (scripts != null)
		{
			currentPanel.updateLuaState(scripts.getActiveScript(), scripts.getStatus(), scripts.getRecentLogs());
		}
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

	String getGameStateName()
	{
		return gameStateName;
	}

	long getTickCount()
	{
		return tickCount;
	}

	int getNearbyNpcCount()
	{
		return nearbyNpcCount;
	}

	String getLastStatus()
	{
		return lastStatus;
	}

	String getLuaStatus()
	{
		GenericClientLuaHost scripts = luaHost;
		return scripts == null ? "IDLE" : scripts.getStatus();
	}

	String getLuaScript()
	{
		GenericClientLuaHost scripts = luaHost;
		return scripts == null ? "none" : scripts.getActiveScript();
	}

	@Provides
	GenericClientConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GenericClientConfig.class);
	}
}
