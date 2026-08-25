package com.genericclient;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
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
	description = "Logs client state and drives a native ground-tile click",
	tags = {"genericclient", "diagnostics"},
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

	private GenericClientPanel panel;
	private GenericClientGameInput gameInput;
	private NavigationButton navigationButton;

	@Override
	protected void startUp()
	{
		startedAt = Instant.now();
		lifecycle = "RUNNING";
		gameStateName = client.getGameState().name();
		lastStatus = "PLUGIN_STARTED stock RuneLite loaded GenericClient";
		gameInput = new GenericClientGameInput(client, clientThread, executor, this::publishResult);
		panel = new GenericClientPanel(this::printDiagnostics, this::logNearbyNpcs, gameInput::walkToRandomTile);
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
		printDiagnostics();
	}

	@Override
	protected void shutDown()
	{
		lifecycle = "STOPPING";
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
		nearbyNpcCount = countNearbyNpcs(config.npcLogRadius());
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
		Player player = client.getLocalPlayer();
		if (client.getGameState() != GameState.LOGGED_IN || player == null)
		{
			publishResult("NPC_SNAPSHOT_ABORTED player_not_logged_in");
			return;
		}

		WorldPoint playerPoint = player.getWorldLocation();
		WorldView worldView = player.getWorldView();
		List<NPC> nearby = new ArrayList<>();
		for (NPC npc : worldView.npcs())
		{
			if (npc != null && npc.getWorldLocation() != null &&
				playerPoint.distanceTo(npc.getWorldLocation()) <= config.npcLogRadius())
			{
				nearby.add(npc);
			}
		}
		nearby.sort(Comparator.comparingInt(npc -> playerPoint.distanceTo(npc.getWorldLocation())));
		nearbyNpcCount = nearby.size();

		log.info("{} NPC_SNAPSHOT_BEGIN radius={} total={} playerLocation={} worldView={}",
			LOG_PREFIX, config.npcLogRadius(), nearby.size(), playerPoint, worldView.getId());
		StringBuilder panelOutput = new StringBuilder();
		panelOutput.append("radius=").append(config.npcLogRadius())
			.append(" total=").append(nearby.size())
			.append("\nplayer=").append(playerPoint)
			.append("\n\n");

		int logged = Math.min(nearby.size(), NPC_LOG_LIMIT);
		for (int i = 0; i < logged; i++)
		{
			NPC npc = nearby.get(i);
			WorldPoint location = npc.getWorldLocation();
			int distance = playerPoint.distanceTo(location);
			String name = Objects.toString(npc.getName(), "<unnamed>");
			String actions = getActions(npc);
			log.info(
				"{} NPC index={} id={} name={} location={} distance={} combatLevel={} animation={} interacting={} actions={}",
				LOG_PREFIX,
				npc.getIndex(),
				npc.getId(),
				name,
				location,
				distance,
				npc.getCombatLevel(),
				npc.getAnimation(),
				npc.getInteracting() == null ? "none" : npc.getInteracting().getName(),
				actions);
			panelOutput.append(String.format(
				"%02d %-18s id=%d idx=%d d=%d\n    at=%s combat=%d\n    actions=%s\n",
				i + 1,
				name,
				npc.getId(),
				npc.getIndex(),
				distance,
				location,
				npc.getCombatLevel(),
				actions));
		}
		if (nearby.size() > logged)
		{
			panelOutput.append("\n...").append(nearby.size() - logged).append(" additional NPCs omitted");
		}

		log.info("{} NPC_SNAPSHOT_END logged={} total={}", LOG_PREFIX, logged, nearby.size());
		GenericClientPanel currentPanel = panel;
		if (currentPanel != null)
		{
			currentPanel.updateNpcDiagnostics(panelOutput.toString());
		}
		publishResult("NPC_SNAPSHOT_WRITTEN logged=" + logged + " total=" + nearby.size());
		postChat("GenericClient logged " + logged + " nearby NPCs");
	}

	private int countNearbyNpcs(int radius)
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return 0;
		}
		WorldPoint playerPoint = player.getWorldLocation();
		int count = 0;
		for (NPC npc : player.getWorldView().npcs())
		{
			if (npc != null && npc.getWorldLocation() != null &&
				playerPoint.distanceTo(npc.getWorldLocation()) <= radius)
			{
				count++;
			}
		}
		return count;
	}

	private static String getActions(NPC npc)
	{
		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null)
		{
			composition = npc.getComposition();
		}
		if (composition == null || composition.getActions() == null)
		{
			return "[]";
		}
		return Arrays.stream(composition.getActions())
			.filter(Objects::nonNull)
			.collect(Collectors.joining(", ", "[", "]"));
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
		if (config.chatNotifications() && client.getGameState() == GameState.LOGGED_IN)
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

	@Provides
	GenericClientConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GenericClientConfig.class);
	}
}
