package com.genericclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Applies current safety and input ownership to a captured activity policy. */
final class GenericClientPolicyResolver
{
	private final Supplier<Signals> signals;
	private final Consumer<String> reporter;
	private final AtomicReference<Resolution> last = new AtomicReference<>();

	GenericClientPolicyResolver(Supplier<Signals> signals, Consumer<String> reporter)
	{
		this.signals = signals;
		this.reporter = reporter;
	}

	Resolution resolve(GenericClientActivityContext context)
	{
		Resolution next = calculate(context, signals.get());
		GenericClientActionBoundary.Ticket ticket = context.inputTicket();
		AtomicReference<Resolution> replaced = new AtomicReference<>();
		Runnable publish = () -> replaced.set(last.getAndSet(next));
		if (ticket == null) publish.run();
		else if (!ticket.applyIfCurrent(publish)) return next;
		Resolution previous = replaced.get();
		if (previous == null || previous.activity != next.activity ||
			!previous.policy.equals(next.policy) || !previous.reasons.equals(next.reasons))
		{
			reporter.accept("BEHAVIOR_POLICY activity=" + next.activity.getValue() +
				" policy=" + next.policy.toMap() + " reasons=" + next.reasons);
		}
		return next;
	}

	Map<String, Object> status()
	{
		Resolution current = last.get();
		return current == null ? Collections.emptyMap() : current.toMap();
	}

	static Resolution declared(GenericClientActivityContext context)
	{
		return calculate(context, Signals.CLEAR);
	}

	private static Resolution calculate(GenericClientActivityContext context, Signals signals)
	{
		GenericClientBehaviorPolicy policy = context.declaredPolicy;
		List<String> reasons = new ArrayList<>();
		reasons.add("activity:" + context.getActivity().getValue());
		if (!signals.automaticPrayerEnabled && policy.prayerOwner == GenericClientBehaviorPolicy.PrayerOwner.GUARD)
		{
			reasons.add("guard_prayer_disabled");
			policy = new GenericClientBehaviorPolicy(policy.breaks, policy.cursorRelease, policy.mouse,
				policy.damageExpected, GenericClientBehaviorPolicy.PrayerOwner.SCRIPT, policy.walkRefresh, policy.fidget);
		}
		boolean unexpectedCombat = signals.snapshotAvailable && !policy.damageExpected &&
			(signals.threatsPresent || signals.tick < signals.damageGraceUntilTick);
		if (unexpectedCombat)
		{
			if (signals.threatsPresent) reasons.add("unexpected_threat");
			if (signals.tick < signals.damageGraceUntilTick) reasons.add("damage_grace");
			policy = new GenericClientBehaviorPolicy(false, GenericClientBehaviorPolicy.CursorRelease.NONE,
				GenericClientBehaviorPolicy.Mouse.FAST, policy.damageExpected, policy.prayerOwner,
				policy.walkRefresh, GenericClientBehaviorPolicy.Fidget.NONE);
		}
		if (!signals.snapshotAvailable) reasons.add("snapshot_unavailable");
		if (signals.manualTakeover) reasons.add("manual_takeover");
		if (signals.randomEvent) reasons.add("random_event");
		if (signals.emergencyRecovery) reasons.add("emergency_recovery");
		if (context.intent) reasons.add("intent");
		if (!signals.snapshotAvailable || signals.manualTakeover || signals.randomEvent ||
			signals.emergencyRecovery || context.intent)
			policy = policy.withoutDiscretionary(false);
		if (!context.humanize)
		{
			reasons.add("plain_execution");
			policy = policy.withoutDiscretionary(true);
		}
		return new Resolution(context.getActivity(), context.declaredPolicy, policy, reasons, unexpectedCombat);
	}

	static final class Signals
	{
		static final Signals CLEAR = new Signals(true, 0, 0, false, false, false, false, true);
		static final Signals UNAVAILABLE = new Signals(false, 0, 0, false, false, false, false, true);
		final boolean snapshotAvailable;
		final long tick;
		final long damageGraceUntilTick;
		final boolean threatsPresent;
		final boolean manualTakeover;
		final boolean randomEvent;
		final boolean emergencyRecovery;
		final boolean automaticPrayerEnabled;

		Signals(boolean snapshotAvailable, long tick, long damageGraceUntilTick,
			boolean threatsPresent, boolean manualTakeover, boolean randomEvent, boolean emergencyRecovery,
			boolean automaticPrayerEnabled)
		{
			this.snapshotAvailable = snapshotAvailable;
			this.tick = tick;
			this.damageGraceUntilTick = damageGraceUntilTick;
			this.threatsPresent = threatsPresent;
			this.manualTakeover = manualTakeover;
			this.randomEvent = randomEvent;
			this.emergencyRecovery = emergencyRecovery;
			this.automaticPrayerEnabled = automaticPrayerEnabled;
		}
	}

	static final class Resolution
	{
		final GenericClientActivityContext.Activity activity;
		final GenericClientBehaviorPolicy declaredPolicy;
		final GenericClientBehaviorPolicy policy;
		final List<String> reasons;
		final boolean unexpectedCombat;

		private Resolution(GenericClientActivityContext.Activity activity, GenericClientBehaviorPolicy declaredPolicy,
			GenericClientBehaviorPolicy policy, List<String> reasons, boolean unexpectedCombat)
		{
			this.activity = activity;
			this.declaredPolicy = declaredPolicy;
			this.policy = policy;
			this.reasons = List.copyOf(reasons);
			this.unexpectedCombat = unexpectedCombat;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("activity", activity.getValue());
			value.put("declared_policy", declaredPolicy.toMap());
			value.put("effective_policy", policy.toMap());
			value.put("policy_reasons", reasons);
			return value;
		}
	}
}
