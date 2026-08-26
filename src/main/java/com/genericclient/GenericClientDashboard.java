package com.genericclient;

import java.awt.BorderLayout;
import java.awt.Color;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

final class GenericClientDashboard extends PluginPanel
{
	private final JLabel status = GenericClientDashboardStyle.muted("Starting");
	private final GenericClientScriptsPanel scripts;
	private final GenericClientConsolePanel console;
	private final GenericClientSettingsPanel settings;

	GenericClientDashboard(GenericClientDashboardActions actions, GenericClientLuaHost host)
	{
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JLabel title = new JLabel("GenericClient");
		title.setForeground(Color.WHITE);
		title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
		header.add(title, BorderLayout.WEST);
		header.add(status, BorderLayout.SOUTH);
		add(header, BorderLayout.NORTH);

		scripts = new GenericClientScriptsPanel(host);
		console = new GenericClientConsolePanel(actions, host);
		settings = new GenericClientSettingsPanel(actions);
		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("Scripts", scripts);
		tabs.addTab("Console", console);
		tabs.addTab("Settings", settings);
		add(tabs, BorderLayout.CENTER);
	}

	void updateLiveState(String gameState, String activeScript, String scriptStatus, String lastResult)
	{
		runOnEdt(() ->
		{
			status.setText(gameState + " · " + activeScript + " " + scriptStatus);
			console.updateLastResult(lastResult);
		});
	}

	void updateNpcDiagnostics(String diagnostics)
	{
		runOnEdt(() -> console.updateNpcDiagnostics(diagnostics));
	}

	void updateLuaState(String activeScript, String scriptStatus, String logs)
	{
		runOnEdt(() -> scripts.update(activeScript, scriptStatus, logs));
	}

	void updateMouseState(
		String currentProfile,
		List<String> profiles,
		int durationMillis,
		GenericClientMouseEffect effect,
		boolean recording,
		int recordedTemplates)
	{
		runOnEdt(() -> settings.updateMouse(
			currentProfile,
			profiles,
			durationMillis,
			effect,
			recording,
			recordedTemplates));
	}

	void updateBehaviorState(Map<String, Object> behavior)
	{
		runOnEdt(() -> settings.updateBehavior(behavior));
	}

	private static void runOnEdt(Runnable runnable)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			runnable.run();
		}
		else
		{
			SwingUtilities.invokeLater(runnable);
		}
	}
}
