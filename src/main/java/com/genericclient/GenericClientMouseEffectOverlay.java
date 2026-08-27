package com.genericclient;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

final class GenericClientMouseEffectOverlay extends Overlay
{
	private static final int TRAIL_LIFETIME_MILLIS = 1_800;
	private static final int TRAIL_SAMPLE_MILLIS = 16;
	private static final int TRAIL_MAX_POINTS = 96;
	private static final Color TRAIL_COLOR = new Color(74, 222, 128);
	private static final Color PATH_COLOR = new Color(56, 189, 248);
	private static final Color PATH_DONE_COLOR = new Color(74, 222, 128);
	private static final BasicStroke THIN_STROKE =
		new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke TRAIL_STROKE =
		new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke PATH_STROKE =
		new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke PATH_DONE_STROKE =
		new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

	private final Object lock = new Object();
	private final Supplier<GenericClientMouseEffect> effect;
	private final IntSupplier canvasWidth;
	private final IntSupplier canvasHeight;
	private final LongSupplier clock;
	private final ArrayDeque<TrailPoint> trail = new ArrayDeque<>();
	private List<PathPoint> activePath = Collections.emptyList();
	private int activePathIndex = -1;
	private Point cursorPoint = new Point(0, 0);
	private boolean cursorOutside;

	@Inject
	private GenericClientMouseEffectOverlay(Client client, GenericClientConfig config)
	{
		this(config::mouseEffect, client::getCanvasWidth, client::getCanvasHeight, System::currentTimeMillis);
	}

	GenericClientMouseEffectOverlay(
		Supplier<GenericClientMouseEffect> effect,
		IntSupplier canvasWidth,
		IntSupplier canvasHeight,
		LongSupplier clock)
	{
		this.effect = effect;
		this.canvasWidth = canvasWidth;
		this.canvasHeight = canvasHeight;
		this.clock = clock;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(PRIORITY_HIGHEST);
	}

	void recordPoint(Point point)
	{
		if (point == null || effect.get() != GenericClientMouseEffect.TRAIL || !inside(point))
		{
			return;
		}
		long now = clock.getAsLong();
		synchronized (lock)
		{
			removeExpiredTrail(now);
			TrailPoint last = trail.peekLast();
			if ((last == null || now - last.createdAt >= TRAIL_SAMPLE_MILLIS) &&
				(last == null || last.x != point.x || last.y != point.y))
			{
				trail.addLast(new TrailPoint(point.x, point.y, now, now + TRAIL_LIFETIME_MILLIS));
			}
			while (trail.size() > TRAIL_MAX_POINTS)
			{
				trail.removeFirst();
			}
		}
	}

	void updateCursor(Point point, boolean outside)
	{
		if (point == null)
		{
			return;
		}
		synchronized (lock)
		{
			cursorPoint = new Point(point);
			cursorOutside = outside;
		}
	}

	void beginPath(List<GenericClientMouseMatcher.PathPoint> points)
	{
		synchronized (lock)
		{
			if (effect.get() != GenericClientMouseEffect.PATH || points == null || points.size() < 2)
			{
				clearPath();
				return;
			}
			List<PathPoint> copy = new ArrayList<>(points.size());
			for (GenericClientMouseMatcher.PathPoint point : points)
			{
				copy.add(new PathPoint(point.x, point.y));
			}
			activePath = copy;
			activePathIndex = 0;
		}
	}

	void advancePath(int index)
	{
		synchronized (lock)
		{
			if (!activePath.isEmpty())
			{
				activePathIndex = Math.max(0, Math.min(index, activePath.size() - 1));
			}
		}
	}

	void endPath()
	{
		synchronized (lock)
		{
			clearPath();
		}
	}

