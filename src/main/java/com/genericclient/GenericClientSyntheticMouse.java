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

final class GenericClientSyntheticMouse implements AutoCloseable, GenericClientCursorBehavior.Motion
{
	private final Canvas canvas;
	private final ScheduledExecutorService executor;
	private final Supplier<GenericClientMouseProfile> profileSupplier;
	private final IntSupplier durationMillis;
	private final Consumer<String> reporter;
	private final Supplier<Random> randomSupplier;
	private final GenericClientMouseEffectOverlay effects;
	private final Consumer<Point> physicalMovementListener;
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
		public void mousePressed(MouseEvent event)
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
	private Point lastClick;
	private boolean outside;
	private boolean moving;
	private boolean preemptibleMove;
	private GenericClientActivityContext moveContext;
	private volatile boolean closed;
	private int pendingClicks;
	private volatile long clickGeneration;
	private CompletableFuture<String> activeMove;

	GenericClientSyntheticMouse(
		Canvas canvas,
		ScheduledExecutorService executor,
		Supplier<GenericClientMouseProfile> profileSupplier,
		IntSupplier durationMillis,
		Point initialPosition,
		GenericClientMouseEffectOverlay effects,
		Consumer<String> reporter,
		Consumer<Point> physicalMovementListener)
	{
		this(canvas, executor, profileSupplier, durationMillis, initialPosition, effects, reporter,
			() -> ThreadLocalRandom.current(), physicalMovementListener);
	}

