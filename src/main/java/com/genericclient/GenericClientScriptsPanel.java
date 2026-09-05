package com.genericclient;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

final class GenericClientScriptsPanel extends JPanel
{
	private final GenericClientLuaHost host;
	private final GenericClientDashboardStyle.Chip status =
		GenericClientDashboardStyle.chip("Idle", GenericClientDashboardStyle.MUTED);
	private final JComboBox<GenericClientScriptRegistry.Script> scripts =
		GenericClientDashboardStyle.combo(new JComboBox<>(), 280);
	private final JLabel description = GenericClientDashboardStyle.muted(" ");
	private final JPanel inputs = GenericClientDashboardStyle.panel(new GridBagLayout());
	private final Map<String, JComboBox<GenericClientScriptInput.Option>> choiceControls =
		new LinkedHashMap<>();
	private final JButton run = GenericClientDashboardStyle.primaryButton("Run");
	private final JLabel notice = GenericClientDashboardStyle.small("");
	private final JTextArea logs = GenericClientDashboardStyle.textArea(
		"Script output appears here once a script runs.", false, true);
	private String activeId = "none";
	private String activeStatus = "IDLE";
	private boolean refreshing;
	private long manifestRevision = -1L;
	private long descriptionRequest;

	GenericClientScriptsPanel(GenericClientLuaHost host)
	{
		this.host = host;
		setLayout(new BorderLayout());
		setBackground(GenericClientDashboardStyle.BACKGROUND);

		scripts.setRenderer(new ScriptRenderer());
		scripts.addActionListener(event ->
		{
			if (!refreshing)
			{
				updateDescription();
			}
		});
		description.setBorder(new EmptyBorder(8, 2, 0, 0));
		inputs.setVisible(false);
		inputs.setBorder(new EmptyBorder(16, 0, 0, 0));

		JButton reload = GenericClientDashboardStyle.ghostButton("Reload script");
		reload.addActionListener(event -> host.reload());
		JButton refresh = GenericClientDashboardStyle.ghostButton("Reload list");
		refresh.addActionListener(event -> host.catalog.reloadManifest()
			.whenComplete((result, error) -> SwingUtilities.invokeLater(this::refreshScripts)));

		run.addActionListener(event -> start());
		JButton stop = GenericClientDashboardStyle.button("Stop");
		stop.addActionListener(event -> host.stop());
		notice.setForeground(GenericClientDashboardStyle.DANGER);
		JPanel actions = GenericClientDashboardStyle.panel(new BorderLayout(14, 0));
		actions.add(GenericClientDashboardStyle.inline(8, run, stop), BorderLayout.WEST);
		actions.add(notice, BorderLayout.CENTER);

		GenericClientDashboardStyle.Card runner = GenericClientDashboardStyle.card("Scripts", reload, refresh);
		runner.put(scripts)
			.put(description)
			.put(inputs)
			.gap(18)
			.put(actions);

		GenericClientDashboardStyle.Card output = GenericClientDashboardStyle.card("Output");
		output.body().setLayout(new BorderLayout());
		output.body().add(GenericClientDashboardStyle.inset(logs, 0), BorderLayout.CENTER);

		JPanel page = GenericClientDashboardStyle.page();
		page.add(GenericClientDashboardStyle.stack(16,
			GenericClientDashboardStyle.pageHeader("Automations", status),
			runner), BorderLayout.NORTH);
		page.add(output, BorderLayout.CENTER);
		add(page, BorderLayout.CENTER);
		refreshScripts();
	}

	void update(String active, String scriptStatus, String recentLogs)
	{
		activeId = active == null || active.isEmpty() ? "none" : active;
		activeStatus = scriptStatus == null ? "IDLE" : scriptStatus;
		updateStatus();
		String text = recentLogs == null ? "" : recentLogs;
		if (!text.equals(logs.getText()))
		{
			logs.setText(text);
			logs.setCaretPosition(logs.getDocument().getLength());
		}
		if (manifestRevision != host.catalog.getManifestRevision())
		{
			refreshScripts();
		}
	}

	/** Manifest display name for a script id, falling back to the id itself. */
	String displayName(String scriptId)
	{
		for (int index = 0; index < scripts.getItemCount(); index++)
		{
			GenericClientScriptRegistry.Script script = scripts.getItemAt(index);
			if (script.getId().equals(scriptId))
			{
				return script.getName();
			}
		}
		return scriptId;
	}

	static String describe(String status)
	{
		if (isRunning(status))
		{
			return "Running";
		}
		String text = GenericClientDashboardStyle.humanize(status);
		return text.isEmpty() ? "Idle" : text;
	}

	static boolean isRunning(String status)
	{
		return "WAITING".equals(status) || "RUNNING".equals(status);
	}

	private static Color tone(String status)
	{
		if (isRunning(status))
		{
			return GenericClientDashboardStyle.ACCENT;
		}
		if ("FAULTED".equals(status))
		{
			return GenericClientDashboardStyle.DANGER;
		}
		return GenericClientDashboardStyle.MUTED;
	}

	private void updateStatus()
	{
		boolean hasScript = !"none".equals(activeId);
		status.setText(hasScript ? describe(activeStatus) + "  ·  " + displayName(activeId) : "Idle");
		status.setTone(hasScript ? tone(activeStatus) : GenericClientDashboardStyle.MUTED);
	}

