package com.genericclient;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

final class GenericClientPanel extends PluginPanel
{
	private final JLabel lifecycleValue = valueLabel("Starting");
	private final JLabel gameStateValue = valueLabel("UNKNOWN");
	private final JLabel tickValue = valueLabel("0");
	private final JLabel npcValue = valueLabel("0");
	private final JLabel statusValue = valueLabel("Waiting for plugin startup");
	private final JTextArea npcDiagnostics = new JTextArea("No NPC snapshot captured yet.");
	private final JLabel luaScriptValue = valueLabel("none");
	private final JLabel luaStatusValue = valueLabel("IDLE");
	private final JComboBox<String> luaScripts = new JComboBox<>();
	private final JTextArea luaLogs = new JTextArea("No Lua output yet.");
	private final GenericClientLuaHost luaHost;

	GenericClientPanel(
		Runnable printDiagnostics,
		Runnable logNearbyNpcs,
		Runnable walkToRandomTile,
		GenericClientLuaHost luaHost)
	{
		this.luaHost = luaHost;
		JLabel title = new JLabel("<html><b>GenericClient</b></html>");
		title.setForeground(Color.WHITE);
		add(title);

		JLabel explanation = new JLabel("<html>Client diagnostics and Lua scripts.</html>");
		explanation.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		add(explanation);

		JPanel state = section("Live state");
		state.add(row("Lifecycle", lifecycleValue));
		state.add(row("Game state", gameStateValue));
		state.add(row("Game ticks", tickValue));
		state.add(row("Nearby NPCs", npcValue));
		state.add(row("Last result", statusValue));
		add(state);

		JButton diagnosticsButton = new JButton("Print diagnostics");
		diagnosticsButton.addActionListener(event -> printDiagnostics.run());
		add(diagnosticsButton);

		JButton npcButton = new JButton("Log nearby NPCs");
		npcButton.addActionListener(event -> logNearbyNpcs.run());
		add(npcButton);

		JButton clickButton = new JButton("Walk to random tile");
		clickButton.addActionListener(event -> walkToRandomTile.run());
		add(clickButton);

		JPanel luaSection = section("Lua scripts");
		luaSection.add(row("Active", luaScriptValue));
		luaSection.add(row("Status", luaStatusValue));
		luaSection.add(luaScripts);

		JPanel luaButtons = new JPanel(new GridLayout(2, 2, 4, 4));
		luaButtons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JButton scanButton = new JButton("Scan");
		scanButton.addActionListener(event -> refreshScripts());
		luaButtons.add(scanButton);
		JButton startButton = new JButton("Start");
		startButton.addActionListener(event ->
		{
			Object selection = luaScripts.getSelectedItem();
			if (selection != null)
			{
				luaHost.start(selection.toString());
			}
		});
		luaButtons.add(startButton);
		JButton reloadButton = new JButton("Reload");
		reloadButton.addActionListener(event -> luaHost.reload());
		luaButtons.add(reloadButton);
		JButton stopButton = new JButton("Stop");
		stopButton.addActionListener(event -> luaHost.stop());
		luaButtons.add(stopButton);
		luaSection.add(luaButtons);

		luaLogs.setEditable(false);
		luaLogs.setLineWrap(false);
		luaLogs.setRows(8);
		luaLogs.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		luaLogs.setForeground(Color.WHITE);
		luaLogs.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11));
		JScrollPane luaScrollPane = new JScrollPane(luaLogs);
		luaScrollPane.setPreferredSize(new Dimension(205, 150));
		luaSection.add(luaScrollPane);
		add(luaSection);
		refreshScripts();

		JPanel npcSection = section("Latest NPC snapshot");
		npcDiagnostics.setEditable(false);
		npcDiagnostics.setLineWrap(false);
		npcDiagnostics.setRows(12);
		npcDiagnostics.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		npcDiagnostics.setForeground(Color.WHITE);
		npcDiagnostics.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11));
		JScrollPane scrollPane = new JScrollPane(npcDiagnostics);
		scrollPane.setPreferredSize(new Dimension(205, 220));
		npcSection.add(scrollPane, BorderLayout.CENTER);
		add(npcSection);
	}

	void updateLiveState(String lifecycle, String gameState, long ticks, int nearbyNpcs, String status)
	{
		runOnEdt(() ->
		{
			lifecycleValue.setText(lifecycle);
			gameStateValue.setText(gameState);
			tickValue.setText(Long.toString(ticks));
			npcValue.setText(Integer.toString(nearbyNpcs));
			statusValue.setText("<html>" + escape(status) + "</html>");
		});
	}

	void updateNpcDiagnostics(String diagnostics)
	{
		runOnEdt(() ->
		{
			npcDiagnostics.setText(diagnostics);
			npcDiagnostics.setCaretPosition(0);
		});
	}

	void updateLuaState(String script, String status, String logs)
	{
		runOnEdt(() ->
		{
			luaScriptValue.setText(script);
			luaStatusValue.setText(status);
			luaLogs.setText(logs.isEmpty() ? "No Lua output yet." : logs);
			luaLogs.setCaretPosition(luaLogs.getDocument().getLength());
		});
	}

	private void refreshScripts()
	{
		Object selected = luaScripts.getSelectedItem();
		luaScripts.removeAllItems();
		for (String script : luaHost.listScripts())
		{
			luaScripts.addItem(script);
		}
		if (selected != null)
		{
			luaScripts.setSelectedItem(selected);
		}
		if (luaScripts.getSelectedItem() == null && luaScripts.getItemCount() > 0)
		{
			luaScripts.setSelectedIndex(0);
		}
	}

	private static JPanel section(String title)
	{
		JPanel panel = new JPanel(new GridLayout(0, 1, 0, 4));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			title,
			0,
			0,
			null,
			Color.WHITE));
		return panel;
	}

	private static JPanel row(String name, JLabel value)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel key = new JLabel(name + ":");
		key.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(key, BorderLayout.WEST);
		row.add(value, BorderLayout.CENTER);
		return row;
	}

	private static JLabel valueLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(Color.WHITE);
		return label;
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

	private static String escape(String value)
	{
		return value
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;");
	}
}
