package com.genericclient;

import static com.genericclient.GenericClientErrors.rootMessage;

import java.awt.Canvas;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;

@SuppressWarnings("deprecation")
final class GenericClientCombatInput implements AutoCloseable
{
	private static final int[] STYLE_WIDGETS =
	{
		InterfaceID.CombatInterface._0,
		InterfaceID.CombatInterface._1,
		InterfaceID.CombatInterface._2,
		InterfaceID.CombatInterface._3
	};
	private static final int[] COMBAT_TABS =
	{
		WidgetInfo.FIXED_VIEWPORT_COMBAT_TAB.getId(),
		WidgetInfo.RESIZABLE_VIEWPORT_COMBAT_TAB.getId(),
		WidgetInfo.RESIZABLE_VIEWPORT_BOTTOM_LINE_COMBAT_ICON.getId()
	};
	private static final int MODE_AUTO_RETALIATE_OFF = 4;
	private static final int MODE_AUTO_RETALIATE_ON = 5;
	private static final long UI_SETTLE_MILLIS = 250L;
	private static final long STYLE_TIMEOUT_MILLIS = 2_500L;

	private final Client client;
	private final ClientThread clientThread;
	private final GenericClientSyntheticMouse syntheticMouse;
	private final Consumer<String> reporter;
	private final AtomicBoolean running = new AtomicBoolean();

	private volatile CompletableFuture<Map<String, Object>> activeResult;
	private final GenericClientInputCallbacks callbacks;
	private volatile int requestedStyle;
	private volatile boolean requestedAutoRetaliate;
	private volatile Operation operation = Operation.STYLE;
	private volatile GenericClientActivityContext activityContext;
	private volatile int clickCount;
	private volatile long deadlineNanos;
	private volatile boolean closed;

	GenericClientCombatInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientSyntheticMouse syntheticMouse,
		Consumer<String> reporter)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.callbacks = new GenericClientInputCallbacks(this, () -> this.activeResult, executor);
		this.syntheticMouse = syntheticMouse;
		this.reporter = reporter;
	}

	CompletableFuture<Map<String, Object>> setStyle(int style, GenericClientActivityContext activityContext)
	{
		if (style < 0 || style >= STYLE_WIDGETS.length)
		{
			throw new IllegalArgumentException("Combat style index must be between 0 and 3");
		}
		return start(Operation.STYLE, style, false, activityContext);
	}

	CompletableFuture<Map<String, Object>> setAutoRetaliate(boolean enabled, GenericClientActivityContext activityContext)
	{
		return start(Operation.AUTO_RETALIATE, -1, enabled, activityContext);
	}

	CompletableFuture<Map<String, Object>> setMode(int mode, GenericClientActivityContext activityContext)
	{
		if (mode >= 0 && mode < STYLE_WIDGETS.length)
		{
			return setStyle(mode, activityContext);
		}
		if (mode == MODE_AUTO_RETALIATE_OFF || mode == MODE_AUTO_RETALIATE_ON)
		{
			return setAutoRetaliate(mode == MODE_AUTO_RETALIATE_ON, activityContext);
		}
		throw new IllegalArgumentException("Unsupported combat mode: " + mode);
	}

	private synchronized CompletableFuture<Map<String, Object>> start(
		Operation operation,
		int style,
		boolean autoRetaliate,
		GenericClientActivityContext activityContext)
	{
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		if (closed || !activityContext.isInputAllowed())
		{
			result.complete(immediateReceipt(operation, style, autoRetaliate,
				closed ? "input_closed" : "action_cancelled"));
			return result;
		}
		if (!running.compareAndSet(false, true))
		{
			result.complete(immediateReceipt(
				operation, style, autoRetaliate, "interaction_already_running"));
			return result;
		}

		this.operation = operation;
		requestedStyle = style;
		requestedAutoRetaliate = autoRetaliate;
		activeResult = result;
		this.activityContext = activityContext;
		clickCount = 0;
		deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STYLE_TIMEOUT_MILLIS);
		reporter.accept(operation == Operation.STYLE
			? "COMBAT_STYLE_SELECTING index=" + style
			: "COMBAT_AUTO_RETALIATE_SELECTING enabled=" + autoRetaliate);
		invokeCurrent(operation == Operation.STYLE
			? this::prepareStyleOnClientThread
			: this::prepareAutoRetaliateOnClientThread);
		return result;
	}

	boolean isRunning()
	{
		return running.get();
	}

	synchronized void cancel(String reason)
	{
		if (running.get())
		{
			finish("rejected", "cancelled: " + reason);
		}
	}

	private void prepareStyleOnClientThread()
	{
		if (!running.get())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			finish("rejected", "client_not_logged_in");
			return;
		}
		if (client.getVarpValue(VarPlayerID.COM_MODE) == requestedStyle)
		{
			finish("unchanged", "style_already_selected");
			return;
		}
		Widget style = visibleWidget(STYLE_WIDGETS[requestedStyle]);
		if (style != null)
		{
			beginWidgetAction(
				new int[]{STYLE_WIDGETS[requestedStyle]},
				"combat_style_not_visible",
				this::clickStyle);
			return;
		}
		openCombatControls(this::selectStyleAfterTab);
	}

	private void selectStyleAfterTab()
	{
		beginWidgetAction(
			new int[]{STYLE_WIDGETS[requestedStyle]},
			"combat_style_not_visible",
			this::clickStyle);
	}

	private void clickStyle(Widget style)
	{
		clickWidget(style).whenComplete(callbacks.bind((ignored, error) ->
		{
			if (error != null)
			{
				finish("rejected", "combat_style_click: " + rootMessage(error));
				return;
			}
			finishAction(() -> callbacks.schedule(
				() -> invokeCurrent(this::checkSelectedStyle), UI_SETTLE_MILLIS));
		}));
	}

	private void checkSelectedStyle()
	{
		if (client.getVarpValue(VarPlayerID.COM_MODE) == requestedStyle)
		{
			finish("set", "combat_style_selected");
			return;
		}
		if (System.nanoTime() >= deadlineNanos)
		{
			finish("rejected", "combat_style_did_not_change");
			return;
		}
		callbacks.schedule(() -> invokeCurrent(this::checkSelectedStyle), 50L);
	}

	private void prepareAutoRetaliateOnClientThread()
	{
		if (!running.get())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			finish("rejected", "client_not_logged_in");
			return;
		}
		if (autoRetaliateEnabled() == requestedAutoRetaliate)
		{
			finish("unchanged", "auto_retaliate_already_selected");
			return;
		}
		Widget retaliate = visibleWidget(InterfaceID.CombatInterface.RETALIATE);
		if (retaliate != null)
		{
			beginWidgetAction(
				new int[]{InterfaceID.CombatInterface.RETALIATE},
				"auto_retaliate_not_visible",
				this::clickAutoRetaliate);
			return;
		}
		openCombatControls(this::selectAutoRetaliateAfterTab);
	}

	private void selectAutoRetaliateAfterTab()
	{
		beginWidgetAction(
			new int[]{InterfaceID.CombatInterface.RETALIATE},
			"auto_retaliate_not_visible",
			this::clickAutoRetaliate);
	}

	private void clickAutoRetaliate(Widget retaliate)
	{
		clickWidget(retaliate).whenComplete(callbacks.bind((ignored, error) ->
		{
			if (error != null)
			{
				finish("rejected", "auto_retaliate_click: " + rootMessage(error));
				return;
			}
			finishAction(() -> callbacks.schedule(
				() -> invokeCurrent(this::checkAutoRetaliate), UI_SETTLE_MILLIS));
		}));
	}

	private void checkAutoRetaliate()
	{
		if (autoRetaliateEnabled() == requestedAutoRetaliate)
		{
			finish("set", "auto_retaliate_selected");
			return;
		}
		if (System.nanoTime() >= deadlineNanos)
		{
			finish("rejected", "auto_retaliate_did_not_change");
			return;
		}
		callbacks.schedule(() -> invokeCurrent(this::checkAutoRetaliate), 50L);
	}

	private boolean autoRetaliateEnabled()
	{
		return client.getVarpValue(VarPlayerID.OPTION_NODEF) == 0;
	}

	private void openCombatControls(Runnable afterOpen)
	{
		if (visibleWidget(InterfaceID.Autocast.INFO) != null)
		{
			beginWidgetAction(
				new int[]{InterfaceID.Autocast.INFO},
				"autocast_cancel_not_visible",
				cancel -> clickWidget(cancel).whenComplete(callbacks.bind((ignored, error) ->
				{
					if (error != null)
					{
						finish("rejected", "autocast_cancel_click: " + rootMessage(error));
						return;
					}
					finishAction(() -> callbacks.schedule(
						() -> invokeCurrent(afterOpen), UI_SETTLE_MILLIS));
				})));
			return;
		}
		openCombatTab(afterOpen);
	}

	private void openCombatTab(Runnable afterOpen)
	{
		Widget tab = visibleWidget(COMBAT_TABS);
		if (tab == null)
		{
			finish("rejected", "combat_tab_not_visible");
			return;
		}
		beginWidgetAction(COMBAT_TABS, "combat_tab_not_visible", currentTab ->
			clickWidget(currentTab).whenComplete(callbacks.bind((ignored, error) ->
		{
			if (error != null)
			{
				finish("rejected", "combat_tab_click: " + rootMessage(error));
				return;
			}
			finishAction(() -> callbacks.schedule(() -> invokeCurrent(afterOpen), UI_SETTLE_MILLIS));
		})));
	}

	private void beginWidgetAction(
		int[] widgetIds,
		String unavailableReason,
		Consumer<Widget> action)
	{
		beginAction(() ->
		{
			Widget widget = visibleWidget(widgetIds);
			if (widget == null)
			{
				finish("rejected", unavailableReason);
				return;
			}
			action.accept(widget);
		});
	}

	private void beginAction(Runnable action)
	{
		invokeCurrent(action);
	}

	private void finishAction(Runnable continuation)
	{
		continuation.run();
	}

	private CompletableFuture<String> clickWidget(Widget widget)
	{
		Rectangle bounds = widget.getBounds();
		Canvas canvas = client.getCanvas();
		if (bounds == null || bounds.width < 1 || bounds.height < 1 || canvas == null || !canvas.isShowing())
		{
			CompletableFuture<String> failed = new CompletableFuture<>();
			failed.completeExceptionally(new IllegalStateException("widget_not_clickable"));
			return failed;
		}
		Point point = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
		clickCount++;
		return syntheticMouse.move(point, activityContext)
			.thenCompose(callbacks.bind(ignored -> syntheticMouse.click(MouseEvent.BUTTON1, activityContext)));
	}

	private Widget visibleWidget(int... ids)
	{
		for (int id : ids)
		{
			Widget widget = client.getWidget(id);
			if (widget != null && !widget.isHidden() && !widget.isSelfHidden())
			{
				Rectangle bounds = widget.getBounds();
				if (bounds != null && bounds.width > 0 && bounds.height > 0)
				{
					return widget;
				}
			}
		}
		return null;
	}

	private synchronized void finish(String status, String result)
	{
		if (!running.getAndSet(false))
		{
			return;
		}
		callbacks.cancelPending();
		Map<String, Object> receipt = receipt(status, result);
		reporter.accept(operation == Operation.STYLE
			? "COMBAT_STYLE_COMPLETED index=" + requestedStyle +
				" status=" + status + " clicks=" + clickCount
			: "COMBAT_AUTO_RETALIATE_COMPLETED enabled=" + requestedAutoRetaliate +
				" status=" + status + " clicks=" + clickCount);
		CompletableFuture<Map<String, Object>> completion = activeResult;
		activeResult = null;
		if (completion != null)
		{
			completion.completeAsync(() -> receipt);
		}
	}

	private Map<String, Object> receipt(String status, String result)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", status);
		receipt.put("result", result);
		if (operation == Operation.STYLE)
		{
			receipt.put("style_index", (long) requestedStyle);
		}
		else
		{
			receipt.put("enabled", requestedAutoRetaliate);
		}
		receipt.put("click_count", (long) clickCount);
		return receipt;
	}

	private static Map<String, Object> immediateReceipt(
		Operation operation,
		int style,
		boolean autoRetaliate,
		String result)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "rejected");
		receipt.put("result", result);
		if (operation == Operation.STYLE)
		{
			receipt.put("style_index", (long) style);
		}
		else
		{
			receipt.put("enabled", autoRetaliate);
		}
		receipt.put("click_count", 0L);
		return receipt;
	}

	private void invokeCurrent(Runnable action)
	{
		clientThread.invoke(callbacks.bind(() -> {
			if (activityContext.isInputAllowed()) action.run();
			else finish("rejected", "action_cancelled");
		}));
	}


	private enum Operation
	{
		STYLE,
		AUTO_RETALIATE
	}

	@Override
	public synchronized void close()
	{
		closed = true;
		if (running.get())
		{
			finish("rejected", "input_closed");
		}
		else
		{
			callbacks.cancelPending();
		}
	}
}
