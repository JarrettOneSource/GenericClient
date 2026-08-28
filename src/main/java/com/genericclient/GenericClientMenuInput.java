package com.genericclient;

import java.awt.Canvas;
import java.awt.Point;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.callback.ClientThread;

final class GenericClientMenuInput implements AutoCloseable
{
	private static final long HOVER_SETTLE_MILLIS = 75L;
	private static final long CONTEXT_MENU_SETTLE_MILLIS = 150L;
	private static final long CLICK_RESULT_TIMEOUT_MILLIS = 2_500L;
	private static final int CONTEXT_MENU_ENTRY_HEIGHT = 15;
	private static final int MAX_DYNAMIC_RETARGETS = 12;
	private static final int MAX_CONTEXT_REOPENS = 3;

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientSyntheticMouse syntheticMouse;
	private final GenericClientBehaviorController behavior;
	private final Consumer<String> reporter;
	private final AtomicBoolean running = new AtomicBoolean();
	private final List<ScheduledFuture<?>> pending = new CopyOnWriteArrayList<>();

	private volatile CompletableFuture<Map<String, Object>> activeResult;
	private volatile TargetResolver resolver;
	private volatile Target target;
	private volatile boolean breaksEnabled;
	private volatile boolean directClick;
	private volatile boolean awaitingMenuResult;
	private volatile int clickCount;
	private volatile int dynamicRetargetCount;
	private volatile int contextReopenCount;
	private volatile String dispatch;
	private volatile Map<String, Object> behaviorBefore = Collections.emptyMap();
	private volatile Map<String, Object> behaviorAfter = Collections.emptyMap();
	private volatile boolean closed;

	GenericClientMenuInput(
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

	CompletableFuture<Map<String, Object>> interact(TargetResolver resolver, boolean breaksEnabled)
	{
		return start(resolver, breaksEnabled, false);
	}

	CompletableFuture<Map<String, Object>> interactDirect(
		TargetResolver resolver,
		boolean breaksEnabled)
	{
		return start(resolver, breaksEnabled, true);
	}

	private CompletableFuture<Map<String, Object>> start(
		TargetResolver resolver,
		boolean breaksEnabled,
		boolean directClick)
	{
		if (resolver == null)
		{
			throw new IllegalArgumentException("Menu target resolver cannot be null");
		}
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		if (closed)
		{
			result.complete(immediateRejected("input_closed"));
			return result;
		}
		if (!running.compareAndSet(false, true))
		{
			result.complete(immediateRejected("interaction_already_running"));
			return result;
		}

		activeResult = result;
		this.resolver = resolver;
		this.breaksEnabled = breaksEnabled;
		this.directClick = directClick;
		target = null;
		awaitingMenuResult = false;
		clickCount = 0;
		dynamicRetargetCount = 0;
		contextReopenCount = 0;
		dispatch = null;
		behaviorBefore = Collections.emptyMap();
		behaviorAfter = Collections.emptyMap();

		behavior.beforeAction(breaksEnabled).whenComplete((before, error) ->
		{
			if (error != null)
			{
				finishRejected("behavior_before: " + rootMessage(error));
				return;
			}
			behaviorBefore = before;
			clientThread.invoke(this::resolveTargetOnClientThread);
		});
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
			finishRejected("cancelled: " + reason);
		}
	}

	void onMenuOptionClicked(MenuOptionClicked event)
	{
		Target current = target;
		if (!awaitingMenuResult || current == null || !current.matches(event.getMenuEntry()))
		{
			return;
		}

		awaitingMenuResult = false;
		Map<String, Object> observed = new LinkedHashMap<>();
		observed.put("menu_action", event.getMenuAction().name().toLowerCase(java.util.Locale.ROOT));
		observed.put("menu_option", event.getMenuOption());
		observed.put("menu_target", event.getMenuTarget());
		behavior.afterAction(breaksEnabled).whenComplete((after, error) ->
		{
			if (error != null)
			{
				finishRejected("behavior_after: " + rootMessage(error));
				return;
			}
			behaviorAfter = after;
			finishSuccess(observed);
		});
	}

	private void resolveTargetOnClientThread()
	{
		if (!running.get())
		{
			return;
		}
		Resolution resolution;
		try
		{
			resolution = resolver.resolve();
		}
		catch (RuntimeException exception)
		{
			finishRejected("target_resolution: " + exception.getMessage());
			return;
		}
		if (resolution == null || resolution.target == null)
		{
			finishRejected(resolution == null ? "target_unavailable" : resolution.reason);
			return;
		}
		target = resolution.target;
		reporter.accept("MENU_INTERACTION_TARGET description=" + target.description +
			" action=" + target.action + " canvas=" + target.point.x + "," + target.point.y);
		Canvas canvas = client.getCanvas();
		if (canvas == null || !canvas.isShowing())
		{
			finishRejected("canvas_not_showing");
			return;
		}
		syntheticMouse.move(target.point).whenComplete((ignored, error) ->
		{
			if (error != null)
			{
				finishRejected("synthetic_mouse_move: " + rootMessage(error));
				return;
			}
			schedule(() -> clientThread.invoke(this::verifyHoverAndClick), HOVER_SETTLE_MILLIS);
		});
	}

