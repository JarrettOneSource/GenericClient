package com.genericclient;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

final class GenericClientDashboard implements AutoCloseable
{
	private static final String AUTOMATIONS = "Automations";
	private static final String CONSOLE = "Console";
	private static final String SETTINGS = "Settings";

	private final Window owner;
	private final JPanel content = new JPanel(new BorderLayout());
	private final JLabel status = GenericClientDashboardStyle.muted("Starting");
	private final JPanel cards = new JPanel(new CardLayout());
	private final GenericClientScriptsPanel scripts;
	private final GenericClientConsolePanel console;
	private final GenericClientSettingsPanel settings;
	private final List<JButton> navigation;
	private JDialog window;

	GenericClientDashboard(Window owner, GenericClientDashboardActions actions, GenericClientLuaHost host)
	{
		this.owner = owner;
		content.setBackground(GenericClientDashboardStyle.BACKGROUND);
		content.add(createHeader(), BorderLayout.NORTH);

		scripts = new GenericClientScriptsPanel(host);
		console = new GenericClientConsolePanel(actions, host);
		settings = new GenericClientSettingsPanel(actions);
		cards.setBackground(GenericClientDashboardStyle.BACKGROUND);
		cards.add(scripts, AUTOMATIONS);
		cards.add(console, CONSOLE);
		cards.add(settings, SETTINGS);

		JButton automations = nav(AUTOMATIONS);
		JButton consoleButton = nav(CONSOLE);
		JButton settingsButton = nav(SETTINGS);
		navigation = Arrays.asList(automations, consoleButton, settingsButton);
		JPanel body = new JPanel(new BorderLayout());
		body.setBackground(GenericClientDashboardStyle.BACKGROUND);
		body.add(createNavigation(), BorderLayout.WEST);
		body.add(cards, BorderLayout.CENTER);
		content.add(body, BorderLayout.CENTER);
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
				window.setMinimumSize(new Dimension(820, 580));
				window.setSize(980, 720);
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
			String label = connected ? "Connected" : readable(gameState);
			if (activeScript != null && !"none".equals(activeScript))
			{
				label += "  ·  " + activeScript;
			}
			status.setText(label);
			status.setForeground(connected
				? GenericClientDashboardStyle.ACCENT
				: GenericClientDashboardStyle.MUTED);
			console.updateLastResult(lastResult);
		});
	}

	void updateNpcDiagnostics(String diagnostics)
	{
		runOnEdt(() -> console.updateNpcDiagnostics(diagnostics));
	}

	void updateLuaState(String activeScript, String scriptStatus, String logs)
	{
		runOnEdt(() -> scripts.update(activeScript, scriptStatus, logs));
	}

	void updateMouseState(
		String currentProfile,
		List<String> profiles,
		GenericClientMouseEffect effect,
		boolean recording,
		int recordedTemplates)
	{
		runOnEdt(() -> settings.updateMouse(
			currentProfile,
			profiles,
			effect,
			recording,
			recordedTemplates));
	}

	void updateBehaviorState(Map<String, Object> behavior)
	{
		runOnEdt(() -> settings.updateBehavior(behavior));
	}

	private JPanel createHeader()
	{
		JPanel header = new JPanel(new BorderLayout(16, 0));
		header.setBackground(GenericClientDashboardStyle.SURFACE);
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, GenericClientDashboardStyle.BORDER),
			BorderFactory.createEmptyBorder(14, 20, 14, 22)));
		header.add(new BrandMark(), BorderLayout.WEST);

		JPanel titles = new JPanel();
		titles.setOpaque(false);
		titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
		JLabel title = new JLabel("GenericClient");
		title.setFont(GenericClientDashboardStyle.TITLE_FONT);
		title.setForeground(GenericClientDashboardStyle.TEXT);
		JLabel subtitle = new JLabel("Automation workspace");
		subtitle.setFont(GenericClientDashboardStyle.BODY_FONT);
		subtitle.setForeground(GenericClientDashboardStyle.MUTED);
		titles.add(title);
		titles.add(subtitle);
		header.add(titles, BorderLayout.CENTER);

		status.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(GenericClientDashboardStyle.BORDER),
			BorderFactory.createEmptyBorder(7, 12, 7, 12)));
		header.add(status, BorderLayout.EAST);
		return header;
	}

	private JPanel createNavigation()
	{
		JPanel rail = new JPanel();
		rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS));
		rail.setBackground(GenericClientDashboardStyle.BACKGROUND);
		rail.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 0, 1, GenericClientDashboardStyle.BORDER),
			BorderFactory.createEmptyBorder(20, 14, 20, 14)));
		rail.setPreferredSize(new Dimension(184, 0));
		for (JButton button : navigation)
		{
			button.setAlignmentX(JButton.LEFT_ALIGNMENT);
			button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
			rail.add(button);
			rail.add(Box.createVerticalStrut(6));
		}
		rail.add(Box.createVerticalGlue());
		return rail;
	}

	private JButton nav(String name)
	{
		JButton button = GenericClientDashboardStyle.navButton(name);
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
		if (value == null || value.isEmpty())
		{
			return "Disconnected";
		}
		String lower = value.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
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
		private BrandMark()
		{
			setOpaque(false);
			setPreferredSize(new Dimension(42, 42));
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D copy = (Graphics2D) graphics.create();
			try
			{
				copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				copy.setColor(GenericClientDashboardStyle.ACCENT);
				copy.fillRoundRect(0, 0, 42, 42, 14, 14);
				copy.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 21));
				copy.setColor(new Color(8, 22, 16));
				copy.drawString("G", 13, 29);
			}
			finally
			{
				copy.dispose();
			}
		}
	}
}
