package com.genericclient;

import java.awt.Canvas;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

final class GenericClientSyntheticMouse implements AutoCloseable
{
	private final Canvas canvas;
	private final ScheduledExecutorService executor;
	private final Supplier<GenericClientMouseProfile> profileSupplier;
	private final IntSupplier durationMillis;
	private final Consumer<String> reporter;
	private final Supplier<Random> randomSupplier;
	private final GenericClientMouseEffectOverlay effects;
	private final MouseAdapter realMouseListener = new MouseAdapter()
	{
		@Override
		public void mouseMoved(MouseEvent event)
		{
			captureRealPosition(event, false);
		}

		@Override
		public void mouseDragged(MouseEvent event)
		{
			captureRealPosition(event, false);
		}

		@Override
		public void mouseEntered(MouseEvent event)
		{
			captureRealPosition(event, false);
		}

		@Override
		public void mouseExited(MouseEvent event)
		{
			captureRealPosition(event, true);
		}
	};
	private final List<ScheduledFuture<?>> pending = new CopyOnWriteArrayList<>();

	private Point position;
	private boolean outside;
	private boolean moving;
	private boolean closed;
	private CompletableFuture<String> activeMove;

	GenericClientSyntheticMouse(
		Canvas canvas,
		ScheduledExecutorService executor,
		Supplier<GenericClientMouseProfile> profileSupplier,
		IntSupplier durationMillis,
		Point initialPosition,
		GenericClientMouseEffectOverlay effects,
		Consumer<String> reporter)
	{
		this(canvas, executor, profileSupplier, durationMillis, initialPosition, effects, reporter,
			() -> ThreadLocalRandom.current());
	}

	GenericClientSyntheticMouse(
		Canvas canvas,
		ScheduledExecutorService executor,
		Supplier<GenericClientMouseProfile> profileSupplier,
		IntSupplier durationMillis,
		Point initialPosition,
		GenericClientMouseEffectOverlay effects,
		Consumer<String> reporter,
		Supplier<Random> randomSupplier)
	{
		this.canvas = canvas;
		this.executor = executor;
		this.profileSupplier = profileSupplier;
		this.durationMillis = durationMillis;
		this.position = initialPosition == null ? new Point(0, 0) : new Point(initialPosition);
		this.outside = !inside(this.position);
		this.effects = effects;
		this.reporter = reporter;
		this.randomSupplier = randomSupplier;
		canvas.addMouseMotionListener(realMouseListener);
		canvas.addMouseListener(realMouseListener);
	}

	CompletableFuture<String> move(Point destination)
	{
		if (destination == null)
		{
			throw new IllegalArgumentException("Synthetic mouse destination cannot be null");
		}
		final CompletableFuture<String> completion;
		final List<GenericClientMouseMatcher.PathPoint> path;
		final int duration;
		synchronized (this)
		{
			ensureOpen();
			if (moving)
			{
				return failed("Synthetic mouse is already moving");
			}
			GenericClientMouseProfile profile = profileSupplier.get();
			if (profile == null)
			{
				return failed("Synthetic mouse profile is unavailable");
			}
			duration = Math.max(25, durationMillis.getAsInt());
			Rectangle viewport = new Rectangle(0, 0, Math.max(1, canvas.getWidth()), Math.max(1, canvas.getHeight()));
			path = GenericClientMouseMatcher.generate(
				profile,
				new Point(position),
				new Point(destination),
				viewport,
				duration,
				randomSupplier.get());
			moving = true;
			completion = new CompletableFuture<>();
			activeMove = completion;
		}

		reporter.accept("SYNTHETIC_MOUSE_PATH_GENERATED profile=" + profileSupplier.get().getProfileId() +
			" points=" + path.size() + " durationMs=" + duration + " destination=" +
			destination.x + "," + destination.y);
		effects.beginPath(path);
		effects.recordPoint(getPosition());
		boolean entering = isOutside() && inside(destination);
		if (entering)
		{
			dispatchFocus(FocusEvent.FOCUS_GAINED);
			dispatchMouse(MouseEvent.MOUSE_ENTERED, clampToCanvas(destination), 0, 0, false);
		}
		for (int index = 1; index < path.size(); index++)
		{
			int pathIndex = index;
			GenericClientMouseMatcher.PathPoint point = path.get(index);
			Point next = new Point((int) Math.round(point.x), (int) Math.round(point.y));
			schedule(() -> dispatchMove(next, pathIndex), Math.round(point.timeMillis));
		}
		schedule(() -> finishMove(destination, completion), duration + 1L);
		return completion;
	}

