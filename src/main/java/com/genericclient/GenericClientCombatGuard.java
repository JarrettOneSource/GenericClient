package com.genericclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Publishes threat and damage observations and owns protection prayer only
 * when the current policy assigns it to the guard.
 */
final class GenericClientCombatGuard
{
	static final long COMBAT_GRACE_MILLIS = 60_000L;
	static final long PRAYER_RELEASE_QUIET_TICKS = 3L;
	private static final long PRAYER_RETRY_TICKS = 3L;

	private final Runtime runtime;
	private final Consumer<String> reporter;
	private final GenericClientDamageTracker damageTracker = new GenericClientDamageTracker();
	private GenericClientDamageTracker.Damage damage = GenericClientDamageTracker.Damage.NONE;

	private long currentTick = -1L;
	private long lastDamageTick = Long.MIN_VALUE;
	private long lastThreatTick = Long.MIN_VALUE;
	private long nextPrayerAttemptTick;
	private int lastObservedHitpoints = -1;
	private boolean combatSignal;
	private boolean inputOwned;
	private boolean damageExpected;
	private boolean damageDetected;
	private boolean scriptControlsProtection;
	private boolean unexpectedCombat;
	private volatile boolean automaticPrayerEnabled = true;
	private String selectedProtection = "none";
	private String ownedProtection = "none";
	private String satisfiedProtection = "none";
	private String pendingProtection = "none";
	private String lastResult = "idle";
	private CompletableFuture<Map<String, Object>> prayerAction;
	private CompletableFuture<Map<String, Object>> prayerRestoreAction;
	private GenericClientActivityContext inputContext = GenericClientActivityContext.none().openInputScope();
	private List<Map<String, Object>> attackers = Collections.emptyList();

	GenericClientCombatGuard(Runtime runtime, Consumer<String> reporter)
	{
		if (runtime == null || reporter == null)
		{
			throw new IllegalArgumentException("Combat guard dependencies are required");
		}
		this.runtime = runtime;
		this.reporter = reporter;
	}

	synchronized void configureScriptBehavior(boolean prayerEnabled)
	{
		automaticPrayerEnabled = prayerEnabled;
		if (!prayerEnabled)
		{
			revokeInput();
			selectedProtection = "none";
			ownedProtection = "none";
			satisfiedProtection = "none";
			pendingProtection = "none";
			lastResult = "automatic_prayer_disabled_by_script";
		}
		reporter.accept("COMBAT_GUARD_SCRIPT_BEHAVIOR prayer=" + prayerEnabled);
	}

	synchronized void resetScriptBehavior()
	{
		revokeInput();
		automaticPrayerEnabled = true;
	}

	boolean isAutomaticPrayerEnabled() { return automaticPrayerEnabled; }
	synchronized boolean hasPendingInput() { return prayerAction != null || prayerRestoreAction != null; }

	synchronized void recordHitsplat(int type, int amount)
	{
		damageTracker.record(type, amount);
	}

	synchronized void publishGameTick(GenericClientSnapshot snapshot,
		GenericClientBehaviorPolicy policy, boolean automationInputOwned)
	{
		if (snapshot == null || !snapshot.isLoggedIn() || snapshot.getPlayer() == null ||
			snapshot.getPlayer().getName() == null || snapshot.getCurrentHitpoints() <= 0)
		{
			reset();
			return;
		}
		if (!automationInputOwned)
		{
			publishIdleTick(snapshot);
			return;
		}
		inputOwned = true;
		List<Threat> threats = threats(snapshot);
		currentTick = snapshot.getGameTick();
		lastObservedHitpoints = snapshot.getCurrentHitpoints();
		damage = damageTracker.observe(snapshot, !threats.isEmpty());
		damageDetected = damage != GenericClientDamageTracker.Damage.NONE;
		if (damage.unexpected) lastDamageTick = currentTick;
		damageExpected = policy.damageExpected;
		scriptControlsProtection = policy.prayerOwner == GenericClientBehaviorPolicy.PrayerOwner.SCRIPT ||
			!automaticPrayerEnabled;
		combatSignal = damageExpected || !threats.isEmpty() || graceTicksRemaining() > 0;
		unexpectedCombat = !damageExpected && (!threats.isEmpty() || graceTicksRemaining() > 0);
		attackers = threatMaps(threats);

		if (runtime.isInputBlocked())
		{
			revokeInput();
			lastResult = "waiting_for_input_owner";
			return;
		}
		if (scriptControlsProtection)
		{
			revokeInput();
			selectedProtection = "none";
			ownedProtection = "none";
			satisfiedProtection = "none";
			lastResult = automaticPrayerEnabled ? "script_owns_prayer" : "automatic_prayer_disabled_by_script";
			return;
		}
		updateThreatPrayer(threats, currentTick, damageExpected);
	}