	GenericClientSyntheticMouse(
		Canvas canvas,
		ScheduledExecutorService executor,
		Supplier<GenericClientMouseProfile> profileSupplier,
		IntSupplier durationMillis,
		Point initialPosition,
		GenericClientMouseEffectOverlay effects,
		Consumer<String> reporter,
		Supplier<Random> randomSupplier,
		Consumer<Point> physicalMovementListener)
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
		this.physicalMovementListener = physicalMovementListener;
		effects.updateCursor(this.position, this.outside);
		canvas.addMouseMotionListener(realMouseListener);
		canvas.addMouseListener(realMouseListener);
	}

	CompletableFuture<String> move(Point destination)
	{
		return move(destination, null, 0);
	}

	CompletableFuture<String> move(
		Point destination,
		GenericClientActivityContext activityContext)
	{
		return move(destination, activityContext, 0);
	}

	public CompletableFuture<String> moveRest(Point destination, int duration, GenericClientActivityContext context)
	{
		if (duration <= 0) throw new IllegalArgumentException("Rest movement duration must be positive");
		return move(destination, context, duration);
	}

	private CompletableFuture<String> move(
		Point destination,
		GenericClientActivityContext activityContext,
		int restDuration)
	{
		if (destination == null)
		{
			throw new IllegalArgumentException("Synthetic mouse destination cannot be null");
		}
		final CompletableFuture<String> completion;
		final List<GenericClientMouseMatcher.PathPoint> path;
		final int duration;
		final boolean entering;
		final Point previousStart;
		final Point pathStart;
		CompletableFuture<String> superseded = null;
		try
		{
			synchronized (this)
			{
				ensureOpen();
				if (activityContext != null && !activityContext.isInputAllowed()) return failed("action_cancelled");
				if (pendingClicks > 0) return failed("Synthetic mouse click is pending");
				if (moving)
				{
					if (!preemptibleMove)
					{
						return failed("Synthetic mouse is already moving");
					}
					superseded = detachMove();
				}
				try
				{
					GenericClientMouseProfile profile = profileSupplier.get();
					if (profile == null)
					{
						return failed("Synthetic mouse profile is unavailable");
					}
					int profileDuration = Math.max(25, durationMillis.getAsInt());
					duration = restDuration > 0 ? restDuration : activityContext == null
						? profileDuration : activityContext.mouseMoveDurationMillis(profileDuration);
					Rectangle viewport = new Rectangle(
						0, 0, Math.max(1, canvas.getWidth()), Math.max(1, canvas.getHeight()));
					Random random = randomSupplier.get();
					outside = !viewport.contains(position);
					entering = outside && viewport.contains(destination);
					previousStart = new Point(position);
					pathStart = entering
						? randomizedReentryStart(previousStart, viewport.width, viewport.height, random)
						: previousStart;
					if (entering)
					{
						position = new Point(pathStart);
					}
					path = restDuration > 0 ? GenericClientMouseMatcher.generateRest(pathStart, destination, duration)
						: GenericClientMouseMatcher.generate(
						profile,
						pathStart,
						new Point(destination),
						viewport,
						duration,
						random);
					moving = true;
					preemptibleMove = restDuration > 0;
					moveContext = activityContext;
					completion = new CompletableFuture<>();
					activeMove = completion;
				}
				catch (RuntimeException exception)
				{
					return failed("Synthetic mouse path generation failed: " + exception.getMessage());
				}
			}

		}
		finally
		{
			if (superseded != null)
			{
				superseded.completeExceptionally(new IllegalStateException("Synthetic mouse cancelled: rest_preempted"));
				reporter.accept("SYNTHETIC_MOUSE_CANCELLED reason=rest_preempted");
			}
		}

		reporter.accept("SYNTHETIC_MOUSE_PATH_GENERATED profile=" + profileSupplier.get().getProfileId() +
			" points=" + path.size() + " durationMs=" + duration + " destination=" +
			destination.x + "," + destination.y);
		if (entering)
		{
			reporter.accept("SYNTHETIC_MOUSE_REENTRY previous=" + previousStart.x + "," + previousStart.y +
				" randomized=" + pathStart.x + "," + pathStart.y);
		}
		effects.beginPath(path);
		effects.recordPoint(getPosition());
		for (int index = 1; index < path.size(); index++)
		{
			int pathIndex = index;
			GenericClientMouseMatcher.PathPoint point = path.get(index);
			Point next = new Point((int) Math.round(point.x), (int) Math.round(point.y));
			schedule(() -> dispatchMove(next, pathIndex), Math.round(point.timeMillis), completion, activityContext);
		}
		schedule(() -> finishMove(destination, completion), duration + 1L, completion, activityContext);
		return completion;
	}

	CompletableFuture<String> moveOffscreen(GenericClientBehaviorProfile.Edge edge, GenericClientActivityContext context)
	{
		if (edge == null) throw new IllegalArgumentException("Offscreen edge cannot be null");
		if (isOutside()) return CompletableFuture.completedFuture("SYNTHETIC_MOUSE_ALREADY_OFFSCREEN edge=" + edge);
		return moveRest(offscreenTarget(edge), 400 + randomSupplier.get().nextInt(501), context);
	}

	public Point offscreenTarget(GenericClientBehaviorProfile.Edge edge)
	{
		if (edge == null)
		{
			throw new IllegalArgumentException("Offscreen edge cannot be null");
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
		return new Point(x, y);
	}

	CompletableFuture<String> click(int button)
	{
		return click(button, null);
	}

	CompletableFuture<String> click(int button, GenericClientActivityContext activityContext)
	{
		if (!(button == MouseEvent.BUTTON1 || button == MouseEvent.BUTTON3))
		{
			throw new IllegalArgumentException("Synthetic click supports only left or right buttons");
		}
		if (activityContext != null && !activityContext.isInputAllowed()) return failed("action_cancelled");
		cancelRest("action_started", null);
		final Point current;
		final long generation;
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
			generation = clickGeneration;
			pendingClicks++;
		}

		CompletableFuture<String> completion = new CompletableFuture<>();
		SwingUtilities.invokeLater(() ->
		{
			RuntimeException failure = null;
			try
			{
				if (closed || clickGeneration != generation || activityContext != null && !activityContext.isInputAllowed())
					throw new IllegalStateException("action_cancelled");
				int mask = button == MouseEvent.BUTTON1 ? InputEvent.BUTTON1_DOWN_MASK : InputEvent.BUTTON3_DOWN_MASK;
				boolean popup = button == MouseEvent.BUTTON3;
				dispatchMouse(MouseEvent.MOUSE_PRESSED, current, mask, button, popup);
				dispatchMouse(MouseEvent.MOUSE_RELEASED, current, 0, button, popup);
				dispatchMouse(MouseEvent.MOUSE_CLICKED, current, 0, button, popup);
				synchronized (this) { lastClick = new Point(current); }
			}
			catch (RuntimeException exception) { failure = exception; }
			finally { synchronized (this) { pendingClicks--; } }
			if (failure != null) completion.completeExceptionally(failure);
			else completion.complete(button == MouseEvent.BUTTON1 ? "SYNTHETIC_LEFT_CLICK" : "SYNTHETIC_RIGHT_CLICK");
		});
		return completion;
	}

	public synchronized Point getPosition()
	{
		return new Point(position);
	}

	synchronized Point getLastClick() { return lastClick == null ? null : new Point(lastClick); }
	synchronized boolean isActionActive() { return pendingClicks > 0 || moving && !preemptibleMove; }

	public synchronized boolean isOutside()
	{
		outside = !inside(position);
		return outside;
	}

	synchronized boolean isMoving()
	{
		return moving;
	}

	void cancel(String reason)
	{
		synchronized (this) { clickGeneration++; }
		cancel(null, reason);
	}

	public void cancelRest(String reason, GenericClientActivityContext context)
	{
		CompletableFuture<String> rest;
		synchronized (this) { rest = preemptibleMove && (context == null || moveContext == context) ? activeMove : null; }
		if (rest != null) cancel(rest, reason);
	}

	private void cancel(CompletableFuture<String> owner, String reason)
	{
		CompletableFuture<String> completion;
		synchronized (this)
		{
			if (!moving || owner != null && activeMove != owner) return;
			completion = detachMove();
		}
		if (completion != null) completion.completeExceptionally(new IllegalStateException("Synthetic mouse cancelled: " + reason));
		reporter.accept("SYNTHETIC_MOUSE_CANCELLED reason=" + reason);
	}

	private CompletableFuture<String> detachMove()
	{
		moving = false;
		preemptibleMove = false;
		moveContext = null;
		for (ScheduledFuture<?> future : new ArrayList<>(pending)) future.cancel(false);
		pending.clear();
		effects.endPath();
		CompletableFuture<String> completion = activeMove;
		activeMove = null;
		return completion;
	}

	private void dispatchMove(Point point, int pathIndex)
	{
		boolean wasOutside;
		synchronized (this)
		{
			wasOutside = outside;
		}
		boolean pointOutside = !inside(point);
		if (wasOutside && !pointOutside)
		{
			dispatchFocus(FocusEvent.FOCUS_GAINED);
			dispatchMouse(MouseEvent.MOUSE_ENTERED, clampToCanvas(point), 0, MouseEvent.NOBUTTON, false);
		}
		dispatchMouse(MouseEvent.MOUSE_MOVED, point, 0, MouseEvent.NOBUTTON, false);
		synchronized (this)
		{
			position = new Point(point);
			outside = pointOutside;
		}
		effects.updateCursor(point, pointOutside);
		effects.recordPoint(point);
		effects.advancePath(pathIndex);
	}

	private void captureRealPosition(MouseEvent event, boolean exited)
	{
		if (event instanceof GenericClientSyntheticMouseEvent)
		{
			return;
		}
		Point physical = event.getPoint();
		boolean physicalOutside;
		synchronized (this)
		{
			position = physical;
			outside = exited || !inside(position);
			physicalOutside = outside;
			if (event.getID() == MouseEvent.MOUSE_PRESSED) lastClick = new Point(physical);
		}
		cancelRest("physical_input", null);
		effects.updateCursor(physical, physicalOutside);
		effects.recordPoint(physical);
		physicalMovementListener.accept(new Point(physical));
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
			preemptibleMove = false;
			moveContext = null;
			activeMove = null;
			pending.removeIf(ScheduledFuture::isDone);
		}
		effects.updateCursor(destination, destinationOutside);
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

	private void schedule(Runnable runnable, long delayMillis, CompletableFuture<String> owner,
		GenericClientActivityContext context)
	{
		ScheduledFuture<?> future = executor.schedule(() -> SwingUtilities.invokeLater(() ->
		{
			synchronized (this)
			{
				if (closed || !moving || activeMove != owner) return;
			}
			if (context != null && !context.isInputAllowed())
			{
				cancel(owner, "action_cancelled");
				return;
			}
			runnable.run();
		}), Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
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

	static Point randomizedReentryStart(Point current, int width, int height, Random random)
	{
		int safeWidth = Math.max(1, width);
		int safeHeight = Math.max(1, height);
		int distance = 5 + random.nextInt(71);
		if (current.x < 0)
		{
			return new Point(-distance, shiftedCoordinate(current.y, safeHeight, random));
		}
		if (current.x >= safeWidth)
		{
			return new Point(safeWidth + distance, shiftedCoordinate(current.y, safeHeight, random));
		}
		if (current.y < 0)
		{
			return new Point(shiftedCoordinate(current.x, safeWidth, random), -distance);
		}
		if (current.y >= safeHeight)
		{
			return new Point(shiftedCoordinate(current.x, safeWidth, random), safeHeight + distance);
		}
		throw new IllegalArgumentException("Re-entry start must be outside the canvas");
	}

	private static int shiftedCoordinate(int current, int size, Random random)
	{
		if (size == 1)
		{
			return 0;
		}
		int origin = Math.max(0, Math.min(size - 1, current));
		int minimumShift = Math.min(size - 1, Math.max(8, size / 12));
		for (int attempt = 0; attempt < 12; attempt++)
		{
			int candidate = random.nextInt(size);
			if (Math.abs(candidate - origin) >= minimumShift)
			{
				return candidate;
			}
		}
		return origin < size / 2 ? size - 1 : 0;
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
	public void close()
	{
		CompletableFuture<String> completion;
		synchronized (this)
		{
			if (closed) return;
			closed = true;
			completion = detachMove();
		}
		canvas.removeMouseMotionListener(realMouseListener);
		canvas.removeMouseListener(realMouseListener);
		if (completion != null) completion.completeExceptionally(new IllegalStateException("Synthetic mouse closed"));
	}
}
