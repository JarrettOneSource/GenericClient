package com.genericclient;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class GenericClientPolicyResolverTest
{
	@Test
	public void disablingAutomaticPrayerReportsScriptOwnershipWithoutChangingOtherFields()
	{
		AtomicReference<GenericClientPolicyResolver.Signals> signals = new AtomicReference<>(
			new GenericClientPolicyResolver.Signals(true, 10, 110, true, false, false, false, false));
		GenericClientActivityContext context = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.COMBAT)
			.withPolicy(Map.of("breaks", true)).withResolver(new GenericClientPolicyResolver(signals::get, message -> { }));
		assertEquals(GenericClientBehaviorPolicy.PrayerOwner.SCRIPT, context.policy().prayerOwner);
		assertTrue(context.allowsBreaks());
		assertEquals(180, context.mouseMoveDurationMillis(500));
		assertEquals(List.of("activity:combat", "guard_prayer_disabled"), context.resolve().reasons);
		signals.set(GenericClientPolicyResolver.Signals.CLEAR);
		assertEquals(GenericClientBehaviorPolicy.PrayerOwner.GUARD, context.policy().prayerOwner);
	}

	@Test
	public void expectedCombatAllowsBreaksWhileUnexpectedThreatsOnlyOverrideDiscretionaryFields()
	{
		GenericClientPolicyResolver resolver = new GenericClientPolicyResolver(
			() -> new GenericClientPolicyResolver.Signals(true, 10, 110, true, false, false, false, true), message -> { });
		GenericClientActivityContext combat = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.COMBAT)
			.withPolicy(Map.of("breaks", true)).withResolver(resolver);
		assertTrue(combat.allowsBreaks());
		assertEquals(180, combat.mouseMoveDurationMillis(550));
		assertEquals(GenericClientBehaviorPolicy.PrayerOwner.GUARD, combat.policy().prayerOwner);
		assertFalse(combat.resolve().unexpectedCombat);

		GenericClientActivityContext skilling = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.SKILLING)
			.withPolicy(Map.of("walk_refresh", true)).withResolver(resolver);
		assertFalse(skilling.allowsBreaks());
		assertFalse(skilling.allowsCursorRelease());
		assertEquals(180, skilling.mouseMoveDurationMillis(550));
		assertTrue(skilling.refreshesWalkClicks());
		assertEquals(GenericClientBehaviorPolicy.PrayerOwner.SCRIPT, skilling.policy().prayerOwner);
		assertTrue(skilling.resolve().unexpectedCombat);
		assertEquals(List.of("activity:skilling", "unexpected_threat", "damage_grace"), skilling.resolve().reasons);
		assertTrue(skilling.withPolicy(Map.of("damage_expected", true)).allowsBreaks());
	}

	@Test
	public void graceExpiresAtItsDeadlineAndMissingFramesCannotBecomeSafe()
	{
		AtomicReference<GenericClientPolicyResolver.Signals> signals = new AtomicReference<>(
			new GenericClientPolicyResolver.Signals(true, 109, 110, false, false, false, false, true));
		GenericClientActivityContext context = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.TRAVEL)
			.withResolver(new GenericClientPolicyResolver(signals::get, message -> { }));
		assertFalse(context.allowsBreaks());
		signals.set(new GenericClientPolicyResolver.Signals(true, 110, 110, false, false, false, false, true));
		assertTrue(context.allowsBreaks());
		signals.set(new GenericClientPolicyResolver.Signals(false, 110, 110, false, false, false, false, true));
		assertFalse(context.allowsBreaks());
		assertFalse(context.resolve().unexpectedCombat);
		assertEquals(List.of("activity:travel", "snapshot_unavailable"), context.resolve().reasons);
	}

	@Test
	public void ownershipSuppressesDiscretionaryBehaviorWithoutRevokingSafetyInput()
	{
		GenericClientActionBoundary.Ticket ticket = new GenericClientActionBoundary.Ticket();
		GenericClientActivityContext context = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.TRAVEL)
			.withTicket(ticket).withResolver(new GenericClientPolicyResolver(
				() -> new GenericClientPolicyResolver.Signals(true, 1, 0, false, true, true, true, true), message -> { }));
		assertFalse(context.allowsBreaks());
		assertFalse(context.allowsCursorRelease());
		assertEquals(GenericClientBehaviorPolicy.Fidget.NONE, context.policy().fidget);
		assertEquals(List.of("activity:travel", "manual_takeover", "random_event", "emergency_recovery"), context.resolve().reasons);
		assertTrue(ticket.isActive());
	}

	@Test
	public void plainExecutionKeepsTravelAndPrayerSettingsEvenDuringUnexpectedCombat()
	{
		GenericClientActivityContext context = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.HAZARDOUS_TRAVEL)
			.withPolicy(Map.of("damage_expected", false, "breaks", true)).plain().inIntent()
			.withResolver(new GenericClientPolicyResolver(
				() -> new GenericClientPolicyResolver.Signals(true, 1, 101, true, false, false, false, true), message -> { }));
		assertEquals(550, context.mouseMoveDurationMillis(550));
		assertTrue(context.refreshesWalkClicks());
		assertEquals(GenericClientBehaviorPolicy.PrayerOwner.GUARD, context.policy().prayerOwner);
		assertFalse(context.allowsBreaks());
		assertEquals(GenericClientBehaviorPolicy.Fidget.NONE, context.policy().fidget);
		assertTrue(context.resolve().reasons.contains("plain_execution"));
		assertTrue(context.resolve().reasons.contains("intent"));
	}

	@Test
	public void onlyChangedEffectivePoliciesLogAndCancelledScopesCannotReplaceCurrentStatus()
	{
		List<String> reports = new ArrayList<>();
		GenericClientPolicyResolver resolver = new GenericClientPolicyResolver(() -> GenericClientPolicyResolver.Signals.CLEAR, reports::add);
		GenericClientActivityContext travel = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.TRAVEL).withResolver(resolver);
		travel.resolve();
		travel.resolve();
		assertEquals(1, reports.size());
		GenericClientActionBoundary.Ticket parent = new GenericClientActionBoundary.Ticket();
		GenericClientActivityContext plain = travel.plain().withTicket(parent.child());
		plain.resolve();
		plain.withPolicy(Map.of("mouse", "fast")).resolve();
		assertEquals(2, reports.size());
		assertEquals("fast", ((Map<?, ?>) resolver.status().get("declared_policy")).get("mouse"));
		assertEquals("natural", ((Map<?, ?>) resolver.status().get("effective_policy")).get("mouse"));
		parent.cancel();
		GenericClientActivityContext.preset(GenericClientActivityContext.Activity.SKILLING).withResolver(resolver).resolve();
		plain.resolve();
		assertEquals(3, reports.size());
		assertEquals("skilling", resolver.status().get("activity"));
	}
}
