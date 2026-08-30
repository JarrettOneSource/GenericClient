package com.genericclient;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

final class GenericClientBankInput implements AutoCloseable
{
	private static final long VERIFY_INTERVAL_MILLIS = 200L;
	private static final long INPUT_SETTLE_MILLIS = 300L;
	private static final int VERIFY_ATTEMPTS = 50;

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientMenuInput menuInput;
	private final GenericClientSyntheticKeyboard keyboard;
	private final GenericClientBehaviorController behavior;
	private final java.util.function.Consumer<String> reporter;
	private final AtomicBoolean running = new AtomicBoolean();
	private final List<ScheduledFuture<?>> pending = new CopyOnWriteArrayList<>();
	private volatile CompletableFuture<Map<String, Object>> activeResult;
	private volatile boolean closed;

	GenericClientBankInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientMenuInput menuInput,
		GenericClientSyntheticKeyboard keyboard,
		GenericClientBehaviorController behavior,
		java.util.function.Consumer<String> reporter)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.menuInput = menuInput;
		this.keyboard = keyboard;
		this.behavior = behavior;
		this.reporter = reporter;
	}

	CompletableFuture<Map<String, Object>> loadout(
		List<Requirement> requirements,
		int minimumFreeSlots,
		boolean closeBank,
		GenericClientActivityContext activityContext)
	{
		validateRequirements(requirements, minimumFreeSlots);
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		if (closed)
		{
			result.complete(rejected("bank_input_closed"));
			return result;
		}
		if (!running.compareAndSet(false, true))
		{
			result.complete(rejected("interaction_already_running"));
			return result;
		}
		activeResult = result;
		List<Requirement> requested = Collections.unmodifiableList(new ArrayList<>(requirements));
		reporter.accept("BANK_LOADOUT_STARTED items=" + requested.size() +
			" minimumFreeSlots=" + minimumFreeSlots);

		clientRead(this::captureState).thenCompose(initial ->
		{
			String rejection = preflight(initial, requested);
			if (rejection != null)
			{
				return CompletableFuture.completedFuture(rejected(rejection));
			}
			CompletableFuture<List<Map<String, Object>>> flow =
				CompletableFuture.completedFuture(new ArrayList<>());
			if (initial.inventoryUsedSlots > 0)
			{
				flow = append(flow, () -> clickAndVerify(
					InterfaceID.Bankmain.DEPOSITINV,
					"deposit_inventory",
					() -> containerUsedSlots(InventoryID.INV) == 0,
					activityContext));
			}
			if (initial.equipmentUsedSlots > 0)
			{
				flow = append(flow, () -> clickAndVerify(
					InterfaceID.Bankmain.DEPOSITWORN,
					"deposit_equipment",
					() -> containerUsedSlots(InventoryID.WORN) == 0,
					activityContext));
			}
			if (initial.withdrawNotes)
			{
				flow = append(flow, () -> clickAndVerify(
					InterfaceID.Bankmain.NOTE,
					"withdraw_as_items",
					() -> client.getVarbitValue(VarbitID.BANK_WITHDRAWNOTES) == 0,
					activityContext));
			}
			for (Requirement requirement : requested)
			{
				flow = append(flow, () -> withdraw(requirement, activityContext));
			}
			return flow.thenCompose(steps -> verifyLoadout(requested, minimumFreeSlots)
				.thenCompose(verification ->
				{
					if (!"complete".equals(verification.get("status")) || !closeBank)
					{
						return CompletableFuture.completedFuture(finalReceipt(verification, steps, false));
					}
					return closeBank(activityContext).thenApply(closeReceipt ->
					{
						steps.add(closeReceipt);
						boolean bankClosed = "complete".equals(closeReceipt.get("status"));
						return finalReceipt(verification, steps, bankClosed);
					});
				}));
		}).whenComplete((receipt, error) ->
		{
			Map<String, Object> completed = receipt;
			if (error != null)
			{
				completed = rejected(rootMessage(error));
			}
			finish(completed);
		});
		return result;
	}

	boolean isRunning()
	{
		return running.get();
	}

	void cancel(String reason)
	{
		if (running.get())
		{
			finish(rejected("cancelled: " + reason));
		}
	}

	private CompletableFuture<Map<String, Object>> withdraw(
		Requirement requirement,
		GenericClientActivityContext activityContext)
	{
		return clientRead(() -> itemQuantity(InventoryID.BANK, requirement.itemId)).thenCompose(bankQuantity ->
		{
			String action;
			if (requirement.quantity == 1 || requirement.quantity == 5 || requirement.quantity == 10)
			{
				action = "Withdraw-" + requirement.quantity;
			}
			else if (bankQuantity == requirement.quantity)
			{
				action = "Withdraw-All";
			}
			else
			{
				action = "Withdraw-X";
			}
			if (!"Withdraw-X".equals(action))
			{
				return menuInput.interact(
					() -> resolveBankItem(requirement.itemId, action), activityContext)
					.thenCompose(receipt -> verifyReceipt(
						receipt,
						"withdraw_" + requirement.itemId,
						() -> itemQuantity(InventoryID.INV, requirement.itemId) == requirement.quantity));
			}
			return withdrawExact(requirement, activityContext);
		});
	}

	private CompletableFuture<Map<String, Object>> withdrawExact(
		Requirement requirement,
		GenericClientActivityContext activityContext)
	{
		Map<String, Object> outerBefore = new LinkedHashMap<>();
		Map<String, Object> outerAfter = new LinkedHashMap<>();
		return behavior.beforeAction(activityContext).thenCompose(before ->
		{
			outerBefore.putAll(before);
			return menuInput.interact(
				() -> resolveBankItem(requirement.itemId, "Withdraw-X"),
				GenericClientActivityContext.none());
		}).thenCompose(receipt ->
		{
			if (!wasDispatched(receipt))
			{
				return CompletableFuture.completedFuture(receipt);
			}
			return waitUntil(
				() -> client.getVarcIntValue(VarClientID.MESLAYERMODE) == 7,
				"bank_quantity_prompt").thenCompose(prompt ->
			{
				if (!"complete".equals(prompt.get("status")))
				{
					return CompletableFuture.completedFuture(prompt);
				}
				return clientRead(() ->
				{
					reporter.accept("BANK_QUANTITY_PROMPT type=" +
						client.getVarcIntValue(VarClientID.MESLAYERMODE) +
						" text=" + client.getVarcStrValue(VarClientID.MESLAYERINPUT));
					return true;
				}).thenCompose(ignored -> keyboard.typeAndEnter(
					Integer.toString(requirement.quantity), INPUT_SETTLE_MILLIS))
					.thenCompose(ignored -> clientRead(() ->
					{
						reporter.accept("BANK_QUANTITY_TYPED type=" +
							client.getVarcIntValue(VarClientID.MESLAYERMODE) +
							" text=" + client.getVarcStrValue(VarClientID.MESLAYERINPUT));
						return true;
					}))
					.thenCompose(typed -> waitUntil(
						() -> itemQuantity(InventoryID.INV, requirement.itemId) == requirement.quantity,
						"withdraw_" + requirement.itemId))
					.thenCompose(verified -> behavior.afterAction(activityContext).thenApply(after ->
					{
						outerAfter.putAll(after);
						Map<String, Object> result = new LinkedHashMap<>(verified);
						result.put("menu_receipt", receipt);
						result.put("typed_quantity", (long) requirement.quantity);
						result.put("behavior_before", outerBefore);
						result.put("behavior_after", outerAfter);
						result.put("click_count", clickCount(receipt));
						return result;
					}));
			});
		});
	}

	private CompletableFuture<Map<String, Object>> clickAndVerify(
		int widgetId,
		String description,
		Supplier<Boolean> condition,
		GenericClientActivityContext activityContext)
	{
		return menuInput.interact(
			() -> resolveWidget(widgetId, description), activityContext)
			.thenCompose(receipt -> verifyReceipt(receipt, description, condition));
	}

	private CompletableFuture<Map<String, Object>> verifyReceipt(
		Map<String, Object> receipt,
		String description,
		Supplier<Boolean> condition)
	{
		if (!wasDispatched(receipt))
		{
			return CompletableFuture.completedFuture(receipt);
		}
		return waitUntil(condition, description).thenApply(verification ->
		{
			Map<String, Object> result = new LinkedHashMap<>(verification);
			result.put("menu_receipt", receipt);
			result.put("click_count", clickCount(receipt));
			return result;
		});
	}

	private CompletableFuture<Map<String, Object>> verifyLoadout(
		List<Requirement> requirements,
		int minimumFreeSlots)
	{
		return clientRead(() ->
		{
			ItemContainer inventory = client.getItemContainer(InventoryID.INV);
			Map<Integer, Integer> expected = new LinkedHashMap<>();
			for (Requirement requirement : requirements)
			{
				expected.put(requirement.itemId, requirement.quantity);
			}
			Map<Integer, Integer> actual = quantities(inventory);
			int usedSlots = containerUsedSlots(inventory);
			int freeSlots = Math.max(0, 28 - usedSlots);
			Map<String, Object> receipt = new LinkedHashMap<>();
			receipt.put("status", expected.equals(actual) && freeSlots >= minimumFreeSlots
				? "complete"
				: "rejected");
			receipt.put("result", expected.equals(actual)
				? freeSlots >= minimumFreeSlots ? "bank_loadout_verified" : "minimum_free_slots_not_met"
				: "inventory_allowlist_mismatch");
			receipt.put("free_slots", (long) freeSlots);
			receipt.put("inventory", quantityMaps(actual));
			return receipt;
		});
	}

	private CompletableFuture<Map<String, Object>> closeBank(GenericClientActivityContext activityContext)
	{
		return behavior.beforeAction(activityContext)
			.thenCompose(before -> keyboard.pressEscape().thenCompose(ignored ->
				waitUntil(() -> !bankOpen(), "close_bank").thenCompose(receipt ->
					behavior.afterAction(activityContext).thenApply(after ->
					{
						Map<String, Object> result = new LinkedHashMap<>(receipt);
						result.put("behavior_before", before);
						result.put("behavior_after", after);
						result.put("click_count", 0L);
						return result;
					}))));
	}

	private GenericClientMenuInput.Resolution resolveWidget(int widgetId, String description)
	{
		if (!bankOpen())
		{
			return GenericClientMenuInput.Resolution.rejected("bank_not_open");
		}
		Widget widget = visibleWidget(widgetId);
		if (widget == null)
		{
			return GenericClientMenuInput.Resolution.rejected(description + "_not_visible");
		}
		Point point = GenericClientMenuInput.randomPointInside(
			widget.getBounds(), client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected(description + "_not_clickable");
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "bank_widget");
		value.put("widget_id", (long) widget.getId());
		value.put("widget_index", (long) widget.getIndex());
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			description,
			description,
			value,
			entry -> matchesWidget(entry, widget)));
	}

	private GenericClientMenuInput.Resolution resolveBankItem(int itemId, String action)
	{
		if (!bankOpen())
		{
			return GenericClientMenuInput.Resolution.rejected("bank_not_open");
		}
		Widget bankItems = visibleWidget(InterfaceID.Bankmain.ITEMS);
		if (bankItems == null)
		{
			return GenericClientMenuInput.Resolution.rejected("bank_items_not_visible");
		}
		Widget[] children = bankItems.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			children = bankItems.getChildren();
		}
		if (children != null)
		{
			for (Widget item : children)
			{
				if (item == null || item.isHidden() || item.getItemId() != itemId ||
					item.getBounds() == null || item.getBounds().width < 1 || item.getBounds().height < 1)
				{
					continue;
				}
				Point point = GenericClientMenuInput.randomPointInside(
					item.getBounds(), client.getCanvasWidth(), client.getCanvasHeight());
				if (point == null)
				{
					continue;
				}
				int slot = item.getIndex();
				int widgetId = bankItems.getId();
				Map<String, Object> value = new LinkedHashMap<>();
				value.put("kind", "bank_item");
				value.put("id", (long) itemId);
				value.put("slot", (long) slot);
				value.put("quantity", (long) item.getItemQuantity());
				return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
					point,
					action,
					"bank_item:" + itemId + ":" + slot,
					value,
					entry -> matchesBankItem(entry, itemId, slot, widgetId, action)));
			}
		}
		return GenericClientMenuInput.Resolution.rejected("requested_bank_item_not_visible");
	}

	private CompletableFuture<Map<String, Object>> waitUntil(
		Supplier<Boolean> condition,
		String description)
	{
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		poll(condition, description, VERIFY_ATTEMPTS, result);
		return result;
	}

	private void poll(
		Supplier<Boolean> condition,
		String description,
		int attemptsRemaining,
		CompletableFuture<Map<String, Object>> result)
	{
		if (!running.get() || closed)
		{
			result.complete(rejected("cancelled_while_waiting_for_" + description));
			return;
		}
		clientRead(condition).whenComplete((satisfied, error) ->
		{
			if (error != null)
			{
				result.complete(rejected(rootMessage(error)));
			}
			else if (Boolean.TRUE.equals(satisfied))
			{
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "complete");
				receipt.put("result", description + "_verified");
				result.complete(receipt);
			}
			else if (attemptsRemaining <= 1)
			{
				result.complete(rejected(description + "_verification_timeout"));
			}
			else
			{
				ScheduledFuture<?> future = executor.schedule(
					() -> poll(condition, description, attemptsRemaining - 1, result),
					VERIFY_INTERVAL_MILLIS,
					TimeUnit.MILLISECONDS);
				pending.add(future);
			}
		});
	}

	private <T> CompletableFuture<T> clientRead(Supplier<T> reader)
	{
		CompletableFuture<T> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			try
			{
				result.complete(reader.get());
			}
			catch (RuntimeException exception)
			{
				result.completeExceptionally(exception);
			}
		});
		return result;
	}

	private BankState captureState()
	{
		return new BankState(
			bankOpen(),
			containerUsedSlots(InventoryID.INV),
			containerUsedSlots(InventoryID.WORN),
			client.getVarbitValue(VarbitID.BANK_WITHDRAWNOTES) == 1,
			quantities(client.getItemContainer(InventoryID.BANK)),
			quantities(client.getItemContainer(InventoryID.INV)),
			quantities(client.getItemContainer(InventoryID.WORN)));
	}

	private static String preflight(BankState state, List<Requirement> requirements)
	{
		if (!state.open)
		{
			return "bank_not_open";
		}
		for (Requirement requirement : requirements)
		{
			int available = state.bank.getOrDefault(requirement.itemId, 0) +
				state.inventory.getOrDefault(requirement.itemId, 0) +
				state.equipment.getOrDefault(requirement.itemId, 0);
			if (available < requirement.quantity)
			{
				return "bank_missing_item:" + requirement.itemId + ":need=" +
					requirement.quantity + ":available=" + available;
			}
		}
		return null;
	}

	private boolean bankOpen()
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		return client.getGameState() == GameState.LOGGED_IN && items != null &&
			!items.isHidden() && !items.isSelfHidden();
	}

	private Widget visibleWidget(int id)
	{
		Widget widget = client.getWidget(id);
		if (widget == null || widget.isHidden() || widget.isSelfHidden())
		{
			return null;
		}
		Rectangle bounds = widget.getBounds();
		return bounds != null && bounds.width > 0 && bounds.height > 0 ? widget : null;
	}

	private int containerUsedSlots(int inventoryId)
	{
		return containerUsedSlots(client.getItemContainer(inventoryId));
	}

	private static int containerUsedSlots(ItemContainer container)
	{
		if (container == null || container.getItems() == null)
		{
			return 0;
		}
		int used = 0;
		for (Item item : container.getItems())
		{
			if (item != null && item.getId() >= 0)
			{
				used++;
			}
		}
		return used;
	}

	private int itemQuantity(int inventoryId, int itemId)
	{
		ItemContainer container = client.getItemContainer(inventoryId);
		return container == null ? 0 : container.count(itemId);
	}

	private static Map<Integer, Integer> quantities(ItemContainer container)
	{
		Map<Integer, Integer> values = new LinkedHashMap<>();
		if (container == null || container.getItems() == null)
		{
			return values;
		}
		for (Item item : container.getItems())
		{
			if (item != null && item.getId() >= 0)
			{
				values.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return values;
	}

	private static List<Map<String, Object>> quantityMaps(Map<Integer, Integer> quantities)
	{
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : quantities.entrySet())
		{
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", entry.getKey().longValue());
			item.put("quantity", entry.getValue().longValue());
			result.add(item);
		}
		return result;
	}

	private static boolean matchesWidget(MenuEntry entry, Widget target)
	{
		Widget widget = entry.getWidget();
		if (widget != null)
		{
			return widget.getId() == target.getId() && widget.getIndex() == target.getIndex();
		}
		return entry.getParam1() == target.getId() &&
			(target.getIndex() < 0 || entry.getParam0() == target.getIndex());
	}

	static boolean matchesBankItem(
		MenuEntry entry,
		int itemId,
		int slot,
		int widgetId,
		String action)
	{
		return entry.getItemId() == itemId && entry.getParam0() == slot &&
			entry.getParam1() == widgetId && action.equalsIgnoreCase(entry.getOption());
	}

	private static CompletableFuture<List<Map<String, Object>>> append(
		CompletableFuture<List<Map<String, Object>>> flow,
		Supplier<CompletableFuture<Map<String, Object>>> step)
	{
		return flow.thenCompose(receipts -> step.get().thenApply(receipt ->
		{
			receipts.add(receipt);
			if (!"complete".equals(receipt.get("status")))
			{
				throw new IllegalStateException(String.valueOf(receipt.get("result")));
			}
			return receipts;
		}));
	}

	private static void validateRequirements(List<Requirement> requirements, int minimumFreeSlots)
	{
		if (requirements == null || requirements.size() > 28)
		{
			throw new IllegalArgumentException("Bank loadout supports at most 28 item requirements");
		}
		if (minimumFreeSlots < 0 || minimumFreeSlots > 28)
		{
			throw new IllegalArgumentException("minimum_free_slots must be between 0 and 28");
		}
		Set<Integer> ids = new HashSet<>();
		for (Requirement requirement : requirements)
		{
			if (requirement == null || requirement.itemId < 0 || requirement.quantity < 1)
			{
				throw new IllegalArgumentException("Bank requirements need a non-negative id and positive quantity");
			}
			if (!ids.add(requirement.itemId))
			{
				throw new IllegalArgumentException("Duplicate bank requirement id: " + requirement.itemId);
			}
		}
	}

	private static Map<String, Object> finalReceipt(
		Map<String, Object> verification,
		List<Map<String, Object>> steps,
		boolean bankClosed)
	{
		Map<String, Object> receipt = new LinkedHashMap<>(verification);
		receipt.put("steps", steps);
		receipt.put("bank_closed", bankClosed);
		long clicks = 0;
		for (Map<String, Object> step : steps)
		{
			clicks += clickCount(step);
		}
		receipt.put("click_count", clicks);
		return receipt;
	}

	private static boolean wasDispatched(Map<String, Object> receipt)
	{
		return receipt != null && "dispatched".equals(receipt.get("status"));
	}

	private static long clickCount(Map<String, Object> receipt)
	{
		Object value = receipt.get("click_count");
		return value instanceof Number ? ((Number) value).longValue() : 0L;
	}

	private static Map<String, Object> rejected(String reason)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "rejected");
		receipt.put("result", reason);
		receipt.put("click_count", 0L);
		return receipt;
	}

	private void finish(Map<String, Object> receipt)
	{
		if (!running.getAndSet(false))
		{
			return;
		}
		for (ScheduledFuture<?> future : pending)
		{
			future.cancel(false);
		}
		pending.clear();
		reporter.accept("BANK_LOADOUT_COMPLETED status=" + receipt.get("status") +
			" result=" + receipt.get("result") + " clicks=" + receipt.get("click_count"));
		CompletableFuture<Map<String, Object>> result = activeResult;
		activeResult = null;
		if (result != null)
		{
			result.complete(receipt);
		}
	}

	private static String rootMessage(Throwable error)
	{
		Throwable current = error;
		while (current.getCause() != null)
		{
			current = current.getCause();
		}
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}

	@Override
	public void close()
	{
		closed = true;
		cancel("input_closed");
	}

	static final class Requirement
	{
		private final int itemId;
		private final int quantity;

		Requirement(int itemId, int quantity)
		{
			this.itemId = itemId;
			this.quantity = quantity;
		}

		int getItemId()
		{
			return itemId;
		}

		int getQuantity()
		{
			return quantity;
		}
	}

	private static final class BankState
	{
		private final boolean open;
		private final int inventoryUsedSlots;
		private final int equipmentUsedSlots;
		private final boolean withdrawNotes;
		private final Map<Integer, Integer> bank;
		private final Map<Integer, Integer> inventory;
		private final Map<Integer, Integer> equipment;

		private BankState(
			boolean open,
			int inventoryUsedSlots,
			int equipmentUsedSlots,
			boolean withdrawNotes,
			Map<Integer, Integer> bank,
			Map<Integer, Integer> inventory,
			Map<Integer, Integer> equipment)
		{
			this.open = open;
			this.inventoryUsedSlots = inventoryUsedSlots;
			this.equipmentUsedSlots = equipmentUsedSlots;
			this.withdrawNotes = withdrawNotes;
			this.bank = bank;
			this.inventory = inventory;
			this.equipment = equipment;
		}
	}
}
