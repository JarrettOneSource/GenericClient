package com.genericclient;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientViaWalkTest
{
	@Test
	public void theNorthernPrisonCorridorDoesNotCutThroughTheCaptureArea() throws Exception
	{
		GenericClientPathfinder finder = new GenericClientPathfinder(GenericClientCollisionMap.loadBundled());
		GenericClientWalkRequest request = new GenericClientWalkRequest(point(2807, 2762), 2, 600,
			GenericClientActivityContext.none(), true, List.of(), GenericClientWalkInterrupts.NONE,
			List.of(point(2784, 2806), point(2784, 2770), point(2807, 2770)), null);
		GenericClientPathfinder.Result result = finder.findThrough(point(2762, 2804), request, 0,
			(x, y, plane, dx, dy, allowed) -> allowed);
		assertEquals(GenericClientPathfinder.Status.FOUND, result.getStatus());
		for (WorldPoint tile : result.getPath())
		{
			boolean prison = tile.getX() >= 2764 && tile.getX() <= 2776 && tile.getY() >= 2793 && tile.getY() <= 2802;
			boolean westClear = tile.getX() <= 2764 && tile.getY() >= 2797 && tile.getY() <= 2799;
			assertFalse("Route entered prison at " + tile, prison && !westClear);
		}
	}

	@Test
	public void choosesAnUnblockedArrivalAlternativeAndRejectsOrdinaryRadiusTiles() throws Exception
	{
		try (Fixture f = new Fixture())
		{
			WorldPoint start = point(3202, 3428);
			WorldPoint destination = point(3210, 3428);
			WorldPoint blocked = point(3208, 3428);
			WorldPoint allowed = point(3210, 3429);
			f.advance(start, false);
			GenericClientWalkRequest request = new GenericClientWalkRequest(destination, 2, 200,
				GenericClientActivityContext.none(), false, List.of(blocked), GenericClientWalkInterrupts.NONE, List.of(), null)
				.withArrivalTiles(List.of(blocked, allowed));
			CompletableFuture<Map<String, Object>> result = f.walker.walkTo(request, (input, action) -> action.get());
			assertEquals(allowed, f.awaitClick(0, start));
			f.advance(destination, false);
			assertFalse(result.isDone());
			f.advance(allowed, false);
			assertEquals("arrived", result.get(2, TimeUnit.SECONDS).get("status"));
		}
	}

	@Test
	public void hazardousRefreshKeepsEveryFinalTargetInsideTheAllowedSet() throws Exception
	{
		try (Fixture f = new Fixture())
		{
			WorldPoint start = point(3202, 3428);
			List<WorldPoint> allowed = List.of(point(3208, 3428), point(3208, 3429));
			f.advance(start, false);
			GenericClientWalkRequest request = new GenericClientWalkRequest(point(3210, 3428), 2, 200,
				GenericClientActivityContext.preset(GenericClientActivityContext.Activity.HAZARDOUS_TRAVEL).inIntent(),
				false, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null).withArrivalTiles(allowed);
			f.walker.walkTo(request, (input, action) -> action.get());
			WorldPoint first = f.awaitClick(0, start);
			WorldPoint second = f.awaitClick(1, start);
			assertTrue(allowed.contains(first));
			assertTrue(allowed.contains(second));
			assertNotEquals(first, second);
			assertEquals("Every accepted click must finish its completion bookkeeping", 2L,
				f.reports.stream().filter(report -> report.startsWith("WALK_CLICK plan=")).count());
		}
	}

	@Test
	public void anArrivalConstraintCannotBeChangedUsingAContinuation() throws Exception
	{
		try (Fixture f = new Fixture())
		{
			WorldPoint destination = point(3210, 3428);
			f.advance(point(3202, 3428), true);
			GenericClientWalkRequest request = new GenericClientWalkRequest(destination, 2, 200,
				GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.parse(java.util.Map.of("dialogue", true)), List.of(), null)
				.withArrivalTiles(List.of(point(3208, 3428)));
			String token = (String) f.walker.walkTo(request, (input, action) -> action.get()).get().get("continuation");
			f.advance(point(3202, 3428), false);
			GenericClientWalkRequest changed = new GenericClientWalkRequest(destination, 2, 200,
				GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.parse(java.util.Map.of("dialogue", true)), List.of(), token)
				.withArrivalTiles(List.of(point(3209, 3428)));
			assertEquals("invalid_resume", f.walker.walkTo(changed, (input, action) -> action.get()).get().get("reason"));
			assertTrue(f.targets.isEmpty());
		}
	}

	@Test
	public void captureAreaInterruptsBeforeOffRouteRecoveryOrDialogue() throws Exception
	{
		try (Fixture f = new Fixture())
		{
			WorldPoint start = point(3202, 3428);
			f.advance(start, false);
			GenericClientWalkInterrupts interrupts = GenericClientWalkInterrupts.parse(Map.of("dialogue", true,
				"area", Map.of("name", "prison", "bounds", List.of(
					Map.of("x1", 3300, "y1", 3300, "x2", 3310, "y2", 3310, "plane", 0)))));
			GenericClientWalkRequest request = new GenericClientWalkRequest(point(3240, 3428), 0, 200,
				GenericClientActivityContext.none(), false, Collections.emptyList(), interrupts, Collections.emptyList(), null);
			CompletableFuture<Map<String, Object>> result = f.walker.walkTo(request, (input, action) -> action.get());
			f.awaitClick(0, start);
			int before = f.targets.size();
			f.advance(point(3301, 3301), true);
			Map<String, Object> receipt = result.get(2, TimeUnit.SECONDS);
			assertEquals("interrupted", receipt.get("status"));
			assertEquals("area", receipt.get("reason"));
			assertEquals("prison", receipt.get("detail"));
			assertEquals(1L, receipt.get("plans"));
			assertEquals(before, f.targets.size());
		}
	}

	@Test
	public void cannotClickBeyondTheNextRequiredViaOrArriveBeforeVisitingIt() throws Exception
	{
		try (Fixture f = new Fixture())
		{
			WorldPoint destination = point(3202, 3428);
			WorldPoint via = point(3210, 3436);
			f.advance(destination, false);
			CompletableFuture<Map<String, Object>> result = f.walker.walkTo(request(destination, List.of(via), null), (input, action) -> action.get());
			WorldPoint first = f.awaitClick(0, destination);
			assertFalse(result.isDone());
			assertTrue(first.distanceTo(via) <= 2);
			assertNotEquals(destination, first);
			int clicks = f.targets.size();
			f.advance(first, false);
			assertEquals(destination, f.awaitClick(clicks, first));
			f.advance(destination, false);
			Map<String, Object> receipt = result.get(2, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			assertEquals(1, receipt.get("via_passed"));
			assertEquals(1L, receipt.get("plans"));
		}
	}

	@Test
	public void explicitContinuationKeepsPassedViaAndRejectsChangedJourneysOrReplay() throws Exception
	{
		try (Fixture f = new Fixture())
		{
			WorldPoint start = point(3202, 3428);
			WorldPoint firstVia = point(3208, 3436);
			WorldPoint secondVia = point(3220, 3436);
			WorldPoint destination = point(3230, 3428);
			List<WorldPoint> via = List.of(firstVia, secondVia);
			f.advance(start, false);
			CompletableFuture<Map<String, Object>> first = f.walker.walkTo(request(destination, via, null), (input, action) -> action.get());
			WorldPoint target = f.awaitClick(0, start);
			f.advance(target, false);
			f.advance(target, true);
			Map<String, Object> interrupted = first.get(2, TimeUnit.SECONDS);
			assertEquals("interrupted", interrupted.get("status"));
			assertEquals(1, interrupted.get("via_passed"));
			String token = (String) interrupted.get("continuation");
			assertNotNull(token);
			f.advance(point(3214, 3436), false);
			assertEquals("invalid_resume", f.walker.walkTo(request(point(3231, 3428), via, token), (input, action) -> action.get())
				.get().get("reason"));
			int oldClicks = f.targets.size();
			CompletableFuture<Map<String, Object>> resumed = f.walker.walkTo(request(destination, via, token), (input, action) -> action.get());
			WorldPoint next = f.awaitClick(oldClicks, point(3214, 3436));
			assertTrue(next.distanceTo(secondVia) <= 2);
			assertTrue(next.distanceTo(firstVia) > 2);
			f.advance(next, false);
			f.advance(destination, false);
			Map<String, Object> arrived = resumed.get(2, TimeUnit.SECONDS);
			assertEquals("arrived", arrived.get("status"));
			assertEquals(interrupted.get("journey"), arrived.get("journey"));
			assertEquals(2, arrived.get("via_passed"));
			assertEquals("invalid_resume", f.walker.walkTo(request(destination, via, token), (input, action) -> action.get())
				.get().get("reason"));
		}
	}

	@Test
	public void plannerReportsTheFailedSegmentWithoutSkippingIt() throws Exception
	{
		GenericClientPathfinder finder = new GenericClientPathfinder(GenericClientCollisionMap.loadBundled());
		GenericClientWalkRequest request = request(point(110, 100), List.of(point(100, 106)), null);
		GenericClientPathfinder.Result result = finder.findThrough(point(100, 100), request, 0,
			(x, y, plane, dx, dy, allowed) -> x + dx >= 90 && x + dx < 105 && y + dy >= 90 && y + dy < 120);
		assertEquals(GenericClientPathfinder.Status.UNREACHABLE, result.getStatus());
		assertEquals(2, result.getFailedSegment());
		assertTrue(result.getPath().isEmpty());
	}

	@Test
	public void retainsViaOnAnotherPlane()
	{
		List<WorldPoint> via = List.of(new WorldPoint(3201, 3201, 1));
		assertEquals(via, request(point(3200, 3200), via, null).via);
	}

	private static WorldPoint point(int x, int y) { return new WorldPoint(x, y, 0); }
	private static GenericClientWalkRequest request(WorldPoint destination, List<WorldPoint> via, String resume)
	{
		return new GenericClientWalkRequest(destination, 0, 200, GenericClientActivityContext.none(),
			false, Collections.emptyList(), GenericClientWalkInterrupts.parse(java.util.Map.of("dialogue", true)), via, resume);
	}

	private static final class Fixture implements AutoCloseable
	{
		private final List<WorldPoint> targets = new java.util.concurrent.CopyOnWriteArrayList<>();
		private final List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
		private final GenericClientWalker walker;
		private long tick;

		private Fixture() throws Exception
		{
			walker = GenericClientTestSupport.walker(new GenericClientWalker.WalkInput()
			{
				@Override public CompletableFuture<GenericClientInteractionResult> walkToFarthest(
					List<WorldPoint> candidates, GenericClientActivityContext context, double reachFraction)
				{
					WorldPoint target = candidates.get(0);
					targets.add(target);
					return CompletableFuture.completedFuture(new GenericClientInteractionResult(target,
						"WALK_TILE_CLICK_EXECUTED test", true, Collections.emptyMap(), Collections.emptyMap()));
				}
				@Override public void cancelWalkToTile(GenericClientActivityContext owner) { }
			}, new GenericClientWalker.ObstacleInput()
			{
				@Override public CompletableFuture<Map<String, Object>> interact(int id, String action,
					WorldPoint world, int within, GenericClientActivityContext context)
				{ throw new AssertionError("Open scene has no obstacles"); }
				@Override public void cancel(String reason, GenericClientActivityContext owner) { }
			}, GenericClientCollisionMap.loadBundled(), reports::add);
		}

		private void advance(WorldPoint player, boolean dialogue)
		{
			walker.publishGameTick(new GenericClientSnapshot(++tick, "LOGGED_IN", 240,
				new GenericClientPlayerSnapshot(1L, "via-test", player.getX(), player.getY(), 0, -1),
				Collections.emptyList(), GenericClientAccountSnapshot.empty(),
				new GenericClientQuestSnapshot(true, new int[0], Collections.emptyList(), dialogue
					? GenericClientQuestSnapshot.DialogueSnapshot.continueDialogue("Test", "Continue")
					: GenericClientQuestSnapshot.DialogueSnapshot.closed()), Collections.emptyList(),
				new GenericClientSceneCollision(true, player.getX() - 52, player.getY() - 52, 0, new int[104][104])));
		}

		private WorldPoint awaitClick(int previousCount, WorldPoint player) throws Exception
		{
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (targets.size() <= previousCount && System.nanoTime() < deadline)
			{
				advance(player, false);
				Thread.sleep(5L);
			}
			assertTrue("Walker did not dispatch the next click", targets.size() > previousCount);
			return targets.get(previousCount);
		}

		@Override public void close() { walker.close(); }
	}
}
