package com.genericclient;

import java.awt.Canvas;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.callback.ClientThread;

final class GenericClientNpcInput implements AutoCloseable
{
	private static final long HOVER_SETTLE_MILLIS = 250L;
	private static final long CONTEXT_MENU_SETTLE_MILLIS = 150L;
	private static final long CLICK_RESULT_TIMEOUT_MILLIS = 2_500L;
	private static final int CONTEXT_MENU_ENTRY_HEIGHT = 15;

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientSyntheticMouse syntheticMouse;
	private final GenericClientBehaviorController behavior;
	private final Consumer<String> reporter;
	private final AtomicBoolean running = new AtomicBoolean();
	private final List<ScheduledFuture<?>> pending = new CopyOnWriteArrayList<>();

	private volatile CompletableFuture<Map<String, Object>> activeResult;
	private volatile NPC targetNpc;
	private volatile Map<String, Object> targetReceipt = Collections.emptyMap();
	private volatile java.awt.Point targetPoint;
	private volatile String targetName;
	private volatile String targetAction;
	private volatile int within;
	private volatile boolean breaksEnabled;
	private volatile boolean awaitingMenuResult;
	private volatile int expectedIdentifier;
	private volatile int expectedWorldViewId;
	private volatile int clickCount;
	private volatile String dispatch;
	private volatile Map<String, Object> behaviorBefore = Collections.emptyMap();
	private volatile Map<String, Object> behaviorAfter = Collections.emptyMap();
	private volatile boolean closed;

