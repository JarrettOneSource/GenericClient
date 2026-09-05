package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

public class GenericClientPoisonInputTest
{
	private final ScheduledExecutorService executor =
		Executors.newSingleThreadScheduledExecutor();

	@After
	public void closeExecutor()
	{
		executor.shutdownNow();
	}

	@Test
	public void curesPositivePoisonWithCarriedAntipoison() throws Exception
	{
		AtomicReference<GenericClientPoisonInput.State> state = new AtomicReference<>(
			new GenericClientPoisonInput.State(true, 6, 181));
		AtomicInteger drinks = new AtomicInteger();
		GenericClientPoisonInput input = new GenericClientPoisonInput(
			executor,
			() -> CompletableFuture.completedFuture(state.get()),
			(itemId, activityContext) ->
			{
				assertEquals(181, itemId);
				drinks.incrementAndGet();
				state.set(new GenericClientPoisonInput.State(true, -19, 183));
				return CompletableFuture.completedFuture(dispatched());
			});

		Map<String, Object> receipt = input.cure(GenericClientActivityContext.none())
			.get(2, TimeUnit.SECONDS);

		assertEquals("complete", receipt.get("status"));
		assertEquals("poison_cured", receipt.get("result"));
		assertEquals(6L, receipt.get("poison_before"));
		assertEquals(-19L, receipt.get("poison_after"));
		assertEquals(181L, receipt.get("item_id"));
		assertEquals(1, drinks.get());
	}

	@Test
	public void rejectsPoisonWithoutAntipoison() throws Exception
	{
		AtomicInteger drinks = new AtomicInteger();
		GenericClientPoisonInput input = new GenericClientPoisonInput(
			executor,
			() -> CompletableFuture.completedFuture(
				new GenericClientPoisonInput.State(true, 6, -1)),
			(itemId, activityContext) ->
			{
				drinks.incrementAndGet();
				return CompletableFuture.completedFuture(dispatched());
			});

		Map<String, Object> receipt = input.cure(GenericClientActivityContext.none())
			.get(2, TimeUnit.SECONDS);

		assertEquals("rejected", receipt.get("status"));
		assertEquals("antipoison_not_available", receipt.get("result"));
		assertEquals(0, drinks.get());
	}

	@Test
	public void leavesHealthyAndProtectedPlayersAlone() throws Exception
	{
		AtomicInteger drinks = new AtomicInteger();
		AtomicReference<GenericClientPoisonInput.State> state = new AtomicReference<>(
			new GenericClientPoisonInput.State(true, 0, 2448));
		GenericClientPoisonInput input = new GenericClientPoisonInput(
			executor,
			() -> CompletableFuture.completedFuture(state.get()),
			(itemId, activityContext) ->
			{
				drinks.incrementAndGet();
				return CompletableFuture.completedFuture(dispatched());
			});

		Map<String, Object> healthy = input.cure(GenericClientActivityContext.none())
			.get(2, TimeUnit.SECONDS);
		state.set(new GenericClientPoisonInput.State(true, -19, 2448));
		Map<String, Object> protectedPlayer = input.cure(GenericClientActivityContext.none())
			.get(2, TimeUnit.SECONDS);

		assertEquals("poison_not_active", healthy.get("result"));
		assertEquals("antipoison_active", protectedPlayer.get("result"));
		assertEquals(0, drinks.get());
	}

	@Test
	public void recognizesEveryStandardAndSuperAntipoisonDose()
	{
		for (int id : new int[]{2446, 175, 177, 179, 2448, 181, 183, 185})
		{
			assertTrue(GenericClientPoisonInput.isAntipoisonItemId(id));
		}
		assertFalse(GenericClientPoisonInput.isAntipoisonItemId(2434));
	}

	private static Map<String, Object> dispatched()
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "dispatched");
		receipt.put("result", "menu_action_executed");
		receipt.put("click_count", 1L);
		return receipt;
	}
}
