package com.genericclient;

import static com.genericclient.GenericClientWidgets.isVisible;
import static com.genericclient.GenericClientWidgets.matchesWidget;
import static com.genericclient.GenericClientInteractionReceipts.clickCount;
import static com.genericclient.GenericClientInteractionReceipts.composite;
import static com.genericclient.GenericClientInteractionReceipts.rejected;
import static com.genericclient.GenericClientInteractionReceipts.wasDispatched;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

final class GenericClientInventoryInput
{
	private static final long INVENTORY_POLL_MILLIS = 50L;
	private static final int INVENTORY_POLL_ATTEMPTS = 10;
	private static final int INVENTORY_TAB_POLL_ATTEMPTS = 40;
	private static final int ITEM_POLL_ATTEMPTS = 40;
	private static final int[] INVENTORY_TABS =
	{
		InterfaceID.Toplevel.STONE3,
		InterfaceID.ToplevelOsrsStretch.STONE3,
		InterfaceID.ToplevelPreEoc.STONE3
	};

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientMenuInput menuInput;

	GenericClientInventoryInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientMenuInput menuInput)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.menuInput = menuInput;
	}

	CompletableFuture<Map<String, Object>> interact(
		int itemId,
		Integer requestedSlot,
		String action,
		GenericClientActivityContext activityContext)
	{
		if (itemId < 0)
		{
			throw new IllegalArgumentException("Item id cannot be negative");
		}
		if (requestedSlot != null && (requestedSlot < 0 || requestedSlot >= 28))
		{
			throw new IllegalArgumentException("Inventory slot must be between 0 and 27");
		}
		String cleanAction = requireText(action, "Item action");
		CompletableFuture<Boolean> inventoryVisible = new CompletableFuture<>();
		clientThread.invoke(() -> inventoryVisible.complete(visibleInventory() != null));
		return inventoryVisible.thenCompose(visible -> visible
			? interactVisible(itemId, requestedSlot, cleanAction, activityContext)
			: openInventoryThenInteract(itemId, requestedSlot, cleanAction, activityContext));
	}

	CompletableFuture<Map<String, Object>> castSelectedSpellOnItem(
		int itemId,
		Integer requestedSlot,
		int spellWidgetId,
		String spellName,
		GenericClientActivityContext activityContext)
	{
		if (itemId < 0)
		{
			throw new IllegalArgumentException("Item id cannot be negative");
		}
		if (requestedSlot != null && (requestedSlot < 0 || requestedSlot >= 28))
		{
			throw new IllegalArgumentException("Inventory slot must be between 0 and 27");
		}
		String cleanSpellName = requireText(spellName, "Spell name");
		CompletableFuture<Boolean> inventoryVisible = new CompletableFuture<>();
		clientThread.invoke(() -> inventoryVisible.complete(visibleInventory() != null));
		return inventoryVisible.thenCompose(visible -> visible
			? castSelectedSpellOnVisibleItem(
				itemId, requestedSlot, spellWidgetId, cleanSpellName, activityContext)
			: openInventoryThenCastSelectedSpell(
				itemId, requestedSlot, spellWidgetId, cleanSpellName, activityContext));
	}

	private CompletableFuture<Map<String, Object>> openInventoryThenCastSelectedSpell(
		int itemId,
		Integer requestedSlot,
		int spellWidgetId,
		String spellName,
		GenericClientActivityContext activityContext)
	{
		return waitForInventoryTab().thenCompose(tabVisible ->
		{
			if (!tabVisible)
			{
				return CompletableFuture.completedFuture(rejected("inventory_tab_not_visible"));
			}
			return menuInput.interactDirect(this::resolveInventoryTab, activityContext)
				.thenCompose(tabReceipt ->
				{
					if (!wasDispatched(tabReceipt))
					{
						return CompletableFuture.completedFuture(tabReceipt);
					}
					return waitForInventory().thenCompose(visible ->
					{
						if (!visible)
						{
							return CompletableFuture.completedFuture(composite(
								"open_inventory_then_spell_item",
								tabReceipt,
								rejected("inventory_did_not_open")));
						}
						return castSelectedSpellOnVisibleItem(
							itemId,
							requestedSlot,
							spellWidgetId,
							spellName,
							activityContext).thenApply(itemReceipt -> composite(
								"open_inventory_then_spell_item", tabReceipt, itemReceipt));
					});
				});
		});
	}

	private CompletableFuture<Map<String, Object>> castSelectedSpellOnVisibleItem(
		int itemId,
		Integer requestedSlot,
		int spellWidgetId,
		String spellName,
		GenericClientActivityContext activityContext)
	{
		return waitForItem(itemId, requestedSlot, null).thenCompose(ignored ->
			menuInput.interact(
				() -> resolveSelectedSpellOnItem(
					itemId, requestedSlot, spellWidgetId, spellName),
				activityContext));
	}

	private CompletableFuture<Map<String, Object>> openInventoryThenInteract(
		int itemId,
		Integer requestedSlot,
		String action,
		GenericClientActivityContext activityContext)
	{
		return waitForInventoryTab().thenCompose(tabVisible ->
		{
			if (!tabVisible)
			{
				return CompletableFuture.completedFuture(rejected("inventory_tab_not_visible"));
			}
			return menuInput.interactDirect(this::resolveInventoryTab, activityContext).thenCompose(tabReceipt ->
			{
				if (!wasDispatched(tabReceipt))
				{
					return retryInventoryThenInteract(
						itemId, requestedSlot, action, activityContext).thenApply(retry ->
							composite("open_inventory_then_item", tabReceipt, retry));
				}
				return interactVisible(itemId, requestedSlot, action, activityContext)
					.thenApply(itemReceipt -> composite(
						"open_inventory_then_item", tabReceipt, itemReceipt));
			});
		});
	}

	private CompletableFuture<Boolean> waitForInventoryTab()
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		pollInventoryTab(0, result);
		return result;
	}

	private void pollInventoryTab(int attempt, CompletableFuture<Boolean> result)
	{
		executor.schedule(() -> clientThread.invoke(() ->
		{
			if (visibleWidget(INVENTORY_TABS) != null)
			{
				result.complete(true);
			}
			else if (attempt + 1 >= INVENTORY_TAB_POLL_ATTEMPTS)
			{
				result.complete(false);
			}
			else
			{
				pollInventoryTab(attempt + 1, result);
			}
		}), INVENTORY_POLL_MILLIS, TimeUnit.MILLISECONDS);
	}

	private CompletableFuture<Map<String, Object>> retryInventoryThenInteract(
		int itemId,
		Integer requestedSlot,
		String action,
		GenericClientActivityContext activityContext)
	{
		return menuInput.interactDirect(this::resolveInventoryTab, activityContext).thenCompose(tabReceipt ->
		{
			if (!wasDispatched(tabReceipt))
			{
				return CompletableFuture.completedFuture(tabReceipt);
			}
			return waitForInventory().thenCompose(visible ->
			{
				if (!visible)
				{
					return CompletableFuture.completedFuture(composite(
						"retry_inventory_then_item",
						tabReceipt,
						rejected("inventory_did_not_open")));
				}
				return interactVisible(itemId, requestedSlot, action, activityContext)
					.thenApply(itemReceipt -> composite(
						"retry_inventory_then_item", tabReceipt, itemReceipt));
			});
		});
	}

	private CompletableFuture<Boolean> waitForInventory()
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		pollInventory(0, result);
		return result;
	}

	private void pollInventory(int attempt, CompletableFuture<Boolean> result)
	{
		executor.schedule(() -> clientThread.invoke(() ->
		{
			if (visibleInventory() != null)
			{
				result.complete(true);
			}
			else if (attempt + 1 >= INVENTORY_POLL_ATTEMPTS)
			{
				result.complete(false);
			}
			else
			{
				pollInventory(attempt + 1, result);
			}
		}), INVENTORY_POLL_MILLIS, TimeUnit.MILLISECONDS);
	}

	private CompletableFuture<Map<String, Object>> interactVisible(
		int itemId,
		Integer requestedSlot,
		String action,
		GenericClientActivityContext activityContext)
	{
		return waitForItem(itemId, requestedSlot, action).thenCompose(ignored ->
			menuInput.interact(
				() -> resolveItem(itemId, requestedSlot, action, null),
				activityContext));
	}

	private CompletableFuture<Boolean> waitForItem(
		int itemId,
		Integer requestedSlot,
		String action)
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		pollItem(itemId, requestedSlot, action, 0, result);
		return result;
	}

	CompletableFuture<Boolean> waitForSelectedItem(int itemId)
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		pollSelectedItem(itemId, 0, result);
		return result;
	}

	CompletableFuture<Map<String, Object>> clearSelectedItem(int itemId, Integer requestedSlot, GenericClientActivityContext activityContext)
	{
		CompletableFuture<Boolean> selected = new CompletableFuture<>();
		clientThread.invoke(() -> selected.complete(
			matchesSelectedItem(client.isWidgetSelected(), client.getSelectedWidget(), itemId)));
		return selected.thenCompose(active ->
		{
			if (!active)
			{
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "complete");
				receipt.put("result", "item_selection_already_clear");
				receipt.put("click_count", 0L);
				return CompletableFuture.completedFuture(receipt);
			}
			return interact(
				itemId,
				requestedSlot,
				"Use",
				activityContext).thenCompose(clicked ->
			{
				if (!"dispatched".equals(clicked.get("status")))
				{
					return CompletableFuture.completedFuture(clicked);
				}
				return waitForSelectionClear().thenApply(cleared ->
				{
					Map<String, Object> receipt = new LinkedHashMap<>();
					receipt.put("status", cleared ? "complete" : "rejected");
					receipt.put("result", cleared
						? "item_selection_cleared"
						: "item_selection_clear_timeout");
					receipt.put("menu_receipt", clicked);
					receipt.put("click_count", clickCount(clicked));
					return receipt;
				});
			});
		});
	}

	private CompletableFuture<Boolean> waitForSelectionClear()
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		pollSelectionClear(0, result);
		return result;
	}

	private void pollSelectionClear(int attempt, CompletableFuture<Boolean> result)
	{
		executor.schedule(() -> clientThread.invoke(() ->
		{
			if (!client.isWidgetSelected())
			{
				result.complete(true);
			}
			else if (attempt + 1 >= ITEM_POLL_ATTEMPTS)
			{
				result.complete(false);
			}
			else
			{
				pollSelectionClear(attempt + 1, result);
			}
		}), INVENTORY_POLL_MILLIS, TimeUnit.MILLISECONDS);
	}

	private void pollSelectedItem(
		int itemId,
		int attempt,
		CompletableFuture<Boolean> result)
	{
		executor.schedule(() -> clientThread.invoke(() ->
		{
			if (matchesSelectedItem(client.isWidgetSelected(), client.getSelectedWidget(), itemId))
			{
				result.complete(true);
			}
			else if (attempt + 1 >= ITEM_POLL_ATTEMPTS)
			{
				result.complete(false);
			}
			else
			{
				pollSelectedItem(itemId, attempt + 1, result);
			}
		}), INVENTORY_POLL_MILLIS, TimeUnit.MILLISECONDS);
	}

	static boolean matchesSelectedItem(boolean selected, Widget widget, int itemId)
	{
		return selected && widget != null && widget.getItemId() == itemId;
	}

	private void pollItem(
		int itemId,
		Integer requestedSlot,
		String action,
		int attempt,
		CompletableFuture<Boolean> result)
	{
		executor.schedule(() -> clientThread.invoke(() ->
		{
			if (findItem(itemId, requestedSlot, action) != null)
			{
				result.complete(true);
			}
			else if (attempt + 1 >= ITEM_POLL_ATTEMPTS)
			{
				result.complete(false);
			}
			else
			{
				pollItem(itemId, requestedSlot, action, attempt + 1, result);
			}
		}), INVENTORY_POLL_MILLIS, TimeUnit.MILLISECONDS);
	}

	private GenericClientMenuInput.Resolution resolveInventoryTab()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		if (visibleInventory() != null)
		{
			return GenericClientMenuInput.Resolution.rejected("inventory_already_visible");
		}
		Widget tab = visibleWidget(INVENTORY_TABS);
		if (tab == null)
		{
			return GenericClientMenuInput.Resolution.rejected("inventory_tab_not_visible");
		}
		Point point = GenericClientMenuInput.randomPointInside(
			tab.getBounds(), client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected("inventory_tab_not_clickable");
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "widget");
		value.put("widget_id", (long) tab.getId());
		value.put("widget_index", (long) tab.getIndex());
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			"Open",
			"inventory_tab",
			value,
			entry -> matchesWidget(entry, tab)));
	}

	private GenericClientMenuInput.Resolution resolveItem(int itemId, Integer requestedSlot, String action, String spellName)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		Widget inventory = visibleInventory();
		if (inventory == null)
		{
			return GenericClientMenuInput.Resolution.rejected("inventory_not_visible");
		}
		Widget item = findItem(itemId, requestedSlot, spellName == null ? action : null);
		if (item == null)
		{
			return GenericClientMenuInput.Resolution.rejected("matching_inventory_item_not_found");
		}
		Rectangle bounds = item.getBounds();
		Point point = GenericClientMenuInput.randomPointInside(
			bounds, client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected("matching_inventory_item_not_clickable");
		}
		int slot = item.getIndex();
		int widgetId = inventory.getId();
		ItemComposition composition = client.getItemDefinition(item.getItemId());
		String itemName = composition == null ? "<unknown>" : composition.getName();
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "inventory_item");
		value.put("id", (long) itemId);
		value.put("name", itemName);
		value.put("slot", (long) slot);
		value.put("quantity", (long) item.getItemQuantity());
		if (spellName != null) value.put("spell", spellName);
		String description = spellName == null ? "inventory_item:" : "spell_item:" + spellName + ":";
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			action,
			description + itemId + ":" + slot,
			value,
			entry -> matchesItem(entry, itemId, slot, widgetId, action), bounds));
	}

	private GenericClientMenuInput.Resolution resolveSelectedSpellOnItem(
		int itemId,
		Integer requestedSlot,
		int spellWidgetId,
		String spellName)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		Widget selected = client.getSelectedWidget();
		if (!client.isWidgetSelected() || selected == null || selected.getId() != spellWidgetId)
		{
			return GenericClientMenuInput.Resolution.rejected(
				"requested_spell_not_selected:" + spellName);
		}
		return resolveItem(itemId, requestedSlot, "Cast", spellName);
	}

	private Widget findItem(int itemId, Integer requestedSlot, String action)
	{
		Widget inventory = visibleInventory();
		if (inventory == null)
		{
			return null;
		}
		for (Widget item : itemChildren(inventory))
		{
			if (!isVisible(item) || item.getItemId() != itemId ||
				(requestedSlot != null && item.getIndex() != requestedSlot))
			{
				continue;
			}
			if (action == null || hasAction(client.getItemDefinition(item.getItemId()), action))
			{
				return item;
			}
		}
		return null;
	}

	private Widget visibleInventory()
	{
		return visibleWidget(InterfaceID.Inventory.ITEMS);
	}

	private Widget visibleWidget(int... ids)
	{
		for (int id : ids)
		{
			Widget widget = client.getWidget(id);
			if (!isVisible(widget))
			{
				continue;
			}
			Rectangle bounds = widget.getBounds();
			if (bounds != null && bounds.width > 0 && bounds.height > 0)
			{
				return widget;
			}
		}
		return null;
	}

	private static List<Widget> itemChildren(Widget inventory)
	{
		Widget[] children = inventory.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			children = inventory.getChildren();
		}
		if (children == null)
		{
			return Collections.emptyList();
		}
		List<Widget> result = new ArrayList<>(children.length);
		Collections.addAll(result, children);
		return result;
	}

	private static boolean hasAction(ItemComposition item, String action)
	{
		if ("Use".equalsIgnoreCase(action))
		{
			return true;
		}
		String[] actions = item == null ? null : item.getInventoryActions();
		if (actions == null)
		{
			return false;
		}
		for (String candidate : actions)
		{
			if (candidate != null && candidate.equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}

	static boolean matchesItem(
		MenuEntry entry,
		int itemId,
		int slot,
		int widgetId,
		String action)
	{
		return entry.getItemId() == itemId && entry.getParam0() == slot &&
			entry.getParam1() == widgetId && action.equalsIgnoreCase(entry.getOption());
	}

	private static String requireText(String value, String label)
	{
		if (value == null || value.trim().isEmpty())
		{
			throw new IllegalArgumentException(label + " cannot be empty");
		}
		return value.trim();
	}
}
