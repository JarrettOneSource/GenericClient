package com.genericclient;

import static com.genericclient.GenericClientErrors.rootMessage;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Discretionary cursor movement admitted only inside an observed quiet window. */
final class GenericClientCursorBehavior
{
	private static final long INPUT_MARGIN_MILLIS = 600;
	private static final long IDLE_PARK_MILLIS = 1200;
	private static final long GLANCE_INTERVAL_MILLIS = 60_000;
	private final Motion mouse;
	private final LongSupplier clock;
	private final Random random;
	private final Consumer<String> reporter;
	private GenericClientActivityContext activeContext;
	private String profileId;
	private long lastFrame = -1;
	private long idleSince = -1;
	private long lastGlance;
	private boolean eligibleLastFrame;
	private boolean parked;
	private double pressure;
	private double budget;

	GenericClientCursorBehavior(Motion mouse, LongSupplier clock, Random random, Consumer<String> reporter)
	{
		this.mouse = mouse;
		this.clock = clock;
		this.random = random;
		this.reporter = reporter;
	}

	synchronized void publish(Frame frame)
	{
		long now = clock.getAsLong();
		long elapsed = lastFrame < 0 ? 0 : Math.max(0, now - lastFrame);
		lastFrame = now;
		if (frame.profile == null || frame.blocked || !frame.context.isInputAllowed())
		{
			cancel();
			return;
		}
		if (!frame.profile.getId().equals(profileId))
		{
			cancel();
			profileId = frame.profile.getId();
			pressure = 0;
			budget = GenericClientBehaviorProfile.sampleExponentialBudget(random);
			lastGlance = frame.activeMillis;
		}
		if (frame.idle)
		{
			publishPark(frame, now);
			return;
		}
		idleSince = -1;
		parked = false;
		GenericClientBehaviorPolicy policy = frame.context.policy();
		boolean eligible = policy.fidget != GenericClientBehaviorPolicy.Fidget.NONE &&
			frame.quietMillis >= 400 + INPUT_MARGIN_MILLIS && !frame.anchors.isEmpty() && !mouse.isOutside();
		if (!eligible)
		{
			cancel();
			return;
		}
		if (eligibleLastFrame) pressure += elapsed * frame.profile.getCursorStyle().fidgetsPerMinute / 60_000.0;
		eligibleLastFrame = true;
		if (activeContext != null || pressure < budget) return;
		Choice choice = choose(frame, policy);
		pressure = 0;
		budget = GenericClientBehaviorProfile.sampleExponentialBudget(random);
		start(frame, choice);
	}

	synchronized void cancel()
	{
		eligibleLastFrame = false;
		idleSince = -1;
		parked = false;
		GenericClientActivityContext previous = activeContext;
		activeContext = null;
		if (previous != null)
		{
			previous.cancelInput();
			mouse.cancelRest("cursor_window_closed", previous);
		}
	}

	private void publishPark(Frame frame, long now)
	{
		eligibleLastFrame = false;
		if (parked || activeContext != null || mouse.isOutside()) return;
		if (idleSince < 0) idleSince = now;
		if (now - idleSince < IDLE_PARK_MILLIS || frame.quietMillis < 900 + INPUT_MARGIN_MILLIS) return;
		parked = true;
		GenericClientBehaviorProfile.Edge edge = frame.profile.getIdleEdge();
		start(frame, new Choice("idle_park", edge.name().toLowerCase(java.util.Locale.ROOT),
			mouse.offscreenTarget(edge), 400 + random.nextInt(501)));
	}

