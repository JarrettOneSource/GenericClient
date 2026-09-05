package com.genericclient;

import static com.genericclient.GenericClientWidgets.visible;

import java.awt.Rectangle;
import java.util.List;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

final class GenericClientGrandExchangeWidgets
{
	private static final int SEARCH_RESULT_COLUMNS = 3;
	private static final int SEARCH_RESULT_ROW_HEIGHT = 32;

	private GenericClientGrandExchangeWidgets()
	{
	}

	static Widget findByAction(Widget root, String action, Integer itemId)
	{
		List<Widget> widgets = visible(root, 512);
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

	static Widget findByText(Widget root, String text)
	{
		for (Widget widget : visible(root, 512))
		{
			if (clean(widget.getText()).equalsIgnoreCase(text))
			{
				return widget;
			}
		}
		return null;
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

	static boolean hasAction(Widget widget, String action)
	{
		if (hasDeclaredAction(widget, action))
		{
			return true;
		}
		return matchesActionText(clean(widget.getName()), action) ||
			matchesActionText(clean(widget.getText()), action);
	}

	static boolean matchesActionText(String candidate, String action)
	{
		String normalizedCandidate = clean(candidate);
		return normalizedCandidate.equalsIgnoreCase(action) ||
			"Buy".equalsIgnoreCase(action) &&
				"Create Buy offer".equalsIgnoreCase(normalizedCandidate);
	}

	static boolean menuMatchesAction(MenuEntry entry, String action)
	{
		return matchesActionText(entry.getOption(), action) ||
			matchesActionText(clean(entry.getOption()) + " " + clean(entry.getTarget()), action);
	}

	static boolean matchesWidgetGroup(MenuEntry entry, Widget target)
	{
		Widget widget = entry.getWidget();
		return widget != null && widget.getId() == target.getId();
	}

	static boolean matchesItem(MenuEntry entry, int itemId)
	{
		Widget widget = entry.getWidget();
		return entry.getItemId() == itemId || widget != null && widget.getItemId() == itemId;
	}

	static boolean matchesOfferSlot(MenuEntry entry, int slot)
	{
		int widgetId = InterfaceID.GeOffers.INDEX_0 + slot;
		Widget widget = entry.getWidget();
		return widget == null ? entry.getParam1() == widgetId : widget.getId() == widgetId;
	}

	static Rectangle resolvedBounds(Widget widget)
	{
		Rectangle bounds = widget == null ? null : widget.getBounds();
		if (bounds == null || bounds.width < 1 || bounds.height < 1 ||
			bounds.x >= 0 && bounds.y >= 0)
		{
			return bounds;
		}
		int relativeX = widget.getRelativeX();
		int relativeY = widget.getRelativeY();
		for (Widget parent = widget.getParent(); parent != null; parent = parent.getParent())
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
		}
		return bounds;
	}

	static Rectangle searchResultHitbox(Widget searchRoot, int cell)
	{
		Rectangle bounds = resolvedBounds(searchRoot);
		if (bounds == null ||
			bounds.width <= 200 || bounds.height <= SEARCH_RESULT_ROW_HEIGHT)
		{
			return bounds;
		}
		int cellWidth = bounds.width / SEARCH_RESULT_COLUMNS;
		int column = cell % SEARCH_RESULT_COLUMNS;
		int row = cell / SEARCH_RESULT_COLUMNS;
		return new Rectangle(
			bounds.x + column * cellWidth,
			bounds.y + row * SEARCH_RESULT_ROW_HEIGHT,
			cellWidth,
			SEARCH_RESULT_ROW_HEIGHT);
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

	static Rectangle offerSlotHitbox(Rectangle indexBounds, int slot)
	{
		if (indexBounds == null || slot < 0 || slot >= 8)
		{
			return null;
		}
		int width = indexBounds.width / 4;
		int height = indexBounds.height / 2;
		return new Rectangle(
			indexBounds.x + slot % 4 * width,
			indexBounds.y + slot / 4 * height,
			width,
			height);
	}

	static Rectangle cancelledRefundHitbox(Rectangle detailsBounds)
	{
		if (detailsBounds == null)
		{
			return null;
		}
		return new Rectangle(
			detailsBounds.x + detailsBounds.width * 79 / 100,
			detailsBounds.y + detailsBounds.height * 84 / 100,
			Math.max(1, detailsBounds.width * 7 / 100),
			Math.max(1, detailsBounds.height * 11 / 100));
	}

	static String clean(String value)
	{
		return value == null ? "" : Text.removeTags(value).trim();
	}

	static boolean hasDeclaredAction(Widget widget, String action)
	{
		if (widget == null || widget.getActions() == null)
		{
			return false;
		}
		for (String candidate : widget.getActions())
		{
			if (matchesActionText(candidate, action))
			{
				return true;
			}
		}
		return false;
	}
}
