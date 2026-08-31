package com.genericclient;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;

@SuppressWarnings("deprecation")
final class GenericClientPrayerInput
{
	private static final int[] PRAYER_TABS =
	{
		WidgetInfo.FIXED_VIEWPORT_PRAYER_TAB.getId(),
		WidgetInfo.RESIZABLE_VIEWPORT_PRAYER_TAB.getId(),
		WidgetInfo.RESIZABLE_VIEWPORT_BOTTOM_LINE_PRAYER_TAB.getId()
	};
	private static final int POLL_ATTEMPTS = 20;
	private static final long POLL_MILLIS = 50L;

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientMenuInput menuInput;
	private final java.util.function.Consumer<String> reporter;

	GenericClientPrayerInput(
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

	CompletableFuture<Map<String, Object>> set(
		String name,
		boolean enabled,
		GenericClientActivityContext activityContext)
	{
		Setting prayer = Setting.fromName(name);
		List<Map<String, Object>> steps = new ArrayList<>();
		reporter.accept("PRAYER_SETTING prayer=" + prayer.id + " enabled=" + enabled);
		return clientRead(() -> preflight(prayer, enabled)).thenCompose(rejection ->
		{
			if (rejection != null)
			{
				return CompletableFuture.completedFuture(
					receipt("rejected", rejection, prayer, enabled, steps));
			}
			if (active(prayer) == enabled)
			{
				return CompletableFuture.completedFuture(
					receipt("unchanged", "prayer_already_set", prayer, enabled, steps));
			}
			return ensurePrayerTab(activityContext, steps).thenCompose(ready ->
			{
				if (!ready)
				{
					return CompletableFuture.completedFuture(
						receipt("rejected", "prayer_tab_did_not_open", prayer, enabled, steps));
				}
				return menuInput.interactDirect(
					() -> resolvePrayer(prayer, enabled), activityContext).thenCompose(clicked ->
				{
					steps.add(clicked);
					if (!"dispatched".equals(clicked.get("status")))
					{
						return CompletableFuture.completedFuture(
							receipt("rejected", "prayer_click_failed", prayer, enabled, steps));
					}
					return waitFor(() -> active(prayer) == enabled).thenApply(verified ->
					{
						Map<String, Object> result = receipt(
							verified ? "set" : "rejected",
							verified ? "prayer_state_verified" : "prayer_state_unverified",
							prayer,
							enabled,
							steps);
						reporter.accept("PRAYER_COMPLETED prayer=" + prayer.id +
							" enabled=" + enabled + " status=" + result.get("status"));
						return result;
					});
				});
			});
		});
	}

	private String preflight(Setting prayer, boolean enabled)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return "client_not_logged_in";
		}
		if (client.getRealSkillLevel(Skill.PRAYER) < prayer.level)
		{
			return "prayer_level_too_low";
		}
		return enabled && client.getBoostedSkillLevel(Skill.PRAYER) < 1
			? "prayer_points_depleted"
			: null;
	}

	private boolean active(Setting prayer)
	{
		return client.getVarbitValue(prayer.varbit) != 0;
	}

	private CompletableFuture<Boolean> ensurePrayerTab(
		GenericClientActivityContext activityContext,
		List<Map<String, Object>> steps)
	{
		return clientRead(() -> findPrayerWidget(Setting.PROTECT_FROM_MISSILES, true) != null)
			.thenCompose(visible ->
			{
				if (visible)
				{
					return CompletableFuture.completedFuture(true);
				}
				return menuInput.interactDirect(this::resolvePrayerTab, activityContext)
					.thenCompose(clicked ->
					{
						steps.add(clicked);
						if (!"dispatched".equals(clicked.get("status")))
						{
							return CompletableFuture.completedFuture(false);
						}
						return waitFor(
							() -> findPrayerWidget(Setting.PROTECT_FROM_MISSILES, true) != null);
					});
			});
	}

	private GenericClientMenuInput.Resolution resolvePrayerTab()
	{
		return resolveWidget(visibleWidget(PRAYER_TABS), "Prayer", "prayer_tab");
	}

	private GenericClientMenuInput.Resolution resolvePrayer(Setting prayer, boolean enabled)
	{
		return resolveWidget(
			findPrayerWidget(prayer, enabled),
			enabled ? "Activate" : "Deactivate",
			"prayer:" + prayer.id);
	}

	private Widget findPrayerWidget(Setting prayer, boolean enabled)
	{
		Widget root = visibleWidget(InterfaceID.Prayerbook.CONTAINER);
		List<Widget> widgets = new ArrayList<>();
		collect(root, widgets);
		String action = enabled ? "Activate" : "Deactivate";
		for (Widget widget : widgets)
		{
			if (clickable(widget) && prayer.matchesSprite(widget.getSpriteId()) &&
				hasAction(widget, action))
			{
				return widget;
			}
		}
		for (Widget widget : widgets)
		{
			if (clickable(widget) && prayer.matchesSprite(widget.getSpriteId()))
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
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			action,
			description,
			value,
			entry -> false));
	}

	private CompletableFuture<Boolean> waitFor(BooleanSupplier condition)
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		poll(condition, POLL_ATTEMPTS, result);
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
		}), POLL_MILLIS, TimeUnit.MILLISECONDS);
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

	private static Map<String, Object> receipt(
		String status,
		String result,
		Setting prayer,
		boolean enabled,
		List<Map<String, Object>> steps)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", status);
		receipt.put("result", result);
		receipt.put("prayer", prayer.id);
		receipt.put("enabled", enabled);
		receipt.put("steps", steps);
		long clicks = 0;
		for (Map<String, Object> step : steps)
		{
			Object value = step.get("click_count");
			if (value instanceof Number)
			{
				clicks += ((Number) value).longValue();
			}
		}
		receipt.put("click_count", clicks);
		return receipt;
	}

	private enum Setting
	{
		PROTECT_FROM_MAGIC(
			"protect_from_magic", "Protect from Magic", 37,
			SpriteID.Prayeron.PROTECT_FROM_MAGIC,
			SpriteID.Prayeroff.PROTECT_FROM_MAGIC_DISABLED,
			VarbitID.PRAYER_PROTECTFROMMAGIC),
		PROTECT_FROM_MISSILES(
			"protect_from_missiles", "Protect from Missiles", 40,
			SpriteID.Prayeron.PROTECT_FROM_MISSILES,
			SpriteID.Prayeroff.PROTECT_FROM_MISSILES_DISABLED,
			VarbitID.PRAYER_PROTECTFROMMISSILES),
		PROTECT_FROM_MELEE(
			"protect_from_melee", "Protect from Melee", 43,
			SpriteID.Prayeron.PROTECT_FROM_MELEE,
			SpriteID.Prayeroff.PROTECT_FROM_MELEE_DISABLED,
			VarbitID.PRAYER_PROTECTFROMMELEE);

		private final String id;
		private final String label;
		private final int level;
		private final int activeSprite;
		private final int disabledSprite;
		private final int varbit;

		Setting(
			String id,
			String label,
			int level,
			int activeSprite,
			int disabledSprite,
			int varbit)
		{
			this.id = id;
			this.label = label;
			this.level = level;
			this.activeSprite = activeSprite;
			this.disabledSprite = disabledSprite;
			this.varbit = varbit;
		}

		private boolean matchesSprite(int sprite)
		{
			return sprite == activeSprite || sprite == disabledSprite;
		}

		private static Setting fromName(String value)
		{
			if (value == null)
			{
				throw new IllegalArgumentException("prayer.set requires prayer");
			}
			String normalized = value.trim().toLowerCase(Locale.ROOT)
				.replace('-', '_').replace(' ', '_');
			for (Setting setting : values())
			{
				if (setting.id.equals(normalized) ||
					setting.label.toLowerCase(Locale.ROOT).replace(' ', '_').equals(normalized))
				{
					return setting;
				}
			}
			throw new IllegalArgumentException("Unsupported prayer: " + value);
		}
	}
}