	private void updateThreatPrayer(List<Threat> threats, long tick, boolean protectedActivity)
	{
		if (!threats.isEmpty())
		{
			lastThreatTick = tick;
			Threat selected = select(threats);
			String protection = selected.style.prayer;
			if (!protection.equals(selectedProtection))
			{
				selectedProtection = protection;
				reporter.accept("COMBAT_GUARD_ENGAGED attacker=" + selected.name +
					" npcId=" + selected.id + " distance=" + selected.distance +
					" protection=" + protection);
			}
			if (protection.equals(satisfiedProtection) &&
				!runtime.isPrayerActive(protection))
			{
				reporter.accept("COMBAT_GUARD_PRAYER_LOST prayer=" + protection);
				ownedProtection = "none";
				satisfiedProtection = "none";
				lastResult = "prayer_lost";
			}
			requestPrayer(protection, true);
		}
		else if (protectedActivity)
		{
			selectedProtection = "none";
			lastResult = "protected_activity_no_active_threat";
		}
		else if (tick - lastThreatTick >= PRAYER_RELEASE_QUIET_TICKS)
		{
			if (!"none".equals(ownedProtection))
			{
				requestPrayer(ownedProtection, false);
			}
			else
			{
				selectedProtection = "none";
				satisfiedProtection = "none";
			}
		}
	}

	synchronized Observation observation()
	{
		return new Observation(currentTick, currentTick + graceTicksRemaining(), !attackers.isEmpty(), automaticPrayerEnabled);
	}

	static final class Observation
	{
		final long tick;
		final long damageGraceUntilTick;
		final boolean threatsPresent;
		final boolean automaticPrayerEnabled;

		private Observation(long tick, long damageGraceUntilTick, boolean threatsPresent, boolean automaticPrayerEnabled)
		{
			this.tick = tick;
			this.damageGraceUntilTick = damageGraceUntilTick;
			this.threatsPresent = threatsPresent;
			this.automaticPrayerEnabled = automaticPrayerEnabled;
		}
	}

