package com.genericclient;

import java.awt.Canvas;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
	private final ScheduledExecutorService executor;
	private final GenericClientSyntheticMouse syntheticMouse;
	private final GenericClientBehaviorController behavior;
	private final Consumer<String> reporter;
	private final AtomicBoolean running = new AtomicBoolean();
	private final java.util.List<ScheduledFuture<?>> pending = new CopyOnWriteArrayList<>();

	private volatile CompletableFuture<Map<String, Object>> activeResult;
	private volatile int requestedStyle;
	private volatile boolean requestedAutoRetaliate;
	private volatile Operation operation = Operation.STYLE;
	private volatile boolean breaksEnabled;
	private volatile int clickCount;
	private volatile long deadlineNanos;
	private volatile Map<String, Object> behaviorBefore = Collections.emptyMap();
	private volatile Map<String, Object> behaviorAfter = Collections.emptyMap();
	private volatile boolean closed;

	GenericClientCombatInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientSyntheticMouse syntheticMouse,
		GenericClientBehaviorController behavior,
		Consumer<String> reporter)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.syntheticMouse = syntheticMouse;
		this.behavior = behavior;
		this.reporter = reporter;
	}

	CompletableFuture<Map<String, Object>> setStyle(int style, boolean breaksEnabled)
	{
		if (style < 0 || style >= STYLE_WIDGETS.length)
		{
			throw new IllegalArgumentException("Combat style index must be between 0 and 3");
		}
		return start(Operation.STYLE, style, false, breaksEnabled);
	}

	CompletableFuture<Map<String, Object>> setAutoRetaliate(boolean enabled, boolean breaksEnabled)
	{
		return start(Operation.AUTO_RETALIATE, -1, enabled, breaksEnabled);
	}

	CompletableFuture<Map<String, Object>> setMode(int mode, boolean breaksEnabled)
	{
		if (mode >= 0 && mode < STYLE_WIDGETS.length)
		{
			return setStyle(mode, breaksEnabled);
		}
		if (mode == MODE_AUTO_RETALIATE_OFF || mode == MODE_AUTO_RETALIATE_ON)
		{
			return setAutoRetaliate(mode == MODE_AUTO_RETALIATE_ON, breaksEnabled);
		}
		throw new IllegalArgumentException("Unsupported combat mode: " + mode);
	}

	private CompletableFuture<Map<String, Object>> start(
		Operation operation,
		int style,
		boolean autoRetaliate,
		boolean breaksEnabled)
	{
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		if (closed)
		{
			result.complete(immediateReceipt(operation, style, autoRetaliate, "input_closed"));
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
		this.breaksEnabled = breaksEnabled;
		clickCount = 0;
		behaviorBefore = Collections.emptyMap();
		behaviorAfter = Collections.emptyMap();
		deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STYLE_TIMEOUT_MILLIS);
		reporter.accept(operation == Operation.STYLE
			? "COMBAT_STYLE_SELECTING index=" + style
			: "COMBAT_AUTO_RETALIATE_SELECTING enabled=" + autoRetaliate);
		clientThread.invoke(operation == Operation.STYLE
			? this::prepareStyleOnClientThread
			: this::prepareAutoRetaliateOnClientThread);
		return result;
	}

	boolean isRunning()
	{
		return running.get();
	}

	void cancel(String reason)
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
		openCombatTab(this::selectStyleAfterTab);
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
		clickWidget(style).whenComplete((ignored, error) ->
		{
			if (error != null)
			{
				finish("rejected", "combat_style_click: " + rootMessage(error));
				return;
			}
			finishAction(() -> schedule(
				() -> clientThread.invoke(this::checkSelectedStyle), UI_SETTLE_MILLIS));
		});
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
		schedule(() -> clientThread.invoke(this::checkSelectedStyle), 50L);
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
		openCombatTab(this::selectAutoRetaliateAfterTab);
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
		clickWidget(retaliate).whenComplete((ignored, error) ->
		{
			if (error != null)
			{
				finish("rejected", "auto_retaliate_click: " + rootMessage(error));
				return;
			}
			finishAction(() -> schedule(
				() -> clientThread.invoke(this::checkAutoRetaliate), UI_SETTLE_MILLIS));
		});
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
		schedule(() -> clientThread.invoke(this::checkAutoRetaliate), 50L);
	}

	private boolean autoRetaliateEnabled()
	{
		return client.getVarpValue(VarPlayerID.OPTION_NODEF) == 0;
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
			clickWidget(currentTab).whenComplete((ignored, error) ->
		{
			if (error != null)
			{
				finish("rejected", "combat_tab_click: " + rootMessage(error));
				return;
			}
			finishAction(() -> schedule(() -> clientThread.invoke(afterOpen), UI_SETTLE_MILLIS));
		}));
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
		behavior.beforeAction(breaksEnabled).whenComplete((before, error) ->
		{
			if (error != null)
			{
				finish("rejected", "behavior_before: " + rootMessage(error));
				return;
			}
			behaviorBefore = before;
			clientThread.invoke(action);
		});
	}

	private void finishAction(Runnable continuation)
	{
		behavior.afterAction(breaksEnabled).whenComplete((after, error) ->
		{
			if (error != null)
			{
				finish("rejected", "behavior_after: " + rootMessage(error));
				return;
			}
			behaviorAfter = after;
			continuation.run();
		});
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
		return syntheticMouse.move(point).thenCompose(ignored -> syntheticMouse.click(MouseEvent.BUTTON1));
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

	private void finish(String status, String result)
	{
		if (!running.getAndSet(false))
		{
			return;
		}
		cancelPending();
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
			completion.complete(receipt);
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
		receipt.put("behavior_before", behaviorBefore);
		if (!behaviorAfter.isEmpty())
		{
			receipt.put("behavior_after", behaviorAfter);
		}
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
		receipt.put("behavior_before", Collections.emptyMap());
		return receipt;
	}

	private void schedule(Runnable runnable, long delayMillis)
	{
		ScheduledFuture<?> future = executor.schedule(() ->
		{
			if (running.get())
			{
				runnable.run();
			}
		}, delayMillis, TimeUnit.MILLISECONDS);
		pending.add(future);
	}

	private void cancelPending()
	{
		for (ScheduledFuture<?> future : pending)
		{
			future.cancel(false);
		}
		pending.clear();
	}

	private static String rootMessage(Throwable error)
	{
		Throwable current = error;
		while (current.getCause() != null)
		{
			current = current.getCause();
		}
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}

	private enum Operation
	{
		STYLE,
		AUTO_RETALIATE
	}

	@Override
	public void close()
	{
		closed = true;
		if (running.get())
		{
			finish("rejected", "input_closed");
		}
		else
		{
			cancelPending();
		}
	}
}
