package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Canvas;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientWalkInputOwnershipTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void interruptedWalkRevokesQueuedNativeClicksBeforeTheScriptCanConsumeItsReceipt() throws Exception
	{
		assertEquals("Only independently owned guard input may dispatch", 1, exerciseQueuedInput(false));
	}

	@Test
	public void resumingAfterEmergencyDoesNotReactivateAPreviousQueuedClick() throws Exception
	{
		assertEquals("Only the resumed walk and guard may dispatch", 2, exerciseQueuedInput(true));
	}

	private int exerciseQueuedInput(boolean pauseAndResume) throws Exception
	{
		Canvas canvas = new Canvas();
		canvas.setSize(800, 600);
		AtomicInteger presses = new AtomicInteger();
		canvas.addMouseListener(new MouseAdapter()
		{
			@Override public void mousePressed(MouseEvent event) { presses.incrementAndGet(); }
		});
		ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
		GenericClientSyntheticMouse mouse = new GenericClientSyntheticMouse(canvas, timer, () -> null,
			() -> 100, new Point(20, 20), new GenericClientMouseEffectOverlay(
				() -> GenericClientMouseEffect.OFF, canvas::getWidth, canvas::getHeight, System::currentTimeMillis),
			message -> { }, point -> { });
		CountDownLatch releaseSwing = new CountDownLatch(1);
		CountDownLatch swingBlocked = new CountDownLatch(1);
		CountDownLatch releaseReceipt = new CountDownLatch(1);
		CountDownLatch receiptBlocked = new CountDownLatch(1);
		AtomicReference<CompletableFuture<Map<String, Object>>> journey = new AtomicReference<>();
		QueuedInput input = new QueuedInput(mouse);
		try (GenericClientWalker walker = GenericClientTestSupport.walker(input, new GenericClientWalker.ObstacleInput()
		{
			@Override public CompletableFuture<Map<String, Object>> interact(int id, String action,
				net.runelite.api.coords.WorldPoint world, int within, GenericClientActivityContext context)
			{ throw new AssertionError("This route has no traversal objects"); }
			@Override public void cancel(String reason, GenericClientActivityContext context) { }
		}, GenericClientCollisionMap.loadBundled(), message -> { });
			GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary, "queued-walk")
				.walkTo((request, clicks) -> {
					CompletableFuture<Map<String, Object>> result = walker.walkTo(request, clicks);
					journey.set(result);
					return result.thenApplyAsync(receipt -> { awaitGate(receiptBlocked,releaseReceipt); return receipt; });
				})
				.build())
		{
			try
			{
				GenericClientSnapshot initial = snapshot(0, false);
				walker.publishGameTick(initial);
				host.publishGameTick(initial);
				host.compile("QueuedWalk", GenericClientTestSupport.javaScript("QueuedWalk", "@ScriptSettings(id=\"walk\")",
					"public int onLoop(){Automation.activity(\"manual\");Automation.finish(Navigation.walk(" +
					"new Navigation.Journey(new org.dreambot.api.methods.map.Tile(3210,3428),0),Map.of(\"dialogue\",true),null));return -1;}"))
					.get(5, TimeUnit.SECONDS);
				CompletableFuture<String> started = host.start("walk");
				SwingUtilities.invokeLater(() -> awaitGate(swingBlocked, releaseSwing));
				assertTrue(swingBlocked.await(2, TimeUnit.SECONDS));
				awaitNativeClicks(walker, input, 1);
				if (pauseAndResume)
				{
					walker.pauseActiveInput("emergency_consumable");
					walker.resumeActiveInput("emergency_consumable");
					awaitNativeClicks(walker, input, 2);
				}
				else
				{
					walker.publishGameTick(snapshot(4, true));
					assertEquals("interrupted", journey.get().get(2, TimeUnit.SECONDS).get("status"));
				}
				CompletableFuture<String> guardClick = mouse.click(MouseEvent.BUTTON1, GenericClientActivityContext.none());
				releaseSwing.countDown();
				guardClick.get(2, TimeUnit.SECONDS);
				if (pauseAndResume)
				{
					walker.publishGameTick(new GenericClientSnapshot(5, "LOGGED_IN", 240,
						new GenericClientPlayerSnapshot(1L, "queued-walk", 3210, 3428, 0, -1), List.of()));
					assertEquals("arrived", journey.get().get(2, TimeUnit.SECONDS).get("status"));
				}
				assertTrue(receiptBlocked.await(2,TimeUnit.SECONDS));
				releaseReceipt.countDown();
				started.get(2, TimeUnit.SECONDS);
				return presses.get();
			}
			finally
			{
				releaseSwing.countDown();
				releaseReceipt.countDown();
			}
		}
		finally
		{
			releaseSwing.countDown();
			releaseReceipt.countDown();
			mouse.close();
			timer.shutdownNow();
		}
	}

	private static void awaitNativeClicks(GenericClientWalker walker, QueuedInput input, int count) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (input.queued.get() < count && System.nanoTime() < deadline)
		{
			walker.publishGameTick(snapshot(count + 2, false));
			Thread.sleep(5);
		}
		assertEquals("Walk did not reach the native click queue", count, input.queued.get());
		assertNotNull(input.pending);
	}

	private static void awaitGate(CountDownLatch entered, CountDownLatch release)
	{
		entered.countDown();
		try { release.await(); }
		catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
	}

	private static GenericClientSnapshot snapshot(long tick, boolean dialogue)
	{
		return new GenericClientSnapshot(tick, "LOGGED_IN", 240,
			new GenericClientPlayerSnapshot(1L, "queued-walk", 3202, 3428, 0, -1), List.of(),
			GenericClientAccountSnapshot.empty(), new GenericClientQuestSnapshot(true, new int[0], List.of(), dialogue
				? GenericClientQuestSnapshot.DialogueSnapshot.continueDialogue("Test", "Continue")
				: GenericClientQuestSnapshot.DialogueSnapshot.closed()));
	}

	private static final class QueuedInput implements GenericClientWalker.WalkInput
	{
		private final GenericClientSyntheticMouse mouse;
		private volatile CompletableFuture<GenericClientInteractionResult> pending;
		private final AtomicInteger queued = new AtomicInteger();

		private QueuedInput(GenericClientSyntheticMouse mouse) { this.mouse = mouse; }

		@Override public CompletableFuture<GenericClientInteractionResult> walkToFarthest(
			List<net.runelite.api.coords.WorldPoint> candidates, GenericClientActivityContext context, double reach)
		{
			CompletableFuture<GenericClientInteractionResult> result = new CompletableFuture<>();
			mouse.click(MouseEvent.BUTTON1, context).whenComplete((value, error) -> {
				if (error == null) result.complete(new GenericClientInteractionResult(candidates.get(0),
					"WALK_TILE_CLICK_EXECUTED test", true, Map.of(), Map.of()));
				else result.completeExceptionally(error);
			});
			pending = result;
			queued.incrementAndGet();
			return result;
		}

		@Override public void cancelWalkToTile(GenericClientActivityContext context)
		{
			if (pending != null) pending.complete(new GenericClientInteractionResult(null,
				"WALK_TILE_CLICK_CANCELLED", false, Map.of(), Map.of()));
		}
	}
}