	CompletableFuture<String> moveOffscreen(GenericClientBehaviorProfile.Edge edge)
	{
		if (edge == null)
		{
			throw new IllegalArgumentException("Offscreen edge cannot be null");
		}
		if (isOutside())
		{
			return CompletableFuture.completedFuture("SYNTHETIC_MOUSE_ALREADY_OFFSCREEN edge=" + edge);
		}
		Random random = randomSupplier.get();
		int distance = 5 + random.nextInt(71);
		int x;
		int y;
		switch (edge)
		{
			case LEFT:
				x = -distance;
				y = random.nextInt(Math.max(1, canvas.getHeight()));
				break;
			case RIGHT:
				x = canvas.getWidth() + distance;
				y = random.nextInt(Math.max(1, canvas.getHeight()));
				break;
			case TOP:
				x = random.nextInt(Math.max(1, canvas.getWidth()));
				y = -distance;
				break;
			case BOTTOM:
				x = random.nextInt(Math.max(1, canvas.getWidth()));
				y = canvas.getHeight() + distance;
				break;
			default:
				throw new IllegalStateException("Unsupported offscreen edge: " + edge);
		}
		return move(new Point(x, y));
	}

	CompletableFuture<String> click(int button)
	{
		if (!(button == MouseEvent.BUTTON1 || button == MouseEvent.BUTTON3))
		{
			throw new IllegalArgumentException("Synthetic click supports only left or right buttons");
		}
		final Point current;
		synchronized (this)
		{
			ensureOpen();
			if (moving)
			{
				return failed("Synthetic mouse is still moving");
			}
			if (outside)
			{
				return failed("Synthetic mouse cannot click outside the canvas");
			}
			current = new Point(position);
		}

		CompletableFuture<String> completion = new CompletableFuture<>();
		SwingUtilities.invokeLater(() ->
		{
			int mask = button == MouseEvent.BUTTON1
				? InputEvent.BUTTON1_DOWN_MASK
				: InputEvent.BUTTON3_DOWN_MASK;
			boolean popup = button == MouseEvent.BUTTON3;
			dispatchMouse(MouseEvent.MOUSE_PRESSED, current, mask, button, popup);
			dispatchMouse(MouseEvent.MOUSE_RELEASED, current, 0, button, popup);
			dispatchMouse(MouseEvent.MOUSE_CLICKED, current, 0, button, popup);
			completion.complete(button == MouseEvent.BUTTON1
				? "SYNTHETIC_LEFT_CLICK"
				: "SYNTHETIC_RIGHT_CLICK");
		});
		return completion;
	}

	synchronized Point getPosition()
	{
		return new Point(position);
	}

	synchronized boolean isOutside()
	{
		return outside;
	}

	synchronized boolean isMoving()
	{
		return moving;
	}

	private void dispatchMove(Point point, int pathIndex)
	{
		dispatchMouse(MouseEvent.MOUSE_MOVED, point, 0, MouseEvent.NOBUTTON, false);
		synchronized (this)
		{
			position = new Point(point);
		}
		effects.recordPoint(point);
		effects.advancePath(pathIndex);
	}

	private synchronized void captureRealPosition(MouseEvent event, boolean exited)
	{
		if (event instanceof GenericClientSyntheticMouseEvent)
		{
			return;
		}
		position = event.getPoint();
		outside = exited || !inside(position);
		effects.recordPoint(position);
	}

