package com.genericclient;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;
import java.util.function.Supplier;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

final class GenericClientScriptOverlay extends Overlay
{
	private static final Color BACKGROUND = new Color(12, 17, 23, 224);
	private static final Color BORDER = new Color(255, 255, 255, 30);
	private static final Color TEXT = new Color(242, 246, 250);
	private static final Color MUTED = new Color(153, 166, 181);
	private static final Color ACCENT = new Color(76, 220, 162);
	private static final Font TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 11);
	private static final Font BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 10);

	private final Supplier<GenericClientActiveScript> scriptSupplier;

	GenericClientScriptOverlay(Supplier<GenericClientActiveScript> scriptSupplier)
	{
		this.scriptSupplier = scriptSupplier;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		GenericClientActiveScript script = scriptSupplier.get();
		if (script == null || !script.isRunning())
		{
			return null;
		}

		String title = compact(script.getName(), 24);
		String runtime = formatRuntime(script.getRuntimeMillis());
		List<GenericClientOverlayRow> rows = script.getOverlayRows();
		FontMetrics titleMetrics = graphics.getFontMetrics(TITLE);
		FontMetrics bodyMetrics = graphics.getFontMetrics(BODY);
		int width = titleMetrics.stringWidth(title) + bodyMetrics.stringWidth(runtime) + 44;
		for (GenericClientOverlayRow row : rows)
		{
			width = Math.max(width,
				bodyMetrics.stringWidth(row.getLabel()) + bodyMetrics.stringWidth(row.getValue()) + 28);
		}
		width = Math.max(112, width);
		int height = 25 + rows.size() * 14;

		Graphics2D copy = (Graphics2D) graphics.create();
		try
		{
			copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			copy.setColor(BACKGROUND);
			copy.fillRoundRect(0, 0, width, height, 10, 10);
			copy.setStroke(new BasicStroke(1.0f));
			copy.setColor(BORDER);
			copy.drawRoundRect(0, 0, width - 1, height - 1, 10, 10);
			copy.setColor(ACCENT);
			copy.fillOval(9, 9, 6, 6);
			copy.setFont(TITLE);
			copy.setColor(TEXT);
			copy.drawString(title, 21, 17);
			copy.setFont(BODY);
			copy.setColor(MUTED);
			copy.drawString(runtime, width - bodyMetrics.stringWidth(runtime) - 9, 17);

			int baseline = 33;
			for (GenericClientOverlayRow row : rows)
			{
				copy.setColor(MUTED);
				copy.drawString(row.getLabel(), 9, baseline);
				copy.setColor(TEXT);
				copy.drawString(
					row.getValue(),
					width - bodyMetrics.stringWidth(row.getValue()) - 9,
					baseline);
				baseline += 14;
			}
		}
		finally
		{
			copy.dispose();
		}
		return new Dimension(width, height);
	}

	static String formatRuntime(long runtimeMillis)
	{
		long seconds = Math.max(0L, runtimeMillis / 1_000L);
		long hours = seconds / 3_600L;
		long minutes = seconds / 60L % 60L;
		long remainder = seconds % 60L;
		return hours == 0L
			? String.format(java.util.Locale.ROOT, "%d:%02d", minutes, remainder)
			: String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder);
	}

	private static String compact(String value, int maximum)
	{
		if (value == null)
		{
			return "Script";
		}
		return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…";
	}
}
