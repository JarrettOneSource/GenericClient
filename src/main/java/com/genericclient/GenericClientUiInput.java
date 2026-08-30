package com.genericclient;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.widgets.Widget;

final class GenericClientUiInput
{
	private final Client client;
	private final GenericClientMenuInput menuInput;
	private final GenericClientSyntheticKeyboard keyboard;
	private final GenericClientBehaviorController behavior;

	GenericClientUiInput(
		Client client,
		GenericClientMenuInput menuInput,
		GenericClientSyntheticKeyboard keyboard,
		GenericClientBehaviorController behavior)
	{
		this.client = client;
		this.menuInput = menuInput;
		this.keyboard = keyboard;
		this.behavior = behavior;
	}

	CompletableFuture<Map<String, Object>> click(
		int widgetId,
		Integer widgetIndex,
		GenericClientActivityContext activityContext)
	{
		if (widgetId < 0)
		{
			throw new IllegalArgumentException("Widget id cannot be negative");
		}
		return menuInput.interactDirect(() -> resolve(widgetId, widgetIndex), activityContext);
	}

	CompletableFuture<Map<String, Object>> closeTopLevel(GenericClientActivityContext activityContext)
	{
		return behavior.beforeAction(activityContext).thenCompose(before ->
			keyboard.pressEscape().thenCompose(keyboardReceipt ->
				behavior.afterAction(activityContext).thenApply(after ->
				{
					Map<String, Object> receipt = new LinkedHashMap<>();
					receipt.put("status", "dispatched");
					receipt.put("result", "escape_dispatched");
					receipt.put("keyboard", keyboardReceipt);
					receipt.put("behavior_before", before);
					receipt.put("behavior_after", after);
					receipt.put("click_count", 0L);
					return receipt;
				})));
	}

	private GenericClientMenuInput.Resolution resolve(int widgetId, Integer widgetIndex)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		Widget widget = client.getWidget(widgetId);
		if (widgetIndex != null && widget != null)
		{
			widget = indexedChild(widget.getDynamicChildren(), widgetIndex);
		}
		if (widget == null || widget.isHidden() || widget.isSelfHidden())
		{
			return GenericClientMenuInput.Resolution.rejected("widget_not_visible:" + widgetId);
		}
		Point point = GenericClientMenuInput.randomPointInside(
			widget.getBounds(), new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight()));
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected("widget_not_clickable:" + widgetId);
		}
		Map<String, Object> target = new LinkedHashMap<>();
		target.put("kind", "widget");
		target.put("widget_id", (long) widget.getId());
		target.put("widget_index", (long) widget.getIndex());
		Widget targetWidget = widget;
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			"Click",
			"widget:" + widgetId,
			target,
			entry -> matchesWidget(entry, targetWidget)));
	}

	private static Widget indexedChild(Widget[] children, int index)
	{
		if (children == null)
		{
			return null;
		}
		for (Widget child : children)
		{
			if (child != null && child.getIndex() == index)
			{
				return child;
			}
		}
		return null;
	}

	private static boolean matchesWidget(net.runelite.api.MenuEntry entry, Widget target)
	{
		Widget widget = entry.getWidget();
		if (widget != null)
		{
			return widget.getId() == target.getId() && widget.getIndex() == target.getIndex();
		}
		return entry.getParam1() == target.getId() &&
			(target.getIndex() < 0 || entry.getParam0() == target.getIndex());
	}
}