	private void refreshScripts()
	{
		GenericClientScriptRegistry.Script selected =
			(GenericClientScriptRegistry.Script) scripts.getSelectedItem();
		String selectedId = selected == null ? null : selected.getId();
		refreshing = true;
		try
		{
			scripts.removeAllItems();
			for (GenericClientScriptRegistry.Script script : host.catalog.listScripts())
			{
				scripts.addItem(script);
			}
			for (int index = 0; selectedId != null && index < scripts.getItemCount(); index++)
			{
				if (selectedId.equals(scripts.getItemAt(index).getId()))
				{
					scripts.setSelectedIndex(index);
					break;
				}
			}
			if (scripts.getSelectedItem() == null && scripts.getItemCount() > 0)
			{
				scripts.setSelectedIndex(0);
			}
		}
		finally
		{
			refreshing = false;
		}
		manifestRevision = host.catalog.getManifestRevision();
		updateStatus();
		updateDescription();
	}

	private void updateDescription()
	{
		long request = ++descriptionRequest;
		GenericClientScriptRegistry.Script selected =
			(GenericClientScriptRegistry.Script) scripts.getSelectedItem();
		String summary = selected == null ? "" : String.valueOf(selected.getDescription());
		description.setText(summary.isEmpty() ? " " : summary);
		description.setToolTipText(summary.isEmpty() ? null : summary);
		choiceControls.clear();
		inputs.removeAll();
		inputs.setVisible(false);
		run.setEnabled(false);
		setNotice("");
		if (selected == null)
		{
			revalidate();
			repaint();
			return;
		}

		String scriptId = selected.getId();
		host.catalog.describe(scriptId).whenComplete((scriptInputs, error) ->
			SwingUtilities.invokeLater(() -> showInputs(request, scriptId, scriptInputs, error)));
	}

	private void showInputs(
		long request,
		String scriptId,
		List<GenericClientScriptInput> scriptInputs,
		Throwable error)
	{
		GenericClientScriptRegistry.Script current =
			(GenericClientScriptRegistry.Script) scripts.getSelectedItem();
		if (request != descriptionRequest || current == null || !scriptId.equals(current.getId()))
		{
			return;
		}
		inputs.removeAll();
		choiceControls.clear();
		if (error != null)
		{
			setNotice("Unable to load script inputs: " + GenericClientDashboardStyle.message(error));
		}
		else
		{
			int row = 0;
			for (GenericClientScriptInput input : scriptInputs)
			{
				JComboBox<GenericClientScriptInput.Option> control = new JComboBox<>();
				for (GenericClientScriptInput.Option choice : input.getChoices())
				{
					control.addItem(choice);
					if (choice.getValue().equals(input.getDefaultValue()))
					{
						control.setSelectedItem(choice);
					}
				}
				GenericClientDashboardStyle.combo(control, 240);
				choiceControls.put(input.getId(), control);
				addInputRow(row++, input.getLabel(), control);
			}
			inputs.setVisible(!scriptInputs.isEmpty());
			run.setEnabled(true);
		}
		inputs.revalidate();
		inputs.repaint();
		revalidate();
		repaint();
	}

	private void addInputRow(int row, String text, JComponent control)
	{
		int top = row == 0 ? 0 : 8;
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridy = row;
		constraints.anchor = GridBagConstraints.WEST;
		constraints.gridx = 0;
		constraints.insets = new Insets(top, 0, 0, 14);
		inputs.add(GenericClientDashboardStyle.secondary(text), constraints);
		constraints.gridx = 1;
		constraints.insets = new Insets(top, 0, 0, 0);
		inputs.add(control, constraints);
		constraints.gridx = 2;
		constraints.weightx = 1.0;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		inputs.add(GenericClientDashboardStyle.spacer(), constraints);
	}

	private void start()
	{
		GenericClientScriptRegistry.Script selected =
			(GenericClientScriptRegistry.Script) scripts.getSelectedItem();
		if (selected == null)
		{
			return;
		}
		setNotice("");
		host.start(selected.getId(), selectedInputs()).whenComplete((result, error) ->
		{
			if (error != null)
			{
				SwingUtilities.invokeLater(() -> setNotice(GenericClientDashboardStyle.message(error)));
			}
		});
	}

	private void setNotice(String text)
	{
		notice.setText(text);
		notice.setToolTipText(text.isEmpty() ? null : text);
	}

	private Map<String, Object> selectedInputs()
	{
		if (choiceControls.isEmpty())
		{
			return Collections.emptyMap();
		}
		Map<String, Object> values = new LinkedHashMap<>();
		for (Map.Entry<String, JComboBox<GenericClientScriptInput.Option>> entry : choiceControls.entrySet())
		{
			GenericClientScriptInput.Option selected =
				(GenericClientScriptInput.Option) entry.getValue().getSelectedItem();
			if (selected != null)
			{
				values.put(entry.getKey(), selected.getValue());
			}
		}
		return values;
	}

	private static final class ScriptRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(
			JList<?> list, Object value, int index, boolean selected, boolean focused)
		{
			super.getListCellRendererComponent(list, value, index, selected, focused);
			if (value instanceof GenericClientScriptRegistry.Script)
			{
				setText(((GenericClientScriptRegistry.Script) value).getName());
			}
			return this;
		}
	}
}
