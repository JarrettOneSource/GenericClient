package com.genericclient;

import static com.genericclient.GenericClientWidgets.clickable;
import static com.genericclient.GenericClientWidgets.hasAction;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.Text;

@SuppressWarnings("deprecation")
final class GenericClientAutocastInput
{
	private static final int[] COMBAT_TABS =
	{
		WidgetInfo.FIXED_VIEWPORT_COMBAT_TAB.getId(),
		WidgetInfo.RESIZABLE_VIEWPORT_COMBAT_TAB.getId(),
		WidgetInfo.RESIZABLE_VIEWPORT_BOTTOM_LINE_COMBAT_ICON.getId()
	};
	private static final long UI_SETTLE_MILLIS = 250L;
	private static final int UI_POLL_ATTEMPTS = 20;
	private static final long UI_POLL_MILLIS = 50L;
	private static final int COMBAT_TAB_CLICK_ATTEMPTS = 3;

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientMenuInput menuInput;
	private final java.util.function.Consumer<String> reporter;
	private final AtomicBoolean running = new AtomicBoolean();
	private final AtomicLong generation = new AtomicLong();

	GenericClientAutocastInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientMenuInput menuInput,
		java.util.function.Consumer<String> reporter)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.menuInput = menuInput;
		this.reporter = reporter;
	}

	CompletableFuture<Map<String, Object>> set(String spellName, GenericClientActivityContext activityContext)
	{
		GenericClientSpellInput.Spell spell = GenericClientSpellInput.Spell.fromName(spellName);
		if (!spell.isAutocastable())
		{
			return CompletableFuture.completedFuture(receipt(
				"unsupported", "spell_not_autocastable", spell, new ArrayList<>()));
		}
		if (!running.compareAndSet(false, true))
		{
			return CompletableFuture.completedFuture(receipt(
				"rejected", "autocast_interaction_already_running", spell, new ArrayList<>()));
		}

		long request = generation.incrementAndGet();
		List<Map<String, Object>> steps = new ArrayList<>();
		reporter.accept("AUTOCAST_SELECTING spell=" + spell.getName());
		CompletableFuture<Map<String, Object>> result = ensureCombatTab(activityContext, steps, request)
			.thenCompose(ready -> selectFromCombatTab(spell, activityContext, steps, request, ready));
		return result.whenComplete((ignored, error) ->
		{
			if (generation.get() == request)
			{
				running.set(false);
			}
		});
	}

	private CompletableFuture<Map<String, Object>> selectFromCombatTab(
		GenericClientSpellInput.Spell spell,
		GenericClientActivityContext activityContext,
		List<Map<String, Object>> steps, long request,
		boolean tabReady)
	{
		if (!active(request)) return completedRejected("autocast_cancelled", spell, steps);
		if (!tabReady)
		{
			return completedRejected("combat_tab_did_not_open", spell, steps);
		}
		return clientRead(() -> selectedSpellMatches(spell)).thenCompose(alreadySelected ->
			alreadySelected
				? CompletableFuture.completedFuture(
					receipt("unchanged", "autocast_already_selected", spell, steps))
				: openAutocastMenu(spell, activityContext, steps, request));
	}

	private CompletableFuture<Map<String, Object>> openAutocastMenu(
		GenericClientSpellInput.Spell spell,
		GenericClientActivityContext activityContext,
		List<Map<String, Object>> steps, long request)
	{
		if (!active(request)) return completedRejected("autocast_cancelled", spell, steps);
		return click(this::resolveAutocastButton, activityContext, steps, request).thenCompose(opened ->
		{
			if (!active(request)) return completedRejected("autocast_cancelled", spell, steps);
			if (!opened)
			{
				return completedRejected("autocast_menu_not_opened", spell, steps);
			}
			return waitFor(() -> visibleWidget(InterfaceID.Autocast.UNIVERSE) != null, request)
				.thenCompose(visible -> selectAutocastSpell(spell, activityContext, steps, request, visible));
		});
	}

	private CompletableFuture<Map<String, Object>> selectAutocastSpell(
		GenericClientSpellInput.Spell spell,
		GenericClientActivityContext activityContext,
		List<Map<String, Object>> steps, long request,
		boolean menuVisible)
	{
		if (!active(request)) return completedRejected("autocast_cancelled", spell, steps);
		if (!menuVisible)
		{
			return completedRejected("autocast_menu_not_visible", spell, steps);
		}
		return click(() -> resolveAutocastSpell(spell), activityContext, steps, request).thenCompose(selected ->
		{
			if (!active(request)) return completedRejected("autocast_cancelled", spell, steps);
			if (!selected)
			{
				return completedRejected("autocast_spell_not_selected", spell, steps);
			}
			return waitFor(() -> selectedSpellMatches(spell), request)
				.thenApply(verified -> autocastReceipt(spell, steps, verified));
		});
	}

	private Map<String, Object> autocastReceipt(
		GenericClientSpellInput.Spell spell,
		List<Map<String, Object>> steps,
		boolean verified)
	{
		Map<String, Object> result = receipt(
			verified ? "set" : "rejected",
			verified ? "autocast_selected" : "autocast_selection_unverified",
			spell,
			steps);
		reporter.accept("AUTOCAST_COMPLETED spell=" + spell.getName() +
			" status=" + result.get("status") + " clicks=" + result.get("click_count"));
		return result;
	}

	boolean isRunning()
	{
		return running.get();
	}

	void cancel(String reason)
	{
		if (running.getAndSet(false))
		{
			generation.incrementAndGet();
			menuInput.cancel(reason);
			reporter.accept("AUTOCAST_CANCELLED reason=" + reason);
		}
	}

	private boolean active(long request)
	{
		return running.get() && generation.get() == request;
	}

	private CompletableFuture<Boolean> ensureCombatTab(
		GenericClientActivityContext activityContext,
		List<Map<String, Object>> steps,
		long request)
	{
		return ensureCombatTab(activityContext, steps, COMBAT_TAB_CLICK_ATTEMPTS, request);
	}

	private CompletableFuture<Boolean> ensureCombatTab(
		GenericClientActivityContext activityContext,
		List<Map<String, Object>> steps,
		int clicksRemaining,
		long request)
	{
		if (!active(request))
		{
			return CompletableFuture.completedFuture(false);
		}
		return clientRead(() -> visibleWidget(InterfaceID.CombatInterface.AUTOCAST_NORMAL) != null)
			.thenCompose(visible ->
			{
				if (visible)
				{
					return CompletableFuture.completedFuture(true);
				}
				if (clicksRemaining <= 0)
				{
					return CompletableFuture.completedFuture(false);
				}
				if (!active(request))
				{
					return CompletableFuture.completedFuture(false);
				}
				return click(this::resolveCombatTab, activityContext, steps, request).thenCompose(clicked ->
				{
					if (!clicked)
					{
						return ensureCombatTab(activityContext, steps, clicksRemaining - 1, request);
					}
					return waitFor(
						() -> visibleWidget(InterfaceID.CombatInterface.AUTOCAST_NORMAL) != null,
						request)
						.thenCompose(opened ->
						{
							if (opened)
							{
								return CompletableFuture.completedFuture(true);
							}
							reporter.accept("AUTOCAST_COMBAT_TAB_RETRY clicksRemaining=" +
								(clicksRemaining - 1));
							return ensureCombatTab(
								activityContext, steps, clicksRemaining - 1, request);
						});
				});
			});
	}

	private CompletableFuture<Boolean> click(
		GenericClientMenuInput.TargetResolver resolver,
		GenericClientActivityContext activityContext,
		List<Map<String, Object>> steps,
		long request)
	{
		if (!active(request))
		{
			return CompletableFuture.completedFuture(false);
		}
		return menuInput.interactDirect(resolver, activityContext).thenCompose(step ->
		{
			steps.add(step);
			if (!active(request) || !"dispatched".equals(step.get("status")))
			{
				return CompletableFuture.completedFuture(false);
			}
			CompletableFuture<Boolean> settled = new CompletableFuture<>();
			executor.schedule(
				() -> settled.complete(active(request)), UI_SETTLE_MILLIS, TimeUnit.MILLISECONDS);
			return settled;
		});
	}

	private GenericClientMenuInput.Resolution resolveCombatTab()
	{
		return GenericClientUiInput.resolveWidget(client, visibleWidget(COMBAT_TABS), "Combat Options", "combat_tab");
	}

	private GenericClientMenuInput.Resolution resolveAutocastButton()
	{
		return GenericClientUiInput.resolveWidget(client,
			visibleWidget(InterfaceID.CombatInterface.AUTOCAST_NORMAL),
			"Choose spell",
			"autocast_button");
	}

	private GenericClientMenuInput.Resolution resolveAutocastSpell(
		GenericClientSpellInput.Spell spell)
	{
		int spriteId = spell.getSpriteId();
		Widget spellWidget = findSpellWidget(
			visibleWidget(InterfaceID.Autocast.SPELLS),
			spriteId,
			spell.getLabel(),
			client.getCanvasWidth(),
			client.getCanvasHeight());
		if (spellWidget == null)
		{
			reporter.accept("AUTOCAST_WIDGET_MISSING spell=" + spell.getName() +
				" sprite=" + spriteId);
			return GenericClientMenuInput.Resolution.rejected(
				"autocast_spell_not_visible:" + spell.getName());
		}
		return GenericClientUiInput.resolveWidget(client, spellWidget, "Autocast", "autocast_spell:" + spell.getName());
	}

	private boolean selectedSpellMatches(GenericClientSpellInput.Spell spell)
	{
		return selectedSpellMatches(
			client.getVarbitValue(VarbitID.AUTOCAST_SET),
			visibleWidget(InterfaceID.CombatInterface.NORMAL_CONTAINER_TEXT1),
			visibleWidget(InterfaceID.CombatInterface.NORMAL_CONTAINER_GRAPHIC0),
			spell);
	}

	static boolean selectedSpellMatches(
		int autocastSet,
		Widget text,
		Widget graphic,
		GenericClientSpellInput.Spell spell)
	{
		if (autocastSet == 0)
		{
			return false;
		}
		return text != null &&
			normalized(text.getText()).contains(normalized(spell.getLabel())) ||
			graphic != null && graphic.getSpriteId() == spell.getSpriteId();
	}

	private CompletableFuture<Boolean> waitFor(BooleanSupplier condition, long request)
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		poll(condition, UI_POLL_ATTEMPTS, result, request);
		return result;
	}

	private void poll(
		BooleanSupplier condition,
		int attemptsRemaining,
		CompletableFuture<Boolean> result,
		long request)
	{
		executor.schedule(() -> clientThread.invoke(() ->
		{
			if (!active(request))
			{
				result.complete(false);
			}
			else if (condition.getAsBoolean())
			{
				result.complete(true);
			}
			else if (attemptsRemaining <= 1)
			{
				result.complete(false);
			}
			else
			{
				poll(condition, attemptsRemaining - 1, result, request);
			}
		}), UI_POLL_MILLIS, TimeUnit.MILLISECONDS);
	}

	private <T> CompletableFuture<T> clientRead(java.util.function.Supplier<T> reader)
	{
		CompletableFuture<T> result = new CompletableFuture<>();
		clientThread.invoke(() -> result.complete(reader.get()));
		return result;
	}

	private Widget visibleWidget(int... ids)
	{
		for (int id : ids)
		{
			Widget widget = client.getWidget(id);
			if (usableOnCanvas(widget, client.getCanvasWidth(), client.getCanvasHeight()))
			{
				return widget;
			}
		}
		return null;
	}

	static Widget findSpellWidget(
		Widget root,
		int spriteId,
		String label,
		int canvasWidth,
		int canvasHeight)
	{
		Rectangle panel = root == null ? null : root.getBounds();
		List<Widget> widgets = GenericClientWidgets.descendants(root, 256);
		for (Widget widget : widgets)
		{
			if (withinPanel(widget, panel, canvasWidth, canvasHeight) &&
				spriteId >= 0 && widget.getSpriteId() == spriteId &&
				hasAction(widget, "Autocast"))
			{
				return widget;
			}
		}
		for (Widget widget : widgets)
		{
			if (withinPanel(widget, panel, canvasWidth, canvasHeight) &&
				matchesLabel(widget, label) && hasAction(widget, "Autocast"))
			{
				return widget;
			}
		}
		for (Widget widget : widgets)
		{
			if (withinPanel(widget, panel, canvasWidth, canvasHeight) &&
				spriteId >= 0 && widget.getSpriteId() == spriteId)
			{
				return widget;
			}
		}
		for (Widget widget : widgets)
		{
			if (withinPanel(widget, panel, canvasWidth, canvasHeight) &&
				matchesLabel(widget, label))
			{
				return widget;
			}
		}
		return null;
	}

	private static boolean withinPanel(
		Widget widget,
		Rectangle panel,
		int canvasWidth,
		int canvasHeight)
	{
		if (!usableOnCanvas(widget, canvasWidth, canvasHeight) || panel == null)
		{
			return false;
		}
		Rectangle bounds = widget.getBounds();
		Point center = new Point(
			bounds.x + bounds.width / 2,
			bounds.y + bounds.height / 2);
		return panel.contains(center);
	}

	static boolean usableOnCanvas(Widget widget, int canvasWidth, int canvasHeight)
	{
		if (!clickable(widget))
		{
			return false;
		}
		Rectangle bounds = widget.getBounds();
		int centerX = bounds.x + bounds.width / 2;
		int centerY = bounds.y + bounds.height / 2;
		return bounds.x >= 0 && bounds.y >= 0 &&
			centerX < canvasWidth && centerY < canvasHeight;
	}

	private static boolean matchesLabel(Widget widget, String label)
	{
		String text = normalized(Objects.toString(widget.getName(), "") + " " +
			Objects.toString(widget.getText(), ""));
		return text.contains(normalized(label));
	}

	private static String normalized(String value)
	{
		return Text.removeTags(Objects.toString(value, ""))
			.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	private static CompletableFuture<Map<String, Object>> completedRejected(
		String result,
		GenericClientSpellInput.Spell spell,
		List<Map<String, Object>> steps)
	{
		return CompletableFuture.completedFuture(receipt("rejected", result, spell, steps));
	}

	private static Map<String, Object> receipt(
		String status,
		String result,
		GenericClientSpellInput.Spell spell,
		List<Map<String, Object>> steps)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", status);
		receipt.put("result", result);
		receipt.put("spell", spell.getName());
		receipt.put("autocastable", spell.isAutocastable());
		receipt.put("steps", new ArrayList<>(steps));
		long clicks = 0L;
		for (Map<String, Object> step : steps)
		{
			Object count = step.get("click_count");
			if (count instanceof Number)
			{
				clicks += ((Number) count).longValue();
			}
		}
		receipt.put("click_count", clicks);
		return receipt;
	}
}
