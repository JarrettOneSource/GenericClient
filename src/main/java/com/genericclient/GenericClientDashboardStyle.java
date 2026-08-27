package com.genericclient;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.font.TextAttribute;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * Design system for the popout dashboard: palette, type scale, and the small
 * set of custom-painted components every page is built from. Components paint
 * themselves so the look does not depend on the active look and feel.
 */
final class GenericClientDashboardStyle
{
	static final Color BACKGROUND = new Color(12, 15, 20);
	static final Color SURFACE = new Color(19, 24, 32);
	static final Color SURFACE_HOVER = new Color(24, 31, 41);
	static final Color RAISED = new Color(28, 36, 48);
	static final Color RAISED_HOVER = new Color(37, 47, 62);
	static final Color INSET = new Color(9, 12, 17);
	static final Color BORDER = new Color(35, 45, 58);
	static final Color BORDER_STRONG = new Color(54, 68, 86);
	static final Color TEXT = new Color(238, 242, 247);
	static final Color TEXT_SECONDARY = new Color(179, 191, 204);
	static final Color MUTED = new Color(122, 136, 153);
	static final Color ACCENT = new Color(76, 220, 162);
	static final Color ACCENT_HOVER = new Color(110, 234, 184);
	static final Color ACCENT_PRESSED = new Color(54, 194, 140);
	static final Color ACCENT_INK = new Color(6, 24, 15);
	static final Color DANGER = new Color(244, 112, 126);
	static final Color WARNING = new Color(242, 187, 86);
	private static final Color TRANSPARENT = new Color(0, 0, 0, 0);

	static final int RADIUS = 8;
	static final int CARD_RADIUS = 12;
	static final int CONTROL_HEIGHT = 30;

	static final Font TITLE_FONT = base(Font.BOLD, 18);
	static final Font HEADING_FONT = base(Font.BOLD, 13);
	static final Font BODY_FONT = base(Font.PLAIN, 12);
	static final Font BODY_STRONG_FONT = base(Font.BOLD, 12);
	static final Font SMALL_FONT = base(Font.PLAIN, 11);
	static final Font SMALL_STRONG_FONT = base(Font.BOLD, 11);
	static final Font EYEBROW_FONT = tracked(base(Font.BOLD, 10), 0.14f);
	static final Font MONO_FONT = mono(12);
	static final Font MONO_SMALL_FONT = mono(11);

	enum NavGlyph
	{
		PLAY,
		CONSOLE,
		SLIDERS
	}

	private enum ButtonKind
	{
		PRIMARY,
		SECONDARY,
		GHOST
	}

	private GenericClientDashboardStyle()
	{
	}

	// ---- text ---------------------------------------------------------------

	static JLabel title(String text)
	{
		return label(text, TITLE_FONT, TEXT);
	}

	static JLabel heading(String text)
	{
		return label(text, HEADING_FONT, TEXT);
	}

	static JLabel eyebrow(String text)
	{
		return label(text.toUpperCase(Locale.ROOT), EYEBROW_FONT, MUTED);
	}

	static JLabel strong(String text)
	{
		return label(text, BODY_STRONG_FONT, TEXT);
	}

	static JLabel secondary(String text)
	{
		return label(text, BODY_FONT, TEXT_SECONDARY);
	}

	static JLabel muted(String text)
	{
		return label(text, BODY_FONT, MUTED);
	}

	static JLabel small(String text)
	{
		return label(text, SMALL_FONT, MUTED);
	}

	static JLabel mono(String text)
	{
		return label(text, MONO_SMALL_FONT, TEXT_SECONDARY);
	}

	static JLabel label(String text, Font font, Color color)
	{
		JLabel label = new JLabel(text);
		label.setFont(font);
		label.setForeground(color);
		return label;
	}

