package com.genericclient;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Window;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

final class GenericClientDashboard implements AutoCloseable
{
	private static final String AUTOMATIONS = "Automations";
	private static final String ACTIVE_SCRIPT = "Active Script";
	private static final String SCHEDULES = "Schedules";
	private static final String CONSOLE = "Console";
	private static final String SETTINGS = "Settings";
	private static final int SIDEBAR_WIDTH = 204;
	private static final Icon ONLINE = GenericClientDashboardStyle.dot(GenericClientDashboardStyle.ACCENT);
	private static final Icon OFFLINE = GenericClientDashboardStyle.dot(GenericClientDashboardStyle.MUTED);

	private final Window owner;
	private final JPanel content = new JPanel(new BorderLayout());
	private final JPanel cards = new JPanel(new CardLayout());
	private final JLabel connection = GenericClientDashboardStyle.strong("Starting");
	private final JLabel activity = GenericClientDashboardStyle.small(" ");
	private final LongBreakBanner longBreak;
	private final GenericClientActiveScriptPanel activeScript;
	private final GenericClientScriptsPanel scripts;
	private final GenericClientAutomationPanel automations;
	private final GenericClientConsolePanel console;
	private final GenericClientSettingsPanel settings;
	private final List<JButton> navigation;
	private JDialog window;

	GenericClientDashboard(Window owner, GenericClientDashboardActions actions, GenericClientLuaHost host)
	{
		this(owner, actions, host, null);
	}

	GenericClientDashboard(
		Window owner,
		GenericClientDashboardActions actions,
		GenericClientLuaHost host,
		GenericClientAutomationScheduler scheduler)
	{
		this.owner = owner;
		longBreak = new LongBreakBanner(actions);
		activeScript = new GenericClientActiveScriptPanel(host);
		scripts = new GenericClientScriptsPanel(host);
		automations = new GenericClientAutomationPanel(scheduler);
		console = new GenericClientConsolePanel(actions, host);
		settings = new GenericClientSettingsPanel(actions);
		cards.setBackground(GenericClientDashboardStyle.BACKGROUND);
		cards.add(activeScript, ACTIVE_SCRIPT);
		cards.add(scripts, AUTOMATIONS);
		cards.add(automations, SCHEDULES);
		cards.add(console, CONSOLE);
		cards.add(settings, SETTINGS);

		navigation = Arrays.asList(
			nav(ACTIVE_SCRIPT, GenericClientDashboardStyle.NavGlyph.ACTIVE),
			nav(AUTOMATIONS, GenericClientDashboardStyle.NavGlyph.PLAY),
			nav(SCHEDULES, GenericClientDashboardStyle.NavGlyph.ACTIVE),
			nav(CONSOLE, GenericClientDashboardStyle.NavGlyph.CONSOLE),
			nav(SETTINGS, GenericClientDashboardStyle.NavGlyph.SLIDERS));
		content.setBackground(GenericClientDashboardStyle.BACKGROUND);
		content.add(createSidebar(), BorderLayout.WEST);
		content.add(cards, BorderLayout.CENTER);
		show(AUTOMATIONS);
	}

	void open()
	{
		runOnEdt(() ->
		{
			if (window == null)
			{
				window = new JDialog(owner, "GenericClient", JDialog.ModalityType.MODELESS);
				window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
				window.setContentPane(content);
				window.setMinimumSize(new Dimension(880, 620));
				window.setSize(1040, 760);
				window.setLocationRelativeTo(owner);
			}
			window.setVisible(true);
			window.toFront();
			window.requestFocus();
		});
	}

	JPanel getContent()
	{
		return content;
	}

	void updateLiveState(String gameState, String activeScript, String scriptStatus, String lastResult)
	{
		runOnEdt(() ->
		{
			boolean connected = "LOGGED_IN".equals(gameState);
			connection.setText(connected ? "Connected" : readable(gameState));
			connection.setIcon(connected ? ONLINE : OFFLINE);
			boolean running = activeScript != null && !activeScript.isEmpty() && !"none".equals(activeScript);
			activity.setText(running
				? scripts.displayName(activeScript) + "  ·  " + GenericClientScriptsPanel.describe(scriptStatus)
				: " ");
			console.updateLastResult(lastResult);
		});
	}

