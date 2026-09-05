package com.genericclient;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Provider;
import net.runelite.api.Client;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

final class GenericClientDesktop implements AutoCloseable
{
	@Inject private Client client;
	@Inject private GenericClientConfig config;
	private GenericClientMouseEffectOverlay mouseEffectOverlay;
	@Inject private Provider<OverlayManager> overlayManagerProvider;
	@Inject private Provider<ClientToolbar> clientToolbarProvider;
	@Inject private MouseManager mouseManager;
	@Inject private ScheduledExecutorService executor;
	private GenericClientScriptHost scriptHost;
	private GenericClientAutomationScheduler automationScheduler;
	private GenericClientBehaviorController behaviorController;
	private GenericClientMouseProfiles mouseProfiles;
	private GenericClientSceneHighlights sceneHighlights;
	private GenericClientDashboard panel;
	private GenericClientBreakOverlay breakOverlay;
	private GenericClientScriptOverlay scriptOverlay;
	private GenericClientScriptPaint scriptPaint;
	private GenericClientSceneOverlay sceneOverlay;
	private OverlayManager presentationOverlayManager;
	private ClientToolbar presentationToolbar;
	private NavigationButton navigationButton;
	private ScheduledFuture<?> panelRefreshFuture;

	void start(GenericClientScriptHost scripts, GenericClientAutomationScheduler automation,
		GenericClientAutomationRuntime runtime, GenericClientMouseProfiles profiles, GenericClientSceneHighlights highlights,
		GenericClientMouseEffectOverlay effects, GenericClientDashboardActions actions, java.util.function.Supplier<String> gameState, java.util.function.Supplier<String> lastStatus)
	{
		scriptHost = scripts;
		mouseEffectOverlay = effects;
		automationScheduler = automation;
		behaviorController = runtime.behaviorController;
		mouseProfiles = profiles;
		sceneHighlights = highlights;

		breakOverlay = new GenericClientBreakOverlay(
			() ->
			{
				GenericClientBehaviorController behaviors = behaviorController;
				return behaviors == null ? null : behaviors.status();
			},
			behaviorController::endActiveBreak);
		scriptOverlay = new GenericClientScriptOverlay(
			scriptHost::getActiveScriptView,
			scriptHost::getActivity,
			scriptHost::getScriptState);
		panel = new GenericClientDashboard(
			javax.swing.SwingUtilities.getWindowAncestor(client.getCanvas()),
			actions,
			scriptHost,
			automationScheduler);
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
		scriptPaint = new GenericClientScriptPaint(scriptHost);
		presentationOverlayManager.add(scriptPaint);
		sceneOverlay = new GenericClientSceneOverlay(client, sceneHighlights::visibleMarkers);
		presentationOverlayManager.add(sceneOverlay);
		presentationToolbar.addNavigation(navigationButton);
		refresh(gameState.get(), lastStatus.get());
		panelRefreshFuture = executor.scheduleAtFixedRate(
			() -> refresh(gameState.get(), lastStatus.get()), 1L, 1L, TimeUnit.SECONDS);
	}

	void refresh(String gameStateName, String lastStatus)
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
		GenericClientScriptHost scripts = scriptHost;
		if (scripts != null)
		{
			currentPanel.updateLiveState(
				gameStateName,
				scripts.getActiveScript(),
				scripts.getStatus(),
				lastStatus);
			currentPanel.updateScriptState(scripts.getActiveScript(), scripts.getStatus(), scripts.getRecentLogs());
		}
		currentPanel.updateMouseState(
			config.mouseProfileFile(),
			mouseProfiles.list(),
			config.mouseEffect(),
			mouseProfiles.isRecording(),
			mouseProfiles.getTemplateCount(),
			config.showMouseTile());
	}

	@Override
	public void close()
	{
		if (panelRefreshFuture != null) panelRefreshFuture.cancel(false);
		if (panel != null) panel.close();
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
		if (scriptPaint != null)
		{
			presentationOverlayManager.remove(scriptPaint);
			scriptPaint = null;
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
}