	static String humanize(String value)
	{
		if (value == null || value.isEmpty())
		{
			return "";
		}
		String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	// ---- chips and dots -----------------------------------------------------

	static Chip chip(String text, Color tone)
	{
		return new Chip(text, tone);
	}

	static Icon dot(Color color)
	{
		return new DotIcon(color, 8);
	}

	static final class Chip extends JLabel
	{
		private Color tone;

		private Chip(String text, Color tone)
		{
			super(text);
			setFont(SMALL_STRONG_FONT);
			setBorder(new EmptyBorder(3, 9, 3, 9));
			setOpaque(false);
			setTone(tone);
		}

		void setTone(Color tone)
		{
			this.tone = tone;
			setForeground(tone);
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D copy = prepare(graphics);
			try
			{
				int height = getHeight();
				copy.setColor(alpha(tone, 36));
				copy.fill(new RoundRectangle2D.Float(0, 0, getWidth(), height, height, height));
			}
			finally
			{
				copy.dispose();
			}
			super.paintComponent(graphics);
		}
	}

	private static final class DotIcon implements Icon
	{
		private final Color color;
		private final int size;

		private DotIcon(Color color, int size)
		{
			this.color = color;
			this.size = size;
		}

		@Override
		public void paintIcon(Component component, Graphics graphics, int x, int y)
		{
			Graphics2D copy = prepare(graphics);
			try
			{
				copy.setColor(color);
				copy.fill(new Ellipse2D.Float(x, y, size, size));
			}
			finally
			{
				copy.dispose();
			}
		}

		@Override
		public int getIconWidth()
		{
			return size;
		}

		@Override
		public int getIconHeight()
		{
			return size;
		}
	}

	// ---- buttons ------------------------------------------------------------

	static JButton primaryButton(String text)
	{
		return new DashButton(text, ButtonKind.PRIMARY);
	}

	static JButton button(String text)
	{
		return new DashButton(text, ButtonKind.SECONDARY);
	}

	static JButton ghostButton(String text)
	{
		return new DashButton(text, ButtonKind.GHOST);
	}

	static JButton navButton(String text, NavGlyph glyph)
	{
		return new NavButton(text, glyph);
	}

	static void selectNav(JButton button, boolean selected)
	{
		if (button instanceof NavButton)
		{
			((NavButton) button).setActive(selected);
		}
	}

	private static final class DashButton extends JButton
	{
		private final ButtonKind kind;

		private DashButton(String text, ButtonKind kind)
		{
			super(text);
			this.kind = kind;
			int pad = kind == ButtonKind.GHOST ? 10 : 16;
			setFont(kind == ButtonKind.GHOST ? BODY_FONT : BODY_STRONG_FONT);
			setBorder(new EmptyBorder(0, pad, 0, pad));
			setOpaque(false);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setRolloverEnabled(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		}

		@Override
		public Dimension getPreferredSize()
		{
			Insets insets = getInsets();
			FontMetrics metrics = getFontMetrics(getFont());
			int width = metrics.stringWidth(text()) + insets.left + insets.right;
			int minimum = kind == ButtonKind.GHOST ? 0 : 84;
			return new Dimension(Math.max(width, minimum), kind == ButtonKind.GHOST ? 28 : 32);
		}

		@Override
		public Dimension getMinimumSize()
		{
			return getPreferredSize();
		}

		@Override
		public Dimension getMaximumSize()
		{
			return getPreferredSize();
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D copy = prepare(graphics);
			try
			{
				if (!isEnabled())
				{
					copy.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
				}
				ButtonModel model = getModel();
				boolean hover = isEnabled() && model.isRollover();
				boolean pressed = hover && model.isArmed() && model.isPressed();
				int width = getWidth();
				int height = getHeight();
				Color fill = fill(hover, pressed);
				if (fill != null)
				{
					copy.setColor(fill);
					copy.fill(round(0, 0, width, height, RADIUS));
				}
				if (kind == ButtonKind.SECONDARY)
				{
					copy.setColor(hover ? BORDER_STRONG : BORDER);
					copy.draw(round(0.5f, 0.5f, width - 1, height - 1, RADIUS));
				}
				copy.setFont(getFont());
				copy.setColor(textColor(hover));
				FontMetrics metrics = copy.getFontMetrics();
				String text = text();
				int x = (width - metrics.stringWidth(text)) / 2;
				int y = (height - metrics.getHeight()) / 2 + metrics.getAscent();
				copy.drawString(text, x, y);
			}
			finally
			{
				copy.dispose();
			}
		}

		private String text()
		{
			return getText() == null ? "" : getText();
		}

		private Color fill(boolean hover, boolean pressed)
		{
			switch (kind)
			{
				case PRIMARY:
					return pressed ? ACCENT_PRESSED : hover ? ACCENT_HOVER : ACCENT;
				case SECONDARY:
					return pressed ? RAISED : hover ? RAISED_HOVER : RAISED;
				default:
					return pressed ? RAISED_HOVER : hover ? RAISED : null;
			}
		}

		private Color textColor(boolean hover)
		{
			switch (kind)
			{
				case PRIMARY:
					return ACCENT_INK;
				case SECONDARY:
					return TEXT;
				default:
					return hover ? TEXT : TEXT_SECONDARY;
			}
		}
	}

	private static final class NavButton extends JButton
	{
		private static final int HEIGHT = 36;
		private static final int GLYPH = 16;
		private final NavGlyph glyph;
		private boolean active;

		private NavButton(String text, NavGlyph glyph)
		{
			super(text);
			this.glyph = glyph;
			setFont(BODY_STRONG_FONT);
			setBorder(new EmptyBorder(0, 12, 0, 12));
			setOpaque(false);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setRolloverEnabled(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		}

		private void setActive(boolean active)
		{
			this.active = active;
			repaint();
		}

		@Override
		public Dimension getPreferredSize()
		{
			Insets insets = getInsets();
			FontMetrics metrics = getFontMetrics(getFont());
			int width = insets.left + GLYPH + 10 + metrics.stringWidth(text()) + insets.right;
			return new Dimension(width, HEIGHT);
		}

		@Override
		public Dimension getMinimumSize()
		{
			return getPreferredSize();
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, HEIGHT);
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D copy = prepare(graphics);
			try
			{
				int width = getWidth();
				int height = getHeight();
				boolean hover = getModel().isRollover();
				Color ground = active ? RAISED : hover ? SURFACE_HOVER : SURFACE;
				if (active || hover)
				{
					copy.setColor(ground);
					copy.fill(round(0, 0, width, height, RADIUS));
				}
				Insets insets = getInsets();
				Color glyphColor = active ? ACCENT : hover ? TEXT_SECONDARY : MUTED;
				paintGlyph(copy, glyph, insets.left, (height - GLYPH) / 2, glyphColor, ground);
				copy.setFont(getFont());
				copy.setColor(active || hover ? TEXT : TEXT_SECONDARY);
				FontMetrics metrics = copy.getFontMetrics();
				int y = (height - metrics.getHeight()) / 2 + metrics.getAscent();
				copy.drawString(text(), insets.left + GLYPH + 10, y);
			}
			finally
			{
				copy.dispose();
			}
		}

		private String text()
		{
			return getText() == null ? "" : getText();
		}
	}

	private static void paintGlyph(Graphics2D copy, NavGlyph glyph, int x, int y, Color color, Color ground)
	{
		copy.setColor(color);
		copy.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		switch (glyph)
		{
			case PLAY:
			{
				Path2D.Float play = new Path2D.Float();
				play.moveTo(x + 4.5, y + 3);
				play.lineTo(x + 13.5, y + 8);
				play.lineTo(x + 4.5, y + 13);
				play.closePath();
				copy.fill(play);
				break;
			}
			case CONSOLE:
			{
				Path2D.Float chevron = new Path2D.Float();
				chevron.moveTo(x + 3, y + 4.5);
				chevron.lineTo(x + 7.5, y + 8);
				chevron.lineTo(x + 3, y + 11.5);
				copy.draw(chevron);
				copy.draw(new Line2D.Float(x + 9f, y + 12.5f, x + 14f, y + 12.5f));
				break;
			}
			default:
			{
				float[] knobs = {11f, 5.5f, 9f};
				for (int index = 0; index < knobs.length; index++)
				{
					float lineY = y + 3.5f + index * 4.5f;
					copy.setColor(color);
					copy.draw(new Line2D.Float(x + 2f, lineY, x + 14f, lineY));
					copy.setColor(ground);
					copy.fill(new Ellipse2D.Float(x + knobs[index] - 3f, lineY - 3f, 6f, 6f));
					copy.setColor(color);
					copy.fill(new Ellipse2D.Float(x + knobs[index] - 2f, lineY - 2f, 4f, 4f));
				}
				break;
			}
		}
	}

	// ---- layout -------------------------------------------------------------

	static JPanel page()
	{
		JPanel page = new JPanel(new BorderLayout(0, 16));
		page.setOpaque(false);
		page.setBorder(new EmptyBorder(22, 26, 26, 26));
		return page;
	}

	static JPanel pageHeader(String text, JComponent... trailing)
	{
		JPanel header = new Line(new BorderLayout(16, 0));
		header.setBorder(new EmptyBorder(0, 0, 2, 0));
		header.add(title(text), BorderLayout.WEST);
		if (trailing.length > 0)
		{
			header.add(trailing(8, trailing), BorderLayout.EAST);
		}
		return header;
	}

	static JPanel panel(LayoutManager layout)
	{
		return new Line(layout);
	}

	static JComponent spacer()
	{
		JPanel spacer = new JPanel();
		spacer.setOpaque(false);
		return spacer;
	}

	static JPanel stack(int gap, JComponent... items)
	{
		JPanel stack = new JPanel();
		stack.setOpaque(false);
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		for (int index = 0; index < items.length; index++)
		{
			if (index > 0 && gap > 0)
			{
				stack.add(Box.createVerticalStrut(gap));
			}
			items[index].setAlignmentX(Component.LEFT_ALIGNMENT);
			stack.add(items[index]);
		}
		return stack;
	}

	static JPanel inline(int gap, JComponent... items)
	{
		return line(false, gap, items);
	}

	static JPanel trailing(int gap, JComponent... items)
	{
		return line(true, gap, items);
	}

	private static JPanel line(boolean trailing, int gap, JComponent... items)
	{
		Line line = new Line(new BorderLayout());
		line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
		if (trailing)
		{
			line.add(Box.createHorizontalGlue());
		}
		for (int index = 0; index < items.length; index++)
		{
			if (index > 0 && gap > 0)
			{
				line.add(Box.createHorizontalStrut(gap));
			}
			line.add(items[index]);
		}
		if (!trailing)
		{
			line.add(Box.createHorizontalGlue());
		}
		return line;
	}

	static JPanel row(String text, JComponent... controls)
	{
		JPanel row = new Line(new BorderLayout(16, 0));
		row.setBorder(new EmptyBorder(3, 0, 3, 0));
		row.add(secondary(text), BorderLayout.WEST);
		row.add(trailing(8, controls), BorderLayout.EAST);
		return row;
	}

	static JPanel row(String text, JComponent control, String unit)
	{
		JLabel label = small(unit);
		size(label, 28, CONTROL_HEIGHT);
		return row(text, control, label);
	}

	static JPanel group(String text, JComponent... rows)
	{
		JLabel heading = strong(text);
		heading.setBorder(new EmptyBorder(0, 0, 6, 0));
		JComponent[] items = new JComponent[rows.length + 1];
		items[0] = heading;
		System.arraycopy(rows, 0, items, 1, rows.length);
		return stack(0, items);
	}

	static JPanel columns(int gap, JComponent... items)
	{
		JPanel columns = new Line(new GridLayout(1, items.length, gap, 0));
		for (JComponent item : items)
		{
			columns.add(item);
		}
		return columns;
	}

	static Card card(String text, JComponent... actions)
	{
		return new Card(text, actions);
	}

	static final class Card extends JPanel
	{
		private final JPanel body = new JPanel();

		private Card(String text, JComponent... actions)
		{
			super(new BorderLayout(0, 14));
			setOpaque(false);
			setBorder(new EmptyBorder(16, 18, 18, 18));
			JPanel header = new JPanel(new BorderLayout(12, 0));
			header.setOpaque(false);
			header.add(eyebrow(text), BorderLayout.WEST);
			if (actions.length > 0)
			{
				header.add(trailing(4, actions), BorderLayout.EAST);
			}
			add(header, BorderLayout.NORTH);
			body.setOpaque(false);
			body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
			add(body, BorderLayout.CENTER);
		}

		JPanel body()
		{
			return body;
		}

		Card put(JComponent child)
		{
			child.setAlignmentX(Component.LEFT_ALIGNMENT);
			body.add(child);
			return this;
		}

		Card gap(int pixels)
		{
			body.add(Box.createVerticalStrut(pixels));
			return this;
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D copy = prepare(graphics);
			try
			{
				int width = getWidth();
				int height = getHeight();
				copy.setColor(SURFACE);
				copy.fill(round(0, 0, width, height, CARD_RADIUS));
				copy.setColor(BORDER);
				copy.draw(round(0.5f, 0.5f, width - 1, height - 1, CARD_RADIUS));
			}
			finally
			{
				copy.dispose();
			}
		}
	}

	/** Transparent panel that never grows taller than its preferred height. */
	private static class Line extends JPanel
	{
		private Line(LayoutManager layout)
		{
			super(layout);
			setOpaque(false);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	// ---- scrolling and text areas -------------------------------------------

	static JScrollPane scroll(JComponent view)
	{
		Tracking tracking = new Tracking();
		tracking.add(view, BorderLayout.CENTER);
		JScrollPane scroll = new JScrollPane(tracking,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setViewportBorder(null);
		scroll.getViewport().setOpaque(false);
		scroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
		slim(scroll.getVerticalScrollBar());
		slim(scroll.getHorizontalScrollBar());
		return scroll;
	}

	static JTextArea textArea(String placeholder, boolean editable, boolean wrap)
	{
		JTextArea area = new PlaceholderArea(placeholder);
		area.setEditable(editable);
		area.setLineWrap(wrap);
		area.setWrapStyleWord(wrap);
		area.setFont(MONO_FONT);
		area.setForeground(TEXT);
		area.setCaretColor(TEXT);
		area.setSelectionColor(alpha(ACCENT, 80));
		area.setSelectedTextColor(TEXT);
		area.setBackground(TRANSPARENT);
		area.setOpaque(false);
		area.setBorder(BorderFactory.createEmptyBorder());
		area.setTabSize(2);
		return area;
	}

	/** Rounded, inset container for a text area. A height of 0 lets it fill. */
	static JScrollPane inset(JTextArea area, int height)
	{
		InsetScroll scroll = new InsetScroll(area);
		if (height > 0)
		{
			scroll.setPreferredSize(new Dimension(0, height));
			scroll.setMinimumSize(new Dimension(0, height));
			scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		}
		return scroll;
	}

	private static void slim(JScrollBar bar)
	{
		bar.setUI(new SlimScrollBarUI());
		bar.setOpaque(false);
		bar.setUnitIncrement(16);
	}

	private static final class Tracking extends JPanel implements Scrollable
	{
		private Tracking()
		{
			super(new BorderLayout());
			setOpaque(false);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
		{
			return Math.max(16, visible.height - 16);
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return getParent() instanceof JViewport && getParent().getHeight() > getPreferredSize().height;
		}
	}

	private static final class PlaceholderArea extends JTextArea
	{
		private final String placeholder;

		private PlaceholderArea(String placeholder)
		{
			this.placeholder = placeholder == null ? "" : placeholder;
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			super.paintComponent(graphics);
			if (placeholder.isEmpty() || getDocument().getLength() > 0)
			{
				return;
			}
			Graphics2D copy = prepare(graphics);
			try
			{
				copy.setFont(getFont());
				copy.setColor(MUTED);
				Insets insets = getInsets();
				copy.drawString(placeholder, insets.left, insets.top + copy.getFontMetrics().getAscent());
			}
			finally
			{
				copy.dispose();
			}
		}
	}

	private static final class InsetScroll extends JScrollPane
	{
		private InsetScroll(JTextArea area)
		{
			super(area,
				ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
				area.getLineWrap()
					? ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
					: ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
			setOpaque(false);
			setBorder(new EmptyBorder(10, 12, 10, 8));
			setViewportBorder(null);
			getViewport().setOpaque(false);
			getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
			slim(getVerticalScrollBar());
			slim(getHorizontalScrollBar());
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D copy = prepare(graphics);
			try
			{
				int width = getWidth();
				int height = getHeight();
				copy.setColor(INSET);
				copy.fill(round(0, 0, width, height, CARD_RADIUS - 2));
				copy.setColor(BORDER);
				copy.draw(round(0.5f, 0.5f, width - 1, height - 1, CARD_RADIUS - 2));
			}
			finally
			{
				copy.dispose();
			}
		}
	}

	/** Buttonless scrollbar with a rounded thumb and no visible track. */
	private static final class SlimScrollBarUI extends BasicScrollBarUI
	{
		private static final int THICKNESS = 8;

		@Override
		protected void configureScrollBarColors()
		{
		}

		@Override
		protected JButton createDecreaseButton(int orientation)
		{
			return hiddenButton();
		}

		@Override
		protected JButton createIncreaseButton(int orientation)
		{
			return hiddenButton();
		}

		@Override
		protected void paintTrack(Graphics graphics, JComponent component, Rectangle bounds)
		{
		}

		@Override
		protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds)
		{
			if (bounds.isEmpty() || !scrollbar.isEnabled())
			{
				return;
			}
			Graphics2D copy = prepare(graphics);
			try
			{
				copy.setColor(isDragging || isThumbRollover() ? BORDER_STRONG : BORDER);
				copy.fill(round(bounds.x + 1, bounds.y + 1, bounds.width - 2, bounds.height - 2, 3));
			}
			finally
			{
				copy.dispose();
			}
		}

		@Override
		public Dimension getPreferredSize(JComponent component)
		{
			return scrollbar.getOrientation() == JScrollBar.VERTICAL
				? new Dimension(THICKNESS, 48)
				: new Dimension(48, THICKNESS);
		}

		private static JButton hiddenButton()
		{
			JButton button = new JButton();
			Dimension zero = new Dimension(0, 0);
			button.setPreferredSize(zero);
			button.setMinimumSize(zero);
			button.setMaximumSize(zero);
			button.setFocusable(false);
			button.setOpaque(false);
			return button;
		}
	}

	// ---- controls -----------------------------------------------------------

	static <T> JComboBox<T> combo(JComboBox<T> combo, int width)
	{
		combo.setFont(BODY_FONT);
		combo.setForeground(TEXT);
		combo.setBackground(RAISED);
		combo.putClientProperty("FlatLaf.style", String.join("; ",
			"arc: " + RADIUS,
			"borderColor: " + hex(BORDER),
			"focusedBorderColor: " + hex(ACCENT),
			"buttonBackground: " + hex(RAISED),
			"buttonArrowColor: " + hex(MUTED),
			"buttonSeparatorWidth: 0",
			"popupBackground: " + hex(RAISED)));
		size(combo, width, CONTROL_HEIGHT);
		return combo;
	}

	static JSpinner spinner(JSpinner spinner, int width)
	{
		spinner.setFont(BODY_FONT);
		spinner.setForeground(TEXT);
		spinner.setBackground(RAISED);
		spinner.putClientProperty("FlatLaf.style", String.join("; ",
			"arc: " + RADIUS,
			"borderColor: " + hex(BORDER),
			"focusedBorderColor: " + hex(ACCENT),
			"buttonBackground: " + hex(RAISED),
			"buttonArrowColor: " + hex(MUTED),
			"buttonSeparatorWidth: 0",
			"padding: 0,8,0,4"));
		JComponent editor = spinner.getEditor();
		if (editor instanceof JSpinner.DefaultEditor)
		{
			JFormattedTextField field = ((JSpinner.DefaultEditor) editor).getTextField();
			field.setFont(BODY_FONT);
			field.setForeground(TEXT);
			field.setBackground(RAISED);
			field.setCaretColor(TEXT);
			field.setSelectionColor(alpha(ACCENT, 80));
			field.setSelectedTextColor(TEXT);
		}
		size(spinner, width, CONTROL_HEIGHT);
		return spinner;
	}

	static void size(JComponent component, int width, int height)
	{
		Dimension dimension = new Dimension(width, height);
		component.setPreferredSize(dimension);
		component.setMinimumSize(dimension);
		component.setMaximumSize(dimension);
	}

	// ---- painting helpers ---------------------------------------------------

	static Graphics2D prepare(Graphics graphics)
	{
		Graphics2D copy = (Graphics2D) graphics.create();
		copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		copy.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
		if (hints instanceof Map)
		{
			copy.addRenderingHints((Map<?, ?>) hints);
		}
		else
		{
			copy.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		}
		return copy;
	}

	static Shape round(float x, float y, float width, float height, float radius)
	{
		return new RoundRectangle2D.Float(x, y, width, height, radius * 2, radius * 2);
	}

	static Color alpha(Color color, int alpha)
	{
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
	}

	static String hex(Color color)
	{
		String value = String.format(Locale.ROOT, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
		return color.getAlpha() == 255 ? value : value + String.format(Locale.ROOT, "%02x", color.getAlpha());
	}

	private static Font base(int style, int size)
	{
		Font laf = UIManager.getFont("Label.font");
		return new Font(laf == null ? Font.SANS_SERIF : laf.getFamily(), style, size);
	}

	private static Font tracked(Font font, float tracking)
	{
		Map<TextAttribute, Object> attributes = new HashMap<>();
		attributes.put(TextAttribute.TRACKING, tracking);
		return font.deriveFont(attributes);
	}

	private static Font mono(int size)
	{
		Set<String> families = new HashSet<>(Arrays.asList(
			GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
		for (String family : Arrays.asList(
			"JetBrains Mono", "Cascadia Mono", "Cascadia Code", "Consolas",
			"SF Mono", "Menlo", "DejaVu Sans Mono", "Fira Code"))
		{
			if (families.contains(family))
			{
				return new Font(family, Font.PLAIN, size);
			}
		}
		return new Font(Font.MONOSPACED, Font.PLAIN, size);
	}

	/** Human-readable text for a failed future, unwrapping completion wrappers. */
	static String message(Throwable error)
	{
		Throwable cause = error instanceof CompletionException && error.getCause() != null
			? error.getCause()
			: error;
		String message = cause.getMessage();
		return message == null || message.isEmpty() ? cause.getClass().getSimpleName() : message;
	}
}
