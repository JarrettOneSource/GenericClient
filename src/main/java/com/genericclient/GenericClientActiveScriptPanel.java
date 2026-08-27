package com.genericclient;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

final class GenericClientActiveScriptPanel extends JPanel
{
	private static final String EMPTY = "empty";
	private static final String SCRIPT = "script";

	private final GenericClientLuaHost host;
	private final GenericClientDashboardStyle.Chip status =
		GenericClientDashboardStyle.chip("Idle", GenericClientDashboardStyle.MUTED);
	private final JPanel cards = new JPanel(new CardLayout());
	private final JLabel title = GenericClientDashboardStyle.heading("");
	private final JLabel runtime = GenericClientDashboardStyle.mono("0:00");
	private final JLabel description = GenericClientDashboardStyle.muted("");
	private final JPanel configuration = GenericClientDashboardStyle.panel(new GridBagLayout());
	private final JPanel scriptActions = GenericClientDashboardStyle.panel(new java.awt.FlowLayout(
		java.awt.FlowLayout.LEFT, 8, 0));
	private final JLabel notice = GenericClientDashboardStyle.small("");
	private final Map<String, JComboBox<GenericClientScriptInput.Option>> controls = new LinkedHashMap<>();
	private final JButton restart = GenericClientDashboardStyle.primaryButton("Restart");
	private final JButton stop = GenericClientDashboardStyle.button("Stop");
	private GenericClientActiveScript current = GenericClientActiveScript.none();
	private String structureKey = "";

	GenericClientActiveScriptPanel(GenericClientLuaHost host)
	{
		this.host = host;
		setLayout(new BorderLayout());
		setBackground(GenericClientDashboardStyle.BACKGROUND);

		GenericClientDashboardStyle.Card empty = GenericClientDashboardStyle.card("Active script")
			.put(GenericClientDashboardStyle.secondary("No active script"));
		cards.setOpaque(false);
		cards.add(empty, EMPTY);

		JPanel identity = GenericClientDashboardStyle.panel(new BorderLayout(12, 0));
		identity.add(title, BorderLayout.WEST);
		identity.add(runtime, BorderLayout.EAST);
		restart.addActionListener(event -> restart());
		restart.setToolTipText("Restart with the selected configuration");
		stop.addActionListener(event -> host.stop().whenComplete(this::showResult));
		JPanel footer = GenericClientDashboardStyle.panel(new BorderLayout(12, 0));
		footer.add(GenericClientDashboardStyle.inline(8, restart, stop), BorderLayout.WEST);
		footer.add(notice, BorderLayout.CENTER);

		GenericClientDashboardStyle.Card script = GenericClientDashboardStyle.card("Script")
			.put(identity)
			.gap(5)
			.put(description)
			.gap(18)
			.put(configuration)
			.gap(14)
			.put(scriptActions)
			.gap(18)
			.put(footer);
		cards.add(script, SCRIPT);

		JPanel page = GenericClientDashboardStyle.page();
		page.add(GenericClientDashboardStyle.pageHeader("Active Script", status), BorderLayout.NORTH);
		page.add(cards, BorderLayout.CENTER);
		add(page, BorderLayout.CENTER);
		update(GenericClientActiveScript.none());
	}

	void update(GenericClientActiveScript value)
	{
		current = value == null ? GenericClientActiveScript.none() : value;
		if (!current.isPresent())
		{
			status.setText("Idle");
			status.setTone(GenericClientDashboardStyle.MUTED);
			((CardLayout) cards.getLayout()).show(cards, EMPTY);
			structureKey = "";
			return;
		}

		((CardLayout) cards.getLayout()).show(cards, SCRIPT);
		title.setText(current.getName());
		description.setText(current.getDescription());
		runtime.setText(GenericClientScriptOverlay.formatRuntime(current.getRuntimeMillis()));
		status.setText(GenericClientScriptsPanel.describe(current.getStatus()));
		status.setTone(tone(current.getStatus()));
		restart.setEnabled(true);
		stop.setEnabled(true);

		String nextKey = current.getId() + inputKey(current) + current.getValues() + actionIds(current);
		if (!nextKey.equals(structureKey))
		{
			structureKey = nextKey;
			rebuildControls();
		}
		for (java.awt.Component component : scriptActions.getComponents())
		{
			component.setEnabled(current.isRunning());
		}
	}

