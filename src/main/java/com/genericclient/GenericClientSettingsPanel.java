package com.genericclient;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ChangeListener;

final class GenericClientSettingsPanel extends JPanel
{
	private final GenericClientDashboardActions actions;
	private final JComboBox<GenericClientMouseEffect> mouseEffect =
		GenericClientDashboardStyle.combo(new JComboBox<>(GenericClientMouseEffect.values()), 170);
	private final JComboBox<String> mouseProfile =
		GenericClientDashboardStyle.combo(new JComboBox<>(), 220);
	private final JLabel recordingState = GenericClientDashboardStyle.small("");
	private final JButton record = GenericClientDashboardStyle.button("Record");

	private final GenericClientDashboardStyle.Chip profileChip =
		GenericClientDashboardStyle.chip("Offline", GenericClientDashboardStyle.MUTED);
	private final JLabel profileTitle = GenericClientDashboardStyle.strong("Profile loads after login");
	private final JLabel breakState = GenericClientDashboardStyle.small("");
	private final JSpinner microChance = spinner(35, 0, 100, 1);
	private final JSpinner microLength = spinner(5.0, 1.0, 119.0, 0.5);
	private final JSpinner microTail = spinner(8, 0, 100, 1);
	private final JSpinner longInterval = spinner(110, 20, 1_440, 5);
	private final JSpinner longLength = spinner(15.0, 3.0, 60.0, 1.0);
	private final JSpinner phaseBoost = spinner(2.5, 1.0, 4.0, 0.1);
	private final JComboBox<GenericClientBehaviorProfile.LongBreakMode> longStyle =
		GenericClientDashboardStyle.combo(
			new JComboBox<>(GenericClientBehaviorProfile.LongBreakMode.values()), 120);
	private final JSpinner styleSwitchChance = spinner(5, 0, 50, 1);
	private final JComboBox<GenericClientBehaviorProfile.Edge> idleEdge =
		GenericClientDashboardStyle.combo(
			new JComboBox<>(GenericClientBehaviorProfile.Edge.values()), 120);
	private final JSpinner mouseDuration = spinner(
		GenericClientBehaviorProfile.DEFAULT_MOUSE_MOVE_DURATION_MILLIS,
		GenericClientBehaviorProfile.MOUSE_MOVE_DURATION_MIN_MILLIS,
		GenericClientBehaviorProfile.MOUSE_MOVE_DURATION_MAX_MILLIS,
		25);
	private final JButton save = GenericClientDashboardStyle.primaryButton("Save custom");
	private final JButton seeded = GenericClientDashboardStyle.ghostButton("Use seeded");
	private final JLabel saveResult = GenericClientDashboardStyle.small("");
	private final List<JComponent> behaviorControls;

	private boolean updatingMouse;
	private boolean updatingBehavior;
	private boolean behaviorDirty;
	private boolean recording;
	private String profileKey;

	GenericClientSettingsPanel(GenericClientDashboardActions actions)
	{
		this.actions = actions;
		setLayout(new BorderLayout());
		setBackground(GenericClientDashboardStyle.BACKGROUND);

		JButton reload = GenericClientDashboardStyle.ghostButton("Reload profile");
		reload.addActionListener(event -> actions.reloadMouseProfile());
		record.addActionListener(event ->
		{
			if (recording)
			{
				actions.stopMouseRecording();
			}
			else
			{
				actions.startMouseRecording();
			}
		});
		GenericClientDashboardStyle.Card mouse = GenericClientDashboardStyle.card("Mouse", reload);
		mouse.put(GenericClientDashboardStyle.row("Effect", mouseEffect))
			.put(GenericClientDashboardStyle.row("Profile", mouseProfile))
			.put(GenericClientDashboardStyle.row("Recording", recordingState, record));

		save.addActionListener(event -> saveBehavior());
		seeded.addActionListener(event ->
		{
			setSaveResult(actions.resetBehaviorOverrides(), GenericClientDashboardStyle.MUTED);
			behaviorDirty = false;
			profileKey = null;
		});
		JPanel footer = GenericClientDashboardStyle.panel(new BorderLayout(14, 0));
		footer.add(GenericClientDashboardStyle.inline(8, save, seeded), BorderLayout.WEST);
		footer.add(saveResult, BorderLayout.CENTER);

		GenericClientDashboardStyle.Card behavior = GenericClientDashboardStyle.card("Behavior");
		behavior.put(GenericClientDashboardStyle.inline(10, profileChip, profileTitle, breakState))
			.gap(18)
			.put(GenericClientDashboardStyle.columns(28,
				GenericClientDashboardStyle.group("Micro breaks",
					GenericClientDashboardStyle.row("Chance", microChance, "%"),
					GenericClientDashboardStyle.row("Length", microLength, "s"),
					GenericClientDashboardStyle.row("Tail", microTail, "%"),
					GenericClientDashboardStyle.row("Phase boost", phaseBoost, "×")),
				GenericClientDashboardStyle.group("Long breaks",
					GenericClientDashboardStyle.row("Every", longInterval, "min"),
					GenericClientDashboardStyle.row("Duration", longLength, "min"),
					GenericClientDashboardStyle.row("Style", longStyle, ""),
					GenericClientDashboardStyle.row("Switch chance", styleSwitchChance, "%"))))
			.gap(18)
			.put(GenericClientDashboardStyle.columns(28,
				GenericClientDashboardStyle.group("Cursor",
					GenericClientDashboardStyle.row("Idle side", idleEdge, ""),
					GenericClientDashboardStyle.row("Move duration", mouseDuration, "ms")),
				GenericClientDashboardStyle.spacer()))
			.gap(20)
			.put(footer);
		behaviorControls = Arrays.asList(
			microChance, microLength, microTail, phaseBoost,
			longInterval, longLength, longStyle, styleSwitchChance,
			idleEdge, mouseDuration, save, seeded);

		JPanel page = GenericClientDashboardStyle.page();
		page.add(GenericClientDashboardStyle.stack(16,
			GenericClientDashboardStyle.pageHeader("Settings"),
			mouse,
			behavior), BorderLayout.NORTH);
		add(GenericClientDashboardStyle.scroll(page), BorderLayout.CENTER);

		wireMouseActions();
		wireBehaviorDirtyState();
		setBehaviorAvailable(false);
	}

