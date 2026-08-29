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

	CompletableFuture<Map<String, Object>> click(int widgetId, boolean breaksEnabled)
	{
		if (widgetId < 0)
		{
			throw new IllegalArgumentException("Widget id cannot be negative");
		}
		return menuInput.interactDirect(() -> resolve(widgetId), breaksEnabled);
	}

	CompletableFuture<Map<String, Object>> closeTopLevel(boolean breaksEnabled)
	{
		return behavior.beforeAction(breaksEnabled).thenCompose(before ->
			keyboard.pressEscape().thenCompose(keyboardReceipt ->
				behavior.afterAction(breaksEnabled).thenApply(after ->
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

	private GenericClientMenuInput.Resolution resolve(int widgetId)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		Widget widget = client.getWidget(widgetId);
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
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			"Click",
			"widget:" + widgetId,
			target,
			entry -> true));
	}
}
