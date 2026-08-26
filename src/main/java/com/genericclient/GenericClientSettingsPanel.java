package com.genericclient;

import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import net.runelite.client.ui.ColorScheme;

final class GenericClientSettingsPanel extends JPanel
{
	private final GenericClientDashboardActions actions;
	private final JComboBox<GenericClientMouseEffect> mouseEffect =
		new JComboBox<>(GenericClientMouseEffect.values());
	private final JComboBox<String> mouseProfile = new JComboBox<>();
	private final JLabel recordingState = GenericClientDashboardStyle.muted("Not recording");
	private final JButton record = GenericClientDashboardStyle.button("Record");
	private final JButton stopRecording = GenericClientDashboardStyle.button("Stop");

	private final JLabel behaviorSource = GenericClientDashboardStyle.muted("Seeded");
	private final JTextArea behaviorSummary =
		GenericClientDashboardStyle.textArea("Behavior profile loads after login.", 5, true);
	private final JSpinner microChance = spinner(35, 0, 100, 1);
	private final JSpinner microLength = spinner(5.0, 1.0, 119.0, 0.5);
	private final JSpinner microTail = spinner(8, 0, 100, 1);
	private final JSpinner longInterval = spinner(110, 20, 1_440, 5);
	private final JSpinner longLength = spinner(15.0, 3.0, 60.0, 1.0);
	private final JSpinner phaseBoost = spinner(2.5, 1.0, 4.0, 0.1);
	private final JComboBox<GenericClientBehaviorProfile.LongBreakMode> longStyle =
		new JComboBox<>(GenericClientBehaviorProfile.LongBreakMode.values());
	private final JSpinner styleSwitchChance = spinner(5, 0, 50, 1);
	private final JComboBox<GenericClientBehaviorProfile.Edge> idleEdge =
		new JComboBox<>(GenericClientBehaviorProfile.Edge.values());
	private final JSpinner mouseDuration = spinner(
		GenericClientBehaviorProfile.DEFAULT_MOUSE_MOVE_DURATION_MILLIS,
		GenericClientBehaviorProfile.MOUSE_MOVE_DURATION_MIN_MILLIS,
		GenericClientBehaviorProfile.MOUSE_MOVE_DURATION_MAX_MILLIS,
		25);
	private final JLabel saveResult = GenericClientDashboardStyle.muted("");

	private boolean updatingMouse;
	private boolean updatingBehavior;
	private boolean behaviorDirty;
	private String profileKey;

