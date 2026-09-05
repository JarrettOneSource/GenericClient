package com.genericclient;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import org.junit.Test;

public class GenericClientInputCallbacksTest
{
	@Test
	public void delayedCallbacksDoNotTouchTheReplacementOperation()
	{
		try (QueuedClient scene = new QueuedClient())
		{
			AtomicReference<CompletableFuture<?>> current = new AtomicReference<>(new CompletableFuture<>());
			GenericClientInputCallbacks callbacks = new GenericClientInputCallbacks(this, current::get, scene.executor);
			AtomicInteger effects = new AtomicInteger();
			Runnable queued = callbacks.bind(() -> { effects.incrementAndGet(); });
			BiConsumer<String, Throwable> finished = callbacks.bind((value, error) -> { effects.incrementAndGet(); });
			Function<String, CompletableFuture<String>> nextClick = callbacks.bind(value -> {
				effects.incrementAndGet();
				return CompletableFuture.completedFuture(value);
			});
			current.set(new CompletableFuture<>());
			queued.run();
			finished.accept("old input", null);
			assertTrue(nextClick.apply("old input").isCompletedExceptionally());
			assertEquals(0, effects.get());
			callbacks.bind(() -> { effects.incrementAndGet(); }).run();
			assertEquals(1, effects.get());
		}
	}

	@Test
	public void aCombatActionRevokedBeforeClientDispatchReleasesItsInput() throws Exception
	{
		try (QueuedClient scene = new QueuedClient();
			GenericClientCombatInput input = new GenericClientCombatInput(scene.client, scene.thread, scene.executor, null, message -> { }))
		{
			GenericClientActivityContext owner = GenericClientActivityContext.none().openInputScope();
			CompletableFuture<Map<String, Object>> stopped = input.setStyle(1, owner);
			owner.cancelInput();
			scene.queue.removeFirst().run();
			assertFalse("Revoked dispatch must release the native action", input.isRunning());
			assertEquals("action_cancelled", stopped.get(3, TimeUnit.SECONDS).get("result"));
			CompletableFuture<Map<String, Object>> replacement = input.setStyle(0, GenericClientActivityContext.none());
			scene.queue.removeFirst().run();
			assertEquals("style_already_selected", replacement.get(3, TimeUnit.SECONDS).get("result"));
		}
	}

	@Test
	public void aWalkRevokedBeforeClientDispatchReleasesItsInput() throws Exception
	{
		try (QueuedClient scene = new QueuedClient();
			GenericClientGameInput input = new GenericClientGameInput(scene.client, scene.thread, scene.executor, null, message -> { }))
		{
			for (boolean route : new boolean[]{true, false})
			{
				GenericClientActivityContext owner = GenericClientActivityContext.none().openInputScope();
				CompletableFuture<GenericClientInteractionResult> stopped = route
					? input.walkToFarthest(List.of(new WorldPoint(3200, 3200, 0)), owner)
					: input.walkToRandomTile(owner);
				owner.cancelInput();
				scene.queue.removeFirst().run();
				assertFalse("Revoked dispatch must release the native action", input.isRunning());
				GenericClientInteractionResult receipt = stopped.get(3, TimeUnit.SECONDS);
				assertEquals(route ? "WALK_TILE_CLICK_CANCELLED" : "WALK_CLICK_STOPPED", receipt.getDetail());
				assertFalse(receipt.isClickDispatched());
			}
			scene.gameState = GameState.LOGIN_SCREEN;
			CompletableFuture<GenericClientInteractionResult> replacement = input.walkToRandomTile(GenericClientActivityContext.none());
			scene.queue.removeFirst().run();
			assertEquals("WALK_CLICK_FAILED reason=client_not_logged_in", replacement.get(3, TimeUnit.SECONDS).getDetail());
		}
	}

	private static final class QueuedClient implements AutoCloseable
	{
		private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
		private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		private GameState gameState = GameState.LOGGED_IN;
		private final Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, arguments) -> {
				if (method.getName().equals("getGameState")) return gameState;
				if (method.getName().equals("getVarpValue")) return 0;
				throw new AssertionError("Unexpected client read: " + method.getName());
			});
		private final ClientThread thread = new ClientThread()
		{
			@Override public void invoke(Runnable action) { queue.addLast(action); }
		};
		@Override public void close() { executor.shutdownNow(); }
	}
}
