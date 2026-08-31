package com.genericclient;

import static com.genericclient.GenericClientInteractionReceipts.composite;
import static com.genericclient.GenericClientInteractionReceipts.rejected;
import static com.genericclient.GenericClientInteractionReceipts.wasDispatched;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.LinkedHashMap;
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

final class GenericClientEquipmentInput
{
	private static final long POLL_MILLIS = 50L;
	private static final int POLL_ATTEMPTS = 40;
	private static final int[] EQUIPMENT_TABS =
	{
		InterfaceID.Toplevel.STONE4,
		InterfaceID.ToplevelOsrsStretch.STONE4,
		InterfaceID.ToplevelPreEoc.STONE4
	};
	private static final int[] EQUIPMENT_SLOTS =
	{
		InterfaceID.Wornitems.SLOT0,
		InterfaceID.Wornitems.SLOT1,
		InterfaceID.Wornitems.SLOT2,
		InterfaceID.Wornitems.SLOT3,
		InterfaceID.Wornitems.SLOT4,
		InterfaceID.Wornitems.SLOT5,
		InterfaceID.Wornitems.SLOT7,
		InterfaceID.Wornitems.SLOT9,
		InterfaceID.Wornitems.SLOT10,
		InterfaceID.Wornitems.SLOT12,
		InterfaceID.Wornitems.SLOT13
	};

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientMenuInput menuInput;

	GenericClientEquipmentInput(
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
		String action,
		GenericClientActivityContext activityContext)
	{
		if (itemId < 0)
		{
			throw new IllegalArgumentException("Equipment item id cannot be negative");
		}
		String cleanAction = requireText(action, "Equipment action");
		CompletableFuture<Boolean> visible = new CompletableFuture<>();
		clientThread.invoke(() -> visible.complete(visibleEquipment() != null));
		return visible.thenCompose(isVisible -> isVisible
			? interactVisible(itemId, cleanAction, activityContext)
			: openThenInteract(itemId, cleanAction, activityContext));
	}

	private CompletableFuture<Map<String, Object>> openThenInteract(
		int itemId,
		String action,
		GenericClientActivityContext activityContext)
	{
		return menuInput.interactDirect(this::resolveEquipmentTab, activityContext)
			.thenCompose(tabReceipt ->
			{
				if (!wasDispatched(tabReceipt))
				{
					return CompletableFuture.completedFuture(tabReceipt);
				}
				return waitForEquipment().thenCompose(visible ->
				{
					if (!visible)
					{
						return CompletableFuture.completedFuture(composite(
							"open_equipment_then_item",
							tabReceipt,
							rejected("equipment_did_not_open")));
					}
					return interactVisible(itemId, action, activityContext)
						.thenApply(itemReceipt -> composite(
							"open_equipment_then_item", tabReceipt, itemReceipt));
				});
			});
	}

	private CompletableFuture<Map<String, Object>> interactVisible(
		int itemId,
		String action,
		GenericClientActivityContext activityContext)
	{
		return waitForItem(itemId).thenCompose(found -> found
			? menuInput.interact(() -> resolveItem(itemId, action), activityContext)
			: CompletableFuture.completedFuture(rejected("matching_equipment_item_not_found")));
	}

	private CompletableFuture<Boolean> waitForEquipment()
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		poll(() -> visibleEquipment() != null, 0, result);
		return result;
	}

	private CompletableFuture<Boolean> waitForItem(int itemId)
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		poll(() -> findItem(itemId) != null, 0, result);
		return result;
	}

	private void poll(Check check, int attempt, CompletableFuture<Boolean> result)
	{
		executor.schedule(() -> clientThread.invoke(() ->
		{
			if (check.test())
			{
				result.complete(true);
			}
			else if (attempt + 1 >= POLL_ATTEMPTS)
			{
				result.complete(false);
			}
			else
			{
				poll(check, attempt + 1, result);
			}
		}), POLL_MILLIS, TimeUnit.MILLISECONDS);
	}

	private GenericClientMenuInput.Resolution resolveEquipmentTab()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		Widget tab = visibleWidget(EQUIPMENT_TABS);
		if (tab == null)
		{
			return GenericClientMenuInput.Resolution.rejected("equipment_tab_not_visible");
		}
		Point point = GenericClientMenuInput.randomPointInside(
			tab.getBounds(), client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected("equipment_tab_not_clickable");
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "widget");
		value.put("widget_id", (long) tab.getId());
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			"Open",
			"equipment_tab",
			value,
			entry -> matchesWidget(entry, tab)));
	}

	private GenericClientMenuInput.Resolution resolveItem(int itemId, String action)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		if (visibleEquipment() == null)
		{
			return GenericClientMenuInput.Resolution.rejected("equipment_not_visible");
		}
		Widget item = findItem(itemId);
		if (item == null)
		{
			return GenericClientMenuInput.Resolution.rejected("matching_equipment_item_not_found");
		}
		Point point = GenericClientMenuInput.randomPointInside(
			item.getBounds(), client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected("matching_equipment_item_not_clickable");
		}
		ItemComposition composition = client.getItemDefinition(itemId);
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "equipment_item");
		value.put("id", (long) itemId);
		value.put("name", composition == null ? "<unknown>" : composition.getName());
		value.put("widget_id", (long) item.getId());
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			action,
			"equipment_item:" + itemId,
			value,
			entry -> matchesItem(entry, itemId, item.getId(), action)));
	}

	private Widget findItem(int itemId)
	{
		for (int widgetId : EQUIPMENT_SLOTS)
		{
			Widget item = findItemWidget(visibleWidget(widgetId), itemId);
			if (item != null)
			{
				return item;
			}
		}
		return null;
	}

	static Widget findItemWidget(Widget slot, int itemId)
	{
		if (slot == null)
		{
			return null;
		}
		if (slot.getItemId() == itemId)
		{
			return slot;
		}
		Widget[] children = slot.getDynamicChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				if (child != null && child.getItemId() == itemId)
				{
					return child;
				}
			}
		}
		return null;
	}

	private Widget visibleEquipment()
	{
		return visibleWidget(InterfaceID.Wornitems.EQUIPMENT);
	}

	private Widget visibleWidget(int... ids)
	{
		for (int id : ids)
		{
			Widget widget = client.getWidget(id);
			if (!GenericClientInventoryInput.isVisible(widget))
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

	private static boolean matchesWidget(MenuEntry entry, Widget target)
	{
		Widget widget = entry.getWidget();
		return widget != null
			? widget.getId() == target.getId()
			: entry.getParam1() == target.getId();
	}

	static boolean matchesItem(
		MenuEntry entry,
		int itemId,
		int widgetId,
		String action)
	{
		Widget widget = entry.getWidget();
		boolean sameWidget = widget != null
			? widget.getId() == widgetId
			: entry.getParam1() == widgetId;
		return sameWidget && (entry.getItemId() == itemId || entry.getItemId() == -1) &&
			action.equalsIgnoreCase(entry.getOption());
	}

	private static String requireText(String value, String label)
	{
		if (value == null || value.trim().isEmpty())
		{
			throw new IllegalArgumentException(label + " cannot be empty");
		}
		return value.trim();
	}

	@FunctionalInterface
	private interface Check
	{
		boolean test();
	}
}
