package com.genericclient;

import static org.junit.Assert.*;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class GenericClientCursorBehaviorTest
{
	private static final GenericClientActivityContext SKILLING = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.SKILLING);

	@Test
	public void driftUsesAnObservedQuietWindowAndNeverAccruesPressureFromRepeatedCalls()
	{
		Fixture fixture = new Fixture(0.001, 0.99);
		fixture.publish(SKILLING, 5000, false, false, null);
		for (int i = 0; i < 100; i++) fixture.publish(SKILLING, 5000, false, false, null);
		assertTrue(fixture.mouse.moves.isEmpty());
		fixture.now = 600;
		fixture.publish(SKILLING, 5000, false, false, null);
		Move drift = fixture.mouse.moves.get(0);
		assertTrue(drift.duration >= 100 && drift.duration <= 400);
		assertTrue(drift.point.distance(new Point(100, 100)) <= 9);
		assertTrue(drift.context.isInputAllowed());
		fixture.mouse.finish();
		assertFalse(drift.context.isInputAllowed());
		assertTrue(fixture.logs.get(0).contains("kind=drift anchor=inventory"));
	}

	@Test
	public void busyShortPlainAndIntentWindowsCannotStartFidgets()
	{
		List<GenericClientActivityContext> policies = List.of(SKILLING.plain(), SKILLING.inIntent(),
			SKILLING.withPolicy(Map.of("fidget", "none")));
		for (GenericClientActivityContext context : policies)
		{
			Fixture fixture = new Fixture(0.001);
			fixture.publish(context, 5000, false, false, null);
			fixture.now = 60_000;
			fixture.publish(context, 5000, false, false, null);
			assertTrue(fixture.mouse.moves.isEmpty());
		}
		Fixture fixture = new Fixture(0.001);
		fixture.publish(SKILLING, 5000, false, true, null);
		fixture.now = 60_000;
		fixture.publish(SKILLING, 5000, false, true, null);
		fixture.publish(SKILLING, 999, false, false, null);
		assertTrue(fixture.mouse.moves.isEmpty());
	}

	@Test
	public void closingAWindowCancelsOnlyItsRestScope()
	{
		GenericClientActivityContext owner = SKILLING.openInputScope();
		GenericClientActivityContext walk = owner.openInputScope();
		Fixture fixture = new Fixture(0.001, 0.99);
		fixture.publish(owner, 5000, false, false, null);
		fixture.now = 600;
		fixture.publish(owner, 5000, false, false, null);
		Move rest = fixture.mouse.moves.get(0);
		assertTrue(walk.isInputAllowed());
		fixture.publish(owner, 999, false, false, null);
		assertTrue(rest.result.isCompletedExceptionally());
		assertFalse(rest.context.isInputAllowed());
		assertTrue(walk.isInputAllowed());
	}

	@Test
	public void anticipationMovesOnlyAFewPixelsTowardTheDeclaredTarget()
	{
		Fixture fixture = new Fixture(0.001, 0.0);
		Point target = new Point(700, 100);
		fixture.publish(SKILLING, 5000, false, false, target);
		fixture.now = 600;
		fixture.publish(SKILLING, 5000, false, false, target);
		Move rest = fixture.mouse.moves.get(0);
		assertTrue(rest.point.x > 100 && rest.point.x < target.x);
		assertEquals(100, rest.point.y);
		assertTrue(rest.duration >= 100 && rest.duration <= 400);
		assertTrue(rest.point.distance(new Point(100, 100)) <= 9);
		fixture.mouse.finish();
		assertTrue(fixture.logs.get(0).contains("kind=anticipation anchor=declared_target"));
	}

	@Test
	public void relocationNeedsEnoughTimeAndCanChooseAnotherAnchor()
	{
		Fixture fixture = new Fixture(0.001, 0.0);
		fixture.anchors = List.of(new GenericClientCursorBehavior.Anchor("inventory", new Point(100, 100)),
			new GenericClientCursorBehavior.Anchor("spell", new Point(700, 400)));
		fixture.publish(SKILLING, 5000, false, false, null);
		fixture.now = 600;
		fixture.publish(SKILLING, 5000, false, false, null);
		Move rest = fixture.mouse.moves.get(0);
		assertTrue(rest.point.distance(new Point(700, 400)) <= 9);
		assertTrue(rest.duration >= 400 && rest.duration <= 900);
		fixture.mouse.finish();
		assertTrue(fixture.logs.get(0).contains("kind=relocation anchor=spell"));
	}

	@Test
	public void glancesRequireAnotherOwnedActiveMinuteEvenWhenWallTimePasses()
	{
		GenericClientActivityContext travel = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.TRAVEL);
		Fixture fixture = new Fixture(0.001, 0.0, 0.001, 0.0, 0.5, 0.5, 0.001, 0.0, 0.5, 0.5, 0.001, 0.0);
		fixture.publish(travel, 5000, false, false, null);
		fixture.now = 600;
		fixture.activeMillis = 60_000;
		fixture.publish(travel, 5000, false, false, null);
		assertTrue(fixture.mouse.moves.get(0).duration >= 400 && fixture.mouse.moves.get(0).duration <= 900);
		fixture.mouse.finish();
		assertTrue(fixture.logs.get(0).contains("kind=glance"));
		fixture.mouse.position = new Point(100, 100);
		fixture.now = 1200;
		fixture.publish(travel, 5000, false, false, null);
		fixture.mouse.finish();
		assertFalse(fixture.logs.get(1).contains("kind=glance"));
		fixture.now = 61_000;
		fixture.publish(travel, 5000, false, false, null);
		fixture.mouse.finish();
		assertFalse(fixture.logs.get(2).contains("kind=glance"));
		fixture.now = 61_600;
		fixture.activeMillis = 120_000;
		fixture.publish(travel, 5000, false, false, null);
		fixture.mouse.finish();
		assertTrue(fixture.logs.get(3).contains("kind=glance"));
	}

	@Test
	public void closedWindowCannotCancelAnIndependentlyOwnedBreakMovement()
	{
		Fixture fixture = new Fixture(0.001);
		GenericClientActivityContext breakOwner = SKILLING.openInputScope();
		CompletableFuture<String> movement = fixture.mouse.moveRest(new Point(-10, 100), 600, breakOwner);
		fixture.publish(SKILLING, 5000, false, true, null);
		assertFalse("The rest model does not own the break's offscreen movement", movement.isDone());
		fixture.mouse.finish();
		assertTrue(movement.isDone());
	}

	@Test
	public void idleParkingIsOneRestStyleAndCanBeInterrupted()
	{
		Fixture fixture = new Fixture(0.001);
		fixture.publish(GenericClientActivityContext.none(), Long.MAX_VALUE, true, false, null);
		fixture.now = 600;
		fixture.publish(GenericClientActivityContext.none(), Long.MAX_VALUE, true, false, null);
		assertTrue(fixture.mouse.moves.isEmpty());
		fixture.now = 1200;
		fixture.publish(GenericClientActivityContext.none(), Long.MAX_VALUE, true, false, null);
		assertEquals(1, fixture.mouse.moves.size());
		assertTrue(fixture.mouse.moves.get(0).duration >= 400 && fixture.mouse.moves.get(0).duration <= 900);
		fixture.publish(GenericClientActivityContext.none(), Long.MAX_VALUE, true, true, null);
		assertTrue(fixture.mouse.moves.get(0).result.isCompletedExceptionally());
		assertTrue(fixture.logs.get(0).contains("kind=idle_park"));
	}

	@Test
	public void losingOrChangingTheAccountRevokesItsPendingRest()
	{
		for (long account : new long[]{0, 43})
		{
			Fixture fixture = new Fixture(0.001, 0.99);
			fixture.publish(SKILLING, 5000, false, false, null);
			fixture.now = 600;
			fixture.publish(SKILLING, 5000, false, false, null);
			Move previous = fixture.mouse.moves.get(0);
			fixture.profile = account == 0 ? null : GenericClientBehaviorProfile.fromAccountHash(account);
			fixture.publish(SKILLING, 5000, false, false, null);
			assertFalse(previous.context.isInputAllowed());
			assertTrue(previous.result.isCompletedExceptionally());
			assertEquals(1, fixture.mouse.moves.size());
		}
	}

	@Test
	public void revokedInputEmptyAnchorsAndAnOutsideMouseCannotFidget()
	{
		GenericClientActivityContext revoked = SKILLING.openInputScope();
		revoked.cancelInput();
		Fixture denied = new Fixture(0.001);
		denied.publish(revoked, 5000, false, false, null);
		denied.now = 60_000;
		denied.publish(revoked, 5000, false, false, null);
		assertTrue(denied.mouse.moves.isEmpty());

		Fixture empty = new Fixture(0.001);
		empty.anchors = List.of();
		empty.publish(SKILLING, 5000, false, false, null);
		empty.now = 60_000;
		empty.publish(SKILLING, 5000, false, false, null);
		assertTrue(empty.mouse.moves.isEmpty());
		empty.anchors = List.of(new GenericClientCursorBehavior.Anchor("inventory", new Point(100, 100)));
		empty.publish(SKILLING, 5000, false, false, null);
		assertTrue("Unavailable windows must not charge a minute of pressure", empty.mouse.moves.isEmpty());
		empty.now += 600;
		empty.publish(SKILLING, 5000, false, false, null);
		assertEquals(1, empty.mouse.moves.size());

		Fixture outside = new Fixture(0.001);
		outside.mouse.position = new Point(-10, 100);
		outside.publish(SKILLING, 5000, false, false, null);
		outside.now = 60_000;
		outside.publish(SKILLING, 5000, false, false, null);
		outside.publish(GenericClientActivityContext.none(), Long.MAX_VALUE, true, false, null);
		assertTrue(outside.mouse.moves.isEmpty());
	}

	@Test
	public void aPendingRestCannotBeReplacedByAnotherFidgetOrIdlePark()
	{
		Fixture fixture = new Fixture(0.001, 0.99);
		fixture.publish(SKILLING, 5000, false, false, null);
		fixture.now = 600;
		fixture.publish(SKILLING, 5000, false, false, null);
		Move first = fixture.mouse.moves.get(0);
		fixture.now = 60_000;
		fixture.publish(SKILLING, 5000, false, false, null);
		fixture.publish(GenericClientActivityContext.none(), Long.MAX_VALUE, true, false, null);
		assertEquals(1, fixture.mouse.moves.size());
		assertTrue(first.context.isInputAllowed());
		fixture.mouse.finish();
		assertFalse(first.context.isInputAllowed());
	}

	@Test
	public void idleParkingWaitsForTheFullQuietWindowAndDoesNotRepeat()
	{
		Fixture fixture = new Fixture(0.001);
		fixture.publish(GenericClientActivityContext.none(), 1499, true, false, null);
		fixture.now = 1200;
		fixture.publish(GenericClientActivityContext.none(), 1499, true, false, null);
		assertTrue(fixture.mouse.moves.isEmpty());
		fixture.publish(GenericClientActivityContext.none(), 1500, true, false, null);
		fixture.publish(GenericClientActivityContext.none(), 1500, true, false, null);
		assertEquals(1, fixture.mouse.moves.size());
		fixture.mouse.finish();
		fixture.publish(GenericClientActivityContext.none(), Long.MAX_VALUE, true, false, null);
		assertEquals(1, fixture.mouse.moves.size());
	}

	@Test
	public void driftStaysNearThePointerWhenEveryAnchorIsFarAway()
	{
		Fixture fixture = new Fixture(0.001);
		fixture.anchors = List.of(new GenericClientCursorBehavior.Anchor("inventory", new Point(700, 400)));
		GenericClientActivityContext drift = SKILLING.withPolicy(Map.of("fidget", "drift"));
		fixture.publish(drift, 5000, false, false, new Point(700, 400));
		fixture.now = 600;
		fixture.publish(drift, 5000, false, false, new Point(700, 400));
		assertTrue(fixture.mouse.moves.get(0).point.distance(new Point(100, 100)) <= 9);
		fixture.mouse.finish();
		assertTrue(fixture.logs.get(0).contains("kind=drift"));
	}

	@Test
	public void shortWindowsAndRejectedStyleDrawsKeepMotionLocal()
	{
		GenericClientActivityContext travel = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.TRAVEL);
		for (long quiet : new long[]{1000, 5000})
		{
			Fixture fixture = new Fixture(0.001, 0.99, 0.99, 0.99);
			Point anticipated = new Point(700, 400);
			fixture.publish(travel, quiet, false, false, anticipated);
			fixture.now = 600;
			fixture.activeMillis = 60_000;
			fixture.publish(travel, quiet, false, false, anticipated);
			assertEquals(1, fixture.mouse.moves.size());
			assertTrue(fixture.mouse.moves.get(0).point.distance(new Point(100, 100)) <= 9);
			fixture.mouse.finish();
			assertTrue(fixture.logs.get(0).contains("kind=drift"));
		}
	}

	@Test
	public void nearbyAnticipationReachesTheTargetWithoutOvershooting()
	{
		Fixture fixture = new Fixture(0.001, 0.0);
		Point target = new Point(101, 100);
		fixture.publish(SKILLING, 5000, false, false, target);
		fixture.now = 600;
		fixture.publish(SKILLING, 5000, false, false, target);
		assertEquals(target, fixture.mouse.moves.get(0).point);
		fixture.mouse.finish();
		assertTrue(fixture.logs.get(0).contains("kind=anticipation"));
	}

	@Test
	public void driftUsesTheNearestCapturedAnchor()
	{
		Fixture fixture = new Fixture(0.001);
		fixture.anchors = List.of(new GenericClientCursorBehavior.Anchor("far", new Point(700, 400)),
			new GenericClientCursorBehavior.Anchor("near", new Point(101, 100)));
		GenericClientActivityContext drift = SKILLING.withPolicy(Map.of("fidget", "drift"));
		fixture.publish(drift, 5000, false, false, null);
		fixture.now = 600;
		fixture.publish(drift, 5000, false, false, null);
		assertTrue(fixture.mouse.moves.get(0).point.distance(new Point(101, 100)) <= 9);
		fixture.mouse.finish();
		assertTrue(fixture.logs.get(0).contains("anchor=near"));
	}

	@Test
	public void cancellationRevokesInputBeforeTheMotionAcknowledgesIt()
	{
		Fixture fixture = new Fixture(0.001, 0.99);
		fixture.publish(SKILLING, 5000, false, false, null);
		fixture.now = 600;
		fixture.publish(SKILLING, 5000, false, false, null);
		Move rest = fixture.mouse.moves.get(0);
		fixture.mouse.acknowledgeCancellation = false;
		fixture.cursor.cancel();
		assertFalse(rest.result.isDone());
		assertFalse(rest.context.isInputAllowed());
		rest.result.complete("cancelled");
	}

	@Test
	public void fidgetCadenceIsIndependentOfPollingFrequencyAndClockOrigin()
	{
		List<Long> sparse = fidgetTimes(10_000, 1000);
		assertFalse(sparse.isEmpty());
		assertEquals(sparse, fidgetTimes(10_000, 100));
		assertEquals(sparse, fidgetTimes(0, 1000));
		double rate = GenericClientBehaviorProfile.fromAccountHash(42).getCursorStyle().fidgetsPerMinute;
		long firstSecond = (long) Math.ceil(-Math.log(0.5) * 60 / rate);
		assertEquals(firstSecond * 1000, sparse.get(0).longValue());
	}

	private static List<Long> fidgetTimes(long origin, long step)
	{
		Fixture fixture = new Fixture(0.5);
		fixture.now = origin;
		GenericClientActivityContext drift = SKILLING.withPolicy(Map.of("fidget", "drift"));
		fixture.publish(drift, 5000, false, false, null);
		List<Long> times = new ArrayList<>();
		for (long elapsed = step; elapsed <= 60_000; elapsed += step)
		{
			fixture.now = origin + elapsed;
			fixture.publish(drift, 5000, false, false, null);
			if (fixture.mouse.active != null)
			{
				times.add((long) Math.ceil(elapsed / 1000.0) * 1000);
				fixture.mouse.finish();
			}
		}
		return times;
	}

	@Test
	public void driftUsesAllDirectionsAndTheOuterHalfOfItsRadius()
	{
		boolean[] quadrants = new boolean[4];
		for (int i = 0; i < 64; i++)
		{
			Fixture fixture = new Fixture(0.001, i / 64.0, 0.9);
			GenericClientActivityContext drift = SKILLING.withPolicy(Map.of("fidget", "drift"));
			fixture.publish(drift, 5000, false, false, null);
			fixture.now = 600;
			fixture.publish(drift, 5000, false, false, null);
			Point point = fixture.mouse.moves.get(0).point;
			double distance = point.distance(100, 100);
			double radius = fixture.profile.getCursorStyle().driftPixels;
			assertTrue("Drift must move into its outer half", distance >= radius / 2 - 0.71);
			assertTrue("Drift must remain inside its radius", distance <= radius + 0.71);
			if (point.x != 100 && point.y != 100) quadrants[(point.x < 100 ? 2 : 0) + (point.y < 100 ? 1 : 0)] = true;
		}
		for (boolean reached : quadrants) assertTrue("Drift must cover every quadrant", reached);
	}

	@Test
	public void driftClipsToEveryViewportEdge()
	{
		for (Point corner : List.of(new Point(30, 40), new Point(79, 99)))
			for (double angle : new double[]{0.0, 0.25, 0.5, 0.75})
			{
				Fixture fixture = new Fixture(0.001, angle, 0.99);
				fixture.viewport = new Rectangle(30, 40, 50, 60);
				fixture.mouse.position = corner;
				fixture.anchors = List.of(new GenericClientCursorBehavior.Anchor("corner", corner));
				GenericClientActivityContext drift = SKILLING.withPolicy(Map.of("fidget", "drift"));
				fixture.publish(drift, 5000, false, false, null);
				fixture.now = 600;
				fixture.publish(drift, 5000, false, false, null);
				assertTrue(fixture.viewport.contains(fixture.mouse.moves.get(0).point));
			}
	}

	@Test
	public void driftCentersOnAnAnchorWithinTwiceItsRadius()
	{
		double radius = GenericClientBehaviorProfile.fromAccountHash(42).getCursorStyle().driftPixels;
		Point anchor = new Point(100 + (int) Math.floor(radius * 1.5), 100);
		long sumX = 0;
		long sumY = 0;
		for (int i = 0; i < 64; i++)
		{
			Fixture fixture = new Fixture(0.001, i / 64.0, 0.9);
			fixture.anchors = List.of(new GenericClientCursorBehavior.Anchor("inventory", anchor));
			GenericClientActivityContext drift = SKILLING.withPolicy(Map.of("fidget", "drift"));
			fixture.publish(drift, 5000, false, false, null);
			fixture.now = 600;
			fixture.publish(drift, 5000, false, false, null);
			Point point = fixture.mouse.moves.get(0).point;
			sumX += point.x;
			sumY += point.y;
		}
		assertEquals(anchor.x, sumX / 64.0, 0.1);
		assertEquals(anchor.y, sumY / 64.0, 0.1);
	}

	@Test
	public void diagonalAnticipationPreservesDirectionAndStopsAtNearbyTargets()
	{
		for (Point target : List.of(new Point(700, 500), new Point(0, 0), new Point(100, 100), new Point(101, 102)))
		{
			Fixture fixture = new Fixture(0.001, 0.0);
			fixture.publish(SKILLING, 5000, false, false, target);
			fixture.now = 600;
			fixture.publish(SKILLING, 5000, false, false, target);
			Point position = fixture.mouse.moves.get(0).point;
			double radius = fixture.profile.getCursorStyle().driftPixels;
			double originalDistance = new Point(100, 100).distance(target);
			assertTrue(position.distance(100, 100) <= radius + 0.71);
			if (originalDistance <= radius) assertEquals(target, position);
			else assertTrue(position.distance(target) <= originalDistance - radius + 0.71);
		}
	}

	@Test
	public void equidistantAnchorsKeepTheFirstDeclaredName()
	{
		Fixture fixture = new Fixture(0.001);
		fixture.anchors = List.of(new GenericClientCursorBehavior.Anchor("first", new Point(99, 100)),
			new GenericClientCursorBehavior.Anchor("second", new Point(101, 100)));
		GenericClientActivityContext drift = SKILLING.withPolicy(Map.of("fidget", "drift"));
		fixture.publish(drift, 5000, false, false, null);
		fixture.now = 600;
		fixture.publish(drift, 5000, false, false, null);
		fixture.mouse.finish();
		assertTrue(fixture.logs.get(0).contains("anchor=first"));
	}

	@Test
	public void parkingDelayStartsAtTheFirstIdleObservationAtAnyClockOrigin()
	{
		Fixture fixture = new Fixture(0.001);
		fixture.now = 60_000;
		fixture.publish(GenericClientActivityContext.none(), 5000, true, false, null);
		assertTrue(fixture.mouse.moves.isEmpty());
		fixture.now = 61_199;
		fixture.publish(GenericClientActivityContext.none(), 5000, true, false, null);
		assertTrue(fixture.mouse.moves.isEmpty());
		fixture.now = 61_200;
		fixture.publish(GenericClientActivityContext.none(), 5000, true, false, null);
		assertEquals(1, fixture.mouse.moves.size());
	}

	@Test
	public void longStyleWindowsIncludeExactlyFifteenHundredMilliseconds()
	{
		for (long quiet : new long[]{1499, 1500})
		{
			Fixture fixture = new Fixture(0.001, 0.0);
			fixture.publish(SKILLING, quiet, false, false, null);
			fixture.now = 600;
			fixture.publish(SKILLING, quiet, false, false, null);
			fixture.mouse.finish();
			assertTrue(fixture.logs.get(0).contains(quiet == 1500 ? "kind=relocation" : "kind=drift"));
		}
	}

	@Test
	public void styleDrawsAtTheirProbabilityThresholdDoNotSelectThatStyle()
	{
		GenericClientBehaviorProfile profile = GenericClientBehaviorProfile.fromAccountHash(42);
		GenericClientActivityContext travel = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.TRAVEL);
		Fixture glance = new Fixture(0.001, profile.getCursorReleaseProbability() * 0.1, 0.99);
		Fixture anticipation = new Fixture(0.001, profile.getCursorStyle().anticipationProbability, 0.99);
		Fixture relocation = new Fixture(0.001, profile.getCursorStyle().relocationShare);
		glance.publish(travel, 5000, false, false, null);
		anticipation.publish(SKILLING, 5000, false, false, new Point(700, 500));
		relocation.publish(SKILLING, 5000, false, false, null);
		for (Fixture fixture : List.of(glance, anticipation, relocation))
		{
			fixture.now = 600;
			fixture.activeMillis = 60_000;
		}
		glance.publish(travel, 5000, false, false, null);
		anticipation.publish(SKILLING, 5000, false, false, new Point(700, 500));
		relocation.publish(SKILLING, 5000, false, false, null);
		for (Fixture fixture : List.of(glance, anticipation, relocation))
		{
			fixture.mouse.finish();
			assertTrue(fixture.logs.get(0).contains("kind=drift"));
		}
	}

	private static final class Fixture
	{
		private final FakeMouse mouse = new FakeMouse();
		private final List<String> logs = new ArrayList<>();
		private long now;
		private long activeMillis;
		private Rectangle viewport = new Rectangle(0, 0, 800, 600);
		private GenericClientBehaviorProfile profile = GenericClientBehaviorProfile.fromAccountHash(42);
		private List<GenericClientCursorBehavior.Anchor> anchors = List.of(new GenericClientCursorBehavior.Anchor("inventory", new Point(100, 100)));
		private final GenericClientCursorBehavior cursor;

		Fixture(double... draws)
		{
			ArrayDeque<Double> values = new ArrayDeque<>();
			for (double value : draws) values.add(value);
			Random random = new Random(0)
			{
				@Override public double nextDouble() { return values.isEmpty() ? 0.5 : values.removeFirst(); }
				@Override public int nextInt(int bound) { return bound > 1 ? 1 : 0; }
			};
			cursor = new GenericClientCursorBehavior(mouse, () -> now, random, logs::add);
		}

		void publish(GenericClientActivityContext context, long quiet, boolean idle, boolean blocked, Point anticipated)
		{
			cursor.publish(new GenericClientCursorBehavior.Frame(context, profile,
				activeMillis, quiet, idle, blocked, anchors, anticipated, viewport));
		}
	}

	private static final class FakeMouse implements GenericClientCursorBehavior.Motion
	{
		private Point position = new Point(100, 100);
		private final List<Move> moves = new ArrayList<>();
		private Move active;
		private boolean acknowledgeCancellation = true;
		@Override public Point getPosition() { return new Point(position); }
		@Override public boolean isOutside() { return position.x < 0; }
		@Override public Point offscreenTarget(GenericClientBehaviorProfile.Edge edge) { return new Point(-10, 100); }
		@Override public CompletableFuture<String> moveRest(Point point, int duration, GenericClientActivityContext context)
		{
			active = new Move(point, duration, context);
			moves.add(active);
			return active.result;
		}
		@Override public void cancelRest(String reason, GenericClientActivityContext context)
		{
			if (active == null || active.context != context) return;
			Move cancelled = active;
			active = null;
			if (acknowledgeCancellation) cancelled.result.completeExceptionally(new IllegalStateException(reason));
		}
		void finish()
		{
			Move completed = active;
			active = null;
			position = completed.point;
			completed.result.complete("moved");
		}
	}

	private static final class Move
	{
		private final Point point;
		private final int duration;
		private final GenericClientActivityContext context;
		private final CompletableFuture<String> result = new CompletableFuture<>();
		Move(Point point, int duration, GenericClientActivityContext context)
		{
			this.point = point;
			this.duration = duration;
			this.context = context;
		}
	}
}