	private void verifyHoverAndClick()
	{
		Target current = target;
		if (!running.get() || current == null)
		{
			return;
		}
		if (client.isMenuOpen())
		{
			finishRejected("context_menu_already_open");
			return;
		}
		if (current.isDynamic())
		{
			Resolution refreshed;
			try
			{
				refreshed = resolver.resolve();
			}
			catch (RuntimeException exception)
			{
				finishRejected("target_refresh: " + exception.getMessage());
				return;
			}
			if (refreshed == null || refreshed.target == null)
			{
				finishRejected(refreshed == null ? "target_refresh_unavailable" : refreshed.reason);
				return;
			}
			current = refreshed.target;
			target = current;
			net.runelite.api.Point refreshedMouse = client.getMouseCanvasPosition();
			if (!current.contains(refreshedMouse.getX(), refreshedMouse.getY()))
			{
				if (dynamicRetargetCount >= MAX_DYNAMIC_RETARGETS)
				{
					finishRejected("dynamic_target_kept_moving");
					return;
				}
				dynamicRetargetCount++;
				Target destination = current;
				reporter.accept("MENU_INTERACTION_RETARGET description=" + current.description +
					" attempt=" + dynamicRetargetCount +
					" canvas=" + current.point.x + "," + current.point.y);
				syntheticMouse.move(current.point).whenComplete((ignored, error) ->
				{
					if (error != null)
					{
						finishRejected("synthetic_retarget_move: " + rootMessage(error));
						return;
					}
					if (target == destination)
					{
						schedule(() -> clientThread.invoke(this::verifyHoverAndClick),
							HOVER_SETTLE_MILLIS);
					}
				});
				return;
			}
		}
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		if (!current.acceptsMouse(mouse.getX(), mouse.getY()))
		{
			finishRejected("mouse_missed_target");
			return;
		}
		if (directClick)
		{
			dispatchDirectClick();
			return;
		}

		MenuEntry[] entries = client.getMenu().getMenuEntries();
		int desiredIndex = findEntryIndex(entries, current);
		if (desiredIndex < 0)
		{
			reportMenuEntries(entries, current);
			finishRejected("hover_has_no_matching_action");
			return;
		}
		if (desiredIndex == entries.length - 1)
		{
			dispatchLeftClick("left_click");
			return;
		}
		openContextMenu();
	}

	private void dispatchDirectClick()
	{
		Target current = target;
		if (!running.get() || current == null)
		{
			return;
		}
		dispatch = "direct_click";
		clickCount++;
		syntheticMouse.click(MouseEvent.BUTTON1).whenComplete((ignored, clickError) ->
		{
			if (clickError != null)
			{
				finishRejected("synthetic_click: " + rootMessage(clickError));
				return;
			}
			behavior.afterAction(breaksEnabled).whenComplete((after, behaviorError) ->
			{
				if (behaviorError != null)
				{
					finishRejected("behavior_after: " + rootMessage(behaviorError));
					return;
				}
				behaviorAfter = after;
				Map<String, Object> observed = new LinkedHashMap<>();
				observed.put("result", "direct_widget_click");
				observed.put("menu_action", "direct_widget");
				observed.put("menu_option", current.action);
				observed.put("menu_target", "");
				finishSuccess(observed);
			});
		});
	}

	private void reportMenuEntries(MenuEntry[] entries, Target current)
	{
		reporter.accept("MENU_INTERACTION_ENTRIES description=" + current.description +
			" count=" + entries.length);
		for (int index = Math.max(0, entries.length - 15); index < entries.length; index++)
		{
			MenuEntry entry = entries[index];
			net.runelite.api.widgets.Widget widget = entry.getWidget();
			reporter.accept("MENU_INTERACTION_ENTRY description=" + current.description +
				" index=" + index +
				" option=" + entry.getOption() +
				" target=" + entry.getTarget() +
				" type=" + entry.getType() +
				" identifier=" + entry.getIdentifier() +
				" param0=" + entry.getParam0() +
				" param1=" + entry.getParam1() +
				" worldView=" + entry.getWorldViewId() +
				" item=" + entry.getItemId() +
				" widget=" + (widget == null ? "null" : widget.getId() + ":" + widget.getIndex()));
		}
	}

