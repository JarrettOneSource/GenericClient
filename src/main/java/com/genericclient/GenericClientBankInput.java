package com.genericclient;

import static com.genericclient.GenericClientErrors.rootMessage;
import static com.genericclient.GenericClientWidgets.matchesWidget;

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
		java.util.function.Consumer<String> reporter)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.menuInput = menuInput;
		this.keyboard = keyboard;
		this.reporter = reporter;
	}

	synchronized CompletableFuture<Map<String, Object>> loadout(
		List<Requirement> requirements,
		int minimumFreeSlots,
		boolean closeBank,
		GenericClientActivityContext activityContext)
	{
		validateRequirements(requirements, minimumFreeSlots);
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		if (closed || !activityContext.isInputAllowed())
		{
			result.complete(rejected(closed ? "bank_input_closed" : "action_cancelled"));
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
			loadoutFrom(initial, requested, minimumFreeSlots, closeBank, activityContext)).whenComplete((receipt, error) ->
		{
			Map<String, Object> completed = receipt;
			if (error != null)
			{
				completed = rejected(rootMessage(error));
			}
			finishOwned(result, completed);
		});
		return result;
	}

	synchronized CompletableFuture<Map<String, Object>> execute(String operation, Map<String, Object> arguments,
		GenericClientActivityContext context)
	{
		if (closed || !context.isInputAllowed()) return CompletableFuture.completedFuture(rejected("action_cancelled"));
		if (!running.compareAndSet(false, true)) return CompletableFuture.completedFuture(rejected("interaction_already_running"));
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		activeResult = result;
		clientRead(this::bankOpen).thenCompose(open ->
		{
			if (!open) return CompletableFuture.completedFuture(rejected("bank_not_open"));
			switch (operation)
			{
				case "bank.close": return closeBank(context);
				case "bank.deposit_inventory": return clickAndVerify(InterfaceID.Bankmain.DEPOSITINV,
					"deposit_inventory", () -> containerUsedSlots(InventoryID.INV) == 0, context);
				case "bank.deposit_equipment": return clickAndVerify(InterfaceID.Bankmain.DEPOSITWORN,
					"deposit_equipment", () -> containerUsedSlots(InventoryID.WORN) == 0, context);
				case "bank.withdraw":
				case "bank.deposit": return transfer(operation.equals("bank.withdraw"),
					((Number) arguments.get("id")).intValue(), ((Number) arguments.get("quantity")).intValue(),
					Boolean.TRUE.equals(arguments.get("all")), context);
				default: throw new IllegalArgumentException("Unknown bank operation: " + operation);
			}
		}).whenComplete((receipt, error) -> finishOwned(result, error == null ? receipt : rejected(rootMessage(error))));
		return result;
	}

	private CompletableFuture<Map<String, Object>> transfer(boolean withdrawing, int itemId, int quantity,
		boolean all, GenericClientActivityContext context)
	{
		if (quantity < 1) throw new IllegalArgumentException("Transfer quantity must be positive");
		String suffix = all ? "All" : quantity == 1 || quantity == 5 || quantity == 10 ? Integer.toString(quantity) : "X";
		String action = (withdrawing ? "Withdraw-" : "Deposit-") + suffix;
		CompletableFuture<Void> visible = withdrawing ? ensureBankItemVisible(itemId, context) : CompletableFuture.completedFuture(null);
		return visible.thenCompose(ignored -> clientRead(() -> observeTransfer(itemId,withdrawing))).thenCompose(before ->
		{
			int widget = withdrawing ? InterfaceID.Bankmain.ITEMS : InterfaceID.Bankside.ITEMS;
			return menuInput.interact(() -> resolveContainerItem(itemId,action,widget),context).thenCompose(click ->
			{
				if (!wasDispatched(click)) return CompletableFuture.completedFuture(click);
				CompletableFuture<?> entered = suffix.equals("X") ? enterQuantity(quantity,context) : CompletableFuture.completedFuture(null);
				return entered.thenCompose(ignored -> verifyReceipt(click,action,() -> withdrawing
					? itemQuantity(InventoryID.INV,before.id) > before.quantity
					: itemQuantity(InventoryID.INV,before.id) < before.quantity));
			});
		});
	}

	private TransferObservation observeTransfer(int itemId, boolean withdrawing)
	{
		int observedId = itemId;
		if (withdrawing && client.getVarbitValue(VarbitID.BANK_WITHDRAWNOTES) != 0)
		{
			int note = client.getItemDefinition(itemId).getLinkedNoteId();
			if (note >= 0) observedId = note;
		}
		return new TransferObservation(observedId,itemQuantity(InventoryID.INV,observedId));
	}

	private CompletableFuture<String> enterQuantity(int quantity, GenericClientActivityContext context)
	{
		return waitUntil(() -> client.getVarcIntValue(VarClientID.MESLAYERMODE) == 7,"bank_quantity_prompt")
			.thenCompose(prompt -> "complete".equals(prompt.get("status"))
				? keyboard.type(Integer.toString(quantity),true,INPUT_SETTLE_MILLIS,context)
				: CompletableFuture.failedFuture(new IllegalStateException("Bank quantity prompt did not open")));
	}

	private static final class TransferObservation
	{
		final int id;
		final int quantity;
		TransferObservation(int id, int quantity) { this.id = id; this.quantity = quantity; }
	}

	private CompletableFuture<Map<String, Object>> loadoutFrom(BankState initial, List<Requirement> requested,
		int minimumFreeSlots, boolean closeBank, GenericClientActivityContext activityContext)
	{		String rejection = preflight(initial, requested);
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
	}

	boolean isRunning()
	{
		return running.get();
	}

	synchronized void cancel(String reason)
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
		return ensureBankItemVisible(requirement.itemId, activityContext).thenCompose(ignored ->
			clientRead(() -> itemQuantity(InventoryID.BANK, requirement.itemId))).thenCompose(bankQuantity ->
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
					() -> resolveContainerItem(requirement.itemId, action, InterfaceID.Bankmain.ITEMS), activityContext)
					.thenCompose(receipt -> verifyReceipt(
						receipt,
						"withdraw_" + requirement.itemId,
						() -> itemQuantity(InventoryID.INV, requirement.itemId) == requirement.quantity));
			}
			return withdrawExact(requirement, activityContext);
		});
	}

	private CompletableFuture<Void> ensureBankItemVisible(int itemId, GenericClientActivityContext activityContext)
	{
		return clientRead(() -> activityContext.isInputAllowed() && scrollBankItemIntoView(itemId)).thenCompose(scrolled ->
		{
			if (!Boolean.TRUE.equals(scrolled))
			{
				return CompletableFuture.completedFuture(null);
			}
			CompletableFuture<Void> settled = new CompletableFuture<>();
			ScheduledFuture<?> future = executor.schedule(
				() -> settled.complete(null),
				INPUT_SETTLE_MILLIS,
				TimeUnit.MILLISECONDS);
			pending.add(future);
			return settled;
		});
	}

	private boolean scrollBankItemIntoView(int itemId)
	{
		Widget bankItems = visibleWidget(InterfaceID.Bankmain.ITEMS);
		if (bankItems == null)
		{
			return false;
		}
		Widget[] children = bankItems.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			children = bankItems.getChildren();
		}
		if (children == null)
		{
			return false;
		}
		for (Widget item : children)
		{
			if (item == null || item.isHidden() || item.getItemId() != itemId)
			{
				continue;
			}
			return scrollBankItem(bankItems, item, itemId);
		}
		return false;
	}

	private boolean scrollBankItem(Widget bankItems, Widget item, int itemId)
	{
		Rectangle container = bankItems.getBounds();
		Rectangle itemBounds = item.getBounds();
		if (container == null || itemBounds == null)
		{
			return false;
		}
		int bottom = Math.min(container.y + container.height, bankControlsTop());
		Rectangle viewport = new Rectangle(
			container.x,
			container.y,
			container.width,
			Math.max(0, bottom - container.y));
		int current = bankItems.getScrollY();
		int maximum = Math.max(0, bankItems.getScrollHeight() - bankItems.getHeight());
		int requested = scrollYForItem(itemBounds, viewport, current, maximum);
		if (requested == current)
		{
			return false;
		}
		bankItems.setScrollY(requested);
		client.setVarcIntValue(VarClientID.BANK_SCROLLPOS, requested);
		bankItems.revalidateScroll();
		reporter.accept("BANK_ITEM_SCROLLED id=" + itemId +
			" from=" + current + " to=" + requested);
		return true;
	}

	static int scrollYForItem(
		Rectangle itemBounds,
		Rectangle viewport,
		int currentScroll,
		int maximumScroll)
	{
		if (itemBounds == null || viewport == null)
		{
			return currentScroll;
		}
		int requested = currentScroll;
		if (itemBounds.y < viewport.y)
		{
			requested -= viewport.y - itemBounds.y + 4;
		}
		else if (itemBounds.y + itemBounds.height > viewport.y + viewport.height)
		{
			requested += itemBounds.y + itemBounds.height -
				(viewport.y + viewport.height) + 4;
		}
		return Math.max(0, Math.min(maximumScroll, requested));
	}

	private CompletableFuture<Map<String, Object>> withdrawExact(
		Requirement requirement, GenericClientActivityContext activityContext)
	{
		return menuInput.interact(() -> resolveContainerItem(requirement.itemId, "Withdraw-X", InterfaceID.Bankmain.ITEMS), activityContext)
			.thenCompose(click ->
			{
				if (!wasDispatched(click)) return CompletableFuture.completedFuture(click);
				return enterQuantity(requirement.quantity,activityContext)
					.thenCompose(ignored -> waitUntil(
						() -> itemQuantity(InventoryID.INV,requirement.itemId) == requirement.quantity,"withdraw_" + requirement.itemId))
					.thenApply(verified ->
					{
						Map<String,Object> result = new LinkedHashMap<>(verified);
						result.put("menu_receipt",click);
						result.put("typed_quantity",(long)requirement.quantity);
						result.put("click_count",clickCount(click));
						return result;
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
		return keyboard.pressEscape(activityContext).thenCompose(ignored -> waitUntil(() -> !bankOpen(), "close_bank"));
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

	private GenericClientMenuInput.Resolution resolveContainerItem(int itemId, String action, int containerWidget)
	{
		if (!bankOpen())
		{
			return GenericClientMenuInput.Resolution.rejected("bank_not_open");
		}
		Widget bankItems = visibleWidget(containerWidget);
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
				Rectangle clickableBounds = clipBankItemBounds(
					item.getBounds(), containerWidget == InterfaceID.Bankmain.ITEMS ? bankControlsTop() : client.getCanvasHeight(),
					client.getCanvasWidth(), client.getCanvasHeight());
				Point point = GenericClientMenuInput.randomPointInside(
					clickableBounds, client.getCanvasWidth(), client.getCanvasHeight());
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

	private int bankControlsTop()
	{
		int top = client.getCanvasHeight();
		int[] controls = {
			InterfaceID.Bankmain.SWAP_INSERT,
			InterfaceID.Bankmain.NOTE,
			InterfaceID.Bankmain.QUANTITY_LAYER,
			InterfaceID.Bankmain.PLACEHOLDER,
			InterfaceID.Bankmain.SEARCH,
			InterfaceID.Bankmain.DEPOSIT_LINE,
		};
		for (int id : controls)
		{
			Widget widget = visibleWidget(id);
			if (widget != null)
			{
				top = Math.min(top, widget.getBounds().y);
			}
		}
		return top;
	}

	static Rectangle clipBankItemBounds(
		Rectangle itemBounds,
		int controlsTop,
		int canvasWidth,
		int canvasHeight)
	{
		if (itemBounds == null)
		{
			return null;
		}
		int visibleHeight = Math.min(canvasHeight, controlsTop);
		Rectangle canvas = new Rectangle(0, 0, canvasWidth, Math.max(0, visibleHeight));
		Rectangle clipped = itemBounds.intersection(canvas);
		return clipped.width > 0 && clipped.height > 0 ? clipped : null;
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
				result.completeAsync(() -> receipt);
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

	private synchronized void finishOwned(CompletableFuture<Map<String, Object>> owner, Map<String, Object> receipt)
	{
		if (activeResult == owner) finish(receipt);
	}

	private synchronized void finish(Map<String, Object> receipt)
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
			result.completeAsync(() -> receipt);
		}
	}


	@Override
	public synchronized void close()
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
