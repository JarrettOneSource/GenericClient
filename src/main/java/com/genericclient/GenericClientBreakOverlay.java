package com.genericclient;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseListener;
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
	private static final Color CLOSE_HOVER = new Color(255, 255, 255, 22);
	private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);
	private static final Font CLOSE_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 17);

	private final Supplier<Map<String, Object>> statusSupplier;
	private final Supplier<CompletableFuture<Map<String, Object>>> endBreakAction;
	private final AtomicBoolean ending = new AtomicBoolean();
	private final MouseListener mouseListener = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			return handlePress(event);
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent event)
		{
			return consumeCloseEvent(event);
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent event)
		{
			return consumeCloseEvent(event);
		}

		@Override
		public MouseEvent mouseMoved(MouseEvent event)
		{
			closeHovered = closeHit(event);
			return event;
		}

		@Override
		public MouseEvent mouseExited(MouseEvent event)
		{
			closeHovered = false;
			return event;
		}
	};
	private volatile Rectangle closeBounds = new Rectangle();
	private volatile boolean closeHovered;

	GenericClientBreakOverlay(Supplier<Map<String, Object>> statusSupplier)
	{
		this(statusSupplier, () -> CompletableFuture.completedFuture(Collections.emptyMap()));
	}

	GenericClientBreakOverlay(
		Supplier<Map<String, Object>> statusSupplier,
		Supplier<CompletableFuture<Map<String, Object>>> endBreakAction)
	{
		this.statusSupplier = statusSupplier;
		this.endBreakAction = endBreakAction;
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
		int width = metrics.stringWidth(text) + 66;
		int height = 28;
		closeBounds = new Rectangle(width - 27, 4, 22, 20);

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
			copy.setColor(BORDER);
			copy.drawLine(width - 31, 6, width - 31, height - 7);
			if (closeHovered && !ending.get())
			{
				copy.setColor(CLOSE_HOVER);
				copy.fillRoundRect(closeBounds.x, closeBounds.y,
					closeBounds.width, closeBounds.height, 9, 9);
			}
			copy.setFont(CLOSE_FONT);
			copy.setColor(ending.get() ? BORDER : TEXT);
			String close = ending.get() ? "·" : "×";
			FontMetrics closeMetrics = copy.getFontMetrics();
			int closeX = closeBounds.x + (closeBounds.width - closeMetrics.stringWidth(close)) / 2;
			int closeY = closeBounds.y +
				(closeBounds.height - closeMetrics.getHeight()) / 2 + closeMetrics.getAscent();
			copy.drawString(close, closeX, closeY);
		}
		finally
		{
			copy.dispose();
		}
		return new Dimension(width, height);
	}

	MouseListener getMouseListener()
	{
		return mouseListener;
	}

	private MouseEvent handlePress(MouseEvent event)
	{
		if (event instanceof GenericClientSyntheticMouseEvent ||
			!SwingUtilities.isLeftMouseButton(event) || !closeHit(event))
		{
			return event;
		}
		event.consume();
		if (ending.compareAndSet(false, true))
		{
			try
			{
				endBreakAction.get().whenComplete((ignored, error) -> ending.set(false));
			}
			catch (RuntimeException exception)
			{
				ending.set(false);
			}
		}
		return event;
	}

	private MouseEvent consumeCloseEvent(MouseEvent event)
	{
		if (!(event instanceof GenericClientSyntheticMouseEvent) && closeHit(event))
		{
			event.consume();
		}
		return event;
	}

	private boolean closeHit(MouseEvent event)
	{
		Map<String, Object> status = statusSupplier.get();
		if (status == null)
		{
			return false;
		}
		String state = String.valueOf(status.get("state"));
		if (!"micro_break".equals(state) && !"long_break".equals(state))
		{
			return false;
		}
		Rectangle overlay = getBounds();
		Rectangle close = closeBounds;
		return new Rectangle(
			overlay.x + close.x,
			overlay.y + close.y,
			close.width,
			close.height).contains(event.getPoint());
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