	void update()
	{
		update(host.getActiveScriptView());
	}

	private void rebuildControls()
	{
		configuration.removeAll();
		controls.clear();
		int row = 0;
		for (GenericClientScriptInput input : current.getInputs())
		{
			JComboBox<GenericClientScriptInput.Option> control = new JComboBox<>();
			Object selectedValue = current.getValues().get(input.getId());
			for (GenericClientScriptInput.Option option : input.getChoices())
			{
				control.addItem(option);
				if (option.getValue().equals(selectedValue))
				{
					control.setSelectedItem(option);
				}
			}
			GenericClientDashboardStyle.combo(control, 240);
			controls.put(input.getId(), control);
			addConfigurationRow(row++, input.getLabel(), control);
		}
		configuration.setVisible(!current.getInputs().isEmpty());

		scriptActions.removeAll();
		for (GenericClientScriptAction action : current.getActions())
		{
			JButton button = GenericClientDashboardStyle.button(action.getLabel());
			button.addActionListener(event -> host.triggerAction(action.getId()).whenComplete(this::showResult));
			scriptActions.add(button);
		}
		scriptActions.setVisible(!current.getActions().isEmpty());
		configuration.revalidate();
		scriptActions.revalidate();
	}

	private void addConfigurationRow(
		int row,
		String label,
		JComboBox<GenericClientScriptInput.Option> control)
	{
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridy = row;
		constraints.gridx = 0;
		constraints.anchor = GridBagConstraints.WEST;
		constraints.insets = new Insets(row == 0 ? 0 : 8, 0, 0, 16);
		configuration.add(GenericClientDashboardStyle.secondary(label), constraints);
		constraints.gridx = 1;
		constraints.insets = new Insets(row == 0 ? 0 : 8, 0, 0, 0);
		configuration.add(control, constraints);
		constraints.gridx = 2;
		constraints.weightx = 1.0;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		configuration.add(GenericClientDashboardStyle.spacer(), constraints);
	}

	private void restart()
	{
		if (!current.isPresent())
		{
			return;
		}
		notice.setText("");
		host.start(current.getId(), selectedValues()).whenComplete(this::showResult);
	}

	private Map<String, Object> selectedValues()
	{
		if (controls.isEmpty())
		{
			return Collections.emptyMap();
		}
		Map<String, Object> values = new LinkedHashMap<>();
		for (Map.Entry<String, JComboBox<GenericClientScriptInput.Option>> entry : controls.entrySet())
		{
			GenericClientScriptInput.Option option =
				(GenericClientScriptInput.Option) entry.getValue().getSelectedItem();
			if (option != null)
			{
				values.put(entry.getKey(), option.getValue());
			}
		}
		return values;
	}

	private void showResult(String result, Throwable error)
	{
		SwingUtilities.invokeLater(() ->
		{
			notice.setForeground(error == null
				? GenericClientDashboardStyle.MUTED
				: GenericClientDashboardStyle.DANGER);
			notice.setText(error == null ? "" : GenericClientDashboardStyle.message(error));
		});
	}

	private static java.awt.Color tone(String value)
	{
		if (GenericClientScriptsPanel.isRunning(value))
		{
			return GenericClientDashboardStyle.ACCENT;
		}
		return "FAULTED".equals(value)
			? GenericClientDashboardStyle.DANGER
			: GenericClientDashboardStyle.MUTED;
	}

	private static String actionIds(GenericClientActiveScript script)
	{
		StringBuilder value = new StringBuilder();
		for (GenericClientScriptAction action : script.getActions())
		{
			value.append(action.getId()).append(':').append(action.getLabel()).append(';');
		}
		return value.toString();
	}

	private static String inputKey(GenericClientActiveScript script)
	{
		StringBuilder value = new StringBuilder();
		for (GenericClientScriptInput input : script.getInputs())
		{
			value.append(input.toMap()).append(';');
		}
		return value.toString();
	}
}
