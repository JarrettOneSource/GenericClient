package com.genericclient;

import static com.genericclient.GenericClientGrandExchangeWidgets.clean;
import static com.genericclient.GenericClientGrandExchangeWidgets.collectAction;
import static com.genericclient.GenericClientGrandExchangeWidgets.matchesItem;
import static com.genericclient.GenericClientWidgets.matchesWidget;
import static com.genericclient.GenericClientGrandExchangeWidgets.matchesWidgetGroup;
import static com.genericclient.GenericClientGrandExchangeWidgets.menuMatchesAction;
import static com.genericclient.GenericClientGrandExchangeWidgets.resolvedBounds;
import static com.genericclient.GenericClientWidgets.isVisible;
import static com.genericclient.GenericClientWidgets.visible;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;

final class GenericClientGrandExchangeView
{
	private final Client client;
	private final GenericClientGrandExchangePolicy policy;

	GenericClientGrandExchangeView(
		Client client,
		Supplier<GenericClientSnapshot> snapshotSupplier)
	{
		this.client = client;
		this.policy = new GenericClientGrandExchangePolicy(client, snapshotSupplier);
	}

	GenericClientGrandExchangePolicy.Preflight preflight(int itemId, int quantity, int maximumUnitPrice, long minimumCashReserve)
	{
		if (!isOpen()) return GenericClientGrandExchangePolicy.Preflight.rejected("grand_exchange_not_open");
		return policy.preflight(itemId, quantity, maximumUnitPrice, minimumCashReserve);
	}

