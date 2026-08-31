package com.genericclient;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

final class GenericClientRunInput
{
	private static final int VERIFY_ATTEMPTS = 20;
	private static final long VERIFY_INTERVAL_MILLIS = 50L;

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientMenuInput menuInput;

	GenericClientRunInput(
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

	CompletableFuture<Map<String, Object>> enable(GenericClientActivityContext activityContext)
	{
		return setEnabled(true, activityContext);
	}

	CompletableFuture<Map<String, Object>> setEnabled(boolean expected, GenericClientActivityContext activityContext)
	{
		return clientRead(this::isEnabled).thenCompose(
			enabled -> toggleIfNeeded(expected, activityContext, enabled));
	}

	private CompletableFuture<Map<String, Object>> toggleIfNeeded(
		boolean expected,
		GenericClientActivityContext activityContext,
		boolean enabled)
	{
		if (enabled == expected)
		{
			return CompletableFuture.completedFuture(unchanged(expected));
		}
		return menuInput.interact(this::resolveRunButton, activityContext).thenCompose(
			receipt -> verifyToggle(expected, receipt));
	}

	private CompletableFuture<Map<String, Object>> verifyToggle(
		boolean expected,
		Map<String, Object> receipt)
	{
		if (!"dispatched".equals(receipt.get("status")))
		{
			return CompletableFuture.completedFuture(receipt);
		}
		return verifyState(expected, VERIFY_ATTEMPTS).thenApply(
			verified -> toggleReceipt(receipt, expected, verified));
	}

	private static Map<String, Object> toggleReceipt(
		Map<String, Object> receipt,
		boolean expected,
		boolean verified)
	{
		Map<String, Object> result = new LinkedHashMap<>(receipt);
		result.put("status", verified ? "complete" : "rejected");
		result.put("result", verified
			? expected ? "run_enabled" : "run_disabled"
			: "run_toggle_unverified");
		return result;
	}

	void cancel(String reason)
	{
		menuInput.cancel(reason);
	}

	private GenericClientMenuInput.Resolution resolveRunButton()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		Widget widget = client.getWidget(InterfaceID.Orbs.RUNBUTTON);
		if (widget == null || widget.isHidden() || widget.isSelfHidden())
		{
			return GenericClientMenuInput.Resolution.rejected("run_button_not_visible");
		}
		Rectangle bounds = widget.getBounds();
		Point point = GenericClientMenuInput.randomPointInside(
			bounds, client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected("run_button_not_clickable");
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "widget");
		value.put("widget_id", (long) widget.getId());
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			"Toggle Run",
			"run_button",
			value,
			entry -> isWidgetAction(entry) && matchesWidget(entry, widget)));
	}

	private CompletableFuture<Boolean> verifyState(boolean expected, int attemptsRemaining)
	{
		return clientRead(this::isEnabled).thenCompose(enabled ->
		{
			if (enabled == expected || attemptsRemaining <= 1)
			{
				return CompletableFuture.completedFuture(enabled == expected);
			}
			CompletableFuture<Void> delay = new CompletableFuture<>();
			executor.schedule(() -> delay.complete(null), VERIFY_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
			return delay.thenCompose(ignored -> verifyState(expected, attemptsRemaining - 1));
		});
	}

	private boolean isEnabled()
	{
		return client.getVarpValue(VarPlayerID.OPTION_RUN) == 1;
	}

	private <T> CompletableFuture<T> clientRead(java.util.function.Supplier<T> reader)
	{
		CompletableFuture<T> result = new CompletableFuture<>();
		clientThread.invoke(() -> result.complete(reader.get()));
		return result;
	}

	static boolean isWidgetAction(MenuEntry entry)
	{
		return entry.getType() == MenuAction.CC_OP ||
			entry.getType() == MenuAction.CC_OP_LOW_PRIORITY;
	}

	static boolean matchesWidget(MenuEntry entry, Widget target)
	{
		Widget widget = entry.getWidget();
		return widget == null
			? entry.getParam1() == target.getId()
			: widget.getId() == target.getId() && widget.getIndex() == target.getIndex();
	}

	private static Map<String, Object> unchanged(boolean enabled)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("status", "unchanged");
		result.put("result", enabled ? "run_already_enabled" : "run_already_disabled");
		result.put("click_count", 0L);
		return result;
	}
}
