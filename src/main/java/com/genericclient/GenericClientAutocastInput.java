package com.genericclient;

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
import java.util.function.BooleanSupplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
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

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientMenuInput menuInput;
	private final java.util.function.Consumer<String> reporter;

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

		List<Map<String, Object>> steps = new ArrayList<>();
		reporter.accept("AUTOCAST_SELECTING spell=" + spell.getName());
		return ensureCombatTab(activityContext, steps).thenCompose(tabReady ->
		{
			if (!tabReady)
			{
				return completedRejected("combat_tab_did_not_open", spell, steps);
			}
			return clientRead(() -> selectedSpellMatches(spell)).thenCompose(alreadySelected ->
			{
				if (alreadySelected)
				{
					return CompletableFuture.completedFuture(
						receipt("unchanged", "autocast_already_selected", spell, steps));
				}
				return click(this::resolveAutocastButton, activityContext, steps).thenCompose(opened ->
				{
					if (!opened)
					{
						return completedRejected("autocast_menu_not_opened", spell, steps);
					}
					return waitFor(() -> visibleWidget(InterfaceID.Autocast.UNIVERSE) != null)
						.thenCompose(menuVisible ->
						{
							if (!menuVisible)
							{
								return completedRejected("autocast_menu_not_visible", spell, steps);
							}
							return click(
								() -> resolveAutocastSpell(spell),
								activityContext,
								steps).thenCompose(selected ->
							{
								if (!selected)
								{
									return completedRejected("autocast_spell_not_selected", spell, steps);
								}
								return waitFor(() -> client.getVarbitValue(VarbitID.AUTOCAST_SET) != 0)
									.thenApply(verified ->
									{
										Map<String, Object> result = receipt(
											verified ? "set" : "rejected",
											verified ? "autocast_selected" : "autocast_selection_unverified",
											spell,
											steps);
										reporter.accept("AUTOCAST_COMPLETED spell=" + spell.getName() +
											" status=" + result.get("status") +
											" clicks=" + result.get("click_count"));
										return result;
									});
							});
						});
				});
			});
		});
	}

	private CompletableFuture<Boolean> ensureCombatTab(
		GenericClientActivityContext activityContext,
		List<Map<String, Object>> steps)
	{
		return clientRead(() -> visibleWidget(InterfaceID.CombatInterface.AUTOCAST_NORMAL) != null)
			.thenCompose(visible -> visible
				? CompletableFuture.completedFuture(true)
				: click(this::resolveCombatTab, activityContext, steps).thenCompose(clicked -> clicked
					? waitFor(() -> visibleWidget(InterfaceID.CombatInterface.AUTOCAST_NORMAL) != null)
					: CompletableFuture.completedFuture(false)));
	}

	private CompletableFuture<Boolean> click(
		GenericClientMenuInput.TargetResolver resolver,
		GenericClientActivityContext activityContext,
		List<Map<String, Object>> steps)
	{
		return menuInput.interactDirect(resolver, activityContext).thenCompose(step ->
		{
			steps.add(step);
			if (!"dispatched".equals(step.get("status")))
			{
				return CompletableFuture.completedFuture(false);
			}
			CompletableFuture<Boolean> settled = new CompletableFuture<>();
			executor.schedule(() -> settled.complete(true), UI_SETTLE_MILLIS, TimeUnit.MILLISECONDS);
			return settled;
		});
	}

	private GenericClientMenuInput.Resolution resolveCombatTab()
	{
		return resolveWidget(visibleWidget(COMBAT_TABS), "Combat Options", "combat_tab");
	}

	private GenericClientMenuInput.Resolution resolveAutocastButton()
	{
		return resolveWidget(
			visibleWidget(InterfaceID.CombatInterface.AUTOCAST_NORMAL),
			"Choose spell",
			"autocast_button");
	}

	private GenericClientMenuInput.Resolution resolveAutocastSpell(
		GenericClientSpellInput.Spell spell)
	{
		int spriteId = spell.getSpriteId();
		Widget spellWidget = findSpellWidget(
			visibleWidget(InterfaceID.Autocast.SPELLS), spriteId, spell.getLabel());
		if (spellWidget == null)
		{
			reporter.accept("AUTOCAST_WIDGET_MISSING spell=" + spell.getName() +
				" sprite=" + spriteId);
			return GenericClientMenuInput.Resolution.rejected(
				"autocast_spell_not_visible:" + spell.getName());
		}
		return resolveWidget(spellWidget, "Autocast", "autocast_spell:" + spell.getName());
	}

	private GenericClientMenuInput.Resolution resolveWidget(
		Widget widget,
		String action,
		String description)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		if (widget == null)
		{
			return GenericClientMenuInput.Resolution.rejected(description + "_not_visible");
		}
		Point point = GenericClientMenuInput.randomPointInside(
			widget.getBounds(), client.getCanvasWidth(), client.getCanvasHeight());
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
		value.put("bounds", rectangleMap(widget.getBounds()));
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			action,
			description,
			value,
			entry -> false));
	}

	private boolean selectedSpellMatches(GenericClientSpellInput.Spell spell)
	{
		if (client.getVarbitValue(VarbitID.AUTOCAST_SET) == 0)
		{
			return false;
		}
		Widget text = visibleWidget(InterfaceID.CombatInterface.NORMAL_CONTAINER_TEXT1);
		return text != null && normalized(text.getText()).contains(normalized(spell.getLabel()));
	}

	private CompletableFuture<Boolean> waitFor(BooleanSupplier condition)
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		poll(condition, UI_POLL_ATTEMPTS, result);
		return result;
	}

	private void poll(
		BooleanSupplier condition,
		int attemptsRemaining,
		CompletableFuture<Boolean> result)
	{
		executor.schedule(() -> clientThread.invoke(() ->
		{
			if (condition.getAsBoolean())
			{
				result.complete(true);
			}
			else if (attemptsRemaining <= 1)
			{
				result.complete(false);
			}
			else
			{
				poll(condition, attemptsRemaining - 1, result);
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
			if (clickable(widget))
			{
				return widget;
			}
		}
		return null;
	}

	static Widget findSpellWidget(Widget root, int spriteId, String label)
	{
		List<Widget> widgets = new ArrayList<>();
		collect(root, widgets);
		for (Widget widget : widgets)
		{
			if (clickable(widget) && spriteId >= 0 && widget.getSpriteId() == spriteId &&
				hasAction(widget, "Autocast"))
			{
				return widget;
			}
		}
		for (Widget widget : widgets)
		{
			if (clickable(widget) && matchesLabel(widget, label) && hasAction(widget, "Autocast"))
			{
				return widget;
			}
		}
		for (Widget widget : widgets)
		{
			if (clickable(widget) && spriteId >= 0 && widget.getSpriteId() == spriteId)
			{
				return widget;
			}
		}
		for (Widget widget : widgets)
		{
			if (clickable(widget) && matchesLabel(widget, label))
			{
				return widget;
			}
		}
		return null;
	}

	private static void collect(Widget widget, List<Widget> result)
	{
		if (widget == null || result.size() >= 256)
		{
			return;
		}
		result.add(widget);
		collect(widget.getStaticChildren(), result);
		collect(widget.getDynamicChildren(), result);
		collect(widget.getNestedChildren(), result);
	}

	private static void collect(Widget[] children, List<Widget> result)
	{
		if (children == null)
		{
			return;
		}
		for (Widget child : children)
		{
			collect(child, result);
		}
	}

	private static boolean matchesLabel(Widget widget, String label)
	{
		String text = normalized(Objects.toString(widget.getName(), "") + " " +
			Objects.toString(widget.getText(), ""));
		return text.contains(normalized(label));
	}

	private static boolean hasAction(Widget widget, String action)
	{
		String[] actions = widget.getActions();
		if (actions == null)
		{
			return false;
		}
		for (String candidate : actions)
		{
			if (candidate != null && candidate.equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean clickable(Widget widget)
	{
		if (widget == null || widget.isHidden() || widget.isSelfHidden())
		{
			return false;
		}
		Rectangle bounds = widget.getBounds();
		return bounds != null && bounds.width > 0 && bounds.height > 0;
	}

	private static String normalized(String value)
	{
		return Text.removeTags(Objects.toString(value, ""))
			.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	private static Map<String, Object> rectangleMap(Rectangle bounds)
	{
		if (bounds == null)
		{
			return null;
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("x", (long) bounds.x);
		value.put("y", (long) bounds.y);
		value.put("width", (long) bounds.width);
		value.put("height", (long) bounds.height);
		return value;
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