	private void openContextMenu()
	{
		dispatch = "context_menu";
		clickCount++;
		syntheticMouse.click(MouseEvent.BUTTON3).whenComplete((ignored, clickError) ->
		{
			if (clickError != null)
			{
				finishRejected("synthetic_context_open: " + rootMessage(clickError));
				return;
			}
			behavior.afterAction(breaksEnabled).thenCompose(after ->
			{
				behaviorAfter = after;
				return behavior.beforeAction(breaksEnabled);
			}).whenComplete((before, behaviorError) ->
			{
				if (behaviorError != null)
				{
					finishRejected("context_behavior: " + rootMessage(behaviorError));
					return;
				}
				behaviorBefore = before;
				schedule(() -> clientThread.invoke(this::moveToContextEntry), CONTEXT_MENU_SETTLE_MILLIS);
			});
		});
	}

	private void moveToContextEntry()
	{
		Target current = target;
		if (!running.get() || current == null)
		{
			return;
		}
		if (!client.isMenuOpen())
		{
			if (contextReopenCount >= MAX_CONTEXT_REOPENS)
			{
				finishRejected("context_menu_did_not_open");
				return;
			}
			contextReopenCount++;
			reporter.accept("MENU_CONTEXT_REOPEN attempt=" + contextReopenCount +
				" description=" + current.description);
			resolveTargetOnClientThread();
			return;
		}
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		int index = findEntryIndex(entries, current);
		if (index < 0)
		{
			finishRejected("context_menu_has_no_matching_action");
			return;
		}
		int headerHeight = Math.max(0,
			client.getMenu().getMenuHeight() - entries.length * CONTEXT_MENU_ENTRY_HEIGHT);
		int rowFromTop = entries.length - 1 - index;
		Point destination = new Point(
			client.getMenu().getMenuX() + client.getMenu().getMenuWidth() / 2,
			client.getMenu().getMenuY() + headerHeight +
				rowFromTop * CONTEXT_MENU_ENTRY_HEIGHT + CONTEXT_MENU_ENTRY_HEIGHT / 2);
		syntheticMouse.move(destination).whenComplete((ignored, error) ->
		{
			if (error != null)
			{
				finishRejected("synthetic_context_move: " + rootMessage(error));
				return;
			}
			schedule(() -> clientThread.invoke(this::clickContextEntry), HOVER_SETTLE_MILLIS);
		});
	}

	private void clickContextEntry()
	{
		Target current = target;
		if (!running.get() || current == null || !client.isMenuOpen())
		{
			finishRejected("context_menu_closed");
			return;
		}
		if (findEntryIndex(client.getMenu().getMenuEntries(), current) < 0)
		{
			finishRejected("context_menu_has_no_matching_action");
			return;
		}
		dispatchLeftClick("context_menu");
	}

	private void dispatchLeftClick(String dispatch)
	{
		this.dispatch = dispatch;
		awaitingMenuResult = true;
		clickCount++;
		syntheticMouse.click(MouseEvent.BUTTON1).whenComplete((ignored, error) ->
		{
			if (error != null)
			{
				awaitingMenuResult = false;
				finishRejected("synthetic_click: " + rootMessage(error));
			}
		});
		schedule(() ->
		{
			if (awaitingMenuResult)
			{
				awaitingMenuResult = false;
				finishRejected("menu_event_timeout");
			}
		}, CLICK_RESULT_TIMEOUT_MILLIS);
	}

	static int findEntryIndex(MenuEntry[] entries, Target target)
	{
		for (int index = entries.length - 1; index >= 0; index--)
		{
			if (target.matches(entries[index]))
			{
				return index;
			}
		}
		return -1;
	}

	static Point randomPointInside(Shape shape, int canvasWidth, int canvasHeight)
	{
		java.awt.Rectangle bounds = boundedShape(shape, canvasWidth, canvasHeight);
		if (bounds == null)
		{
			return null;
		}
		for (int attempt = 0; attempt < 20; attempt++)
		{
			int x = java.util.concurrent.ThreadLocalRandom.current().nextInt(
				bounds.x, bounds.x + Math.max(1, bounds.width));
			int y = java.util.concurrent.ThreadLocalRandom.current().nextInt(
				bounds.y, bounds.y + Math.max(1, bounds.height));
			if (shape.contains(x, y))
			{
				return new Point(x, y);
			}
		}
		return firstPointInside(shape, canvasWidth, canvasHeight);
	}

