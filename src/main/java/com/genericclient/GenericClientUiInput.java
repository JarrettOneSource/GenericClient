package com.genericclient;

import static com.genericclient.GenericClientWidgets.isVisible;
import static com.genericclient.GenericClientWidgets.matchesWidget;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.Text;

final class GenericClientUiInput
{
	private final Client client;
	private final ClientThread clientThread;
	private final GenericClientMenuInput menuInput;
	private final GenericClientSyntheticKeyboard keyboard;

	GenericClientUiInput(
		Client client,
		ClientThread clientThread,
		GenericClientMenuInput menuInput,
		GenericClientSyntheticKeyboard keyboard)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.menuInput = menuInput;
		this.keyboard = keyboard;
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

	CompletableFuture<Map<String, Object>> closeTopLevel(GenericClientActivityContext context)
	{
		return keyboard.pressEscape(context).thenApply(keyboardReceipt ->
		{
			Map<String, Object> receipt = new LinkedHashMap<>();
			receipt.put("status", "dispatched");
			receipt.put("result", "escape_dispatched");
			receipt.put("keyboard", keyboardReceipt);
			receipt.put("click_count", 0L);
			return receipt;
		});
	}

	CompletableFuture<Map<String, Object>> selectDestination(int menuId, String label, GenericClientActivityContext context)
	{
		return menuInput.interactDirect(() -> resolveDestination(menuId, label), context);
	}

	CompletableFuture<Map<String, Object>> key(
		String key, GenericClientActivityContext context)
	{
		String text = "SPACE".equalsIgnoreCase(key) ? " " : key;
		if (text == null || text.length() != 1 || text.charAt(0) < 32 || text.charAt(0) > 126)
		{
			throw new IllegalArgumentException(
				"ui.key requires one printable ASCII character or SPACE");
		}
		if (isDigit(text))
		{
			return numberedMenuVisible().thenCompose(visible -> visible
				? dispatchKey(text, context)
				: CompletableFuture.completedFuture(rejectedKey(text)));
		}
		return dispatchKey(text, context);
	}

	private CompletableFuture<Map<String, Object>> dispatchKey(String text, GenericClientActivityContext context)
	{
		return sendKey(text, context).thenApply(keyboardReceipt ->
		{
			Map<String, Object> receipt = new LinkedHashMap<>();
			receipt.put("status", "dispatched");
			receipt.put("result", "key_dispatched");
			receipt.put("key", " ".equals(text) ? "SPACE" : text);
			receipt.put("keyboard", keyboardReceipt);
			receipt.put("click_count", 0L);
			return receipt;
		});
	}

	private CompletableFuture<String> sendKey(String text, GenericClientActivityContext context)
	{
		if (" ".equals(text))
		{
			return keyboard.pressSpace(0L, context);
		}
		return keyboard.type(text, false, 0L, context);
	}

	private CompletableFuture<Boolean> numberedMenuVisible()
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		clientThread.invoke(() -> result.complete(
			isVisible(client.getWidget(InterfaceID.Menu.LJ_LAYER2)) ||
			isVisible(client.getWidget(InterfaceID.Menu.LJ_LAYER1)) ||
			isVisible(client.getWidget(InterfaceID.Menu.KEYLISTENERS)) ||
			isVisible(client.getWidget(InterfaceID.MenuNew.UNIVERSE)) ||
			isVisible(client.getWidget(InterfaceID.MenuNew.CONTENT)) ||
			isVisible(client.getWidget(InterfaceID.MenuNew.KEYLISTENERS))));
		return result;
	}

	private static boolean isDigit(String text)
	{
		return text.length() == 1 && text.charAt(0) >= '1' && text.charAt(0) <= '9';
	}

	private static Map<String, Object> rejectedKey(String text)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "rejected");
		receipt.put("result", "numbered_menu_not_visible");
		receipt.put("key", text);
		receipt.put("click_count", 0L);
		return receipt;
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
		return resolveWidget(client, widget, "Click", "widget:" + widgetId);
	}

	private GenericClientMenuInput.Resolution resolveDestination(int menuId, String label)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		for (Widget widget : GenericClientWidgets.visible(client.getWidget(menuId)))
		{
			String[] actions = widget.getActions();
			if (GenericClientWidgets.matchesLabel(label, widget.getText(), widget.getName(),
				actions == null ? List.of() : Arrays.asList(actions)))
				return resolveWidget(client, widget, "Click", "widget:" + widget.getId());
		}
		return GenericClientMenuInput.Resolution.rejected("destination_not_visible:" + label);
	}

	static GenericClientMenuInput.Resolution resolveWidget(
		Client client,
		Widget widget,
		String action,
		String description)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		if (!isVisible(widget))
		{
			return GenericClientMenuInput.Resolution.rejected(description + "_not_visible");
		}
		Rectangle bounds = widget.getBounds();
		Point point = GenericClientMenuInput.randomPointInside(
			bounds, client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected(description + "_not_clickable");
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "widget");
		value.put("widget_id", (long) widget.getId());
		value.put("widget_index", (long) widget.getIndex());
		value.put("sprite_id", (long) widget.getSpriteId());
		value.put("name", Text.removeTags(Objects.toString(widget.getName(), "")));
		value.put("text", Text.removeTags(Objects.toString(widget.getText(), "")));
		value.put("actions", widget.getActions());
		value.put("bounds", Map.of("x", (long) bounds.x, "y", (long) bounds.y,
			"width", (long) bounds.width, "height", (long) bounds.height));
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			action,
			description,
			value,
			entry -> matchesWidget(entry, widget), bounds));
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
}
