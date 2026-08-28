package com.genericclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.runelite.api.coords.WorldPoint;

final class GenericClientEmergencyController
{
	private static final int FORCED_HEAL_PERCENT = 30;

	private final FoodAction foodAction;
	private final EscapeAction escapeAction;
	private final Supplier<CompletableFuture<Map<String, Object>>> endBreakAction;
	private final Function<String, CompletableFuture<?>> stopAction;
	private final Consumer<String> reporter;
	private final AtomicBoolean recovering = new AtomicBoolean();

	private volatile Guard guard;
	private volatile String lastEvent = "unarmed";
	private volatile int lastHitpoints = -1;

	GenericClientEmergencyController(
		FoodAction foodAction,
		EscapeAction escapeAction,
		Supplier<CompletableFuture<Map<String, Object>>> endBreakAction,
		Function<String, CompletableFuture<?>> stopAction,
		Consumer<String> reporter)
	{
		this.foodAction = foodAction;
		this.escapeAction = escapeAction;
		this.endBreakAction = endBreakAction;
		this.stopAction = stopAction;
		this.reporter = reporter;
	}

	CompletableFuture<Map<String, Object>> configure(
		int minimumHitpoints,
		List<Consumable> consumables,
		Escape escape,
		boolean continueAfterConsumable,
		boolean allowOverheal)
	{
		if (minimumHitpoints < 1 || minimumHitpoints > 98)
		{
			throw new IllegalArgumentException("Emergency hitpoints must be between 1 and 98");
		}
		if (consumables == null || consumables.isEmpty() || consumables.size() > 8)
		{
			throw new IllegalArgumentException("Emergency guard requires 1-8 approved consumables");
		}
		guard = new Guard(
			minimumHitpoints, consumables, escape, continueAfterConsumable, allowOverheal);
		lastEvent = "armed";
		reporter.accept("EMERGENCY_GUARD_ARMED hitpoints=" + minimumHitpoints +
			" consumables=" + consumables.size() +
			" continueAfterConsumable=" + continueAfterConsumable +
			" allowOverheal=" + allowOverheal);
		return CompletableFuture.completedFuture(receipt("complete", "emergency_guard_armed"));
	}

	CompletableFuture<Map<String, Object>> clear()
	{
		guard = null;
		lastEvent = "unarmed";
		reporter.accept("EMERGENCY_GUARD_CLEARED");
		return CompletableFuture.completedFuture(receipt("complete", "emergency_guard_cleared"));
	}

	void publishGameTick(GenericClientSnapshot snapshot)
	{
		Guard current = guard;
		if (current == null || snapshot == null || recovering.get())
		{
			return;
		}
		int hitpoints = snapshot.getCurrentHitpoints();
		int maximumHitpoints = snapshot.getMaximumHitpoints();
		int missingHitpoints = Math.max(0, maximumHitpoints - hitpoints);
		lastHitpoints = hitpoints;
		if (hitpoints <= 0)
		{
			lastEvent = "death_observed";
			reporter.accept("EMERGENCY_DEATH_OBSERVED");
			return;
		}
		boolean forcedHealReady = maximumHitpoints > 0 &&
			(long) hitpoints * 100L < (long) maximumHitpoints * FORCED_HEAL_PERCENT;
		boolean continueAfterConsumable = current.continueAfterConsumable || forcedHealReady;
		boolean exactHealReady = continueAfterConsumable &&
			current.hasConsumableThatFits(missingHitpoints);
		boolean hardFloorReached = hitpoints <= current.minimumHitpoints;
		if ((!hardFloorReached && !exactHealReady && !forcedHealReady) ||
			!recovering.compareAndSet(false, true))
		{
			return;
		}

		lastEvent = "triggered";
		reporter.accept("EMERGENCY_TRIGGERED hitpoints=" + hitpoints +
			" threshold=" + current.minimumHitpoints +
			" missing=" + missingHitpoints + " exactHealReady=" + exactHealReady +
			" forcedHealReady=" + forcedHealReady +
			" allowOverheal=" + current.allowOverheal);
		CompletableFuture<Map<String, Object>> recovery = continueAfterConsumable
			? consumeAndContinue(
				current,
				missingHitpoints,
				forcedHealReady ||
					(hardFloorReached && current.allowOverheal && !exactHealReady),
				forcedHealReady || hardFloorReached)
			: stopConsumeAndEscape(current);
		recovery
			.whenComplete((receipt, error) ->
			{
				if (error != null)
				{
					lastEvent = "recovery_failed";
					reporter.accept("EMERGENCY_RECOVERY_FAILED message=" + rootMessage(error));
				}
				else if (receipt != null && "dispatched".equals(receipt.get("status")))
				{
					lastEvent = String.valueOf(receipt.get("result"));
					reporter.accept("EMERGENCY_RECOVERY_DISPATCHED result=" + receipt.get("result"));
				}
				else
				{
					lastEvent = receipt == null
						? "recovery_unavailable"
						: String.valueOf(receipt.get("result"));
					reporter.accept("EMERGENCY_RECOVERY_UNAVAILABLE result=" + lastEvent);
				}
				recovering.set(false);
			});
	}