	GenericClientNpcInput(
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

	CompletableFuture<Map<String, Object>> interact(
		String name,
		String action,
		int within,
		boolean breaksEnabled)
	{
		String cleanName = requireText(name, "NPC name");
		String cleanAction = requireText(action, "NPC action");
		if (within < 1 || within > 32)
		{
			throw new IllegalArgumentException("NPC interaction radius must be between 1 and 32 tiles");
		}

		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		if (closed)
		{
			result.complete(immediateRejected(cleanAction, "input_closed"));
			return result;
		}
		if (!running.compareAndSet(false, true))
		{
			result.complete(immediateRejected(cleanAction, "interaction_already_running"));
			return result;
		}

		this.activeResult = result;
		this.targetNpc = null;
		this.targetReceipt = Collections.emptyMap();
		this.targetPoint = null;
		this.targetName = cleanName;
		this.targetAction = cleanAction;
		this.within = within;
		this.breaksEnabled = breaksEnabled;
		this.awaitingMenuResult = false;
		this.clickCount = 0;
		this.dispatch = null;
		this.behaviorBefore = Collections.emptyMap();
		this.behaviorAfter = Collections.emptyMap();

		reporter.accept("NPC_INTERACTION_SELECTING name=" + cleanName +
			" action=" + cleanAction + " within=" + within);
		behavior.beforeAction(breaksEnabled).whenComplete((before, error) ->
		{
			if (error != null)
			{
				finishRejected("behavior_before: " + rootMessage(error));
				return;
			}
			behaviorBefore = before;
			clientThread.invoke(this::selectTargetOnClientThread);
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
		if (!awaitingMenuResult ||
			event.getId() != expectedIdentifier ||
			!targetAction.equalsIgnoreCase(event.getMenuOption()) ||
			event.getMenuEntry().getWorldViewId() != expectedWorldViewId)
		{
			return;
		}

		awaitingMenuResult = false;
		behavior.afterAction(breaksEnabled).whenComplete((after, error) ->
		{
			if (error != null)
			{
				finishRejected("behavior_after: " + rootMessage(error));
				return;
			}
			behaviorAfter = after;
			finishSuccess(event);
		});
	}

	private void selectTargetOnClientThread()
	{
		if (!running.get())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			finishRejected("client_not_logged_in");
			return;
		}
		Player player = client.getLocalPlayer();
		if (player == null || player.getWorldLocation() == null)
		{
			finishRejected("local_player_unavailable");
			return;
		}

		WorldView worldView = player.getWorldView();
		NPC nearest = null;
		int nearestDistance = Integer.MAX_VALUE;
		for (NPC npc : worldView.npcs())
		{
			if (npc == null || npc.getWorldLocation() == null ||
				!targetName.equalsIgnoreCase(Objects.toString(npc.getName(), "")) ||
				!hasAction(npc, targetAction) ||
				("Attack".equalsIgnoreCase(targetAction) &&
					npc.getInteracting() != null && npc.getInteracting() != player))
			{
				continue;
			}
			int distance = player.getWorldLocation().distanceTo(npc.getWorldLocation());
			if (distance <= within && distance < nearestDistance)
			{
				nearest = npc;
				nearestDistance = distance;
			}
		}
		if (nearest == null)
		{
			finishRejected("matching_npc_not_found");
			return;
		}

		Shape shape = nearest.getConvexHull();
		if (shape == null)
		{
			shape = nearest.getCanvasTilePoly();
		}
		java.awt.Point point = randomPointInside(shape);
		if (point == null)
		{
			finishRejected("npc_not_visible");
			return;
		}

		targetNpc = nearest;
		targetReceipt = npcMap(nearest);
		targetPoint = point;
		reporter.accept("NPC_INTERACTION_TARGET name=" + nearest.getName() +
			" id=" + nearest.getId() + " index=" + nearest.getIndex() +
			" distance=" + nearestDistance + " canvas=" + point.x + "," + point.y);
		Canvas canvas = client.getCanvas();
		if (canvas == null || !canvas.isShowing())
		{
			finishRejected("canvas_not_showing");
			return;
		}
		syntheticMouse.move(point).whenComplete((ignored, error) ->
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
		if (!running.get())
		{
			return;
		}
		if (client.isMenuOpen())
		{
			finishRejected("context_menu_already_open");
			return;
		}
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		if (Math.abs(mouse.getX() - targetPoint.x) > 20 || Math.abs(mouse.getY() - targetPoint.y) > 20)
		{
			finishRejected("mouse_missed_target");
			return;
		}

		MenuEntry[] entries = client.getMenu().getMenuEntries();
		int desiredIndex = findNpcEntryIndex(entries, targetNpc.getIndex(), targetAction);
		if (desiredIndex < 0)
		{
			finishRejected("hover_has_no_matching_action");
			return;
		}
		if (desiredIndex == entries.length - 1)
		{
			dispatchLeftClick(entries[desiredIndex], "left_click");
			return;
		}
		openContextMenu();
	}

	private void openContextMenu()
	{
		dispatch = "context_menu";
		clickCount++;
		reporter.accept("NPC_CONTEXT_OPEN name=" + targetNpc.getName() + " action=" + targetAction);
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
		if (!running.get() || !client.isMenuOpen())
		{
			finishRejected("context_menu_did_not_open");
			return;
		}
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		int index = findNpcEntryIndex(entries, targetNpc.getIndex(), targetAction);
		if (index < 0)
		{
			finishRejected("context_menu_has_no_matching_action");
			return;
		}
		int headerHeight = Math.max(0,
			client.getMenu().getMenuHeight() - entries.length * CONTEXT_MENU_ENTRY_HEIGHT);
		int rowFromTop = entries.length - 1 - index;
		java.awt.Point destination = new java.awt.Point(
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
		if (!running.get() || !client.isMenuOpen())
		{
			finishRejected("context_menu_closed");
			return;
		}
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		int index = findNpcEntryIndex(entries, targetNpc.getIndex(), targetAction);
		if (index < 0)
		{
			finishRejected("context_menu_has_no_matching_action");
			return;
		}
		dispatchLeftClick(entries[index], "context_menu");
	}

	private void dispatchLeftClick(MenuEntry entry, String dispatch)
	{
		this.dispatch = dispatch;
		this.expectedIdentifier = entry.getIdentifier();
		this.expectedWorldViewId = entry.getWorldViewId();
		this.awaitingMenuResult = true;
		this.clickCount++;
		reporter.accept("NPC_INTERACTION_DISPATCH name=" + targetNpc.getName() +
			" action=" + targetAction + " dispatch=" + dispatch +
			" identifier=" + expectedIdentifier + " worldView=" + expectedWorldViewId);
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

	static int findNpcEntryIndex(MenuEntry[] entries, int npcIndex, String action)
	{
		for (int index = entries.length - 1; index >= 0; index--)
		{
			MenuEntry entry = entries[index];
			NPC resolvedNpc = entry.getNpc();
			boolean sameNpc = resolvedNpc == null
				? entry.getIdentifier() == npcIndex
				: resolvedNpc.getIndex() == npcIndex;
			if (sameNpc && action.equalsIgnoreCase(entry.getOption()))
			{
				return index;
			}
		}
		return -1;
	}

	private java.awt.Point randomPointInside(Shape shape)
	{
		if (shape == null)
		{
			return null;
		}
		Rectangle bounds = shape.getBounds();
		for (int attempt = 0; attempt < 20; attempt++)
		{
			int x = ThreadLocalRandom.current().nextInt(bounds.x, bounds.x + Math.max(1, bounds.width));
			int y = ThreadLocalRandom.current().nextInt(bounds.y, bounds.y + Math.max(1, bounds.height));
			if (shape.contains(x, y) && x >= 0 && y >= 0 &&
				x < client.getCanvasWidth() && y < client.getCanvasHeight())
			{
				return new java.awt.Point(x, y);
			}
		}
		return null;
	}

	private static boolean hasAction(NPC npc, String action)
	{
		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null)
		{
			composition = npc.getComposition();
		}
		if (composition == null || composition.getActions() == null)
		{
			return false;
		}
		for (String candidate : composition.getActions())
		{
			if (candidate != null && candidate.equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}

	private void finishSuccess(MenuOptionClicked event)
	{
		Map<String, Object> receipt = baseReceipt("dispatched", "menu_action_executed");
		receipt.put("menu_action", event.getMenuAction().name().toLowerCase(java.util.Locale.ROOT));
		receipt.put("menu_target", event.getMenuTarget());
		finish(receipt);
	}

	private void finishRejected(String reason)
	{
		finish(rejected(reason));
	}

	private Map<String, Object> rejected(String reason)
	{
		return baseReceipt("rejected", reason);
	}

	private static Map<String, Object> immediateRejected(String action, String reason)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "rejected");
		receipt.put("result", reason);
		receipt.put("action", action);
		receipt.put("dispatch", null);
		receipt.put("click_count", 0L);
		return receipt;
	}

	private Map<String, Object> baseReceipt(String status, String result)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", status);
		receipt.put("result", result);
		receipt.put("action", targetAction);
		receipt.put("dispatch", dispatch);
		receipt.put("click_count", (long) clickCount);
		if (!targetReceipt.isEmpty())
		{
			receipt.put("npc", targetReceipt);
		}
		receipt.put("behavior_before", behaviorBefore);
		if (!behaviorAfter.isEmpty())
		{
			receipt.put("behavior_after", behaviorAfter);
		}
		return receipt;
	}

	private static Map<String, Object> npcMap(NPC npc)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("index", (long) npc.getIndex());
		value.put("id", (long) npc.getId());
		value.put("name", npc.getName());
		if (npc.getWorldLocation() != null)
		{
			Map<String, Object> world = new LinkedHashMap<>();
			world.put("x", (long) npc.getWorldLocation().getX());
			world.put("y", (long) npc.getWorldLocation().getY());
			world.put("plane", (long) npc.getWorldLocation().getPlane());
			value.put("world", Collections.unmodifiableMap(world));
		}
		return Collections.unmodifiableMap(value);
	}

	private void finish(Map<String, Object> receipt)
	{
		if (!running.getAndSet(false))
		{
			return;
		}
		awaitingMenuResult = false;
		cancelPending();
		reporter.accept("NPC_INTERACTION_COMPLETED status=" + receipt.get("status") +
			" result=" + receipt.get("result") + " clicks=" + receipt.get("click_count"));
		CompletableFuture<Map<String, Object>> completion = activeResult;
		activeResult = null;
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

	private static String requireText(String value, String label)
	{
		if (value == null || value.trim().isEmpty())
		{
			throw new IllegalArgumentException(label + " cannot be empty");
		}
		return value.trim();
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
}
