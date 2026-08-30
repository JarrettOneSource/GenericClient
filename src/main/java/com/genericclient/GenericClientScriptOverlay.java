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
	private final Supplier<String> activitySupplier;
	private final Supplier<String> scriptStateSupplier;

	GenericClientScriptOverlay(
		Supplier<GenericClientActiveScript> scriptSupplier,
		Supplier<String> activitySupplier,
		Supplier<String> scriptStateSupplier)
	{
		this.scriptSupplier = scriptSupplier;
		this.activitySupplier = activitySupplier;
		this.scriptStateSupplier = scriptStateSupplier;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		GenericClientActiveScript script = scriptSupplier.get();
		boolean running = script != null && script.isRunning();
		String title = running ? compact(script.getName(), 24) : "GenericClient";
		String activity = displayDescriptor(activitySupplier.get());
		String scriptState = running
			? compact(displayDescriptor(scriptStateSupplier.get()), 28)
			: "Idle";
		String meta = running ? formatRuntime(script.getRuntimeMillis()) : "";
		List<GenericClientOverlayRow> rows = running
			? script.getOverlayRows()
			: java.util.Collections.emptyList();
		FontMetrics titleMetrics = graphics.getFontMetrics(TITLE);
		FontMetrics bodyMetrics = graphics.getFontMetrics(BODY);
		int width = titleMetrics.stringWidth(title) + bodyMetrics.stringWidth(meta) + 32;
		width = Math.max(width,
			bodyMetrics.stringWidth("Global") + bodyMetrics.stringWidth(activity) +
			bodyMetrics.stringWidth("Script") + bodyMetrics.stringWidth(scriptState) + 42);
		for (GenericClientOverlayRow row : rows)
		{
			width = Math.max(width,
				bodyMetrics.stringWidth(row.getLabel()) + bodyMetrics.stringWidth(row.getValue()) + 28);
		}
		width = Math.max(176, width);
		int height = 39 + rows.size() * 14;

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
			if (!meta.isEmpty())
			{
				copy.setColor(MUTED);
				copy.drawString(meta, width - bodyMetrics.stringWidth(meta) - 9, 17);
			}

			int globalValueX = 9 + bodyMetrics.stringWidth("Global") + 5;
			copy.setColor(MUTED);
			copy.drawString("Global", 9, 32);
			copy.setColor(TEXT);
			copy.drawString(activity, globalValueX, 32);
			int scriptValueWidth = bodyMetrics.stringWidth(scriptState);
			int scriptLabelX = width - 14 - scriptValueWidth - bodyMetrics.stringWidth("Script");
			copy.setColor(MUTED);
			copy.drawString("Script", scriptLabelX, 32);
			copy.setColor(TEXT);
			copy.drawString(scriptState, scriptLabelX + bodyMetrics.stringWidth("Script") + 5, 32);

			int baseline = 47;
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

	private static String displayDescriptor(String descriptor)
	{
		String value = descriptor == null || descriptor.trim().isEmpty()
			? "idle"
			: descriptor.trim().replace('_', ' ').replace('-', ' ').replace('.', ' ')
				.replaceAll("\\s+", " ");
		return Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}
}
