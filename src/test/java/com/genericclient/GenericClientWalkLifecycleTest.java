package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

public class GenericClientWalkLifecycleTest
{
	@Rule public TemporaryFolder folders = new TemporaryFolder();

	@Test
	public void aPlanQueuedBeforeAnEdgeExpiresIsRebuiltWithCurrentMemory() throws Exception
	{
		ExecutorService planning = Executors.newSingleThreadExecutor();
		CountDownLatch release = new CountDownLatch(1);
		planning.execute(() -> {
			try { release.await(); }
			catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
		});
		java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000);
		GenericClientEdgeMemory memory = new GenericClientEdgeMemory(folders.newFolder().toPath(), clock::get, message -> { });
		memory.activateAccount(42);
		WorldPoint start = new WorldPoint(3202, 3428, 0);
		WorldPoint next = new WorldPoint(3203, 3428, 0);
		WorldPoint destination = new WorldPoint(3230, 3428, 0);
		memory.record(GenericClientWalkTestFixtures.solidWallSnapshot(0, start, start, next)
			.findRouteBlock(List.of(start, next), 0, 1), GenericClientEdgeMemory.Reason.SOLID, null);
		Inputs inputs = new Inputs();
		List<String> reports = new CopyOnWriteArrayList<>();
		try (GenericClientWalker walker = new GenericClientWalker(inputs, inputs, inputs,
			(step, frame, context) -> CompletableFuture.failedFuture(new AssertionError("Unexpected transition")),
			GenericClientCollisionMap.loadBundled(), memory, reports::add, planning))
		{
			walker.publishGameTick(snapshot(0, start));
			CompletableFuture<Map<String, Object>> completion = walker.walkTo(new GenericClientWalkRequest(
				destination, 0, 60, GenericClientActivityContext.none(), true, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null));
			clock.addAndGet(5 * 60_000L);
			release.countDown();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (inputs.targets.isEmpty() && !completion.isDone() && System.nanoTime() < deadline)
			{
				walker.publishGameTick(snapshot(1, start));
				Thread.sleep(5);
			}
			assertEquals(List.of(destination), inputs.targets);
			walker.publishGameTick(snapshot(2, destination));
			Map<String, Object> receipt = completion.get(3, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			assertEquals(2L, receipt.get("plans"));
			assertEquals(List.of(), receipt.get("blocked_edges"));
			List<String> plans = reports.stream().filter(message -> message.startsWith("WALK_PLANNING"))
				.collect(java.util.stream.Collectors.toList());
			assertEquals(2, plans.size());
			assertTrue(plans.get(0).contains("blockedEdges=1"));
			assertTrue(plans.get(1).contains("blockedEdges=0"));
		}
		finally
		{
			release.countDown();
			planning.shutdownNow();
		}
	}

	@Test
	public void anUnchangedUnreachableStartReturnsItsFailureWithoutRetrying() throws Exception
	{
		Inputs inputs = new Inputs();
		try (GenericClientWalker walker = new GenericClientWalker(inputs, inputs, inputs,
			(step, frame, context) -> CompletableFuture.failedFuture(new AssertionError("Unexpected transition")),
			GenericClientCollisionMap.loadBundled(), GenericClientTestSupport.edgeMemory(), message -> { }))
		{
			walker.publishGameTick(snapshot(0, new WorldPoint(16000, 16000, 0)));
			Map<String, Object> receipt = walker.walkTo(new GenericClientWalkRequest(
				new WorldPoint(3210, 3428, 0), 0, 60, GenericClientActivityContext.none(),
				true, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null)).get(2, TimeUnit.SECONDS);
			assertEquals("unreachable", receipt.get("status"));
			assertEquals(1L, receipt.get("plans"));
			assertEquals(List.of(), inputs.targets);
		}
	}

