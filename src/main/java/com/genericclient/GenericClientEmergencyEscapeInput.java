package com.genericclient;

import java.util.Collections;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;

final class GenericClientEmergencyEscapeInput
{
	private static final long POLL_MILLIS = 50L;
	private static final int CHOICE_POLL_ATTEMPTS = 40;
	private static final int ARRIVAL_POLL_ATTEMPTS = 160;

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientInventoryInput inventoryInput;
	private final GenericClientDialogueInput dialogueInput;
	private final GenericClientWalker walker;
	private final Consumer<String> reporter;

	GenericClientEmergencyEscapeInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientInventoryInput inventoryInput,
		GenericClientDialogueInput dialogueInput,
		GenericClientWalker walker,
		Consumer<String> reporter)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.inventoryInput = inventoryInput;
		this.dialogueInput = dialogueInput;
		this.walker = walker;
		this.reporter = reporter;
	}

	CompletableFuture<Map<String, Object>> escape(GenericClientEmergencyEscape escape)
	{
		if (escape.getType() == GenericClientEmergencyEscape.Type.WALK)
		{
			return walker.walkTo(new GenericClientWalkRequest(escape.getDestination(), escape.getWithin(), 300, GenericClientActivityContext.none(),
				true, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
		}
		return inventoryDialogueEscape(escape);
	}

	private CompletableFuture<Map<String, Object>> inventoryDialogueEscape(
		GenericClientEmergencyEscape escape)
	{
		reporter.accept("EMERGENCY_INVENTORY_ESCAPE_ITEMS candidates=" + escape.getItemIds() +
			" action=" + escape.getItemAction());
		return resolveInventoryItem(escape).thenCompose(itemId ->
		{
			if (itemId < 0)
			{
				return CompletableFuture.completedFuture(
					rejected("emergency_escape_item_missing", null, null));
			}
			reporter.accept("EMERGENCY_INVENTORY_ESCAPE_ITEM resolved=" + itemId);
			return inventoryInput.interact(
			itemId,
			null,
			escape.getItemAction(),
			GenericClientActivityContext.none()).thenCompose(itemReceipt ->
		{
			if (!accepted(itemReceipt))
			{
				return CompletableFuture.completedFuture(
					rejected("emergency_escape_item_failed", itemReceipt, null));
			}
			return waitForChoice(escape.getDialogueChoice(), 0).thenCompose(visibleChoice ->
			{
				if (visibleChoice == null)
				{
					return CompletableFuture.completedFuture(
						rejected("emergency_escape_choice_not_visible", itemReceipt, null));
				}
				reporter.accept("EMERGENCY_INVENTORY_ESCAPE_CHOICE choice=" +
					visibleChoice);
				return dialogueInput.choose(
					visibleChoice, null, true,
					GenericClientActivityContext.none(),
					false).thenCompose(choiceReceipt ->
				{
					if (!accepted(choiceReceipt))
					{
						return CompletableFuture.completedFuture(rejected(
							"emergency_escape_choice_failed", itemReceipt, choiceReceipt));
					}
					return waitForArrival(
						escape.getDestination(), escape.getWithin(), 0).thenApply(arrived ->
						arrived
							? completed(escape, itemReceipt, choiceReceipt)
							: rejected(
								"emergency_escape_arrival_unverified", itemReceipt, choiceReceipt));
				});
			});
			});
		});
	}

	private CompletableFuture<Integer> resolveInventoryItem(
		GenericClientEmergencyEscape escape)
	{
		CompletableFuture<Integer> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			ItemContainer inventory = client.getItemContainer(InventoryID.INV);
			result.complete(escape.resolveItemId(
				itemId -> carried(inventory, itemId)));
		});
		return result;
	}

	private static boolean carried(ItemContainer inventory, int itemId)
	{
		if (inventory == null)
		{
			return false;
		}
		for (Item item : inventory.getItems())
		{
			if (item != null && item.getId() == itemId && item.getQuantity() > 0)
			{
				return true;
			}
		}
		return false;
	}

	private CompletableFuture<String> waitForChoice(String choice, int attempt)
	{
		CompletableFuture<String> result = new CompletableFuture<>();
		executor.schedule(() -> clientThread.invoke(() ->
		{
			String visible = dialogueInput.matchingVisibleChoice(choice);
			if (visible != null)
			{
				result.complete(visible);
			}
			else if (attempt + 1 >= CHOICE_POLL_ATTEMPTS)
			{
				result.complete(null);
			}
			else
			{
				waitForChoice(choice, attempt + 1).whenComplete((found, error) ->
				{
					if (error == null)
					{
						result.complete(found);
					}
					else
					{
						result.completeExceptionally(error);
					}
				});
			}
		}), POLL_MILLIS, TimeUnit.MILLISECONDS);
		return result;
	}

	private CompletableFuture<Boolean> waitForArrival(
		WorldPoint destination,
		int within,
		int attempt)
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		executor.schedule(() -> clientThread.invoke(() ->
		{
			Player player = client.getLocalPlayer();
			WorldPoint world = player == null ? null : player.getWorldLocation();
			if (client.getGameState() == GameState.LOGGED_IN && isWithin(world, destination, within))
			{
				result.complete(true);
			}
			else if (attempt + 1 >= ARRIVAL_POLL_ATTEMPTS)
			{
				result.complete(false);
			}
			else
			{
				waitForArrival(destination, within, attempt + 1).whenComplete((arrived, error) ->
				{
					if (error == null)
					{
						result.complete(arrived);
					}
					else
					{
						result.completeExceptionally(error);
					}
				});
			}
		}), POLL_MILLIS, TimeUnit.MILLISECONDS);
		return result;
	}

	private static boolean isWithin(WorldPoint world, WorldPoint destination, int within)
	{
		return world != null && world.getPlane() == destination.getPlane() &&
			Math.max(
				Math.abs(world.getX() - destination.getX()),
				Math.abs(world.getY() - destination.getY())) <= within;
	}

	private static Map<String, Object> completed(
		GenericClientEmergencyEscape escape,
		Map<String, Object> itemReceipt,
		Map<String, Object> choiceReceipt)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("status", "dispatched");
		result.put("result", "emergency_inventory_dialogue_escape_complete");
		result.put("click_count", clickCount(itemReceipt) + clickCount(choiceReceipt));
		result.put("item", itemReceipt);
		result.put("choice", choiceReceipt);
		result.put("escape", escape.toMap());
		return result;
	}

	private static Map<String, Object> rejected(
		String reason,
		Map<String, Object> itemReceipt,
		Map<String, Object> choiceReceipt)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("status", "rejected");
		result.put("result", reason);
		result.put("click_count", clickCount(itemReceipt) + clickCount(choiceReceipt));
		result.put("item", itemReceipt);
		if (choiceReceipt != null)
		{
			result.put("choice", choiceReceipt);
		}
		return result;
	}

	private static boolean accepted(Map<String, Object> receipt)
	{
		return receipt != null && "dispatched".equals(receipt.get("status"));
	}

	private static long clickCount(Map<String, Object> receipt)
	{
		return receipt != null && receipt.get("click_count") instanceof Number
			? ((Number) receipt.get("click_count")).longValue()
			: 0L;
	}
}