	private Choice choose(Frame frame, GenericClientBehaviorPolicy policy)
	{
		GenericClientBehaviorProfile.CursorStyle style = frame.profile.getCursorStyle();
		Point current = mouse.getPosition();
		Anchor anchor = nearest(frame.anchors, current);
		if (policy.fidget == GenericClientBehaviorPolicy.Fidget.FULL)
		{
			boolean longWindow = frame.quietMillis >= 900 + INPUT_MARGIN_MILLIS;
			if (longWindow && policy.cursorRelease == GenericClientBehaviorPolicy.CursorRelease.INDEPENDENT &&
				frame.activeMillis - lastGlance >= GLANCE_INTERVAL_MILLIS &&
				random.nextDouble() < frame.profile.getCursorReleaseProbability() * 0.10)
			{
				lastGlance = frame.activeMillis;
				return new Choice("glance", "offscreen", mouse.offscreenTarget(frame.profile.getIdleEdge()),
					400 + random.nextInt(501));
			}
			if (frame.anticipated != null && random.nextDouble() < style.anticipationProbability)
				return new Choice("anticipation", "declared_target", toward(current, frame.anticipated, style.driftPixels),
					100 + random.nextInt(301));
			if (longWindow && random.nextDouble() < style.relocationShare)
			{
				Anchor destination = frame.anchors.get(random.nextInt(frame.anchors.size()));
				return new Choice("relocation", destination.name, jitter(destination.point, style.driftPixels, frame.viewport),
					400 + random.nextInt(501));
			}
		}
		Point center = current.distance(anchor.point) <= style.driftPixels * 2 ? anchor.point : current;
		return new Choice("drift", anchor.name, jitter(center, style.driftPixels, frame.viewport), 100 + random.nextInt(301));
	}

	private Point jitter(Point center, double amplitude, Rectangle viewport)
	{
		double angle = random.nextDouble() * Math.PI * 2;
		double radius = amplitude * (0.5 + random.nextDouble() * 0.5);
		return new Point(Math.max(viewport.x, Math.min(viewport.x + viewport.width - 1,
			(int) Math.round(center.x + Math.cos(angle) * radius))),
			Math.max(viewport.y, Math.min(viewport.y + viewport.height - 1,
				(int) Math.round(center.y + Math.sin(angle) * radius))));
	}

	private static Point toward(Point current, Point target, double amplitude)
	{
		double scale = Math.min(1.0, amplitude / current.distance(target));
		return new Point((int) Math.round(current.x + (target.x - current.x) * scale),
			(int) Math.round(current.y + (target.y - current.y) * scale));
	}

	private static Anchor nearest(List<Anchor> anchors, Point current)
	{
		Anchor nearest = anchors.get(0);
		for (Anchor anchor : anchors)
			if (current.distanceSq(anchor.point) < current.distanceSq(nearest.point)) nearest = anchor;
		return nearest;
	}

	private void start(Frame frame, Choice choice)
	{
		GenericClientActivityContext context = frame.context.forkInputScope();
		activeContext = context;
		mouse.moveRest(choice.destination, choice.durationMillis, context).whenComplete((result, error) ->
		{
			context.cancelInput();
			synchronized (this) { if (activeContext == context) activeContext = null; }
			reporter.accept("CURSOR_FIDGET kind=" + choice.kind + " anchor=" + choice.anchor +
				" durationMillis=" + choice.durationMillis + " result=" +
				(error == null ? "completed" : rootMessage(error)));
		});
	}

	interface Motion
	{
		Point getPosition();
		boolean isOutside();
		Point offscreenTarget(GenericClientBehaviorProfile.Edge edge);
		CompletableFuture<String> moveRest(Point destination, int duration, GenericClientActivityContext context);
		void cancelRest(String reason, GenericClientActivityContext context);
	}

	static final class Anchor
	{
		final String name;
		final Point point;
		Anchor(String name, Point point) { this.name = name; this.point = new Point(point); }
	}

	static final class Frame
	{
		final GenericClientActivityContext context;
		final GenericClientBehaviorProfile profile;
		final long activeMillis;
		final long quietMillis;
		final boolean idle;
		final boolean blocked;
		final List<Anchor> anchors;
		final Point anticipated;
		final Rectangle viewport;

		Frame(GenericClientActivityContext context, GenericClientBehaviorProfile profile, long activeMillis, long quietMillis,
			boolean idle, boolean blocked, List<Anchor> anchors, Point anticipated, Rectangle viewport)
		{
			this.context = context;
			this.profile = profile;
			this.activeMillis = activeMillis;
			this.quietMillis = quietMillis;
			this.idle = idle;
			this.blocked = blocked;
			this.anchors = List.copyOf(anchors);
			this.anticipated = anticipated == null ? null : new Point(anticipated);
			this.viewport = new Rectangle(viewport);
		}
	}

	private static final class Choice
	{
		final String kind;
		final String anchor;
		final Point destination;
		final int durationMillis;
		Choice(String kind, String anchor, Point destination, int durationMillis)
		{
			this.kind = kind;
			this.anchor = anchor;
			this.destination = destination;
			this.durationMillis = durationMillis;
		}
	}
}