	void updateLuaState(String activeScript, String scriptStatus, String logs)
	{
		runOnEdt(() ->
		{
			this.activeScript.update();
			scripts.update(activeScript, scriptStatus, logs);
		});
	}

	void updateMouseState(
		String currentProfile,
		List<String> profiles,
		GenericClientMouseEffect effect,
		boolean recording,
		int recordedTemplates,
		boolean showMouseTile)
	{
		runOnEdt(() -> settings.updateMouse(
			currentProfile,
			profiles,
				effect,
				recording,
				recordedTemplates,
				showMouseTile));
	}

	void updateBehaviorState(Map<String, Object> behavior)
	{
		runOnEdt(() ->
		{
			settings.updateBehavior(behavior);
			if (behavior != null && "long_break".equals(behavior.get("state")))
			{
				Object remaining = behavior.get("break_remaining_millis");
				longBreak.update(remaining instanceof Number ? ((Number) remaining).longValue() : 0L);
			}
			else
			{
				longBreak.hideBanner();
			}
		});
	}

	void updateAutomationState(Map<String, Object> automation)
	{
		runOnEdt(() -> automations.update(automation));
	}

	private JPanel createSidebar()
	{
		JPanel sidebar = new JPanel(new BorderLayout());
		sidebar.setBackground(GenericClientDashboardStyle.SURFACE);
		sidebar.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 0, 1, GenericClientDashboardStyle.BORDER),
			BorderFactory.createEmptyBorder(18, 12, 18, 12)));
		sidebar.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));

		JPanel brand = GenericClientDashboardStyle.panel(new BorderLayout(10, 0));
		brand.setBorder(BorderFactory.createEmptyBorder(2, 6, 22, 6));
		brand.add(new BrandMark(), BorderLayout.WEST);
		brand.add(GenericClientDashboardStyle.heading("GenericClient"), BorderLayout.CENTER);

		JPanel links = GenericClientDashboardStyle.stack(2, navigation.toArray(new JButton[0]));
		sidebar.add(GenericClientDashboardStyle.stack(0, brand, links), BorderLayout.NORTH);
		sidebar.add(createStatus(), BorderLayout.SOUTH);
		return sidebar;
	}

	private JPanel createStatus()
	{
		connection.setIcon(OFFLINE);
		connection.setIconTextGap(8);
		activity.setBorder(BorderFactory.createEmptyBorder(3, 16, 0, 0));
		JPanel status = GenericClientDashboardStyle.stack(0, connection, activity);
		status.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, GenericClientDashboardStyle.BORDER),
			BorderFactory.createEmptyBorder(14, 6, 0, 6)));
		JPanel footer = GenericClientDashboardStyle.panel(new BorderLayout(0, 10));
		footer.add(longBreak, BorderLayout.NORTH);
		footer.add(status, BorderLayout.SOUTH);
		return footer;
	}

	private JButton nav(String name, GenericClientDashboardStyle.NavGlyph glyph)
	{
		JButton button = GenericClientDashboardStyle.navButton(name, glyph);
		button.setActionCommand(name);
		button.addActionListener(event -> show(event.getActionCommand()));
		return button;
	}

	private void show(String name)
	{
		((CardLayout) cards.getLayout()).show(cards, name);
		for (JButton button : navigation)
		{
			GenericClientDashboardStyle.selectNav(button, name.equals(button.getActionCommand()));
		}
	}

	private static String readable(String value)
	{
		String text = GenericClientDashboardStyle.humanize(value);
		return text.isEmpty() ? "Disconnected" : text;
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

	@Override
	public void close()
	{
		runOnEdt(() ->
		{
			if (window != null)
			{
				window.dispose();
				window = null;
			}
		});
	}

	private static final class BrandMark extends JPanel
	{
		private static final int SIZE = 26;

		private BrandMark()
		{
			setOpaque(false);
			GenericClientDashboardStyle.size(this, SIZE, SIZE);
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D copy = GenericClientDashboardStyle.prepare(graphics);
			try
			{
				copy.setColor(GenericClientDashboardStyle.ACCENT);
				copy.fill(GenericClientDashboardStyle.round(0, 0, SIZE, SIZE, 8));
				copy.setFont(GenericClientDashboardStyle.HEADING_FONT.deriveFont(Font.BOLD, 14f));
				copy.setColor(GenericClientDashboardStyle.ACCENT_INK);
				FontMetrics metrics = copy.getFontMetrics();
				int x = (SIZE - metrics.stringWidth("G")) / 2;
				int y = (SIZE - metrics.getHeight()) / 2 + metrics.getAscent();
				copy.drawString("G", x, y);
			}
			finally
			{
				copy.dispose();
			}
		}
	}

	private static final class LongBreakBanner extends JPanel
	{
		private final GenericClientDashboardActions actions;
		private final JLabel remaining = GenericClientDashboardStyle.small("");
		private final JButton end = GenericClientDashboardStyle.ghostButton("×");
		private boolean ending;

		private LongBreakBanner(GenericClientDashboardActions actions)
		{
			super(new BorderLayout(8, 0));
			this.actions = actions;
			setOpaque(false);
			setVisible(false);
			setBorder(BorderFactory.createEmptyBorder(9, 11, 9, 7));
			setPreferredSize(new Dimension(SIDEBAR_WIDTH - 24, 54));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
			add(GenericClientDashboardStyle.stack(1,
				GenericClientDashboardStyle.strong("Break"), remaining), BorderLayout.CENTER);
			end.setToolTipText("End long break");
			end.getAccessibleContext().setAccessibleName("End long break");
			end.addActionListener(event -> endBreak());
			add(end, BorderLayout.EAST);
		}

		private void update(long remainingMillis)
		{
			if (!ending)
			{
				remaining.setText("Long · " + formatDuration(remainingMillis) + " left");
				remaining.setToolTipText(null);
			}
			setVisible(true);
		}

		private void hideBanner()
		{
			ending = false;
			end.setEnabled(true);
			remaining.setToolTipText(null);
			setVisible(false);
		}

		private void endBreak()
		{
			if (ending)
			{
				return;
			}
			ending = true;
			end.setEnabled(false);
			remaining.setText("Ending...");
			actions.endLongBreak().whenComplete((result, error) -> runOnEdt(() ->
			{
				if (error == null)
				{
					hideBanner();
				}
				else
				{
					ending = false;
					end.setEnabled(true);
					remaining.setText("Could not end break");
					remaining.setToolTipText(GenericClientDashboardStyle.message(error));
				}
			}));
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D copy = GenericClientDashboardStyle.prepare(graphics);
			try
			{
				copy.setColor(GenericClientDashboardStyle.RAISED);
				copy.fill(GenericClientDashboardStyle.round(
					0, 0, getWidth(), getHeight(), GenericClientDashboardStyle.RADIUS));
				copy.setColor(GenericClientDashboardStyle.BORDER_STRONG);
				copy.draw(GenericClientDashboardStyle.round(
					0.5f, 0.5f, getWidth() - 1, getHeight() - 1,
					GenericClientDashboardStyle.RADIUS));
				copy.setColor(GenericClientDashboardStyle.WARNING);
				copy.fill(GenericClientDashboardStyle.round(0, 8, 3, getHeight() - 16, 2));
			}
			finally
			{
				copy.dispose();
			}
			super.paintComponent(graphics);
		}

		private static String formatDuration(long millis)
		{
			long seconds = Math.max(0L, (millis + 999L) / 1_000L);
			if (seconds < 60L)
			{
				return seconds + "s";
			}
			return seconds / 60L + "m " + seconds % 60L + "s";
		}
	}
}