	@Test
	public void aQueuedFailureFromAnOldStartDoesNotTerminateTheReachableJourney() throws Exception
	{
		ExecutorService planning = Executors.newSingleThreadExecutor();
		CountDownLatch release = new CountDownLatch(1);
		planning.execute(() -> {
			try { release.await(); }
			catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
		});
		Inputs inputs = new Inputs();
		try (GenericClientWalker walker = new GenericClientWalker(inputs, inputs, inputs,
			(step, frame, context) -> CompletableFuture.failedFuture(new AssertionError("Unexpected transition")),
			GenericClientCollisionMap.loadBundled(), GenericClientTestSupport.edgeMemory(), message -> { }, planning))
		{
			WorldPoint moved = new WorldPoint(3202, 3428, 0);
			WorldPoint destination = new WorldPoint(3210, 3428, 0);
			walker.publishGameTick(snapshot(0, new WorldPoint(16000, 16000, 0)));
			CompletableFuture<Map<String, Object>> completion = walker.walkTo(new GenericClientWalkRequest(
				destination, 0, 60, GenericClientActivityContext.none(), true, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null));
			walker.publishGameTick(snapshot(1, moved));
			release.countDown();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (inputs.targets.isEmpty() && !completion.isDone() && System.nanoTime() < deadline)
			{
				walker.publishGameTick(snapshot(2, moved));
				Thread.sleep(5);
			}
			assertFalse("Old-start failure was applied at the new start: " + completion.getNow(Map.of()), completion.isDone());
			assertEquals(List.of(destination), inputs.targets);
			walker.publishGameTick(snapshot(3, destination));
			Map<String, Object> receipt = completion.get(2, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			assertEquals(2L, receipt.get("plans"));
		}
		finally
		{
			release.countDown();
			planning.shutdownNow();
		}
	}

	@Test
	public void aQueuedSuccessfulPlanRejoinsFromTheLatestPosition() throws Exception
	{
		ExecutorService planning = Executors.newSingleThreadExecutor();
		CountDownLatch release = new CountDownLatch(1);
		planning.execute(() -> {
			try { release.await(); }
			catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
		});
		Inputs inputs = new Inputs();
		try (GenericClientWalker walker = new GenericClientWalker(inputs, inputs, inputs,
			(step, frame, context) -> CompletableFuture.failedFuture(new AssertionError("Unexpected transition")),
			GenericClientCollisionMap.loadBundled(), GenericClientTestSupport.edgeMemory(), message -> { }, planning))
		{
			WorldPoint moved = new WorldPoint(3203, 3433, 0);
			WorldPoint destination = new WorldPoint(3230, 3428, 0);
			walker.publishGameTick(snapshot(0, new WorldPoint(3202, 3428, 0)));
			CompletableFuture<Map<String, Object>> completion = walker.walkTo(new GenericClientWalkRequest(
				destination, 0, 60, GenericClientActivityContext.none(), true, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null));
			walker.publishGameTick(snapshot(1, moved));
			release.countDown();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (inputs.targets.isEmpty() && !completion.isDone() && System.nanoTime() < deadline)
			{
				walker.publishGameTick(snapshot(2, moved));
				Thread.sleep(5);
			}
			assertEquals(List.of(destination), inputs.targets);
			List<WorldPoint> candidates = inputs.candidates.get(0);
			assertEquals(1, moved.distanceTo(candidates.get(candidates.size() - 1)));
			walker.publishGameTick(snapshot(3, destination));
			Map<String, Object> receipt = completion.get(2, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			assertEquals(1L, receipt.get("plans"));
			assertEquals(1, receipt.get("start_rejoins"));
		}
		finally
		{
			release.countDown();
			planning.shutdownNow();
		}
	}

	private static GenericClientSnapshot snapshot(long tick, WorldPoint point)
	{
		return new GenericClientSnapshot(tick, "LOGGED_IN", 240,
			new GenericClientPlayerSnapshot(1L, "lifecycle-test", point.getX(), point.getY(), point.getPlane(), -1), List.of());
	}

	private static final class Inputs implements GenericClientWalker.WalkInput, GenericClientWalker.ObstacleInput, GenericClientWalker.RunInput
	{
		final List<WorldPoint> targets = new CopyOnWriteArrayList<>();
		final List<List<WorldPoint>> candidates = new CopyOnWriteArrayList<>();

		@Override public CompletableFuture<GenericClientInteractionResult> walkToFarthest(
			List<WorldPoint> candidates, GenericClientActivityContext context, double reach)
		{
			WorldPoint target = candidates.get(0);
			this.candidates.add(candidates);
			targets.add(target);
			return CompletableFuture.completedFuture(new GenericClientInteractionResult(target,
				"WALK_TILE_CLICK_EXECUTED test", true, Map.of(), Map.of()));
		}

		@Override public CompletableFuture<Map<String, Object>> interact(int id, String action,
			WorldPoint world, int within, GenericClientActivityContext context)
		{ throw new AssertionError("This journey has no traversal objects"); }

		@Override public CompletableFuture<Map<String, Object>> setEnabled(boolean enabled, GenericClientActivityContext context)
		{ return CompletableFuture.completedFuture(Map.of("status", "unchanged")); }

		@Override public void cancelWalkToTile(GenericClientActivityContext context) { }
		@Override public void cancel(String reason, GenericClientActivityContext context) { }
	}
}
