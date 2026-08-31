package com.genericclient;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

final class GenericClientGrandExchangeView
{
	private static final int[] OFFER_WIDGETS =
	{
		InterfaceID.GeOffers.INDEX_0,
		InterfaceID.GeOffers.INDEX_1,
		InterfaceID.GeOffers.INDEX_2,
		InterfaceID.GeOffers.INDEX_3,
		InterfaceID.GeOffers.INDEX_4,
		InterfaceID.GeOffers.INDEX_5,
		InterfaceID.GeOffers.INDEX_6,
		InterfaceID.GeOffers.INDEX_7
	};

	private final Client client;
	private final Supplier<GenericClientSnapshot> snapshotSupplier;
	private final Consumer<String> reporter;

	GenericClientGrandExchangeView(
		Client client,
		Supplier<GenericClientSnapshot> snapshotSupplier,
		Consumer<String> reporter)
	{
		this.client = client;
		this.snapshotSupplier = snapshotSupplier;
		this.reporter = reporter;
	}

	Preflight preflight(
		int itemId,
		int quantity,
		int maximumUnitPrice,
		long minimumCashReserve)
	{
		if (!isOpen())
		{
			return Preflight.rejected("grand_exchange_not_open");
		}
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null || offers.length < OFFER_WIDGETS.length)
		{
			return Preflight.rejected("grand_exchange_offers_unavailable");
		}
		int empty = -1;
		for (int slot = 0; slot < OFFER_WIDGETS.length; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				if (empty < 0)
				{
					empty = slot;
				}
				continue;
			}
			if (offer.getItemId() != itemId)
			{
				continue;
			}
			if (offer.getState() == GrandExchangeOfferState.BUYING ||
				offer.getState() == GrandExchangeOfferState.BOUGHT)
			{
				return Preflight.existing(slot);
			}
			return Preflight.rejected("conflicting_existing_offer_for_item");
		}

		long maximumSpend = (long) quantity * maximumUnitPrice;
		String cashRejection = cashRejection(cashSnapshot(), maximumSpend, minimumCashReserve);
		if (cashRejection != null)
		{
			return Preflight.rejected(cashRejection);
		}
		return empty < 0
			? Preflight.rejected("no_empty_grand_exchange_slot")
			: Preflight.empty(empty);
	}

	int offerWidgetId(int slot)
	{
		return OFFER_WIDGETS[slot];
	}

	boolean isVisible(int widgetId)
	{
		return visibleWidget(widgetId) != null;
	}

	boolean itemSearchVisible()
	{
		return isVisible(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
	}

	boolean searchResultVisible(int itemId)
	{
		return findItemSearchResult(itemId) != null;
	}

	boolean setupContainsItem(int itemId)
	{
		for (Widget widget : descendants(visibleWidget(InterfaceID.GeOffers.SETUP)))
		{
			if (widget.getItemId() == itemId)
			{
				return true;
			}
		}
		return false;
	}

	int visibleSetupUnitPrice()
	{
		for (Widget widget : descendants(visibleWidget(InterfaceID.GeOffers.SETUP)))
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

	boolean offerMatches(int slot, int itemId, int quantity, int maximumUnitPrice)
	{
		GrandExchangeOffer offer = offerAt(slot);
		return offer != null && offer.getItemId() == itemId &&
			offer.getTotalQuantity() == quantity && offer.getPrice() <= maximumUnitPrice &&
			(offer.getState() == GrandExchangeOfferState.BUYING ||
				offer.getState() == GrandExchangeOfferState.BOUGHT);
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
		Widget root = visibleWidget(InterfaceID.GeOffers.DETAILS_COLLECT);
		if (root == null)
		{
			return false;
		}
		for (Widget widget : descendants(root))
		{
			if (widget.getItemId() == itemId)
			{
				return true;
			}
		}
		return false;
	}

	boolean inputModeIs(int expected)
	{
		return client.getVarcIntValue(VarClientID.MESLAYERMODE) == expected;
	}

	boolean priceWarningVisible()
	{
		return priceWarningTarget() != null;
	}

	GenericClientMenuInput.Resolution resolvePriceWarning()
	{
		return targetForWidget(priceWarningTarget(), "Yes", null, "confirm_price_warning");
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
			reportWidgetTree("collect_offer", root);
			return GenericClientMenuInput.Resolution.rejected(
				"collect_offer_has_no_inventory_action");
		}
		return targetForWidget(target.widget, target.action, itemId, "collect_offer");
	}

	GenericClientMenuInput.Resolution resolveSearchResult(int itemId)
	{
		Widget widget = findItemSearchResult(itemId);
		Widget item = findItemSearchResultItem(itemId);
		if (!isOpen())
		{
			return GenericClientMenuInput.Resolution.rejected("grand_exchange_not_open");
		}
		if (widget == null)
		{
			return GenericClientMenuInput.Resolution.rejected("ge_search_result_not_visible");
		}
		Point point = GenericClientMenuInput.randomPointInside(
			searchResultHitbox(widget, item), client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected("ge_search_result_not_clickable");
		}
		String itemName = clean(widget.getName());
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "ge_search_result");
		value.put("item_id", (long) itemId);
		value.put("item_name", itemName);
		value.put("widget_id", (long) widget.getId());
		value.put("widget_index", (long) widget.getIndex());
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			"Select",
			"ge_search_result:" + itemId,
			value,
			entry -> menuMatchesAction(entry, "Select") &&
				itemName.equalsIgnoreCase(clean(entry.getTarget()))));
	}

	GenericClientMenuInput.Resolution resolveWidgetAction(
		int rootId,
		String action,
		Integer itemId,
		String description)
	{
		Widget root = visibleWidget(rootId);
		Widget target = findByAction(root, action, itemId);
		if (target == null)
		{
			reportWidgetTree(description, root);
		}
		if (target == null && root != null && itemId == null)
		{
			target = root;
		}
		return targetForWidget(target, action, itemId, description);
	}

	GenericClientMenuInput.Resolution resolveBuyOffer(int slot)
	{
		Widget slotWidget = visibleWidget(OFFER_WIDGETS[slot]);
		if (!isOpen())
		{
			return GenericClientMenuInput.Resolution.rejected("grand_exchange_not_open");
		}
		if (slotWidget == null)
		{
			return GenericClientMenuInput.Resolution.rejected("open_buy_offer_not_visible");
		}
		Rectangle hitbox = buyOfferHitbox(resolvedWidgetBounds(slotWidget));
		Point point = GenericClientMenuInput.randomPointInside(
			hitbox, client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected("open_buy_offer_not_clickable");
		}

		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "ge_offer_slot");
		value.put("slot", (long) slot);
		value.put("widget_id", (long) slotWidget.getId());
		value.put("widget_index", (long) slotWidget.getIndex());
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			"Buy",
			"open_buy_offer",
			value,
			entry -> menuMatchesAction(entry, "Buy")));
	}

	static boolean shouldReplaceZeroFill(
		GrandExchangeOffer offer,
		int quantity,
		int maximumUnitPrice)
	{
		return offer != null && offer.getState() == GrandExchangeOfferState.BUYING &&
			offer.getQuantitySold() == 0 &&
			(offer.getTotalQuantity() != quantity || offer.getPrice() != maximumUnitPrice);
	}

	static boolean matchesRequestedOffer(
		GrandExchangeOffer offer,
		int quantity,
		int maximumUnitPrice)
	{
		return offer != null && offer.getTotalQuantity() == quantity &&
			offer.getPrice() <= maximumUnitPrice &&
			(offer.getState() == GrandExchangeOfferState.BUYING ||
				offer.getState() == GrandExchangeOfferState.BOUGHT);
	}

	static String cashRejection(Map<?, ?> cash, long maximumSpend, long minimumCashReserve)
	{
		if (cash == null || !Boolean.TRUE.equals(cash.get("complete")))
		{
			return "complete_cash_snapshot_required";
		}
		long knownCash = longValue(cash.get("known_total_value"));
		long inventoryCoins = longValue(cash.get("inventory_coins"));
		if (knownCash - maximumSpend < minimumCashReserve)
		{
			return "cash_reserve_would_be_breached";
		}
		return inventoryCoins < maximumSpend
			? "insufficient_inventory_coins_for_offer"
			: null;
	}

	static Rectangle priceWarningScope(Rectangle setupBounds, Rectangle indexBounds)
	{
		return setupBounds == null ? indexBounds : setupBounds;
	}

	static Widget findByTextWithin(Widget root, String text, Rectangle scope)
	{
		for (Widget candidate : descendants(root))
		{
			if (inside(candidate, scope) && matchesWidgetText(candidate, text))
			{
				return candidate;
			}
		}
		return null;
	}

	static boolean matchesWidgetText(Widget widget, String expected)
	{
		return widget != null && (expected.equalsIgnoreCase(clean(widget.getText())) ||
			expected.equalsIgnoreCase(clean(widget.getName())));
	}

	static String collectAction(Widget widget, String collectMode)
	{
		if ("bank".equals(collectMode))
		{
			return hasAction(widget, "Bank") ? "Bank" : null;
		}
		if ("notes".equals(collectMode))
		{
			return hasAction(widget, "Collect-notes") ? "Collect-notes" : null;
		}
		if (hasAction(widget, "Collect-items"))
		{
			return "Collect-items";
		}
		if (hasAction(widget, "Collect-item"))
		{
			return "Collect-item";
		}
		return hasAction(widget, "Collect") ? "Collect" : null;
	}

	static Rectangle buyOfferHitbox(Rectangle slotBounds)
	{
		if (slotBounds == null || slotBounds.width < 1 || slotBounds.height < 1)
		{
			return slotBounds;
		}
		return new Rectangle(
			slotBounds.x + Math.max(1, slotBounds.width * 18 / 100),
			slotBounds.y + Math.max(1, slotBounds.height * 56 / 100),
			Math.max(1, slotBounds.width * 18 / 100),
			Math.max(1, slotBounds.height * 20 / 100));
	}

	static boolean matchesActionText(String candidate, String action)
	{
		String normalizedCandidate = clean(candidate);
		return normalizedCandidate.equalsIgnoreCase(action) ||
			"Buy".equalsIgnoreCase(action) &&
			"Create Buy offer".equalsIgnoreCase(normalizedCandidate);
	}

	static Rectangle resolvedWidgetBounds(Widget widget)
	{
		Rectangle bounds = widget == null ? null : widget.getBounds();
		if (bounds == null || bounds.width < 1 || bounds.height < 1 ||
			bounds.x >= 0 && bounds.y >= 0)
		{
			return bounds;
		}
		int relativeX = widget.getRelativeX();
		int relativeY = widget.getRelativeY();
		Widget parent = widget.getParent();
		while (parent != null)
		{
			Rectangle parentBounds = parent.getBounds();
			if (parentBounds != null && parentBounds.x >= 0 && parentBounds.y >= 0)
			{
				return new Rectangle(
					parentBounds.x + relativeX,
					parentBounds.y + relativeY,
					bounds.width,
					bounds.height);
			}
			relativeX += parent.getRelativeX();
			relativeY += parent.getRelativeY();
			parent = parent.getParent();
		}
		return bounds;
	}

	static Rectangle searchResultHitbox(Widget actionWidget, Widget itemWidget)
	{
		Rectangle itemBounds = resolvedWidgetBounds(itemWidget);
		return itemBounds != null && itemBounds.width > 0 && itemBounds.height > 0
			? itemBounds
			: resolvedWidgetBounds(actionWidget);
	}

	private Map<?, ?> cashSnapshot()
	{
		GenericClientSnapshot snapshot = snapshotSupplier.get();
		Object value = snapshot == null ? null : snapshot.read("cash", Collections.emptyMap());
		return value instanceof Map ? (Map<?, ?>) value : null;
	}

	private boolean isOpen()
	{
		Widget root = client.getWidget(InterfaceID.GeOffers.UNIVERSE);
		return client.getGameState() == GameState.LOGGED_IN && root != null &&
			!root.isHidden() && !root.isSelfHidden();
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

	private Widget priceWarningTarget()
	{
		Widget setup = visibleWidget(InterfaceID.GeOffers.SETUP);
		Widget index = visibleWidget(InterfaceID.GeOffers.INDEX);
		Rectangle scope = priceWarningScope(
			resolvedWidgetBounds(setup), resolvedWidgetBounds(index));
		if (scope == null)
		{
			return null;
		}
		Widget popup = visibleWidget(InterfaceID.Popupoverlay.UNIVERSE);
		Widget target = findByTextWithin(popup, "Yes", scope);
		if (target == null)
		{
			target = findByAction(popup, "Yes", null);
		}
		if (target != null && inside(target, scope))
		{
			return target;
		}
		target = findByTextAcrossVisibleRoots("Yes", scope);
		return target == null
			? findByActionAcrossVisibleRoots("Yes", scope)
			: target;
	}

	private Widget findByTextAcrossVisibleRoots(String text, Rectangle scope)
	{
		Widget[] roots = client.getWidgetRoots();
		if (roots == null)
		{
			return null;
		}
		for (Widget root : roots)
		{
			Widget candidate = findByTextWithin(root, text, scope);
			if (candidate != null)
			{
				return candidate;
			}
		}
		return null;
	}

	private Widget findByActionAcrossVisibleRoots(String action, Rectangle scope)
	{
		Widget[] roots = client.getWidgetRoots();
		if (roots == null)
		{
			return null;
		}
		List<Widget> candidates = new ArrayList<>();
		for (Widget root : roots)
		{
			candidates.addAll(descendants(root));
		}
		for (Widget candidate : candidates)
		{
			if (inside(candidate, scope) && hasDeclaredAction(candidate, action))
			{
				return candidate;
			}
		}
		return null;
	}

	private static boolean inside(Widget widget, Rectangle scope)
	{
		Rectangle bounds = widget.getBounds();
		return bounds != null && scope != null && scope.contains(
			bounds.x + bounds.width / 2,
			bounds.y + bounds.height / 2);
	}

	private static Widget findItemWidget(Widget root, int itemId)
	{
		for (Widget widget : descendants(root))
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
		for (Widget candidate : descendants(root))
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

	private Widget findItemSearchResult(int itemId)
	{
		Widget root = visibleWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		List<Widget> widgets = descendants(root);
		boolean exactItemPresent = false;
		for (Widget widget : widgets)
		{
			if (widget.getItemId() == itemId)
			{
				exactItemPresent = true;
				break;
			}
		}
		if (!exactItemPresent)
		{
			return null;
		}
		net.runelite.api.ItemComposition composition = client.getItemDefinition(itemId);
		String expectedName = composition == null ? "" : clean(composition.getName());
		for (Widget widget : widgets)
		{
			if (hasAction(widget, "Select") && expectedName.equalsIgnoreCase(clean(widget.getName())))
			{
				return widget;
			}
		}
		return null;
	}

	private Widget findItemSearchResultItem(int itemId)
	{
		Widget root = visibleWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		for (Widget widget : descendants(root))
		{
			if (widget.getItemId() == itemId)
			{
				return widget;
			}
		}
		return null;
	}

	private GenericClientMenuInput.Resolution targetForWidget(
		Widget widget,
		String action,
		Integer itemId,
		String description)
	{
		if (!isOpen())
		{
			return GenericClientMenuInput.Resolution.rejected("grand_exchange_not_open");
		}
		if (widget == null)
		{
			return GenericClientMenuInput.Resolution.rejected(description + "_not_visible");
		}
		Point point = GenericClientMenuInput.randomPointInside(
			resolvedWidgetBounds(widget), client.getCanvasWidth(), client.getCanvasHeight());
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

	private static Widget findByAction(Widget root, String action, Integer itemId)
	{
		List<Widget> widgets = descendants(root);
		for (Widget widget : widgets)
		{
			if ((itemId == null || widget.getItemId() == itemId) &&
				hasDeclaredAction(widget, action))
			{
				return widget;
			}
		}
		for (Widget widget : widgets)
		{
			if ((itemId == null || widget.getItemId() == itemId) && hasAction(widget, action))
			{
				return widget;
			}
		}
		return null;
	}

	private static List<Widget> descendants(Widget root)
	{
		if (root == null)
		{
			return Collections.emptyList();
		}
		List<Widget> result = new ArrayList<>();
		Set<Widget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Widget> queue = new ArrayDeque<>();
		queue.add(root);
		while (!queue.isEmpty() && result.size() < 512)
		{
			Widget widget = queue.removeFirst();
			if (widget == null || !seen.add(widget))
			{
				continue;
			}
			if (!widget.isHidden() && !widget.isSelfHidden() && widget.getBounds() != null &&
				widget.getBounds().width > 0 && widget.getBounds().height > 0)
			{
				result.add(widget);
			}
			enqueueChildren(queue, widget.getChildren());
			enqueueChildren(queue, widget.getDynamicChildren());
			enqueueChildren(queue, widget.getStaticChildren());
			enqueueChildren(queue, widget.getNestedChildren());
		}
		return result;
	}

	private static void enqueueChildren(ArrayDeque<Widget> queue, Widget[] children)
	{
		if (children == null)
		{
			return;
		}
		for (Widget child : children)
		{
			if (child != null)
			{
				queue.addLast(child);
			}
		}
	}

	private static boolean hasAction(Widget widget, String action)
	{
		if (hasDeclaredAction(widget, action))
		{
			return true;
		}
		String name = clean(widget.getName());
		String text = clean(widget.getText());
		return matchesActionText(name, action) || matchesActionText(text, action);
	}

	private static boolean hasDeclaredAction(Widget widget, String action)
	{
		String[] actions = widget.getActions();
		if (actions != null)
		{
			for (String candidate : actions)
			{
				if (matchesActionText(candidate, action))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static boolean menuMatchesAction(MenuEntry entry, String action)
	{
		return matchesActionText(entry.getOption(), action) ||
			matchesActionText(clean(entry.getOption()) + " " + clean(entry.getTarget()), action);
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

	private static boolean matchesWidgetGroup(MenuEntry entry, Widget target)
	{
		Widget widget = entry.getWidget();
		return widget != null && widget.getId() == target.getId();
	}

	private static boolean matchesItem(MenuEntry entry, int itemId)
	{
		Widget widget = entry.getWidget();
		return entry.getItemId() == itemId || widget != null && widget.getItemId() == itemId;
	}

	private static String clean(String value)
	{
		return value == null ? "" : Text.removeTags(value).trim();
	}

	private static long longValue(Object value)
	{
		return value instanceof Number ? ((Number) value).longValue() : 0L;
	}

	private void reportWidgetTree(String description, Widget root)
	{
		List<Widget> widgets = descendants(root);
		reporter.accept("GE_WIDGET_TREE description=" + description + " count=" + widgets.size());
		for (int index = 0; index < Math.min(50, widgets.size()); index++)
		{
			Widget widget = widgets.get(index);
			reporter.accept("GE_WIDGET description=" + description +
				" id=" + widget.getId() +
				" index=" + widget.getIndex() +
				" item=" + widget.getItemId() +
				" text=" + clean(widget.getText()) +
				" name=" + clean(widget.getName()) +
				" actions=" + Arrays.toString(widget.getActions()) +
				" bounds=" + widget.getBounds());
		}
	}

	static final class Preflight
	{
		private final int emptySlot;
		private final int existingSlot;
		private final String rejection;

		private Preflight(int emptySlot, int existingSlot, String rejection)
		{
			this.emptySlot = emptySlot;
			this.existingSlot = existingSlot;
			this.rejection = rejection;
		}

		int emptySlot()
		{
			return emptySlot;
		}

		int existingSlot()
		{
			return existingSlot;
		}

		String rejection()
		{
			return rejection;
		}

		private static Preflight empty(int slot)
		{
			return new Preflight(slot, -1, null);
		}

		private static Preflight existing(int slot)
		{
			return new Preflight(-1, slot, null);
		}

		private static Preflight rejected(String reason)
		{
			return new Preflight(-1, -1, reason);
		}
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
}
