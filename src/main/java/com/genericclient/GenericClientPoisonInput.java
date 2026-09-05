package com.genericclient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;

final class GenericClientPoisonInput
{
	private static final long POLL_MILLIS = 50L;
	private static final int CURE_POLL_ATTEMPTS = 40;
	private static final Set<Integer> ANTIPOISON_ITEM_IDS = Set.of(
		ItemID._4DOSEANTIPOISON,
		ItemID._3DOSEANTIPOISON,
		ItemID._2DOSEANTIPOISON,
		ItemID._1DOSEANTIPOISON,
		ItemID._4DOSE2ANTIPOISON,
		ItemID._3DOSE2ANTIPOISON,
		ItemID._2DOSE2ANTIPOISON,
		ItemID._1DOSE2ANTIPOISON);

	private final ScheduledExecutorService executor;
	private final Supplier<CompletableFuture<State>> stateReader;
	private final DrinkAction drinkAction;

	GenericClientPoisonInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientInventoryInput inventoryInput)
	{
		this(
			executor,
			() -> captureState(client, clientThread),
			(itemId, activityContext) -> inventoryInput.interact(
				itemId, null, "Drink", activityContext));
	}

	GenericClientPoisonInput(
		ScheduledExecutorService executor,
		Supplier<CompletableFuture<State>> stateReader,
		DrinkAction drinkAction)
	{
		this.executor = executor;
		this.stateReader = stateReader;
		this.drinkAction = drinkAction;
	}

	CompletableFuture<Map<String, Object>> cure(
		GenericClientActivityContext activityContext)
	{
		return stateReader.get().thenCompose(before ->
		{
			if (!before.loggedIn)
			{
				return CompletableFuture.completedFuture(
					receipt("rejected", "client_not_logged_in", before, before, null));
			}
			if (before.poisonValue < 0)
			{
				return CompletableFuture.completedFuture(
					receipt("unchanged", "antipoison_active", before, before, null));
			}
			if (before.poisonValue == 0)
			{
				return CompletableFuture.completedFuture(
					receipt("unchanged", "poison_not_active", before, before, null));
			}
			if (before.antipoisonItemId < 0)
			{
				return CompletableFuture.completedFuture(
					receipt("rejected", "antipoison_not_available", before, before, null));
			}

			return drinkAction.drink(before.antipoisonItemId, activityContext)
				.thenCompose(drinkReceipt ->
				{
					if (!dispatched(drinkReceipt))
					{
						return CompletableFuture.completedFuture(receipt(
							"rejected",
							"antipoison_drink_failed",
							before,
							before,
							drinkReceipt));
					}
					return waitForCure().thenApply(after -> receipt(
						after.loggedIn && after.poisonValue <= 0 ? "complete" : "rejected",
						after.loggedIn && after.poisonValue <= 0
							? "poison_cured"
							: "poison_cure_unverified",
						before,
						after,
						drinkReceipt));
				});
		});
	}

	private CompletableFuture<State> waitForCure()
	{
		CompletableFuture<State> result = new CompletableFuture<>();
		pollCure(0, result);
		return result;
	}

	private void pollCure(int attempt, CompletableFuture<State> result)
	{
		executor.schedule(() -> stateReader.get().whenComplete((state, error) ->
		{
			if (error != null)
			{
				result.completeExceptionally(error);
			}
			else if (!state.loggedIn || state.poisonValue <= 0 ||
				attempt + 1 >= CURE_POLL_ATTEMPTS)
			{
				result.complete(state);
			}
			else
			{
				pollCure(attempt + 1, result);
			}
		}), POLL_MILLIS, TimeUnit.MILLISECONDS);
	}

	private static CompletableFuture<State> captureState(
		Client client,
		ClientThread clientThread)
	{
		CompletableFuture<State> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
			int poisonValue = loggedIn ? client.getVarpValue(VarPlayerID.POISON) : 0;
			ItemContainer inventory = client.getItemContainer(InventoryID.INV);
			result.complete(new State(
				loggedIn,
				poisonValue,
				firstAntipoison(inventory)));
		});
		return result;
	}

	private static int firstAntipoison(ItemContainer inventory)
	{
		if (inventory == null || inventory.getItems() == null)
		{
			return -1;
		}
		for (Item item : inventory.getItems())
		{
			if (item != null && item.getQuantity() > 0 &&
				isAntipoisonItemId(item.getId()))
			{
				return item.getId();
			}
		}
		return -1;
	}

	static boolean isAntipoisonItemId(int itemId)
	{
		return ANTIPOISON_ITEM_IDS.contains(itemId);
	}

	private static boolean dispatched(Map<String, Object> receipt)
	{
		return receipt != null && "dispatched".equals(receipt.get("status"));
	}

	private static Map<String, Object> receipt(
		String status,
		String result,
		State before,
		State after,
		Map<String, Object> drinkReceipt)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("status", status);
		value.put("result", result);
		value.put("click_count", clickCount(drinkReceipt));
		value.put("poison_before", (long) before.poisonValue);
		value.put("poison_after", (long) after.poisonValue);
		value.put("item_id", before.antipoisonItemId < 0
			? null
			: (long) before.antipoisonItemId);
		if (drinkReceipt != null)
		{
			value.put("item_receipt", drinkReceipt);
		}
		return value;
	}

	private static long clickCount(Map<String, Object> receipt)
	{
		Object raw = receipt == null ? null : receipt.get("click_count");
		return raw instanceof Number ? ((Number) raw).longValue() : 0L;
	}

	@FunctionalInterface
	interface DrinkAction
	{
		CompletableFuture<Map<String, Object>> drink(
			int itemId,
			GenericClientActivityContext activityContext);
	}

	static final class State
	{
		private final boolean loggedIn;
		private final int poisonValue;
		private final int antipoisonItemId;

		State(boolean loggedIn, int poisonValue, int antipoisonItemId)
		{
			this.loggedIn = loggedIn;
			this.poisonValue = poisonValue;
			this.antipoisonItemId = antipoisonItemId;
		}
	}
}
