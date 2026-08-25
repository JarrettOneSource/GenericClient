package com.genericclient;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
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

	GenericClientPanel(Runnable printDiagnostics, Runnable logNearbyNpcs, Runnable walkToRandomTile)
	{
		setLayout(new GridLayout(0, 1, 0, 8));

		JLabel title = new JLabel("<html><b>GenericClient</b></html>");
		title.setForeground(Color.WHITE);
		add(title);

		JLabel explanation = new JLabel("<html>Client status and nearby NPC diagnostics.</html>");
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