	synchronized Map<String, Object> status()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("active", combatSignal);
		value.put("input_owned", inputOwned);
		value.put("damage_expected", damageExpected);
		value.put("damage_type", damage.name().toLowerCase(Locale.ROOT));
		value.put("damage_detected", damageDetected);
		value.put("last_hitpoints", lastObservedHitpoints < 0 ? null : lastObservedHitpoints);
		value.put("script_controls_protection", scriptControlsProtection);
		value.put("unexpected_combat", unexpectedCombat);
		value.put("automatic_prayer_enabled", automaticPrayerEnabled);
		value.put("damage_grace", graceTicksRemaining() > 0L);
		value.put("grace_ticks_remaining", graceTicksRemaining());
		value.put("selected_protection", selectedProtection);
		value.put("owned_protection", ownedProtection);
		value.put("satisfied_protection", satisfiedProtection);
		value.put("pending_protection", pendingProtection);
		value.put("prayer_restore_pending", prayerRestoreAction != null);
		value.put("attackers", new ArrayList<>(attackers));
		value.put("last_result", lastResult);
		return value;
	}

	synchronized void reset()
	{
		revokeInput();
		damageTracker.reset();
		damage = GenericClientDamageTracker.Damage.NONE;
		combatSignal = false;
		inputOwned = false;
		damageExpected = false;
		damageDetected = false;
		scriptControlsProtection = false;
		unexpectedCombat = false;
		selectedProtection = "none";
		ownedProtection = "none";
		satisfiedProtection = "none";
		pendingProtection = "none";
		attackers = Collections.emptyList();
		lastDamageTick = Long.MIN_VALUE;
		lastThreatTick = Long.MIN_VALUE;
		lastObservedHitpoints = -1;
		nextPrayerAttemptTick = 0L;
		prayerAction = null;
		prayerRestoreAction = null;
		lastResult = "idle";
	}

	private void publishIdleTick(GenericClientSnapshot snapshot)
	{
		if (runtime.isInputBlocked())
		{
			reset();
			lastResult = "waiting_for_input_owner";
			return;
		}
		damageTracker.reset();
		damage = GenericClientDamageTracker.Damage.NONE;
		inputOwned = false;
		combatSignal = false;
		damageExpected = false;
		damageDetected = false;
		scriptControlsProtection = false;
		unexpectedCombat = false;
		selectedProtection = "none";
		satisfiedProtection = "none";
		pendingProtection = "none";
		attackers = Collections.emptyList();
		currentTick = snapshot.getGameTick();
		lastObservedHitpoints = snapshot.getCurrentHitpoints();
		if (prayerAction != null || prayerRestoreAction != null)
		{
			return;
		}
		String protection = "none".equals(ownedProtection)
			? null
			: ownedProtection;
		ownedProtection = "none";
		if (protection != null && runtime.isPrayerActive(protection))
		{
			lastResult = "idle_releasing_prayer";
			requestPrayer(protection, false);
			return;
		}
		lastResult = "idle";
	}

	private void requestPrayer(String prayer, boolean enabled)
	{
		if (prayerAction != null || prayerRestoreAction != null ||
			currentTick < nextPrayerAttemptTick)
		{
			return;
		}
		if (enabled && prayer.equals(satisfiedProtection))
		{
			return;
		}
		pendingProtection = prayer;
		try
		{
			CompletableFuture<Map<String, Object>> attempt = runtime.setPrayer(prayer, enabled, inputContext);
			prayerAction = attempt;
			attempt.whenComplete((receipt, error) ->
				prayerFinished(attempt, prayer, enabled, receipt, error));
		}
		catch (RuntimeException exception)
		{
			prayerAction = null;
			pendingProtection = "none";
			nextPrayerAttemptTick = currentTick + PRAYER_RETRY_TICKS;
			lastResult = "prayer_failed:" + message(exception);
			reporter.accept("COMBAT_GUARD_PRAYER_FAILED prayer=" + prayer +
				" message=" + message(exception));
		}
	}

	private void prayerFinished(CompletableFuture<Map<String, Object>> attempt,
		String prayer, boolean enabled, Map<String, Object> receipt, Throwable error)
	{
		synchronized (this)
		{
			if (prayerAction != attempt) return;
			prayerAction = null;
			pendingProtection = "none";
			if (error != null)
			{
				nextPrayerAttemptTick = currentTick + PRAYER_RETRY_TICKS;
				lastResult = "prayer_failed:" + message(error);
				reporter.accept("COMBAT_GUARD_PRAYER_FAILED prayer=" + prayer +
					" message=" + message(error));
				return;
			}

			applyPrayerReceipt(prayer, enabled, receipt);
		}
	}

	private void applyPrayerReceipt(String prayer, boolean enabled, Map<String, Object> receipt)
	{
		String status = String.valueOf(receipt.get("status"));
		String result = String.valueOf(receipt.get("result"));
		if ("set".equals(status))
		{
			ownedProtection = enabled ? prayer : "none";
			satisfiedProtection = enabled ? prayer : "none";
			if (!enabled)
			{
				selectedProtection = "none";
			}
			lastResult = enabled ? "prayer_enabled" : "prayer_disabled";
			reporter.accept("COMBAT_GUARD_PRAYER_" +
				(enabled ? "ENABLED" : "DISABLED") + " prayer=" + prayer);
			return;
		}
		if ("unchanged".equals(status))
		{
			satisfiedProtection = enabled ? prayer : "none";
			if (!enabled)
			{
				ownedProtection = "none";
				selectedProtection = "none";
			}
			lastResult = enabled ? "prayer_already_active" : "prayer_already_inactive";
			return;
		}

		if (enabled && "prayer_points_depleted".equals(result))
		{
			requestPrayerRestore();
			return;
		}

		nextPrayerAttemptTick = currentTick + PRAYER_RETRY_TICKS;
		lastResult = "prayer_rejected:" + result;
		reporter.accept("COMBAT_GUARD_PRAYER_REJECTED prayer=" + prayer +
			" result=" + result);
	}

	private void requestPrayerRestore()
	{
		if (prayerRestoreAction != null)
		{
			return;
		}
		try
		{
			CompletableFuture<Map<String, Object>> attempt = runtime.restorePrayer(inputContext);
			prayerRestoreAction = attempt;
			attempt.whenComplete((receipt, error) ->
				prayerRestoreFinished(attempt, receipt, error));
		}
		catch (RuntimeException exception)
		{
			prayerRestoreAction = null;
			nextPrayerAttemptTick = currentTick + PRAYER_RETRY_TICKS;
			lastResult = "prayer_restore_failed:" + message(exception);
			reporter.accept("COMBAT_GUARD_PRAYER_RESTORE_FAILED message=" +
				message(exception));
		}
	}

	private void prayerRestoreFinished(CompletableFuture<Map<String, Object>> attempt,
		Map<String, Object> receipt, Throwable error)
	{
		synchronized (this)
		{
			if (prayerRestoreAction != attempt) return;
			prayerRestoreAction = null;
			if (error != null)
			{
				nextPrayerAttemptTick = currentTick + PRAYER_RETRY_TICKS;
				lastResult = "prayer_restore_failed:" + message(error);
				reporter.accept("COMBAT_GUARD_PRAYER_RESTORE_FAILED message=" +
					message(error));
				return;
			}
			if ("dispatched".equals(String.valueOf(receipt.get("status"))))
			{
				nextPrayerAttemptTick = currentTick + 1L;
				lastResult = "prayer_restore_dispatched";
				reporter.accept("COMBAT_GUARD_PRAYER_RESTORE_DISPATCHED");
				return;
			}
			nextPrayerAttemptTick = currentTick + PRAYER_RETRY_TICKS;
			lastResult = "prayer_restore_rejected:" + String.valueOf(receipt.get("result"));
			reporter.accept("COMBAT_GUARD_PRAYER_RESTORE_REJECTED result=" +
				String.valueOf(receipt.get("result")));
		}
	}

	private long graceTicksRemaining()
	{
		if (currentTick < 0L || lastDamageTick == Long.MIN_VALUE)
		{
			return 0L;
		}
		long graceTicks = COMBAT_GRACE_MILLIS / 600L;
		return Math.max(0L, graceTicks - (currentTick - lastDamageTick));
	}

	private void revokeInput()
	{
		GenericClientActivityContext revoked = inputContext;
		revoked.cancelInput();
		inputContext = GenericClientActivityContext.none().openInputScope();
		prayerAction = null;
		prayerRestoreAction = null;
		pendingProtection = "none";
		runtime.cancelInput(revoked);
	}

	private static List<Threat> threats(GenericClientSnapshot snapshot)
	{
		String playerName = snapshot.getPlayer().getName();
		List<Threat> result = new ArrayList<>();
		for (GenericClientNpcSnapshot npc : snapshot.getNpcs())
		{
			if (npc == null || npc.isDead() || npc.getCombatLevel() <= 0 ||
				!playerName.equals(npc.getInteracting()))
			{
				continue;
			}
			Classification classification = classify(npc);
			result.add(new Threat(
				npc.getId(),
				npc.getName(),
				npc.getCombatLevel(),
				npc.getDistance(),
				npc.getAnimation(),
				classification.style,
				classification.confidence));
		}
		return result;
	}

	private static Threat select(List<Threat> threats)
	{
		Threat selected = threats.get(0);
		for (int index = 1; index < threats.size(); index++)
		{
			Threat candidate = threats.get(index);
			if (candidate.combatLevel > selected.combatLevel ||
				(candidate.combatLevel == selected.combatLevel &&
					candidate.confidence > selected.confidence))
			{
				selected = candidate;
			}
		}
		return selected;
	}

	private static Classification classify(GenericClientNpcSnapshot npc)
	{
		String name = npc.getName().toLowerCase(Locale.ROOT);
		if (containsAny(name,
			"mage", "wizard", "sorcer", "shaman", "magician", "necromancer", "spellcaster"))
		{
			return new Classification(Style.MAGIC, 2);
		}
		if (containsAny(name,
			"archer", "ranger", "bowman", "marksman", "thrower", "gunner"))
		{
			return new Classification(Style.MISSILES, 2);
		}
		if (npc.getDistance() <= Math.max(1, npc.getSize()))
		{
			return new Classification(Style.MELEE, 1);
		}
		return new Classification(Style.MELEE, 0);
	}

	private static boolean containsAny(String value, String... fragments)
	{
		for (String fragment : fragments)
		{
			if (value.contains(fragment))
			{
				return true;
			}
		}
		return false;
	}

	private static List<Map<String, Object>> threatMaps(List<Threat> threats)
	{
		List<Map<String, Object>> result = new ArrayList<>();
		for (Threat threat : threats)
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("id", (long) threat.id);
			value.put("name", threat.name);
			value.put("combat_level", (long) threat.combatLevel);
			value.put("distance", (long) threat.distance);
			value.put("animation", (long) threat.animation);
			value.put("protection", threat.style.prayer);
			value.put("classification",
				threat.confidence == 2 ? "name" :
					threat.confidence == 1 ? "adjacent" : "default_melee");
			result.add(value);
		}
		return Collections.unmodifiableList(result);
	}

	private static String message(Throwable error)
	{
		return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
	}

	interface Runtime
	{
		void cancelInput(GenericClientActivityContext context);

		boolean isInputBlocked();

		boolean isPrayerActive(String prayer);

		CompletableFuture<Map<String, Object>> setPrayer(String prayer, boolean enabled,
			GenericClientActivityContext context);

		CompletableFuture<Map<String, Object>> restorePrayer(GenericClientActivityContext context);
	}

	private enum Style
	{
		MAGIC("protect_from_magic"),
		MISSILES("protect_from_missiles"),
		MELEE("protect_from_melee");

		private final String prayer;

		Style(String prayer)
		{
			this.prayer = prayer;
		}
	}

	private static final class Classification
	{
		private final Style style;
		private final int confidence;

		private Classification(Style style, int confidence)
		{
			this.style = style;
			this.confidence = confidence;
		}
	}

	private static final class Threat
	{
		private final int id;
		private final String name;
		private final int combatLevel;
		private final int distance;
		private final int animation;
		private final Style style;
		private final int confidence;

		private Threat(
			int id,
			String name,
			int combatLevel,
			int distance,
			int animation,
			Style style,
			int confidence)
		{
			this.id = id;
			this.name = name;
			this.combatLevel = combatLevel;
			this.distance = distance;
			this.animation = animation;
			this.style = style;
			this.confidence = confidence;
		}
	}
}
