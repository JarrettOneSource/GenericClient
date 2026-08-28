package com.genericclient;

import java.awt.BorderLayout;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

final class GenericClientAutomationPanel extends JPanel
{
	private final GenericClientAutomationScheduler scheduler;
	private final JLabel mode = GenericClientDashboardStyle.strong("Waiting for account");
	private final JLabel detail = GenericClientDashboardStyle.small("");
	private final JLabel activeRule = GenericClientDashboardStyle.secondary("None");
	private final JLabel nextTransition = GenericClientDashboardStyle.secondary("None");
	private final JLabel configPath = GenericClientDashboardStyle.small("");
	private final JLabel notice = GenericClientDashboardStyle.small("");
	private final JTextArea rules = GenericClientDashboardStyle.textArea("No rules configured", false, true);
	private final JButton enabled = GenericClientDashboardStyle.primaryButton("Enable");
	private final JButton paused = GenericClientDashboardStyle.button("Pause");
	private final JButton reload = GenericClientDashboardStyle.ghostButton("Reload rules");
	private boolean currentEnabled;
	private boolean currentPaused;

	GenericClientAutomationPanel(GenericClientAutomationScheduler scheduler)
	{
		this.scheduler = scheduler;
		setLayout(new BorderLayout());
		setBackground(GenericClientDashboardStyle.BACKGROUND);

		enabled.addActionListener(event -> requestEnabled(!currentEnabled));
		paused.addActionListener(event -> setPaused(!currentPaused));
		reload.addActionListener(event ->
		{
			if (scheduler != null)
			{
				show(scheduler.reload());
			}
		});

		GenericClientDashboardStyle.Card state = GenericClientDashboardStyle.card("Scheduler");
		state.put(GenericClientDashboardStyle.inline(10, mode, detail))
			.put(GenericClientDashboardStyle.row("Active rule", activeRule))
			.put(GenericClientDashboardStyle.row("Next transition", nextTransition))
			.put(GenericClientDashboardStyle.row("Configuration", configPath));

		GenericClientDashboardStyle.Card ruleCard = GenericClientDashboardStyle.card("Rule decisions");
		ruleCard.put(GenericClientDashboardStyle.inset(rules, 250));

		JPanel controls = GenericClientDashboardStyle.panel(new BorderLayout());
		controls.add(GenericClientDashboardStyle.inline(8, enabled, paused, reload), BorderLayout.WEST);
		controls.add(notice, BorderLayout.CENTER);

		JPanel page = GenericClientDashboardStyle.page();
		page.add(GenericClientDashboardStyle.stack(16,
			GenericClientDashboardStyle.pageHeader("Schedules"),
			state,
			ruleCard,
			controls), BorderLayout.NORTH);
		add(GenericClientDashboardStyle.scroll(page), BorderLayout.CENTER);
		setControlsEnabled(scheduler != null);
	}

	@SuppressWarnings("unchecked")
	void update(Map<String, Object> status)
	{
		if (status == null || !Boolean.TRUE.equals(status.get("available")))
		{
			mode.setText("Waiting for account");
			detail.setText("");
			activeRule.setText("None");
			nextTransition.setText("None");
			configPath.setText("");
			rules.setText("");
			setControlsEnabled(false);
			return;
		}
		setControlsEnabled(scheduler != null);
		currentEnabled = Boolean.TRUE.equals(status.get("enabled"));
		currentPaused = Boolean.TRUE.equals(status.get("paused"));
		mode.setText(GenericClientDashboardStyle.humanize(String.valueOf(status.get("mode"))));
		detail.setText(String.valueOf(status.getOrDefault("detail", "")));
		activeRule.setText(stringOr(status.get("active_rule"), "None"));
		nextTransition.setText(stringOr(status.get("next_transition"), "None"));
		configPath.setText(stringOr(status.get("config_path"), ""));
		enabled.setText(currentEnabled ? "Disable" : "Enable");
		paused.setText(currentPaused ? "Resume" : "Pause");

		StringBuilder lines = new StringBuilder();
		Object rawRules = status.get("rules");
		if (rawRules instanceof List)
		{
			for (Object rawRule : (List<?>) rawRules)
			{
				if (!(rawRule instanceof Map))
				{
					continue;
				}
				Map<String, Object> rule = (Map<String, Object>) rawRule;
				if (lines.length() > 0)
				{
					lines.append('\n');
				}
				lines.append(rule.get("id"))
					.append(" · ").append(String.valueOf(rule.get("truth")).toUpperCase())
					.append(" · ").append(rule.get("reason"));
			}
		}
		rules.setText(lines.toString());
		rules.setCaretPosition(0);
	}

	private void requestEnabled(boolean value)
	{
		if (scheduler != null)
		{
			show(scheduler.setEnabled(value));
		}
	}

	private void setPaused(boolean value)
	{
		if (scheduler != null)
		{
			show(scheduler.setPaused(value, "dashboard"));
		}
	}

	private void show(java.util.concurrent.CompletableFuture<Map<String, Object>> action)
	{
		setControlsEnabled(false);
		notice.setText("Applying...");
		action.whenComplete((value, error) -> SwingUtilities.invokeLater(() ->
		{
			setControlsEnabled(true);
			notice.setText(error == null ? "" : GenericClientDashboardStyle.message(error));
			if (error == null)
			{
				update(value);
			}
		}));
	}

	private void setControlsEnabled(boolean value)
	{
		enabled.setEnabled(value);
		paused.setEnabled(value);
		reload.setEnabled(value);
	}

	private static String stringOr(Object value, String fallback)
	{
		return value == null ? fallback : String.valueOf(value);
	}
}