	private void finishMove(Point destination, CompletableFuture<String> completion)
	{
		boolean destinationOutside = !inside(destination);
		if (destinationOutside)
		{
			dispatchMouse(MouseEvent.MOUSE_EXITED, destination, 0, MouseEvent.NOBUTTON, false);
			dispatchFocus(FocusEvent.FOCUS_LOST);
		}
		effects.recordPoint(destination);
		effects.endPath();
		synchronized (this)
		{
			if (activeMove != completion)
			{
				return;
			}
			position = new Point(destination);
			outside = destinationOutside;
			moving = false;
			activeMove = null;
			pending.removeIf(ScheduledFuture::isDone);
		}
		completion.complete("SYNTHETIC_MOUSE_MOVED destination=" + destination.x + "," + destination.y +
			" outside=" + destinationOutside);
	}

	private void dispatchMouse(int id, Point point, int modifiers, int button, boolean popup)
	{
		GenericClientSyntheticMouseEvent event = new GenericClientSyntheticMouseEvent(
			canvas,
			id,
			System.currentTimeMillis(),
			modifiers,
			point.x,
			point.y,
			id == MouseEvent.MOUSE_CLICKED ? 1 : 0,
			popup,
			button);
		dispatchOnEdt(event);
	}

	private void dispatchFocus(int id)
	{
		FocusEvent event = new FocusEvent(canvas, id, false);
		runOnEdt(() ->
		{
			for (FocusListener listener : canvas.getFocusListeners())
			{
				if (id == FocusEvent.FOCUS_GAINED)
				{
					listener.focusGained(event);
				}
				else
				{
					listener.focusLost(event);
				}
			}
		});
	}

	private void dispatchOnEdt(java.awt.AWTEvent event)
	{
		runOnEdt(() -> canvas.dispatchEvent(event));
	}

	private void runOnEdt(Runnable runnable)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			runnable.run();
		}
		else
		{
			try
			{
				SwingUtilities.invokeAndWait(runnable);
			}
			catch (Exception exception)
			{
				throw new IllegalStateException("Unable to dispatch synthetic canvas input", exception);
			}
		}
	}

	private void schedule(Runnable runnable, long delayMillis)
	{
		ScheduledFuture<?> future = executor.schedule(() ->
		{
			synchronized (GenericClientSyntheticMouse.this)
			{
				if (closed || !moving)
				{
					return;
				}
			}
			runnable.run();
		}, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
		pending.add(future);
	}

	private boolean inside(Point point)
	{
		return point.x >= 0 && point.y >= 0 && point.x < canvas.getWidth() && point.y < canvas.getHeight();
	}

	private Point clampToCanvas(Point point)
	{
		return new Point(
			Math.max(0, Math.min(Math.max(0, canvas.getWidth() - 1), point.x)),
			Math.max(0, Math.min(Math.max(0, canvas.getHeight() - 1), point.y)));
	}

	private static CompletableFuture<String> failed(String message)
	{
		CompletableFuture<String> result = new CompletableFuture<>();
		result.completeExceptionally(new IllegalStateException(message));
		return result;
	}

	private void ensureOpen()
	{
		if (closed)
		{
			throw new IllegalStateException("Synthetic mouse is closed");
		}
	}

	@Override
	public synchronized void close()
	{
		if (closed)
		{
			return;
		}
		closed = true;
		moving = false;
		for (ScheduledFuture<?> future : new ArrayList<>(pending))
		{
			future.cancel(false);
		}
		pending.clear();
		effects.endPath();
		canvas.removeMouseMotionListener(realMouseListener);
		canvas.removeMouseListener(realMouseListener);
		if (activeMove != null)
		{
			activeMove.completeExceptionally(new IllegalStateException("Synthetic mouse closed"));
			activeMove = null;
		}
	}
}