	GenericClientSettingsPanel(GenericClientDashboardActions actions)
	{
		this.actions = actions;
		setLayout(new java.awt.BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setControlWidth(mouseEffect, 86);
		setControlWidth(mouseProfile, 86);
		setControlWidth(longStyle, 86);
		setControlWidth(idleEdge, 86);
		JPanel page = GenericClientDashboardStyle.page();

		JPanel mouse = GenericClientDashboardStyle.section("Mouse");
		mouse.add(GenericClientDashboardStyle.settingRow("Effect", mouseEffect));
		mouse.add(GenericClientDashboardStyle.settingRow("Profile", mouseProfile));
		JPanel mouseButtons = new JPanel(new GridLayout(1, 3, 4, 4));
		mouseButtons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JButton reload = GenericClientDashboardStyle.button("Reload");
		reload.addActionListener(event -> actions.reloadMouseProfile());
		mouseButtons.add(reload);
		record.addActionListener(event -> actions.startMouseRecording());
		mouseButtons.add(record);
		stopRecording.addActionListener(event -> actions.stopMouseRecording());
		mouseButtons.add(stopRecording);
		mouse.add(mouseButtons);
		mouse.add(recordingState);
		page.add(mouse);

		JPanel behavior = GenericClientDashboardStyle.section("Behavior");
		behavior.add(behaviorSource);
		behaviorSummary.setEditable(false);
		behaviorSummary.setFont(behaviorSource.getFont());
		behavior.add(GenericClientDashboardStyle.scroll(behaviorSummary, 105));
		behavior.add(GenericClientDashboardStyle.settingRow("Micro chance %", microChance));
		behavior.add(GenericClientDashboardStyle.settingRow("Micro length s", microLength));
		behavior.add(GenericClientDashboardStyle.settingRow("Micro tail %", microTail));
		behavior.add(GenericClientDashboardStyle.settingRow("Long every min", longInterval));
		behavior.add(GenericClientDashboardStyle.settingRow("Long length min", longLength));
		behavior.add(GenericClientDashboardStyle.settingRow("Phase boost", phaseBoost));
		behavior.add(GenericClientDashboardStyle.settingRow("Usually", longStyle));
		behavior.add(GenericClientDashboardStyle.settingRow("Mode switch %", styleSwitchChance));
		behavior.add(GenericClientDashboardStyle.settingRow("Idle side", idleEdge));
		behavior.add(GenericClientDashboardStyle.settingRow("Mouse move ms", mouseDuration));
		JPanel behaviorButtons = new JPanel(new GridLayout(1, 2, 4, 4));
		behaviorButtons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JButton save = GenericClientDashboardStyle.button("Save custom");
		save.addActionListener(event -> saveBehavior());
		behaviorButtons.add(save);
		JButton seeded = GenericClientDashboardStyle.button("Use seeded");
		seeded.addActionListener(event ->
		{
			saveResult.setText(actions.resetBehaviorOverrides());
			behaviorDirty = false;
			profileKey = null;
		});
		behaviorButtons.add(seeded);
		behavior.add(behaviorButtons);
		behavior.add(saveResult);
		page.add(behavior);

		wireMouseActions();
		wireBehaviorDirtyState();
		add(new javax.swing.JScrollPane(page), java.awt.BorderLayout.CENTER);
	}

	void updateMouse(
		String currentProfile,
		List<String> profiles,
		GenericClientMouseEffect effect,
		boolean recording,
		int recordedTemplates)
	{
		updatingMouse = true;
		try
		{
			List<String> currentItems = new ArrayList<>();
			for (int index = 0; index < mouseProfile.getItemCount(); index++)
			{
				currentItems.add(mouseProfile.getItemAt(index));
			}
			if (!currentItems.equals(profiles))
			{
				mouseProfile.removeAllItems();
				for (String profile : profiles)
				{
					mouseProfile.addItem(profile);
				}
			}
			mouseProfile.setSelectedItem(currentProfile);
			mouseEffect.setSelectedItem(effect);
			recordingState.setText(recording
				? "Recording · " + recordedTemplates + " samples"
				: "Not recording");
			record.setEnabled(!recording);
			stopRecording.setEnabled(recording);
		}
		finally
		{
			updatingMouse = false;
		}
	}

	@SuppressWarnings("unchecked")
	void updateBehavior(Map<String, Object> behavior)
	{
		if (behavior == null || !Boolean.TRUE.equals(behavior.get("available")))
		{
			behaviorSource.setText("Seeded profile unavailable");
			behaviorSummary.setText("Behavior profile loads after login.");
			return;
		}
		Map<String, Object> profile = (Map<String, Object>) behavior.get("profile");
		boolean customized = Boolean.TRUE.equals(profile.get("customized"));
		long remaining = number(behavior.get("break_remaining_millis")).longValue();
		String state = String.valueOf(behavior.get("state"));
		behaviorSource.setText((customized ? "Custom" : "Seeded") + " · " +
			("ready".equals(state) ? "ready" : state + " " + formatDuration(remaining)));
		behaviorSummary.setText(compactSummary(profile));
		behaviorSummary.setCaretPosition(0);

		String nextKey = profile.get("id") + ":" + customized;
		if (behaviorDirty && nextKey.equals(profileKey))
		{
			return;
		}
		updatingBehavior = true;
		try
		{
			microChance.setValue(percent(profile.get("short_release_probability")));
			microLength.setValue(roundTo(number(profile.get("short_body_median_seconds")).doubleValue(), 0.5));
			microTail.setValue(percent(profile.get("short_tail_probability")));
			longInterval.setValue((int) Math.round(number(profile.get("long_cadence_minutes")).doubleValue()));
			longLength.setValue(roundTo(number(profile.get("long_median_minutes")).doubleValue(), 1.0));
			phaseBoost.setValue(roundTo(number(profile.get("phase_short_chances")).doubleValue(), 0.1));
			longStyle.setSelectedItem(GenericClientBehaviorProfile.LongBreakMode.valueOf(
				String.valueOf(profile.get("favored_long_break_mode")).toUpperCase(java.util.Locale.ROOT)));
			styleSwitchChance.setValue(percent(profile.get("opposite_long_break_probability")));
			idleEdge.setSelectedItem(GenericClientBehaviorProfile.Edge.valueOf(
				String.valueOf(profile.get("idle_edge")).toUpperCase(java.util.Locale.ROOT)));
			mouseDuration.setValue(number(profile.get("mouse_move_duration_millis")).intValue());
			profileKey = nextKey;
			behaviorDirty = false;
		}
		finally
		{
			updatingBehavior = false;
		}
	}

	private void wireMouseActions()
	{
		mouseEffect.addActionListener(event ->
		{
			if (!updatingMouse)
			{
				actions.setMouseEffect((GenericClientMouseEffect) mouseEffect.getSelectedItem());
			}
		});
		mouseProfile.addActionListener(event ->
		{
			if (!updatingMouse && mouseProfile.getSelectedItem() != null)
			{
				actions.setMouseProfile(String.valueOf(mouseProfile.getSelectedItem()));
			}
		});
	}

	private void wireBehaviorDirtyState()
	{
		javax.swing.event.ChangeListener change = event -> markBehaviorDirty();
		microChance.addChangeListener(change);
		microLength.addChangeListener(change);
		microTail.addChangeListener(change);
		longInterval.addChangeListener(change);
		longLength.addChangeListener(change);
		phaseBoost.addChangeListener(change);
		styleSwitchChance.addChangeListener(change);
		mouseDuration.addChangeListener(change);
		longStyle.addActionListener(event -> markBehaviorDirty());
		idleEdge.addActionListener(event -> markBehaviorDirty());
	}

	private void markBehaviorDirty()
	{
		if (!updatingBehavior)
		{
			behaviorDirty = true;
			saveResult.setText("Unsaved");
		}
	}

	private void saveBehavior()
	{
		try
		{
			GenericClientBehaviorOverrides overrides = new GenericClientBehaviorOverrides(
				((Number) microChance.getValue()).doubleValue() / 100.0,
				((Number) microLength.getValue()).doubleValue(),
				((Number) microTail.getValue()).doubleValue() / 100.0,
				((Number) longInterval.getValue()).doubleValue(),
				((Number) longLength.getValue()).doubleValue(),
				((Number) phaseBoost.getValue()).doubleValue(),
				(GenericClientBehaviorProfile.LongBreakMode) longStyle.getSelectedItem(),
				((Number) styleSwitchChance.getValue()).doubleValue() / 100.0,
				(GenericClientBehaviorProfile.Edge) idleEdge.getSelectedItem(),
				((Number) mouseDuration.getValue()).intValue());
			saveResult.setText(actions.saveBehaviorOverrides(overrides));
			behaviorDirty = false;
			profileKey = null;
		}
		catch (RuntimeException exception)
		{
			saveResult.setText(exception.getMessage());
		}
	}

	private static JSpinner spinner(Number value, Comparable<?> minimum, Comparable<?> maximum, Number step)
	{
		JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, step));
		spinner.setEditor(new JSpinner.NumberEditor(spinner,
			step.doubleValue() < 1.0 ? "0.0" : "0"));
		setControlWidth(spinner, 72);
		return spinner;
	}

	private static void setControlWidth(javax.swing.JComponent component, int width)
	{
		Dimension size = new Dimension(width, 28);
		component.setPreferredSize(size);
		component.setMinimumSize(size);
		component.setMaximumSize(size);
	}

	private static String compactSummary(Map<String, Object> profile)
	{
		return String.format(java.util.Locale.ROOT,
			"%s%n%.0f%% micro · %.1fs typical%nLong about every %.0fm · %.1fm typical%nUsually %s · idle %s · mouse %dms",
			profile.get("title"),
			number(profile.get("short_release_probability")).doubleValue() * 100.0,
			number(profile.get("short_body_median_seconds")).doubleValue(),
			number(profile.get("long_cadence_minutes")).doubleValue(),
			number(profile.get("long_median_minutes")).doubleValue(),
			profile.get("favored_long_break_mode"),
			profile.get("idle_edge"),
			number(profile.get("mouse_move_duration_millis")).intValue());
	}

	private static double roundTo(double value, double step)
	{
		return Math.round(value / step) * step;
	}

	private static int percent(Object value)
	{
		return (int) Math.round(number(value).doubleValue() * 100.0);
	}

	private static Number number(Object value)
	{
		return value instanceof Number ? (Number) value : 0;
	}

	private static String formatDuration(long millis)
	{
		long seconds = Math.max(0L, millis / 1_000L);
		return seconds < 60L ? seconds + "s" : seconds / 60L + "m " + seconds % 60L + "s";
	}
}
