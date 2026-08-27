package com.genericclient;

import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

final class GenericClientScriptsPanel extends JPanel
{
	private final GenericClientLuaHost host;
	private final JLabel status = GenericClientDashboardStyle.value("none · IDLE");
	private final JComboBox<GenericClientScriptRegistry.Script> scripts = new JComboBox<>();
	private final JTextArea description = GenericClientDashboardStyle.textArea("", 2, true);
	private final JTextArea logs = GenericClientDashboardStyle.textArea("No script output yet.", 13, true);
	private long manifestRevision = -1L;

	GenericClientScriptsPanel(GenericClientLuaHost host)
	{
		this.host = host;
		setLayout(new java.awt.BorderLayout());
		setBackground(GenericClientDashboardStyle.BACKGROUND);
		JPanel page = GenericClientDashboardStyle.page();

		JPanel runner = GenericClientDashboardStyle.section("Scripts");
		runner.add(GenericClientDashboardStyle.row("Active", status));
		scripts.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		GenericClientDashboardStyle.styleControl(scripts);
		scripts.addActionListener(event -> updateDescription());
		runner.add(scripts);
		description.setEditable(false);
		runner.add(GenericClientDashboardStyle.scroll(description, 55));

		JPanel buttons = new JPanel(new GridLayout(1, 4, 8, 0));
		buttons.setOpaque(false);
		JButton run = GenericClientDashboardStyle.primaryButton("Run");
		run.addActionListener(event ->
		{
			GenericClientScriptRegistry.Script selected =
				(GenericClientScriptRegistry.Script) scripts.getSelectedItem();
			if (selected != null)
			{
				host.start(selected.getId());
			}
		});
		buttons.add(run);
		JButton stop = GenericClientDashboardStyle.button("Stop");
		stop.addActionListener(event -> host.stop());
		buttons.add(stop);
		JButton reload = GenericClientDashboardStyle.button("Reload script");
		reload.addActionListener(event -> host.reload());
		buttons.add(reload);
		JButton refresh = GenericClientDashboardStyle.button("Reload list");
		refresh.addActionListener(event -> host.reloadManifest()
			.whenComplete((result, error) -> SwingUtilities.invokeLater(this::refreshScripts)));
		buttons.add(refresh);
		runner.add(buttons);
		page.add(runner);
		page.add(Box.createVerticalStrut(14));

		JPanel output = GenericClientDashboardStyle.section("Output");
		logs.setEditable(false);
		output.add(GenericClientDashboardStyle.scroll(logs, 360));
		page.add(output);
		add(GenericClientDashboardStyle.pageScroll(page), java.awt.BorderLayout.CENTER);
		refreshScripts();
	}

	void update(String active, String scriptStatus, String recentLogs)
	{
		status.setText(active + " · " + scriptStatus);
		logs.setText(recentLogs.isEmpty() ? "No script output yet." : recentLogs);
		logs.setCaretPosition(logs.getDocument().getLength());
		if (manifestRevision != host.getManifestRevision())
		{
			refreshScripts();
		}
	}

	private void refreshScripts()
	{
		GenericClientScriptRegistry.Script selected =
			(GenericClientScriptRegistry.Script) scripts.getSelectedItem();
		String selectedId = selected == null ? null : selected.getId();
		scripts.removeAllItems();
		for (GenericClientScriptRegistry.Script script : host.listScripts())
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
		manifestRevision = host.getManifestRevision();
		updateDescription();
	}

	private void updateDescription()
	{
		GenericClientScriptRegistry.Script selected =
			(GenericClientScriptRegistry.Script) scripts.getSelectedItem();
		description.setText(selected == null ? "" : selected.getDescription());
		description.setCaretPosition(0);
	}
}
