package com.genericclient;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

final class GenericClientDashboardStyle
{
	static final Color BACKGROUND = new Color(13, 17, 23);
	static final Color SURFACE = new Color(20, 26, 34);
	static final Color SURFACE_RAISED = new Color(27, 35, 45);
	static final Color BORDER = new Color(43, 54, 68);
	static final Color TEXT = new Color(241, 245, 249);
	static final Color MUTED = new Color(142, 156, 173);
	static final Color ACCENT = new Color(76, 220, 162);
	static final Color ACCENT_DARK = new Color(34, 112, 83);
	static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 22);
	static final Font SECTION_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
	static final Font BODY_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	static final Font MONO_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 12);

	private GenericClientDashboardStyle()
	{
	}

	static JPanel page()
	{
		JPanel panel = new DashboardPage();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(BACKGROUND);
		panel.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
		return panel;
	}

	static JPanel section(String title)
	{
		JPanel panel = new CardPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
		panel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
		JLabel heading = new JLabel(title.toUpperCase(java.util.Locale.ROOT));
		heading.setForeground(MUTED);
		heading.setFont(SECTION_FONT);
		heading.setAlignmentX(JLabel.LEFT_ALIGNMENT);
		panel.add(heading);
		panel.add(Box.createVerticalStrut(10));
		return panel;
	}

	static JPanel row(String name, java.awt.Component value)
	{
		JPanel row = new JPanel(new BorderLayout(12, 0));
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
		row.setAlignmentX(JPanel.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
		JLabel key = new JLabel(name);
		key.setForeground(MUTED);
		key.setFont(BODY_FONT);
		row.add(key, BorderLayout.WEST);
		row.add(value, BorderLayout.CENTER);
		return row;
	}

	static JPanel settingRow(String name, java.awt.Component value)
	{
		JPanel row = new JPanel(new BorderLayout(16, 0));
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
		row.setAlignmentX(JPanel.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
		JLabel key = new JLabel(name);
		key.setForeground(MUTED);
		key.setFont(BODY_FONT);
		row.add(key, BorderLayout.WEST);
		row.add(value, BorderLayout.EAST);
		return row;
	}

	static JButton button(String text)
	{
		JButton button = new JButton(text);
		button.setFont(BODY_FONT.deriveFont(Font.BOLD));
		button.setForeground(TEXT);
		button.setBackground(SURFACE_RAISED);
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(BORDER),
			new EmptyBorder(8, 14, 8, 14)));
		button.setMargin(new Insets(0, 0, 0, 0));
		button.setFocusPainted(false);
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return button;
	}

	static JButton primaryButton(String text)
	{
		JButton button = button(text);
		button.setForeground(new Color(8, 22, 16));
		button.setBackground(ACCENT);
		button.setBorder(new EmptyBorder(10, 18, 10, 18));
		return button;
	}

	static JButton navButton(String text)
	{
		JButton button = button(text);
		button.setHorizontalAlignment(JButton.LEFT);
		button.setBorder(new EmptyBorder(11, 14, 11, 14));
		button.setBackground(BACKGROUND);
		return button;
	}

	static void selectNav(JButton button, boolean selected)
	{
		button.setBackground(selected ? SURFACE_RAISED : BACKGROUND);
		button.setForeground(selected ? ACCENT : MUTED);
	}

	static void styleControl(JComponent control)
	{
		control.setFont(BODY_FONT);
		control.setForeground(TEXT);
		control.setBackground(SURFACE_RAISED);
	}

	static JLabel value(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(TEXT);
		label.setFont(BODY_FONT);
		return label;
	}

	static JLabel muted(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(MUTED);
		label.setFont(BODY_FONT);
		return label;
	}

	static JTextArea textArea(String text, int rows, boolean wrap)
	{
		JTextArea area = new JTextArea(text);
		area.setRows(rows);
		area.setLineWrap(wrap);
		area.setWrapStyleWord(wrap);
		area.setBackground(SURFACE_RAISED);
		area.setForeground(TEXT);
		area.setCaretColor(ACCENT);
		area.setSelectionColor(ACCENT_DARK);
		area.setSelectedTextColor(TEXT);
		area.setFont(MONO_FONT);
		area.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		return area;
	}

	static JScrollPane scroll(JTextArea area, int height)
	{
		JScrollPane scroll = new JScrollPane(area);
		scroll.setBorder(BorderFactory.createLineBorder(BORDER));
		scroll.getViewport().setBackground(SURFACE_RAISED);
		scroll.setPreferredSize(new Dimension(640, height));
		scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		scroll.setAlignmentX(JPanel.LEFT_ALIGNMENT);
		return scroll;
	}

	static JScrollPane pageScroll(JPanel page)
	{
		JScrollPane scroll = new JScrollPane(page);
		scroll.setBorder(null);
		scroll.getViewport().setBackground(BACKGROUND);
		scroll.getVerticalScrollBar().setUnitIncrement(18);
		return scroll;
	}

	private static final class DashboardPage extends JPanel implements Scrollable
	{
		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return new Dimension(660, getPreferredSize().height);
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 18;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return Math.max(18, visibleRect.height - 18);
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	private static final class CardPanel extends JPanel
	{
		private CardPanel()
		{
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D copy = (Graphics2D) graphics.create();
			try
			{
				copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				copy.setColor(SURFACE);
				copy.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
				copy.setStroke(new BasicStroke(1.0f));
				copy.setColor(BORDER);
				copy.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
			}
			finally
			{
				copy.dispose();
			}
			super.paintComponent(graphics);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}
}