	private CompletableFuture<Map<String, Object>> consumeAndContinue(
		Guard current,
		int missingHitpoints,
		boolean allowOverhealNow,
		boolean fallbackRequired)
	{
		return endBreakAction.get().handle((result, error) -> null)
			.thenCompose(ignored -> tryConsumable(
				current, 0, allowOverhealNow ? null : missingHitpoints))
			.thenCompose(food ->
			{
				if (wasAccepted(food))
				{
					return CompletableFuture.completedFuture(food);
				}
				if (!fallbackRequired)
				{
					return CompletableFuture.completedFuture(food);
				}
				return stopAction.apply("emergency_consumable_unavailable")
					.handle((ignored, error) -> null)
					.thenCompose(ignored -> startEscape(current, food));
			});
	}

	private CompletableFuture<Map<String, Object>> stopConsumeAndEscape(Guard current)
	{
		return stopAction.apply("emergency_low_hitpoints").handle((ignored, error) -> null)
			.thenCompose(ignored -> endBreakAction.get().handle((result, error) -> null))
			.thenCompose(ignored -> tryConsumable(current, 0, null))
			.thenCompose(food -> startEscape(current, food));
	}

	Map<String, Object> status()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		Guard current = guard;
		value.put("armed", current != null);
		value.put("recovering", recovering.get());
		value.put("last_event", lastEvent);
		value.put("last_hitpoints", lastHitpoints < 0 ? null : (long) lastHitpoints);
		if (current != null)
		{
			value.put("minimum_hitpoints", (long) current.minimumHitpoints);
			List<Map<String, Object>> items = new ArrayList<>();
			for (Consumable consumable : current.consumables)
			{
				items.add(consumable.toMap());
			}
			value.put("consumables", items);
			value.put("escape", current.escape == null ? null : current.escape.toMap());
			value.put("continue_after_consumable", current.continueAfterConsumable);
			value.put("allow_overheal", current.allowOverheal);
			value.put("forced_heal_percent", (long) FORCED_HEAL_PERCENT);
		}
		return value;
	}

	private CompletableFuture<Map<String, Object>> startEscape(
		Guard current,
		Map<String, Object> food)
	{
		if (current.escape == null)
		{
			return CompletableFuture.completedFuture(food);
		}
		lastEvent = "escaping";
		reporter.accept("EMERGENCY_ESCAPE_STARTED destination=" +
			current.escape.getDestination());
		return escapeAction.escape(current.escape).thenApply(escapeReceipt ->
		{
			if (!wasAccepted(escapeReceipt))
			{
				Map<String, Object> result = new LinkedHashMap<>(food);
				result.put("escape", escapeReceipt);
				return result;
			}
			Map<String, Object> result = new LinkedHashMap<>(food);
			result.put("status", "dispatched");
			result.put("result", "dispatched".equals(food.get("status"))
				? "emergency_food_and_escape_complete"
				: "emergency_escape_complete");
			result.put("escape", escapeReceipt);
			return result;
		});
	}

	private CompletableFuture<Map<String, Object>> tryConsumable(
		Guard current,
		int index,
		Integer maximumHeal)
	{
		if (index >= current.consumables.size())
		{
			return CompletableFuture.completedFuture(
				receipt("rejected", "no_approved_emergency_consumable_available"));
		}
		Consumable consumable = current.consumables.get(index);
		if (maximumHeal != null && consumable.healAmount > maximumHeal)
		{
			return tryConsumable(current, index + 1, maximumHeal);
		}
		return foodAction.consume(consumable.itemId, consumable.action).thenCompose(receipt ->
		{
			if (receipt != null && "dispatched".equals(receipt.get("status")))
			{
				Map<String, Object> result = new LinkedHashMap<>(receipt);
				result.put("result", "emergency_consumable_dispatched");
				result.put("consumable", consumable.toMap());
				return CompletableFuture.completedFuture(result);
			}
			return tryConsumable(current, index + 1, maximumHeal);
		});
	}

	private static Map<String, Object> receipt(String status, String result)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", status);
		receipt.put("result", result);
		receipt.put("click_count", 0L);
		return receipt;
	}

	private static boolean wasAccepted(Map<String, Object> receipt)
	{
		if (receipt == null)
		{
			return false;
		}
		Object status = receipt.get("status");
		return "dispatched".equals(status) || "arrived".equals(status) ||
			"complete".equals(status);
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

	@FunctionalInterface
	interface FoodAction
	{
		CompletableFuture<Map<String, Object>> consume(int itemId, String action);
	}

	@FunctionalInterface
	interface EscapeAction
	{
		CompletableFuture<Map<String, Object>> escape(Escape escape);
	}

	static final class Escape
	{
		private final WorldPoint destination;
		private final int within;

		Escape(WorldPoint destination, int within)
		{
			if (destination == null)
			{
				throw new IllegalArgumentException("Emergency escape destination is required");
			}
			if (within < 0 || within > 10)
			{
				throw new IllegalArgumentException(
					"Emergency escape radius must be between 0 and 10");
			}
			this.destination = destination;
			this.within = within;
		}

		WorldPoint getDestination()
		{
			return destination;
		}

		int getWithin()
		{
			return within;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("x", (long) destination.getX());
			value.put("y", (long) destination.getY());
			value.put("plane", (long) destination.getPlane());
			value.put("within", (long) within);
			return value;
		}
	}

	static final class Consumable
	{
		private final int itemId;
		private final String action;
		private final int healAmount;

		Consumable(int itemId, String action, int healAmount)
		{
			if (itemId < 0)
			{
				throw new IllegalArgumentException("Emergency consumable id cannot be negative");
			}
			if (action == null || action.trim().isEmpty())
			{
				throw new IllegalArgumentException("Emergency consumable action cannot be empty");
			}
			if (healAmount < 1 || healAmount > 99)
			{
				throw new IllegalArgumentException(
					"Emergency consumable heal amount must be between 1 and 99");
			}
			this.itemId = itemId;
			this.action = action.trim();
			this.healAmount = healAmount;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("id", (long) itemId);
			value.put("action", action);
			value.put("heal_amount", (long) healAmount);
			return value;
		}
	}

	private static final class Guard
	{
		private final int minimumHitpoints;
		private final List<Consumable> consumables;
		private final Escape escape;
		private final boolean continueAfterConsumable;
		private final boolean allowOverheal;

		private Guard(
			int minimumHitpoints,
			List<Consumable> consumables,
			Escape escape,
			boolean continueAfterConsumable,
			boolean allowOverheal)
		{
			this.minimumHitpoints = minimumHitpoints;
			this.consumables = Collections.unmodifiableList(new ArrayList<>(consumables));
			this.escape = escape;
			this.continueAfterConsumable = continueAfterConsumable;
			this.allowOverheal = allowOverheal;
		}

		private boolean hasConsumableThatFits(int missingHitpoints)
		{
			for (Consumable consumable : consumables)
			{
				if (consumable.healAmount <= missingHitpoints)
				{
					return true;
				}
			}
			return false;
		}
	}
}