	boolean itemSearchVisible()
	{
		return visibleWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS) != null;
	}

	boolean setupContainsItem(int itemId)
	{
		return findItemWidget(visibleWidget(InterfaceID.GeOffers.SETUP), itemId) != null;
	}

	int visibleSetupUnitPrice()
	{
		for (Widget widget : visible(visibleWidget(InterfaceID.GeOffers.SETUP), 512))
		{
			String text = clean(widget.getText());
			if (!text.endsWith(" coins"))
			{
				continue;
			}
			String number = text.substring(0, text.length() - " coins".length()).replace(",", "");
			try
			{
				return Integer.parseInt(number);
			}
			catch (NumberFormatException ignored)
			{
				// Continue to the next visible value.
			}
		}
		return -1;
	}

	GrandExchangeOffer offerAt(int slot)
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		return offers == null || slot < 0 || slot >= offers.length ? null : offers[slot];
	}

	boolean offerEmpty(int slot)
	{
		GrandExchangeOffer offer = offerAt(slot);
		return offer == null || offer.getState() == GrandExchangeOfferState.EMPTY;
	}

	int inventoryQuantity(int itemId)
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		return inventory == null ? 0 : inventory.count(itemId);
	}

	boolean collectItemPresent(int itemId)
	{
		return findItemWidget(visibleWidget(InterfaceID.GeOffers.DETAILS_COLLECT), itemId) != null;
	}

	boolean inputModeIs(int expected)
	{
		return client.getVarcIntValue(VarClientID.MESLAYERMODE) == expected;
	}

	GenericClientMenuInput.Resolution resolveCollectItem(int itemId, String collectMode)
	{
		Widget root = visibleWidget(InterfaceID.GeOffers.DETAILS_COLLECT);
		Widget item = findItemWidget(root, itemId);
		if (item == null)
		{
			return GenericClientMenuInput.Resolution.rejected("collect_offer_not_visible");
		}
		CollectTarget target = findCollectTarget(root, item, collectMode);
		if (target.action == null)
		{
			return GenericClientMenuInput.Resolution.rejected(
				"collect_offer_has_no_inventory_action");
		}
		return targetForWidget(target.widget, target.action, itemId, "collect_offer");
	}

	boolean isOpen()
	{
		Widget root = client.getWidget(InterfaceID.GeOffers.UNIVERSE);
		return client.getGameState() == GameState.LOGGED_IN && isVisible(root);
	}

	Widget visibleWidget(int id)
	{
		Widget widget = client.getWidget(id);
		if (!isVisible(widget))
		{
			return null;
		}
		Rectangle bounds = widget.getBounds();
		return bounds != null && bounds.width > 0 && bounds.height > 0 ? widget : null;
	}

	private static Widget findItemWidget(Widget root, int itemId)
	{
		for (Widget widget : visible(root, 512))
		{
			if (widget.getItemId() == itemId)
			{
				return widget;
			}
		}
		return null;
	}

	private static CollectTarget findCollectTarget(Widget root, Widget item, String collectMode)
	{
		Widget target = item;
		String action = collectAction(target, collectMode);
		for (Widget parent = item.getParent(); action == null && parent != null;
			parent = parent.getParent())
		{
			target = parent;
			action = collectAction(target, collectMode);
			if (parent == root)
			{
				break;
			}
		}
		return action == null
			? overlappingCollectTarget(root, item, collectMode)
			: new CollectTarget(target, action);
	}

	private static CollectTarget overlappingCollectTarget(
		Widget root,
		Widget item,
		String collectMode)
	{
		Rectangle itemBounds = item.getBounds();
		for (Widget candidate : visible(root, 512))
		{
			String action = collectAction(candidate, collectMode);
			Rectangle bounds = candidate.getBounds();
			if (action != null && bounds != null && itemBounds != null && bounds.contains(
				itemBounds.x + itemBounds.width / 2,
				itemBounds.y + itemBounds.height / 2))
			{
				return new CollectTarget(candidate, action);
			}
		}
		return new CollectTarget(item, null);
	}

	Widget findItemSearchResult(int itemId)
	{
		List<Widget> widgets = visible(visibleWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS), 512);
		if (widgets.stream().noneMatch(widget -> widget.getItemId() == itemId)) return null;
		ItemComposition composition = client.getItemDefinition(itemId);
		String expectedName = composition == null ? "" : clean(composition.getName());
		for (Widget widget : widgets)
		{
			if (GenericClientGrandExchangeWidgets.hasAction(widget, "Select") &&
				expectedName.equalsIgnoreCase(clean(widget.getName()))) return widget;
		}
		return null;
	}

	GenericClientMenuInput.Resolution targetForWidget(
		Widget widget,
		String action,
		Integer itemId,
		String description)
	{
		if (!isOpen())
		{
			return GenericClientMenuInput.Resolution.rejected("grand_exchange_not_open");
		}
		if (!isVisible(widget))
		{
			return GenericClientMenuInput.Resolution.rejected(description + "_not_visible");
		}
		Point point = GenericClientMenuInput.randomPointInside(
			resolvedBounds(widget), client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected(description + "_not_clickable");
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "ge_widget");
		value.put("widget_id", (long) widget.getId());
		value.put("widget_index", (long) widget.getIndex());
		if (itemId != null)
		{
			value.put("item_id", itemId.longValue());
		}
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			action,
			description,
			value,
			entry -> menuMatchesAction(entry, action) &&
				(itemId == null
					? matchesWidget(entry, widget)
					: matchesWidget(entry, widget) ||
						matchesItem(entry, itemId) && matchesWidgetGroup(entry, widget))));
	}

	private static final class CollectTarget
	{
		private final Widget widget;
		private final String action;

		private CollectTarget(Widget widget, String action)
		{
			this.widget = widget;
			this.action = action;
		}
	}

	GenericClientMenuInput.Resolution directTargetForWidget(Widget widget, String description)
	{
		if (!isOpen())
		{
			return GenericClientMenuInput.Resolution.rejected("grand_exchange_not_open");
		}
		if (!isVisible(widget))
		{
			return GenericClientMenuInput.Resolution.rejected(description + "_not_visible");
		}
		Point point = GenericClientMenuInput.randomPointInside(
			resolvedBounds(widget),
			client.getCanvasWidth(),
			client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected(description + "_not_clickable");
		}
		LinkedHashMap<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "ge_widget");
		value.put("widget_id", (long) widget.getId());
		value.put("widget_index", (long) widget.getIndex());
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			"Click",
			description,
			value,
			entry -> matchesWidget(entry, widget)));
	}

	GenericClientMenuInput.Resolution directTargetForHitbox(
		Rectangle hitbox,
		String description)
	{
		if (!isOpen())
		{
			return GenericClientMenuInput.Resolution.rejected("grand_exchange_not_open");
		}
		Point point = GenericClientMenuInput.randomPointInside(
			hitbox,
			client.getCanvasWidth(),
			client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected(description + "_not_visible");
		}
		LinkedHashMap<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "ge_control");
		value.put("control", description);
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			"Click",
			description,
			value,
			entry -> false));
	}

	GenericClientMenuInput.Resolution targetForOfferSlot(
		Rectangle hitbox,
		int slot,
		String action,
		String description)
	{
		Point point = GenericClientMenuInput.randomPointInside(
			hitbox, client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected(description + "_not_clickable");
		}
		LinkedHashMap<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "ge_offer_slot");
		value.put("slot", (long) slot);
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			action,
			description,
			value,
			entry -> menuMatchesAction(entry, action) &&
				GenericClientGrandExchangeWidgets.matchesOfferSlot(entry, slot)));
	}

	GenericClientMenuInput.Resolution targetForSearchResult(
		Widget widget,
		int itemId,
		int cell)
	{
		if (!isOpen())
		{
			return GenericClientMenuInput.Resolution.rejected("grand_exchange_not_open");
		}
		if (!isVisible(widget))
		{
			return GenericClientMenuInput.Resolution.rejected("ge_search_result_not_visible");
		}
		Point point = GenericClientMenuInput.randomPointInside(
			GenericClientGrandExchangeWidgets.searchResultHitbox(
				visibleWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS), cell),
			client.getCanvasWidth(),
			client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected("ge_search_result_not_clickable");
		}
		String itemName = clean(widget.getName());
		LinkedHashMap<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "ge_search_result");
		value.put("item_id", (long) itemId);
		value.put("item_name", itemName);
		value.put("cell", (long) cell);
		value.put("widget_id", (long) widget.getId());
		value.put("widget_index", (long) widget.getIndex());
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point, "Select", "ge_search_result:" + itemId, value,
			entry -> menuMatchesAction(entry, "Select") && itemName.equalsIgnoreCase(clean(entry.getTarget()))));
	}
}
