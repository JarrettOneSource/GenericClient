package com.genericclient;

import static com.genericclient.GenericClientErrors.rootMessage;

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
	private static final int CONSUMABLE_OBSERVATION_TICKS = 3;

	private final FoodAction foodAction;
	private final EscapeAction escapeAction;
	private final Supplier<CompletableFuture<Map<String, Object>>> endBreakAction;
	private final InputControl inputControl;
	private final Function<String, CompletableFuture<?>> stopAction;
	private final Consumer<String> reporter;
	private final AtomicBoolean recovering = new AtomicBoolean();

	private volatile Guard guard;
	private volatile PendingConsumable pendingConsumable;
	private volatile String lastEvent = "unarmed";
	private volatile int lastHitpoints = -1;
	private volatile int lastMaximumHitpoints = -1;
	private volatile WorldPoint lastWorld;
	private volatile GenericClientSnapshot lastSnapshot;
	private volatile boolean inputOwned;
	private volatile boolean automaticConsumablesEnabled = true;
	private volatile boolean automaticEscapeEnabled = true;

	GenericClientEmergencyController(
		FoodAction foodAction,
		EscapeAction escapeAction,
		Supplier<CompletableFuture<Map<String, Object>>> endBreakAction,
		Function<String, CompletableFuture<?>> stopAction,
		Consumer<String> reporter)
	{
		this(
			foodAction,
			escapeAction,
			endBreakAction,
			InputControl.none(),
			stopAction,
			reporter);
	}

	GenericClientEmergencyController(
		FoodAction foodAction,
		EscapeAction escapeAction,
		Supplier<CompletableFuture<Map<String, Object>>> endBreakAction,
		InputControl inputControl,
		Function<String, CompletableFuture<?>> stopAction,
		Consumer<String> reporter)
	{
		this.foodAction = foodAction;
		this.escapeAction = escapeAction;
		this.endBreakAction = endBreakAction;
		this.inputControl = inputControl;
		this.stopAction = stopAction;
		this.reporter = reporter;
	}

	CompletableFuture<Map<String, Object>> configure(
		int minimumHitpoints,
		List<Consumable> consumables,
		GenericClientEmergencyEscape escape,
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
		clearPendingConsumable();
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
		clearPendingConsumable();
		lastEvent = "unarmed";
		reporter.accept("EMERGENCY_GUARD_CLEARED");
		if (!recovering.getAndSet(false))
		{
			return CompletableFuture.completedFuture(
				receipt("complete", "emergency_guard_cleared"));
		}
		return stopAction.apply("emergency_guard_cleared")
			.handle((ignored, error) -> receipt("complete", "emergency_guard_cleared"));
	}

	void disarmForScriptStart(String scriptId)
	{
		if (recovering.get())
		{
			return;
		}
		if (guard != null)
		{
			reporter.accept("EMERGENCY_GUARD_DISARMED reason=script_started script=" + scriptId);
		}
		guard = null;
		clearPendingConsumable();
		lastEvent = "unarmed";
	}

	void disarmForManualEscape()
	{
		guard = null;
		clearPendingConsumable();
		recovering.set(false);
		lastEvent = "manual_control";
		reporter.accept("EMERGENCY_GUARD_DISARMED reason=manual_escape");
	}

	void configureScriptBehavior(boolean consumablesEnabled, boolean escapeEnabled)
	{
		automaticConsumablesEnabled = consumablesEnabled;
		automaticEscapeEnabled = escapeEnabled;
		reporter.accept("EMERGENCY_SCRIPT_BEHAVIOR consumables=" + consumablesEnabled +
			" escape=" + escapeEnabled);
	}

	void resetScriptBehavior()
	{
		automaticConsumablesEnabled = true;
		automaticEscapeEnabled = true;
	}

	void publishGameTick(GenericClientSnapshot snapshot)
	{
		publishGameTick(snapshot, true);
	}

	void publishGameTick(GenericClientSnapshot snapshot, boolean automationInputOwned)
	{
		inputOwned = automationInputOwned || recovering.get();
		if (snapshot != null)
		{
			lastSnapshot = snapshot;
			lastWorld = snapshot.getPlayerWorldPoint();
		}
		if (!inputOwned)
		{
			if (guard != null)
			{
				reporter.accept("EMERGENCY_GUARD_DISARMED reason=client_idle");
			}
			guard = null;
			clearPendingConsumable();
			lastEvent = "idle";
			return;
		}
		Guard current = guard;
		if (current == null || snapshot == null)
		{
			return;
		}
		int hitpoints = snapshot.getCurrentHitpoints();
		int maximumHitpoints = snapshot.getMaximumHitpoints();
		int missingHitpoints = Math.max(0, maximumHitpoints - hitpoints);
		lastHitpoints = hitpoints;
		lastMaximumHitpoints = maximumHitpoints;
		if (hitpoints <= 0)
		{
			handleDeath();
			return;
		}
		PendingConsumable pending = pendingConsumable;
		if (pending != null)
		{
			long quantity = snapshot.getInventoryQuantity(pending.itemId);
			boolean inventoryChanged = pending.quantityBefore >= 0 && quantity >= 0 &&
				quantity < pending.quantityBefore;
			boolean healed = hitpoints > pending.hitpointsBefore;
			if (inventoryChanged || healed)
			{
				pendingConsumable = null;
				lastEvent = "consumable_observed";
				reporter.accept("EMERGENCY_CONSUMABLE_OBSERVED item=" + pending.itemId +
					" hitpoints=" + hitpoints + " quantity=" + quantity);
				pending.observation.complete(true);
				return;
			}
			else if (pending.ticksRemaining > 0)
			{
				pendingConsumable = pending.nextTick();
				return;
			}
			else
			{
				pendingConsumable = null;
				reporter.accept("EMERGENCY_CONSUMABLE_OBSERVATION_TIMEOUT item=" +
					pending.itemId + " hitpoints=" + hitpoints + " quantity=" + quantity);
				pending.observation.complete(false);
				return;
			}
		}
		if (recovering.get())
		{
			return;
		}
		startRecovery(current, hitpoints, maximumHitpoints, missingHitpoints, snapshot);
	}

	private void handleDeath()
	{
		guard = null;
		clearPendingConsumable();
		recovering.set(false);
		lastEvent = "death_observed";
		reporter.accept("EMERGENCY_DEATH_OBSERVED");
		stopAction.apply("emergency_death_observed").handle((ignored, error) -> null);
	}

	private void startRecovery(
		Guard current,
		int hitpoints,
		int maximumHitpoints,
		int missingHitpoints, GenericClientSnapshot snapshot)
	{
		boolean forcedHealReady = maximumHitpoints > 0 &&
			hitpoints * 100L < maximumHitpoints * FORCED_HEAL_PERCENT;
		boolean continueAfterConsumable = current.continueAfterConsumable || forcedHealReady;
		boolean exactHealReady = continueAfterConsumable &&
			current.hasConsumableThatFits(missingHitpoints);
		boolean hardFloorReached = hitpoints <= current.minimumHitpoints;
		boolean consumableRecovery = automaticConsumablesEnabled &&
			(hardFloorReached || exactHealReady || forcedHealReady);
		boolean escapeOnlyRecovery = !automaticConsumablesEnabled &&
			automaticEscapeEnabled && current.escape != null &&
			(hardFloorReached || forcedHealReady);
		if ((!consumableRecovery && !escapeOnlyRecovery) ||
			!recovering.compareAndSet(false, true))
		{
			if ((hardFloorReached || exactHealReady || forcedHealReady) &&
				!consumableRecovery && !escapeOnlyRecovery)
			{
				lastEvent = "emergency_behavior_disabled_by_script";
				reporter.accept("EMERGENCY_SUPPRESSED_BY_SCRIPT hitpoints=" + hitpoints +
					" consumables=" + automaticConsumablesEnabled +
					" escape=" + automaticEscapeEnabled);
			}
			return;
		}

		lastEvent = "triggered";
		reporter.accept("EMERGENCY_TRIGGERED hitpoints=" + hitpoints +
			" threshold=" + current.minimumHitpoints +
			" missing=" + missingHitpoints + " exactHealReady=" + exactHealReady +
			" forcedHealReady=" + forcedHealReady +
			" allowOverheal=" + current.allowOverheal);
		CompletableFuture<Map<String, Object>> recovery = !automaticConsumablesEnabled
			? stopAndEscape(current)
			: continueAfterConsumable
			? consumeAndContinue(
				current,
				missingHitpoints,
				forcedHealReady ||
					(hardFloorReached && current.allowOverheal && !exactHealReady),
				forcedHealReady || hardFloorReached, snapshot)
				: stopConsumeAndEscape(current, snapshot);
		recovery.whenComplete((receipt, error) -> completeRecovery(current, receipt, error));
	}

	private void completeRecovery(Guard expected, Map<String, Object> receipt, Throwable error)
	{
		if (guard != expected)
		{
			recovering.set(false);
			return;
		}
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
		if (error == null && completedEscape(receipt))
		{
			guard = null;
			clearPendingConsumable();
			reporter.accept("EMERGENCY_GUARD_DISARMED reason=escape_complete");
		}
		recovering.set(false);
	}

	CompletableFuture<Map<String, Object>> recoverNow()
	{
		Guard current = guard;
		if (current == null)
		{
			return CompletableFuture.completedFuture(
				receipt("rejected", "safety_net_not_configured"));
		}
		if (current.escape != null && isWithin(
			lastWorld, current.escape.getDestination(), current.escape.getWithin()))
		{
			guard = null;
			clearPendingConsumable();
			lastEvent = "safety_recovery_not_needed_at_destination";
			reporter.accept("EMERGENCY_SAFETY_NET_DECLINED reason=destination_reached");
			return CompletableFuture.completedFuture(
				receipt("complete", "safety_recovery_not_needed_at_destination"));
		}
		boolean forcedHealReady = lastMaximumHitpoints > 0 && lastHitpoints > 0 &&
			lastHitpoints * 100L <
				(long) lastMaximumHitpoints * FORCED_HEAL_PERCENT;
		boolean hardFloorReached = lastHitpoints > 0 &&
			lastHitpoints <= current.minimumHitpoints;
		if (!forcedHealReady && !hardFloorReached)
		{
			lastEvent = "safety_recovery_not_needed_no_emergency";
			reporter.accept("EMERGENCY_SAFETY_NET_DECLINED reason=no_emergency" +
				" hitpoints=" + lastHitpoints +
				" maximum=" + lastMaximumHitpoints +
				" threshold=" + current.minimumHitpoints);
			return CompletableFuture.completedFuture(
				receipt("complete", "safety_recovery_not_needed_no_emergency"));
		}
		if (!automaticConsumablesEnabled &&
			(!automaticEscapeEnabled || current.escape == null))
		{
			lastEvent = "safety_recovery_disabled_by_script";
			return CompletableFuture.completedFuture(
				receipt("complete", "safety_recovery_disabled_by_script"));
		}
		if (!recovering.compareAndSet(false, true))
		{
			return CompletableFuture.completedFuture(
				receipt("rejected", "safety_recovery_already_running"));
		}

		int missingHitpoints = Math.max(0, lastMaximumHitpoints - lastHitpoints);
		boolean allowOverhealNow = lastMaximumHitpoints > 0 &&
			(lastHitpoints * 100L <
				(long) lastMaximumHitpoints * FORCED_HEAL_PERCENT ||
				(lastHitpoints <= current.minimumHitpoints && current.allowOverheal));
		lastEvent = "safety_net_recovering";
		reporter.accept("EMERGENCY_SAFETY_NET_STARTED hitpoints=" + lastHitpoints +
			" missing=" + missingHitpoints + " escape=" +
			(current.escape == null ? "none" : current.escape.describe()));

		CompletableFuture<Map<String, Object>> recovery = !automaticConsumablesEnabled
			? stopAndEscape(current)
			: endBreakAction.get()
			.handle((result, error) -> null)
			.thenCompose(ignored -> tryConsumable(
				current, 0, allowOverhealNow ? null : missingHitpoints))
			.thenCompose(food -> observeConsumable(food, lastSnapshot))
			.thenCompose(food -> startEscape(current, food, false));
		recovery.whenComplete(this::finishSafetyRecovery);
		return recovery;
	}

	private void finishSafetyRecovery(Map<String, Object> result, Throwable error)
	{		if (error != null)
		{
			lastEvent = "safety_net_failed";
			reporter.accept("EMERGENCY_SAFETY_NET_FAILED message=" + rootMessage(error));
		}
		else if (completedEscape(result))
		{
			guard = null;
			clearPendingConsumable();
			lastEvent = String.valueOf(result.get("result"));
			reporter.accept("EMERGENCY_GUARD_DISARMED reason=safety_net_complete");
		}
		else
		{
			lastEvent = result == null
				? "safety_net_unavailable"
				: String.valueOf(result.get("result"));
		}
		recovering.set(false);
	}

	CompletableFuture<Map<String, Object>> forceEscapeNow()
	{
		Guard current = guard;
		if (current == null)
		{
			return CompletableFuture.completedFuture(
				receipt("rejected", "safety_net_not_configured"));
		}
		if (current.escape != null && isWithin(
			lastWorld, current.escape.getDestination(), current.escape.getWithin()))
		{
			guard = null;
			clearPendingConsumable();
			lastEvent = "safety_recovery_not_needed_at_destination";
			return CompletableFuture.completedFuture(
				receipt("complete", "safety_recovery_not_needed_at_destination"));
		}
		if (current.escape == null)
		{
			return CompletableFuture.completedFuture(
				receipt("rejected", "no_approved_emergency_escape"));
		}
		if (!automaticEscapeEnabled)
		{
			return CompletableFuture.completedFuture(
				receipt("rejected", "emergency_escape_disabled_by_script"));
		}
		if (!recovering.compareAndSet(false, true))
		{
			return CompletableFuture.completedFuture(
				receipt("rejected", "safety_recovery_already_running"));
		}

		lastEvent = "safety_net_forced_escape";
		reporter.accept("EMERGENCY_SAFETY_NET_FORCED_ESCAPE destination=" +
			current.escape.describe());
		CompletableFuture<Map<String, Object>> recovery =
			stopAction.apply("safety_net_forced_escape")
				.handle((ignored, error) -> null)
				.thenCompose(ignored -> endBreakAction.get().handle((result, error) -> null))
				.thenCompose(ignored -> startEscape(
					current,
					receipt("rejected", "forced_escape_without_consumable"),
					true));
		recovery.whenComplete((result, error) ->
		{
			if (error != null)
			{
				lastEvent = "safety_net_failed";
				reporter.accept("EMERGENCY_SAFETY_NET_FAILED message=" + rootMessage(error));
			}
			else if (completedEscape(result))
			{
				guard = null;
				clearPendingConsumable();
				lastEvent = String.valueOf(result.get("result"));
				reporter.accept("EMERGENCY_GUARD_DISARMED reason=safety_net_complete");
			}
			else
			{
				lastEvent = result == null
					? "safety_net_unavailable"
					: String.valueOf(result.get("result"));
			}
			recovering.set(false);
		});
		return recovery;
	}

	private CompletableFuture<Map<String, Object>> consumeAndContinue(
		Guard current,
		int missingHitpoints,
		boolean allowOverhealNow,
		boolean fallbackRequired,
		GenericClientSnapshot snapshot)
	{
		return inputControl.pause("emergency_consumable")
			.handle((ignored, error) -> null)
			.thenCompose(ignored ->
				tryConsumable(current, 0, allowOverhealNow ? null : missingHitpoints))
			.thenCompose(food -> observeConsumable(food, snapshot))
			.thenCompose(food ->
			{
				if (guard != current)
				{
					return CompletableFuture.completedFuture(food);
				}
				boolean observed = !Boolean.FALSE.equals(food.get("consumable_observed"));
				if (wasAccepted(food) && (observed || !fallbackRequired))
				{
					return endBreakAction.get()
						.handle((result, error) -> null)
						.thenCompose(ignored -> inputControl.resume("emergency_consumable"))
						.handle((ignored, error) -> food);
				}
				if (!fallbackRequired || current.escape == null)
				{
					return inputControl.resume("emergency_consumable")
						.handle((ignored, error) -> food);
				}
				return stopAction.apply("emergency_consumable_unavailable")
					.handle((ignored, error) -> null)
					.thenCompose(ignored -> endBreakAction.get().handle((result, error) -> null))
					.thenCompose(ignored -> startEscape(current, food, false));
			});
	}

	private CompletableFuture<Map<String, Object>> stopConsumeAndEscape(
		Guard current,
		GenericClientSnapshot snapshot)
	{
		return stopAction.apply("emergency_low_hitpoints").handle((ignored, error) -> null)
			.thenCompose(ignored -> endBreakAction.get().handle((result, error) -> null))
			.thenCompose(ignored -> tryConsumable(current, 0, null))
			.thenCompose(food -> observeConsumable(food, snapshot))
			.thenCompose(food -> startEscape(current, food, false));
	}

	private CompletableFuture<Map<String, Object>> stopAndEscape(Guard current)
	{
		return stopAction.apply("emergency_low_hitpoints").handle((ignored, error) -> null)
			.thenCompose(ignored -> endBreakAction.get().handle((result, error) -> null))
			.thenCompose(ignored -> startEscape(
				current,
				receipt("rejected", "emergency_consumables_disabled_by_script"),
				false));
	}

	Map<String, Object> status()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		Guard current = guard;
		value.put("armed", current != null);
		value.put("input_owned", inputOwned);
		value.put("recovering", recovering.get());
		value.put("consumable_pending", pendingConsumable != null);
		value.put("last_event", lastEvent);
		value.put("automatic_consumables_enabled", automaticConsumablesEnabled);
		value.put("automatic_escape_enabled", automaticEscapeEnabled);
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

	private CompletableFuture<Map<String, Object>> observeConsumable(
		Map<String, Object> food,
		GenericClientSnapshot snapshot)
	{
		Integer itemId = dispatchedConsumable(food);
		if (itemId == null || snapshot == null)
		{
			return CompletableFuture.completedFuture(food);
		}
		long quantity = snapshot.getInventoryQuantity(itemId);
		if (quantity <= 0)
		{
			return CompletableFuture.completedFuture(food);
		}

		clearPendingConsumable();
		CompletableFuture<Boolean> observation = new CompletableFuture<>();
		pendingConsumable = new PendingConsumable(
			itemId,
			snapshot.getCurrentHitpoints(),
			quantity,
			CONSUMABLE_OBSERVATION_TICKS,
			observation);
		lastEvent = "consumable_pending";
		return observation.thenApply(observed ->
		{
			Map<String, Object> result = new LinkedHashMap<>(food);
			result.put("consumable_observed", observed);
			return result;
		});
	}

	private boolean emergencyStillActive(Guard current)
	{
		if (lastHitpoints < 1 || lastMaximumHitpoints < 1)
		{
			return true;
		}
		return lastHitpoints <= current.minimumHitpoints ||
			lastHitpoints * 100L <
				(long) lastMaximumHitpoints * FORCED_HEAL_PERCENT;
	}

	private void clearPendingConsumable()
	{
		PendingConsumable pending = pendingConsumable;
		pendingConsumable = null;
		if (pending != null)
		{
			pending.observation.complete(false);
		}
	}

	private CompletableFuture<Map<String, Object>> startEscape(
		Guard current,
		Map<String, Object> food,
		boolean forced)
	{
		if (guard != current)
		{
			return CompletableFuture.completedFuture(
				receipt("rejected", "emergency_recovery_cancelled"));
		}
		if (current.escape == null || !automaticEscapeEnabled)
		{
			return CompletableFuture.completedFuture(food);
		}
		if (!forced && !emergencyStillActive(current))
		{
			Map<String, Object> result = new LinkedHashMap<>(food);
			result.put("result", "emergency_escape_no_longer_needed");
			result.put("hitpoints", (long) lastHitpoints);
			reporter.accept("EMERGENCY_ESCAPE_DECLINED reason=hitpoints_recovered" +
				" hitpoints=" + lastHitpoints);
			return CompletableFuture.completedFuture(result);
		}
		lastEvent = "escaping";
		reporter.accept("EMERGENCY_ESCAPE_STARTED destination=" +
			current.escape.describe());
		return escapeAction.escape(current.escape).thenApply(escapeReceipt ->
		{
			if (!wasAccepted(escapeReceipt))
			{
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("status", "rejected");
				result.put("result", escapeReceipt == null
					? "emergency_escape_failed"
					: String.valueOf(escapeReceipt.get("result")));
				result.put("click_count", 0L);
				result.put("food", food);
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
		if (guard != current)
		{
			return CompletableFuture.completedFuture(
				receipt("rejected", "emergency_recovery_cancelled"));
		}
		if (!automaticConsumablesEnabled)
		{
			return CompletableFuture.completedFuture(
				receipt("rejected", "emergency_consumables_disabled_by_script"));
		}
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

	private static boolean completedEscape(Map<String, Object> receipt)
	{
		if (receipt == null || !"dispatched".equals(receipt.get("status")))
		{
			return false;
		}
		String result = String.valueOf(receipt.get("result"));
		return "emergency_escape_complete".equals(result) ||
			"emergency_food_and_escape_complete".equals(result);
	}

	private static boolean isWithin(WorldPoint world, WorldPoint destination, int within)
	{
		return world != null && destination != null &&
			world.getPlane() == destination.getPlane() &&
			Math.max(
				Math.abs(world.getX() - destination.getX()),
				Math.abs(world.getY() - destination.getY())) <= within;
	}

	private static Integer dispatchedConsumable(Map<String, Object> receipt)
	{
		if (receipt == null)
		{
			return null;
		}
		Object consumable = receipt.get("consumable");
		if (!(consumable instanceof Map) && receipt.get("food") instanceof Map)
		{
			consumable = ((Map<?, ?>) receipt.get("food")).get("consumable");
		}
		if (!(consumable instanceof Map))
		{
			return null;
		}
		Object id = ((Map<?, ?>) consumable).get("id");
		return id instanceof Number ? ((Number) id).intValue() : null;
	}


	@FunctionalInterface
	interface FoodAction
	{
		CompletableFuture<Map<String, Object>> consume(int itemId, String action);
	}

	@FunctionalInterface
	interface EscapeAction
	{
		CompletableFuture<Map<String, Object>> escape(GenericClientEmergencyEscape escape);
	}

	interface InputControl
	{
		CompletableFuture<?> pause(String reason);

		CompletableFuture<?> resume(String reason);

		static InputControl none()
		{
			return new InputControl()
			{
				@Override
				public CompletableFuture<?> pause(String reason)
				{
					return CompletableFuture.completedFuture(null);
				}

				@Override
				public CompletableFuture<?> resume(String reason)
				{
					return CompletableFuture.completedFuture(null);
				}
			};
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
		private final GenericClientEmergencyEscape escape;
		private final boolean continueAfterConsumable;
		private final boolean allowOverheal;

		private Guard(
			int minimumHitpoints,
			List<Consumable> consumables,
			GenericClientEmergencyEscape escape,
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

	private static final class PendingConsumable
	{
		private final int itemId;
		private final int hitpointsBefore;
		private final long quantityBefore;
		private final int ticksRemaining;
		private final CompletableFuture<Boolean> observation;

		private PendingConsumable(
			int itemId,
			int hitpointsBefore,
			long quantityBefore,
			int ticksRemaining,
			CompletableFuture<Boolean> observation)
		{
			this.itemId = itemId;
			this.hitpointsBefore = hitpointsBefore;
			this.quantityBefore = quantityBefore;
			this.ticksRemaining = ticksRemaining;
			this.observation = observation;
		}

		private PendingConsumable nextTick()
		{
			return new PendingConsumable(
				itemId, hitpointsBefore, quantityBefore, ticksRemaining - 1, observation);
		}
	}
}