	void updateMouse(
		String currentProfile,
		List<String> profiles,
		GenericClientMouseEffect effect,
		boolean recordingActive,
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
			recording = recordingActive;
			recordingState.setText(recording ? recordedTemplates + " samples captured" : "");
			record.setText(recording ? "Stop recording" : "Record");
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
			profileChip.setText("Offline");
			profileChip.setTone(GenericClientDashboardStyle.MUTED);
			profileTitle.setText("Profile loads after login");
			breakState.setText("");
			setBehaviorAvailable(false);
			return;
		}
		Map<String, Object> profile = (Map<String, Object>) behavior.get("profile");
		boolean customized = Boolean.TRUE.equals(profile.get("customized"));
		long remaining = number(behavior.get("break_remaining_millis")).longValue();
		String state = String.valueOf(behavior.get("state"));
		profileChip.setText(customized ? "Custom" : "Seeded");
		profileChip.setTone(customized ? GenericClientDashboardStyle.ACCENT : GenericClientDashboardStyle.MUTED);
		profileTitle.setText(String.valueOf(profile.get("title")));
		breakState.setText("ready".equals(state)
			? ""
			: GenericClientDashboardStyle.humanize(state) + " · " + formatDuration(remaining) + " left");
		setBehaviorAvailable(true);

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
				String.valueOf(profile.get("favored_long_break_mode")).toUpperCase(Locale.ROOT)));
			styleSwitchChance.setValue(percent(profile.get("opposite_long_break_probability")));
			idleEdge.setSelectedItem(GenericClientBehaviorProfile.Edge.valueOf(
				String.valueOf(profile.get("idle_edge")).toUpperCase(Locale.ROOT)));
			mouseDuration.setValue(number(profile.get("mouse_move_duration_millis")).intValue());
			profileKey = nextKey;
			behaviorDirty = false;
		}
		finally
		{
			updatingBehavior = false;
		}
	}

	private void setBehaviorAvailable(boolean available)
	{
		for (JComponent control : behaviorControls)
		{
			control.setEnabled(available);
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
		ChangeListener change = event -> markBehaviorDirty();
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
			setSaveResult("Unsaved changes", GenericClientDashboardStyle.WARNING);
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
			setSaveResult(actions.saveBehaviorOverrides(overrides), GenericClientDashboardStyle.MUTED);
			behaviorDirty = false;
			profileKey = null;
		}
		catch (RuntimeException exception)
		{
			setSaveResult(GenericClientDashboardStyle.message(exception), GenericClientDashboardStyle.DANGER);
		}
	}

	private void setSaveResult(String text, java.awt.Color color)
	{
		saveResult.setForeground(color);
		saveResult.setText(text == null ? "" : text);
	}

	private static JSpinner spinner(Number value, Comparable<?> minimum, Comparable<?> maximum, Number step)
	{
		JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, step));
		spinner.setEditor(new JSpinner.NumberEditor(spinner, step.doubleValue() < 1.0 ? "0.0" : "0"));
		return GenericClientDashboardStyle.spinner(spinner, 96);
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
