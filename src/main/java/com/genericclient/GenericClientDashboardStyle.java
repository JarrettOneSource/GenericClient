package com.genericclient;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Rectangle;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JTextArea;
import net.runelite.client.ui.ColorScheme;

final class GenericClientDashboardStyle
{
	private GenericClientDashboardStyle()
	{
	}

	static JPanel page()
	{
		JPanel panel = new DashboardPage();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		return panel;
	}

	static JPanel section(String title)
	{
		JPanel panel = new WidthFillPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createTitledBorder(
				BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
				title,
				0,
				0,
				null,
				Color.WHITE),
			BorderFactory.createEmptyBorder(2, 4, 4, 4)));
		panel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
		return panel;
	}

	static JPanel row(String name, java.awt.Component value)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(JPanel.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		JLabel key = new JLabel(name);
		key.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(key, BorderLayout.WEST);
		row.add(value, BorderLayout.CENTER);
		return row;
	}

	static JPanel settingRow(String name, java.awt.Component value)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(JPanel.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		JLabel key = new JLabel(name);
		key.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(key, BorderLayout.WEST);
		row.add(value, BorderLayout.EAST);
		return row;
	}

	static JButton button(String text)
	{
		JButton button = new JButton(text);
		button.setMargin(new Insets(2, 4, 2, 4));
		return button;
	}

	static JLabel value(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(Color.WHITE);
		return label;
	}

	static JLabel muted(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return label;
	}

	static JTextArea textArea(String text, int rows, boolean wrap)
	{
		JTextArea area = new JTextArea(text);
		area.setRows(rows);
		area.setLineWrap(wrap);
		area.setWrapStyleWord(wrap);
		area.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		area.setForeground(Color.WHITE);
		area.setCaretColor(Color.WHITE);
		area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
		return area;
	}

	static JScrollPane scroll(JTextArea area, int height)
	{
		JScrollPane scroll = new JScrollPane(area);
		scroll.setPreferredSize(new Dimension(205, height));
		scroll.setAlignmentX(JPanel.LEFT_ALIGNMENT);
		return scroll;
	}

	private static final class DashboardPage extends JPanel implements Scrollable
	{
		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return new Dimension(205, getPreferredSize().height);
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return Math.max(16, visibleRect.height - 16);
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

	private static final class WidthFillPanel extends JPanel
	{
		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}
}
