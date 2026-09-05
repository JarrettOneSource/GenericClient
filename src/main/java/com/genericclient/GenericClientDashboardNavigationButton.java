package com.genericclient;

import static com.genericclient.GenericClientDashboardStyle.ACCENT;
import static com.genericclient.GenericClientDashboardStyle.BODY_STRONG_FONT;
import static com.genericclient.GenericClientDashboardStyle.MUTED;
import static com.genericclient.GenericClientDashboardStyle.RADIUS;
import static com.genericclient.GenericClientDashboardStyle.RAISED;
import static com.genericclient.GenericClientDashboardStyle.SURFACE;
import static com.genericclient.GenericClientDashboardStyle.SURFACE_HOVER;
import static com.genericclient.GenericClientDashboardStyle.TEXT;
import static com.genericclient.GenericClientDashboardStyle.TEXT_SECONDARY;
import static com.genericclient.GenericClientDashboardStyle.prepare;
import static com.genericclient.GenericClientDashboardStyle.round;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import javax.swing.JButton;
import javax.swing.border.EmptyBorder;

final class GenericClientDashboardNavigationButton extends JButton
{
	private static final int HEIGHT = 36;
	private static final int GLYPH = 16;
	private final Glyph glyph;
	private boolean active;

	GenericClientDashboardNavigationButton(String text, Glyph glyph)
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

	void setActive(boolean active)
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

	enum Glyph
	{
		ACTIVE,
		PLAY,
		CONSOLE,
		SLIDERS
	}

	private static void paintGlyph(Graphics2D copy, Glyph glyph, int x, int y, Color color, Color ground)
	{
		copy.setColor(color);
		copy.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		switch (glyph)
		{
			case ACTIVE:
				copy.draw(new Ellipse2D.Float(x + 3, y + 3, 10, 10));
				copy.fill(new Ellipse2D.Float(x + 6, y + 6, 4, 4));
				break;
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
}
