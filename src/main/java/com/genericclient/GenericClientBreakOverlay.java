package com.genericclient;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Map;
import java.util.function.Supplier;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

final class GenericClientBreakOverlay extends Overlay
{
	private static final Color BACKGROUND = new Color(12, 17, 23, 224);
	private static final Color BORDER = new Color(255, 255, 255, 32);
	private static final Color TEXT = new Color(245, 248, 250);
	private static final Color MICRO = new Color(92, 200, 255);
	private static final Color LONG = new Color(255, 184, 92);
	private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);

	private final Supplier<Map<String, Object>> statusSupplier;

	GenericClientBreakOverlay(Supplier<Map<String, Object>> statusSupplier)
	{
		this.statusSupplier = statusSupplier;
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Map<String, Object> status = statusSupplier.get();
		if (status == null)
		{
			return null;
		}
		String state = String.valueOf(status.get("state"));
		boolean micro = "micro_break".equals(state);
		if (!micro && !"long_break".equals(state))
		{
			return null;
		}

		long remaining = status.get("break_remaining_millis") instanceof Number
			? ((Number) status.get("break_remaining_millis")).longValue()
			: 0L;
		String text = (micro ? "MICRO" : "LONG") + "  ·  " + formatDuration(remaining);
		FontMetrics metrics = graphics.getFontMetrics(FONT);
		int width = metrics.stringWidth(text) + 42;
		int height = 28;

		Graphics2D copy = (Graphics2D) graphics.create();
		try
		{
			copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			copy.setColor(BACKGROUND);
			copy.fillRoundRect(0, 0, width, height, 14, 14);
			copy.setStroke(new BasicStroke(1.0f));
			copy.setColor(BORDER);
			copy.drawRoundRect(0, 0, width - 1, height - 1, 14, 14);
			copy.setColor(micro ? MICRO : LONG);
			copy.fillOval(11, 10, 8, 8);
			copy.setFont(FONT);
			copy.setColor(TEXT);
			copy.drawString(text, 28, 19);
		}
		finally
		{
			copy.dispose();
		}
		return new Dimension(width, height);
	}

	private static String formatDuration(long millis)
	{
		long seconds = Math.max(0L, (millis + 999L) / 1_000L);
		if (seconds < 60L)
		{
			return seconds + "s";
		}
		long minutes = seconds / 60L;
		long remainder = seconds % 60L;
		return remainder == 0L ? minutes + "m" : minutes + "m " + remainder + "s";
	}
}