	void clear()
	{
		synchronized (lock)
		{
			trail.clear();
			clearPath();
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		GenericClientMouseEffect selected = effect.get();

		Graphics2D copy = (Graphics2D) graphics.create();
		try
		{
			copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			if (selected == GenericClientMouseEffect.OFF)
			{
				clear();
			}
			else if (selected == GenericClientMouseEffect.TRAIL)
			{
				drawTrail(copy);
			}
			else
			{
				drawPath(copy);
			}
			drawCursor(copy);
		}
		finally
		{
			copy.dispose();
		}
		return null;
	}

	private void drawCursor(Graphics2D graphics)
	{
		Point point;
		boolean outside;
		synchronized (lock)
		{
			point = new Point(cursorPoint);
			outside = cursorOutside;
		}
		if (outside || !inside(point))
		{
			drawOffscreenIndicator(graphics, point);
			return;
		}

		int x = Math.max(1, Math.min(Math.max(1, canvasWidth.getAsInt() - 17), point.x));
		int y = Math.max(1, Math.min(Math.max(1, canvasHeight.getAsInt() - 22), point.y));
		Path2D cursor = new Path2D.Double();
		cursor.moveTo(x, y);
		cursor.lineTo(x, y + 18);
		cursor.lineTo(x + 5, y + 13);
		cursor.lineTo(x + 10, y + 21);
		cursor.lineTo(x + 14, y + 19);
		cursor.lineTo(x + 9, y + 11);
		cursor.lineTo(x + 16, y + 10);
		cursor.closePath();

		graphics.translate(1, 1);
		graphics.setColor(new Color(0, 0, 0, 180));
		graphics.fill(cursor);
		graphics.translate(-1, -1);
		graphics.setColor(new Color(245, 248, 250, 245));
		graphics.fill(cursor);
		graphics.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		graphics.setColor(new Color(20, 25, 31, 235));
		graphics.draw(cursor);
		graphics.setColor(TRAIL_COLOR);
		graphics.fillOval(x + 2, y + 3, 4, 4);
	}

	private void drawOffscreenIndicator(Graphics2D graphics, Point point)
	{
		int width = Math.max(1, canvasWidth.getAsInt());
		int height = Math.max(1, canvasHeight.getAsInt());
		int x;
		int y;
		Direction direction;
		if (point.x < 0)
		{
			x = 11;
			y = clamp(point.y, 11, height - 12);
			direction = Direction.LEFT;
		}
		else if (point.x >= width)
		{
			x = width - 12;
			y = clamp(point.y, 11, height - 12);
			direction = Direction.RIGHT;
		}
		else if (point.y < 0)
		{
			x = clamp(point.x, 11, width - 12);
			y = 11;
			direction = Direction.TOP;
		}
		else
		{
			x = clamp(point.x, 11, width - 12);
			y = height - 12;
			direction = Direction.BOTTOM;
		}

		graphics.setColor(new Color(12, 17, 23, 220));
		graphics.fillOval(x - 10, y - 10, 20, 20);
		graphics.setStroke(new BasicStroke(1.4f));
		graphics.setColor(new Color(TRAIL_COLOR.getRed(), TRAIL_COLOR.getGreen(), TRAIL_COLOR.getBlue(), 235));
		graphics.drawOval(x - 10, y - 10, 20, 20);
		Path2D arrow = direction.arrow(x, y);
		graphics.fill(arrow);
	}

	private static int clamp(int value, int minimum, int maximum)
	{
		if (maximum < minimum)
		{
			return Math.max(0, maximum / 2);
		}
		return Math.max(minimum, Math.min(maximum, value));
	}

	private void drawTrail(Graphics2D graphics)
	{
		long now = clock.getAsLong();
		List<TrailPoint> points;
		synchronized (lock)
		{
			removeExpiredTrail(now);
			points = new ArrayList<>(trail);
		}
		if (points.size() < 2)
		{
			return;
		}
		TrailPoint previous = points.get(0);
		for (int index = 1; index < points.size(); index++)
		{
			TrailPoint current = points.get(index);
			int alpha = current.alpha(now);
			if (alpha > 0)
			{
				graphics.setStroke(alpha > 180 ? TRAIL_STROKE : THIN_STROKE);
				graphics.setColor(new Color(0, 0, 0, Math.min(110, alpha)));
				graphics.drawLine(previous.x + 1, previous.y + 1, current.x + 1, current.y + 1);
				graphics.setColor(new Color(
					TRAIL_COLOR.getRed(), TRAIL_COLOR.getGreen(), TRAIL_COLOR.getBlue(), alpha));
				graphics.drawLine(previous.x, previous.y, current.x, current.y);
			}
			previous = current;
		}
	}

	private void drawPath(Graphics2D graphics)
	{
		List<PathPoint> points;
		int progress;
		synchronized (lock)
		{
			points = new ArrayList<>(activePath);
			progress = activePathIndex;
		}
		if (points.size() < 2)
		{
			return;
		}
		graphics.setStroke(PATH_STROKE);
		graphics.setColor(new Color(PATH_COLOR.getRed(), PATH_COLOR.getGreen(), PATH_COLOR.getBlue(), 105));
		drawSegments(graphics, points, points.size() - 1);
		if (progress > 0)
		{
			graphics.setStroke(PATH_DONE_STROKE);
			graphics.setColor(new Color(
				PATH_DONE_COLOR.getRed(), PATH_DONE_COLOR.getGreen(), PATH_DONE_COLOR.getBlue(), 195));
			drawSegments(graphics, points, progress);
		}
		drawDot(graphics, points.get(0), PATH_DONE_COLOR, 5);
		drawDot(graphics, points.get(points.size() - 1), PATH_COLOR, 6);
	}

	private static void drawSegments(Graphics2D graphics, List<PathPoint> points, int end)
	{
		for (int index = 1; index <= Math.min(end, points.size() - 1); index++)
		{
			PathPoint first = points.get(index - 1);
			PathPoint second = points.get(index);
			graphics.drawLine(first.x(), first.y(), second.x(), second.y());
		}
	}

	private static void drawDot(Graphics2D graphics, PathPoint point, Color color, int size)
	{
		int radius = size / 2;
		graphics.setStroke(THIN_STROKE);
		graphics.setColor(new Color(0, 0, 0, 140));
		graphics.fillOval(point.x() - radius - 1, point.y() - radius - 1, size + 2, size + 2);
		graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 200));
		graphics.fillOval(point.x() - radius, point.y() - radius, size, size);
	}

	private boolean inside(Point point)
	{
		return point.x >= 0 && point.y >= 0 &&
			point.x < Math.max(1, canvasWidth.getAsInt()) && point.y < Math.max(1, canvasHeight.getAsInt());
	}

	private void removeExpiredTrail(long now)
	{
		while (!trail.isEmpty() && trail.peekFirst().expiresAt <= now)
		{
			trail.removeFirst();
		}
	}

	private void clearPath()
	{
		activePath = Collections.emptyList();
		activePathIndex = -1;
	}

	private enum Direction
	{
		LEFT,
		RIGHT,
		TOP,
		BOTTOM;

		private Path2D arrow(int x, int y)
		{
			Path2D path = new Path2D.Double();
			switch (this)
			{
				case LEFT:
					path.moveTo(x - 6, y);
					path.lineTo(x + 3, y - 5);
					path.lineTo(x + 3, y + 5);
					break;
				case RIGHT:
					path.moveTo(x + 6, y);
					path.lineTo(x - 3, y - 5);
					path.lineTo(x - 3, y + 5);
					break;
				case TOP:
					path.moveTo(x, y - 6);
					path.lineTo(x - 5, y + 3);
					path.lineTo(x + 5, y + 3);
					break;
				case BOTTOM:
					path.moveTo(x, y + 6);
					path.lineTo(x - 5, y - 3);
					path.lineTo(x + 5, y - 3);
					break;
				default:
					throw new IllegalStateException("Unknown direction: " + this);
			}
			path.closePath();
			return path;
		}
	}

	private static final class TrailPoint
	{
		private final int x;
		private final int y;
		private final long createdAt;
		private final long expiresAt;

		private TrailPoint(int x, int y, long createdAt, long expiresAt)
		{
			this.x = x;
			this.y = y;
			this.createdAt = createdAt;
			this.expiresAt = expiresAt;
		}

		private int alpha(long now)
		{
			double remaining = Math.max(0.0, Math.min(1.0,
				(expiresAt - now) / (double) TRAIL_LIFETIME_MILLIS));
			return (int) Math.round((1.0 - Math.cos(remaining * Math.PI)) / 2.0 * 255.0);
		}
	}

	private static final class PathPoint
	{
		private final double x;
		private final double y;

		private PathPoint(double x, double y)
		{
			this.x = x;
			this.y = y;
		}

		private int x()
		{
			return (int) Math.round(x);
		}

		private int y()
		{
			return (int) Math.round(y);
		}
	}
}
