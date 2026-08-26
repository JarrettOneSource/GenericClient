package com.genericclient;

import com.google.gson.GsonBuilder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
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
	private final JLabel behaviorTitleValue = valueLabel("Waiting for account");
	private final JLabel behaviorStateValue = valueLabel("unavailable");
	private final JLabel behaviorRemainingValue = valueLabel("0s");
	private final JLabel behaviorEdgeValue = valueLabel("unknown");
	private final JLabel behaviorPressureValue = valueLabel("0%");
	private final JLabel behaviorCountsValue = valueLabel("0 micro / 0 long");
	private final JTextArea behaviorSummary = new JTextArea("Behavior profile loads after account login.");
	private final JTextArea npcDiagnostics = new JTextArea("No NPC snapshot captured yet.");
	private final JLabel mouseProfileValue = valueLabel("loading");
	private final JLabel mouseTemplateValue = valueLabel("0");
	private final JLabel mouseRecordingValue = valueLabel("stopped");
	private final JButton mouseRecordButton = new JButton("Record new profile");
	private final JButton mouseStopButton = new JButton("Stop and use recording");
	private final JLabel luaScriptValue = valueLabel("none");
	private final JLabel luaStatusValue = valueLabel("IDLE");
	private final JLabel luaDescriptionValue = valueLabel("");
	private final JComboBox<GenericClientScriptRegistry.Script> luaScripts = new JComboBox<>();
	private final JTextArea luaLogs = new JTextArea("No Lua output yet.");
	private final JTextArea luaReplInput = new JTextArea("return gc.read(\"player\")");
	private final JTextArea luaReplOutput = new JTextArea("No REPL result yet.");
	private final GenericClientLuaHost luaHost;
	private long scriptManifestRevision = -1;

	GenericClientPanel(
		Runnable printDiagnostics,
		Runnable logNearbyNpcs,
		Runnable walkToRandomTile,
		Runnable reloadMouseProfile,
		Runnable startMouseRecording,
		Runnable stopMouseRecording,
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

		JPanel behaviorSection = section("Behavior profile");
		behaviorSection.add(row("Profile", behaviorTitleValue));
		behaviorSection.add(row("State", behaviorStateValue));
		behaviorSection.add(row("Remaining", behaviorRemainingValue));
		behaviorSection.add(row("Idle edge", behaviorEdgeValue));
		behaviorSection.add(row("Long pressure", behaviorPressureValue));
		behaviorSection.add(row("Breaks", behaviorCountsValue));
		behaviorSummary.setEditable(false);
		behaviorSummary.setRows(5);
		behaviorSummary.setLineWrap(true);
		behaviorSummary.setWrapStyleWord(true);
		behaviorSummary.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		behaviorSummary.setForeground(Color.WHITE);
		JScrollPane behaviorScroll = new JScrollPane(behaviorSummary);
		behaviorScroll.setPreferredSize(new Dimension(205, 105));
		behaviorSection.add(behaviorScroll);
		add(behaviorSection);

		JButton diagnosticsButton = new JButton("Print diagnostics");
		diagnosticsButton.addActionListener(event -> printDiagnostics.run());
		add(diagnosticsButton);

		JButton npcButton = new JButton("Log nearby NPCs");
		npcButton.addActionListener(event -> logNearbyNpcs.run());
		add(npcButton);

		JButton clickButton = new JButton("Walk to random tile");
		clickButton.addActionListener(event -> walkToRandomTile.run());
		add(clickButton);

		JPanel mouseSection = section("Mouse profile");
		mouseSection.add(row("File", mouseProfileValue));
		mouseSection.add(row("Templates", mouseTemplateValue));
		mouseSection.add(row("Recording", mouseRecordingValue));
		JButton mouseReloadButton = new JButton("Reload profile");
		mouseReloadButton.addActionListener(event -> reloadMouseProfile.run());
		mouseSection.add(mouseReloadButton);
		mouseRecordButton.addActionListener(event -> startMouseRecording.run());
		mouseSection.add(mouseRecordButton);
		mouseStopButton.setEnabled(false);
		mouseStopButton.addActionListener(event -> stopMouseRecording.run());
		mouseSection.add(mouseStopButton);
		add(mouseSection);

		JPanel luaSection = section("Lua scripts");
		luaSection.add(row("Active", luaScriptValue));
		luaSection.add(row("Status", luaStatusValue));
		luaSection.add(row("Selected", luaDescriptionValue));
		luaScripts.addActionListener(event -> updateSelectedScriptDescription());
		luaSection.add(luaScripts);

		JPanel luaButtons = new JPanel(new GridLayout(2, 2, 4, 4));
		luaButtons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JButton scanButton = new JButton("Reload manifest");
		scanButton.addActionListener(event -> luaHost.reloadManifest()
			.whenComplete((result, error) -> runOnEdt(this::refreshScripts)));
		luaButtons.add(scanButton);
		JButton startButton = new JButton("Start");
		startButton.addActionListener(event ->
		{
			GenericClientScriptRegistry.Script selection =
				(GenericClientScriptRegistry.Script) luaScripts.getSelectedItem();
			if (selection != null)
			{
				luaHost.start(selection.getId());
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

		JPanel replSection = section("Lua REPL");
		luaReplInput.setRows(5);
		luaReplInput.setLineWrap(false);
		luaReplInput.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		luaReplInput.setForeground(Color.WHITE);
		luaReplInput.setCaretColor(Color.WHITE);
		luaReplInput.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11));
		JScrollPane replInputScroll = new JScrollPane(luaReplInput);
		replInputScroll.setPreferredSize(new Dimension(205, 105));
		replSection.add(replInputScroll);

		JPanel replButtons = new JPanel(new GridLayout(1, 2, 4, 4));
		replButtons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JButton runReplButton = new JButton("Run Lua");
		runReplButton.addActionListener(event ->
		{
			luaReplOutput.setText("Running...");
			luaHost.evaluate(luaReplInput.getText()).whenComplete((result, error) ->
				runOnEdt(() -> luaReplOutput.setText(error == null
					? new GsonBuilder().setPrettyPrinting().create().toJson(result)
					: error.getMessage())));
		});
		replButtons.add(runReplButton);
		JButton resetReplButton = new JButton("Reset REPL");
		resetReplButton.addActionListener(event -> luaHost.resetRepl()
			.whenComplete((result, error) -> runOnEdt(() -> luaReplOutput.setText(
				error == null ? result : error.getMessage()))));
		replButtons.add(resetReplButton);
		replSection.add(replButtons);

		luaReplOutput.setEditable(false);
		luaReplOutput.setRows(6);
		luaReplOutput.setLineWrap(false);
		luaReplOutput.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		luaReplOutput.setForeground(Color.WHITE);
		luaReplOutput.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11));
		JScrollPane replOutputScroll = new JScrollPane(luaReplOutput);
		replOutputScroll.setPreferredSize(new Dimension(205, 125));
		replSection.add(replOutputScroll);
		add(replSection);

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

	@SuppressWarnings("unchecked")
	void updateBehaviorState(Map<String, Object> behavior)
	{
		runOnEdt(() ->
		{
			if (behavior == null || !Boolean.TRUE.equals(behavior.get("available")))
			{
				behaviorTitleValue.setText("Waiting for account");
				behaviorStateValue.setText(behavior == null
					? "unavailable"
					: String.valueOf(behavior.get("state")));
				behaviorSummary.setText("Behavior profile loads after account login.");
				return;
			}

			Map<String, Object> profile = (Map<String, Object>) behavior.get("profile");
			behaviorTitleValue.setText(String.valueOf(profile.get("title")));
			behaviorStateValue.setText(String.valueOf(behavior.get("state")));
			long remaining = number(behavior.get("break_remaining_millis")).longValue();
			behaviorRemainingValue.setText(formatDuration(remaining));
			behaviorEdgeValue.setText(String.valueOf(profile.get("idle_edge")));
			double hazard = number(behavior.get("long_hazard")).doubleValue();
			double budget = number(behavior.get("long_hazard_budget")).doubleValue();
			double pressure = budget <= 0.0 ? 0.0 : Math.min(999.0, 100.0 * hazard / budget);
			behaviorPressureValue.setText(String.format(java.util.Locale.ROOT, "%.0f%%", pressure));
			behaviorCountsValue.setText(number(behavior.get("micro_break_count")).longValue() +
				" micro / " + number(behavior.get("long_break_count")).longValue() + " long");
			behaviorSummary.setText(String.valueOf(profile.get("summary")));
			behaviorSummary.setCaretPosition(0);
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

	void updateMouseState(String file, int templates, boolean recording, int recordedTemplates)
	{
		runOnEdt(() ->
		{
			mouseProfileValue.setText(file);
			mouseTemplateValue.setText(Integer.toString(templates));
			mouseRecordingValue.setText(recording
				? "running (" + recordedTemplates + ")"
				: "stopped");
			mouseRecordButton.setEnabled(!recording);
			mouseStopButton.setEnabled(recording);
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
			if (scriptManifestRevision != luaHost.getManifestRevision())
			{
				refreshScripts();
			}
		});
	}

	private void refreshScripts()
	{
		GenericClientScriptRegistry.Script selected =
			(GenericClientScriptRegistry.Script) luaScripts.getSelectedItem();
		String selectedId = selected == null ? null : selected.getId();
		luaScripts.removeAllItems();
		for (GenericClientScriptRegistry.Script script : luaHost.listScripts())
		{
			luaScripts.addItem(script);
		}
		if (selectedId != null)
		{
			for (int index = 0; index < luaScripts.getItemCount(); index++)
			{
				if (selectedId.equals(luaScripts.getItemAt(index).getId()))
				{
					luaScripts.setSelectedIndex(index);
					break;
				}
			}
		}
		if (luaScripts.getSelectedItem() == null && luaScripts.getItemCount() > 0)
		{
			luaScripts.setSelectedIndex(0);
		}
		updateSelectedScriptDescription();
		scriptManifestRevision = luaHost.getManifestRevision();
	}

	private void updateSelectedScriptDescription()
	{
		GenericClientScriptRegistry.Script selected =
			(GenericClientScriptRegistry.Script) luaScripts.getSelectedItem();
		luaDescriptionValue.setText(selected == null ? "" : selected.getDescription());
	}

	private static JPanel section(String title)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
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

	private static Number number(Object value)
	{
		return value instanceof Number ? (Number) value : 0L;
	}

	private static String formatDuration(long millis)
	{
		long seconds = Math.max(0L, millis / 1_000L);
		if (seconds < 60L)
		{
			return seconds + "s";
		}
		return seconds / 60L + "m " + seconds % 60L + "s";
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
