package com.genericclient;

import static com.genericclient.GenericClientErrors.rootMessage;

import java.awt.Canvas;
import java.awt.Point;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
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
	private static final int MAX_SELECTED_WIDGET_MENU_SETTLE_RETRIES = 10;

	private final Client client;
	private final ClientThread clientThread;
	private final GenericClientSyntheticMouse syntheticMouse;
	private final Consumer<String> reporter;
	private final AtomicBoolean running = new AtomicBoolean();

	private volatile CompletableFuture<Map<String, Object>> activeResult;
	private final GenericClientInputCallbacks callbacks;
	private volatile TargetResolver resolver;
	private volatile Target target;
	private volatile GenericClientActivityContext activityContext;
	private volatile boolean directClick;
	private volatile boolean awaitingMenuResult;
	private volatile boolean clickFinished;
	private volatile Map<String, Object> observedMenuResult;
	private volatile int clickCount;
	private volatile int dynamicRetargetCount;
	private volatile int contextReopenCount;
	private volatile int selectedWidgetMenuSettleRetries;
	private volatile String dispatch;
	private volatile boolean cursorRetained;
	private volatile Map<String, Object> receiptMetadata = Collections.emptyMap();
	private volatile boolean closed;

	GenericClientMenuInput(
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

	CompletableFuture<Map<String, Object>> interact(TargetResolver resolver, GenericClientActivityContext activityContext)
	{
		return start(resolver, activityContext, false, null);
	}

	CompletableFuture<Map<String, Object>> interactDirect(
		TargetResolver resolver,
		GenericClientActivityContext activityContext)
	{
		return start(resolver, activityContext, true, null);
	}

	CompletableFuture<Map<String, Object>> interactDirect(
		TargetResolver resolver,
		GenericClientActivityContext activityContext,
		PreInteractionResolver preInteractionResolver)
	{
		return start(resolver, activityContext, true, preInteractionResolver);
	}

	private synchronized CompletableFuture<Map<String, Object>> start(
		TargetResolver resolver,
		GenericClientActivityContext activityContext,
		boolean directClick,
		PreInteractionResolver preInteractionResolver)
	{
		if (resolver == null)
		{
			throw new IllegalArgumentException("Menu target resolver cannot be null");
		}
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		if (closed || !activityContext.isInputAllowed())
		{
			result.complete(immediateRejected(closed ? "input_closed" : "action_cancelled"));
			return result;
		}
		if (!running.compareAndSet(false, true))
		{
			result.complete(immediateRejected("interaction_already_running"));
			return result;
		}

		activeResult = result;
		this.resolver = resolver;
		this.activityContext = activityContext;
		this.directClick = directClick;
		receiptMetadata = Collections.emptyMap();
		target = null;
		awaitingMenuResult = false;
		clickFinished = false;
		observedMenuResult = null;
		clickCount = 0;
		dynamicRetargetCount = 0;
		contextReopenCount = 0;
		selectedWidgetMenuSettleRetries = 0;
		dispatch = null;
		cursorRetained = false;

		invokeCurrent(() -> prepareOnClientThread(preInteractionResolver));
		return result;
	}

	private void prepareOnClientThread(PreInteractionResolver preInteractionResolver)
	{
		if (!running.get())
		{
			return;
		}
		PreInteraction preInteraction;
		try
		{
			preInteraction = preInteractionResolver == null
				? PreInteraction.none()
				: preInteractionResolver.resolve();
		}
		catch (RuntimeException exception)
		{
			finishRejected("pre_interaction: " + exception.getMessage());
			return;
		}
		if (preInteraction == null)
		{
			finishRejected("pre_interaction_unavailable");
			return;
		}
		receiptMetadata = preInteraction.metadata;
		if (preInteraction.delayMillis == 0L)
		{
			resolveTargetOnClientThread();
		}
		else
		{
			callbacks.schedule(
				() -> invokeCurrent(this::resolveTargetOnClientThread),
				preInteraction.delayMillis);
		}
	}

	boolean isRunning()
	{
		return running.get();
	}

	synchronized void cancel(String reason)
	{
		if (running.get())
		{
			finishRejected("cancelled: " + reason);
		}
	}

	synchronized void cancel(String reason, GenericClientActivityContext owner)
	{
		if (running.get() && activityContext.ownsSameInput(owner)) finishRejected("cancelled: " + reason);
	}

	void onMenuOptionClicked(MenuOptionClicked event)
	{
		Target current = target;
		if (!awaitingMenuResult || current == null || !current.matches(event.getMenuEntry()))
		{
			return;
		}

		Map<String, Object> observed = new LinkedHashMap<>();
		observed.put("menu_action", event.getMenuAction().name().toLowerCase(java.util.Locale.ROOT));
		observed.put("menu_option", event.getMenuOption());
		observed.put("menu_target", event.getMenuTarget());
		synchronized (this)
		{
			awaitingMenuResult = false;
			observedMenuResult = observed;
		}
		finishLeftClickIfReady();
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
			String reason = resolution == null ? "target_unavailable" : resolution.reason;
			finishRejected(reason);
			return;
		}
		target = resolution.target;
		reporter.accept("MENU_INTERACTION_TARGET description=" + target.description +
			" action=" + target.action + " canvas=" + target.point.x + "," + target.point.y);
		Canvas canvas = client.getCanvas();
		if (canvas == null || !canvas.isShowing())
		{
			target = null;
			finishRejected("canvas_not_showing");
			return;
		}
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		if (!client.isMenuOpen() && mouse.getX() >= 0 && mouse.getY() >= 0 &&
			mouse.getX() < client.getCanvasWidth() && mouse.getY() < client.getCanvasHeight() &&
			findEntryIndex(client.getMenu().getMenuEntries(), target) >= 0)
		{
			cursorRetained = true;
			reporter.accept("MENU_INTERACTION_CURSOR_RETAINED description=" + target.description +
				" action=" + target.action + " canvas=" + mouse.getX() + "," + mouse.getY());
			callbacks.schedule(() -> invokeCurrent(this::verifyHoverAndClick), HOVER_SETTLE_MILLIS);
			return;
		}
		syntheticMouse.move(target.point, activityContext).whenComplete(callbacks.bind((ignored, error) ->
		{
			if (error != null)
			{
				finishRejected("synthetic_mouse_move: " + rootMessage(error));
				return;
			}
			callbacks.schedule(() -> invokeCurrent(this::verifyHoverAndClick), HOVER_SETTLE_MILLIS);
		}));
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
		current = refreshDynamicTarget(current);
		if (current == null)
		{
			return;
		}
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (restoreRetainedCursor(current, entries))
		{
			return;
		}
		if (!cursorRetained && !current.acceptsMouse(mouse.getX(), mouse.getY()))
		{
			finishRejected("mouse_missed_target");
			return;
		}
		if (directClick)
		{
			dispatchDirectClick();
			return;
		}

		int desiredIndex = findEntryIndex(entries, current);
		if (desiredIndex < 0)
		{
			boolean onlyCancel = entries.length == 1 &&
				entries[0] != null && entries[0].getType() == net.runelite.api.MenuAction.CANCEL;
			if (shouldSettleSelectedWidgetTarget(
				client.isWidgetSelected(), onlyCancel, selectedWidgetMenuSettleRetries))
			{
				selectedWidgetMenuSettleRetries++;
				reporter.accept("MENU_INTERACTION_SELECTED_WIDGET_SETTLE description=" +
					current.description + " attempt=" + selectedWidgetMenuSettleRetries);
				callbacks.schedule(() -> invokeCurrent(this::verifyHoverAndClick),
					HOVER_SETTLE_MILLIS);
				return;
			}
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

	private Target refreshDynamicTarget(Target current)
	{
		if (!current.isDynamic())
		{
			return current;
		}
		Resolution refreshed;
		try
		{
			refreshed = resolver.resolve();
		}
		catch (RuntimeException exception)
		{
			finishRejected("target_refresh: " + exception.getMessage());
			return null;
		}
		if (refreshed == null || refreshed.target == null)
		{
			finishRejected(refreshed == null ? "target_refresh_unavailable" : refreshed.reason);
			return null;
		}
		Target destination = refreshed.target;
		target = destination;
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		if (destination.contains(mouse.getX(), mouse.getY()))
		{
			return destination;
		}
		if (dynamicRetargetCount >= MAX_DYNAMIC_RETARGETS)
		{
			finishRejected("dynamic_target_kept_moving");
			return null;
		}
		dynamicRetargetCount++;
		cursorRetained = false;
		reporter.accept("MENU_INTERACTION_RETARGET description=" + destination.description +
			" attempt=" + dynamicRetargetCount +
			" canvas=" + destination.point.x + "," + destination.point.y);
		syntheticMouse.move(destination.point, activityContext).whenComplete(callbacks.bind((ignored, error) ->
		{
			if (error != null)
			{
				finishRejected("synthetic_retarget_move: " + rootMessage(error));
				return;
			}
			if (target == destination)
			{
				callbacks.schedule(() -> invokeCurrent(this::verifyHoverAndClick), HOVER_SETTLE_MILLIS);
			}
		}));
		return null;
	}

	private boolean restoreRetainedCursor(Target current, MenuEntry[] entries)
	{
		if (!cursorRetained || findEntryIndex(entries, current) >= 0)
		{
			return false;
		}
		cursorRetained = false;
		reporter.accept("MENU_INTERACTION_CURSOR_RETENTION_EXPIRED description=" +
			current.description + " canvas=" + current.point.x + "," + current.point.y);
		syntheticMouse.move(current.point, activityContext).whenComplete(callbacks.bind((ignored, error) ->
		{
			if (error != null)
			{
				finishRejected("synthetic_mouse_move: " + rootMessage(error));
				return;
			}
			callbacks.schedule(() -> invokeCurrent(this::verifyHoverAndClick), HOVER_SETTLE_MILLIS);
		}));
		return true;
	}

	private void dispatchDirectClick()
	{
		Target current = target;
		if (!running.get() || current == null) return;
		dispatch = "direct_click";
		clickCount++;
		syntheticMouse.click(MouseEvent.BUTTON1, activityContext).whenComplete(callbacks.bind((ignored, error) ->
		{
			if (error != null)
			{
				finishRejected("synthetic_click: " + rootMessage(error));
				return;
			}
			Map<String, Object> observed = new LinkedHashMap<>();
			observed.put("result", "direct_widget_click");
			observed.put("menu_action", "direct_widget");
			observed.put("menu_option", current.action);
			observed.put("menu_target", "");
			finishSuccess(observed);
		}));
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
		syntheticMouse.click(MouseEvent.BUTTON3, activityContext).whenComplete(callbacks.bind((ignored, clickError) ->
		{
			if (clickError != null)
			{
				finishRejected("synthetic_context_open: " + rootMessage(clickError));
				return;
			}
			// Opening the menu and selecting its entry are one composite interaction.
			// Moving offscreen between those clicks closes the menu before it can be used.
			callbacks.schedule(() -> invokeCurrent(this::moveToContextEntry), CONTEXT_MENU_SETTLE_MILLIS);
		}));
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
		syntheticMouse.move(destination, activityContext).whenComplete(callbacks.bind((ignored, error) ->
		{
			if (error != null)
			{
				finishRejected("synthetic_context_move: " + rootMessage(error));
				return;
			}
			callbacks.schedule(() -> invokeCurrent(this::clickContextEntry), HOVER_SETTLE_MILLIS);
		}));
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
		clickFinished = false;
		observedMenuResult = null;
		clickCount++;
		syntheticMouse.click(MouseEvent.BUTTON1, activityContext).whenComplete(callbacks.bind((ignored, error) ->
		{
			if (error != null)
			{
				awaitingMenuResult = false;
				finishRejected("synthetic_click: " + rootMessage(error));
				return;
			}
			clickFinished = true;
			finishLeftClickIfReady();
		}));
		callbacks.schedule(() ->
		{
			if (awaitingMenuResult || !clickFinished)
			{
				awaitingMenuResult = false;
				finishRejected(observedMenuResult == null
					? "menu_event_timeout"
					: "synthetic_click_timeout");
			}
		}, CLICK_RESULT_TIMEOUT_MILLIS);
	}

	private synchronized void finishLeftClickIfReady()
	{
		if (!running.get() || !clickFinished || observedMenuResult == null) return;
		finishSuccess(new LinkedHashMap<>(observedMenuResult));
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

	static boolean shouldSettleSelectedWidgetTarget(
		boolean widgetSelected,
		boolean onlyCancel,
		int attempts)
	{
		return widgetSelected && onlyCancel &&
			attempts < MAX_SELECTED_WIDGET_MENU_SETTLE_RETRIES;
	}

	static Point randomPointInside(Shape shape, int canvasWidth, int canvasHeight)
	{
		return randomPointInside(
			shape, new java.awt.Rectangle(0, 0, canvasWidth, canvasHeight));
	}

	static Point randomPointInside(Shape shape, java.awt.Rectangle clipBounds)
	{
		java.awt.Rectangle bounds = boundedShape(shape, clipBounds);
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
		return firstPointInside(shape, clipBounds);
	}

	static Point firstPointInside(Shape shape, int canvasWidth, int canvasHeight)
	{
		return firstPointInside(
			shape, new java.awt.Rectangle(0, 0, canvasWidth, canvasHeight));
	}

	static Point firstPointInside(Shape shape, java.awt.Rectangle clipBounds)
	{
		java.awt.Rectangle bounds = boundedShape(shape, clipBounds);
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

	static java.awt.Rectangle viewportBounds(Client client)
	{
		java.awt.Rectangle canvas = new java.awt.Rectangle(
			0, 0, client.getCanvasWidth(), client.getCanvasHeight());
		java.awt.Rectangle viewport = new java.awt.Rectangle(
			client.getViewportXOffset(),
			client.getViewportYOffset(),
			client.getViewportWidth(),
			client.getViewportHeight());
		return viewport.intersection(canvas);
	}

	private static java.awt.Rectangle boundedShape(
		Shape shape,
		java.awt.Rectangle clipBounds)
	{
		if (shape == null || clipBounds == null || clipBounds.isEmpty())
		{
			return null;
		}
		java.awt.Rectangle bounds = shape.getBounds().intersection(clipBounds);
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
		Map<String, Object> receipt = new LinkedHashMap<>(receiptMetadata);
		receipt.put("status", status);
		receipt.put("result", result);
		Target current = target;
		receipt.put("action", current == null ? null : current.action);
		receipt.put("target", current == null ? null : current.value);
		receipt.put("dispatch", dispatch);
		receipt.put("click_count", (long) clickCount);
		receipt.put("cursor_retained", cursorRetained);
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

	private synchronized void finish(Map<String, Object> receipt)
	{
		if (!running.getAndSet(false))
		{
			return;
		}
		awaitingMenuResult = false;
		clickFinished = false;
		observedMenuResult = null;
		callbacks.cancelPending();
		reporter.accept("MENU_INTERACTION_COMPLETED status=" + receipt.get("status") +
			" result=" + receipt.get("result") + " clicks=" + receipt.get("click_count"));
		CompletableFuture<Map<String, Object>> completion = activeResult;
		activeResult = null;
		resolver = null;
		target = null;
		receiptMetadata = Collections.emptyMap();
		if (completion != null)
		{
			completion.completeAsync(() -> receipt);
		}
	}

	private void invokeCurrent(Runnable action)
	{
		clientThread.invoke(callbacks.bind(() -> {
			if (activityContext.isInputAllowed()) action.run();
			else finishRejected("action_cancelled");
		}));
	}


	@Override
	public synchronized void close()
	{
		closed = true;
		if (running.get())
		{
			finishRejected("input_closed");
		}
		else
		{
			callbacks.cancelPending();
		}
	}

	interface TargetResolver
	{
		Resolution resolve();
	}

	interface PreInteractionResolver
	{
		PreInteraction resolve();
	}

	static final class PreInteraction
	{
		private final long delayMillis;
		private final Map<String, Object> metadata;

		private PreInteraction(long delayMillis, Map<String, Object> metadata)
		{
			if (delayMillis < 0L || delayMillis > 60_000L)
			{
				throw new IllegalArgumentException(
					"Menu interaction delay must be between 0 and 60000ms");
			}
			this.delayMillis = delayMillis;
			this.metadata = metadata == null || metadata.isEmpty()
				? Collections.emptyMap()
				: Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
		}

		static PreInteraction none()
		{
			return new PreInteraction(0L, Collections.emptyMap());
		}

		static PreInteraction delayed(long delayMillis, Map<String, Object> metadata)
		{
			return new PreInteraction(delayMillis, metadata);
		}
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