	static Point firstPointInside(Shape shape, int canvasWidth, int canvasHeight)
	{
		java.awt.Rectangle bounds = boundedShape(shape, canvasWidth, canvasHeight);
		if (bounds == null)
		{
			return null;
		}
		int centerX = bounds.x + bounds.width / 2;
		int centerY = bounds.y + bounds.height / 2;
		if (shape.contains(centerX, centerY))
		{
			return new Point(centerX, centerY);
		}
		for (int y = bounds.y; y < bounds.y + bounds.height; y++)
		{
			for (int x = bounds.x; x < bounds.x + bounds.width; x++)
			{
				if (shape.contains(x, y))
				{
					return new Point(x, y);
				}
			}
		}
		return null;
	}

	private static java.awt.Rectangle boundedShape(
		Shape shape,
		int canvasWidth,
		int canvasHeight)
	{
		if (shape == null || canvasWidth <= 0 || canvasHeight <= 0)
		{
			return null;
		}
		java.awt.Rectangle bounds = shape.getBounds().intersection(
			new java.awt.Rectangle(0, 0, canvasWidth, canvasHeight));
		return bounds.isEmpty() ? null : bounds;
	}

	private void finishSuccess(Map<String, Object> observed)
	{
		Map<String, Object> receipt = baseReceipt("dispatched", "menu_action_executed");
		receipt.putAll(observed);
		finish(receipt);
	}

	private void finishRejected(String reason)
	{
		finish(baseReceipt("rejected", reason));
	}

	private Map<String, Object> baseReceipt(String status, String result)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", status);
		receipt.put("result", result);
		Target current = target;
		receipt.put("action", current == null ? null : current.action);
		receipt.put("target", current == null ? null : current.value);
		receipt.put("dispatch", dispatch);
		receipt.put("click_count", (long) clickCount);
		receipt.put("behavior_before", behaviorBefore);
		if (!behaviorAfter.isEmpty())
		{
			receipt.put("behavior_after", behaviorAfter);
		}
		return receipt;
	}

	private static Map<String, Object> immediateRejected(String reason)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "rejected");
		receipt.put("result", reason);
		receipt.put("click_count", 0L);
		return receipt;
	}

	private void finish(Map<String, Object> receipt)
	{
		if (!running.getAndSet(false))
		{
			return;
		}
		awaitingMenuResult = false;
		cancelPending();
		reporter.accept("MENU_INTERACTION_COMPLETED status=" + receipt.get("status") +
			" result=" + receipt.get("result") + " clicks=" + receipt.get("click_count"));
		CompletableFuture<Map<String, Object>> completion = activeResult;
		activeResult = null;
		resolver = null;
		target = null;
		if (completion != null)
		{
			completion.complete(receipt);
		}
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

	@Override
	public void close()
	{
		closed = true;
		if (running.get())
		{
			finishRejected("input_closed");
		}
		else
		{
			cancelPending();
		}
	}

	interface TargetResolver
	{
		Resolution resolve();
	}

	interface EntryMatcher
	{
		boolean matches(MenuEntry entry);
	}

	static final class Resolution
	{
		private final Target target;
		private final String reason;

		private Resolution(Target target, String reason)
		{
			this.target = target;
			this.reason = reason;
		}

		static Resolution resolved(Target target)
		{
			return new Resolution(target, null);
		}

		static Resolution rejected(String reason)
		{
			return new Resolution(null, reason);
		}
	}

	static final class Target
	{
		private final Point point;
		private final String action;
		private final String description;
		private final Map<String, Object> value;
		private final EntryMatcher matcher;
		private final java.awt.geom.Area dynamicRegion;

		Target(
			Point point,
			String action,
			String description,
			Map<String, Object> value,
			EntryMatcher matcher)
		{
			this(point, action, description, value, matcher, null);
		}

		Target(
			Point point,
			String action,
			String description,
			Map<String, Object> value,
			EntryMatcher matcher,
			Shape dynamicRegion)
		{
			this.point = new Point(point);
			this.action = action;
			this.description = description;
			this.value = Collections.unmodifiableMap(new LinkedHashMap<>(value));
			this.matcher = matcher;
			this.dynamicRegion = dynamicRegion == null
				? null
				: new java.awt.geom.Area(dynamicRegion);
		}

		boolean matches(MenuEntry entry)
		{
			return matcher.matches(entry);
		}

		boolean isDynamic()
		{
			return dynamicRegion != null;
		}

		boolean contains(int x, int y)
		{
			return dynamicRegion != null && dynamicRegion.contains(x, y);
		}

		boolean acceptsMouse(int x, int y)
		{
			return dynamicRegion == null
				? Math.abs(x - point.x) <= 20 && Math.abs(y - point.y) <= 20
				: dynamicRegion.contains(x, y);
		}
	}
}
