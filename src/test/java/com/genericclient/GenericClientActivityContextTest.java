package com.genericclient;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GenericClientActivityContextTest
{
	@Test
	public void restScopesFollowTheirAwaitWithoutReplacingItsActiveInputChild()
	{
		GenericClientActivityContext root = GenericClientActivityContext.none().openInputScope();
		GenericClientActivityContext walk = root.openInputScope();
		GenericClientActivityContext rest = root.forkInputScope();
		assertTrue(walk.isInputAllowed());
		assertTrue(rest.isInputAllowed());
		root.inputTicket().suspendInput(true);
		assertFalse(rest.isInputAllowed());
		root.inputTicket().suspendInput(false);
		assertTrue(rest.isInputAllowed());
		rest.cancelInput();
		assertTrue(walk.isInputAllowed());
		GenericClientActivityContext anotherRest = root.forkInputScope();
		root.cancelInput();
		assertFalse(anotherRest.isInputAllowed());
		assertFalse(walk.isInputAllowed());
	}

	@Test
	public void activityPoliciesMatchTheirInteractionRisk()
	{
		for (GenericClientActivityContext.Activity activity : new GenericClientActivityContext.Activity[]{
			GenericClientActivityContext.Activity.GENERAL,
			GenericClientActivityContext.Activity.QUESTING,
			GenericClientActivityContext.Activity.TRAVEL,
			GenericClientActivityContext.Activity.SKILLING})
		{
			GenericClientActivityContext context = GenericClientActivityContext.preset(activity);
			assertTrue(context.allowsBreaks());
			assertTrue(context.allowsCursorRelease());
		}

		for (GenericClientActivityContext.Activity activity : new GenericClientActivityContext.Activity[]{
			GenericClientActivityContext.Activity.DIALOGUE,
			GenericClientActivityContext.Activity.HAZARDOUS_TRAVEL,
			GenericClientActivityContext.Activity.COMBAT,
			GenericClientActivityContext.Activity.BANKING,
			GenericClientActivityContext.Activity.TRADING})
		{
			GenericClientActivityContext context = GenericClientActivityContext.preset(activity);
			assertFalse(context.allowsBreaks());
			assertFalse(context.allowsCursorRelease());
		}
	}

	@Test
	public void hazardousTravelAndCombatUseTheFixedPerformanceMouseSpeed()
	{
		for (GenericClientActivityContext.Activity activity : new GenericClientActivityContext.Activity[]{
			GenericClientActivityContext.Activity.HAZARDOUS_TRAVEL,
			GenericClientActivityContext.Activity.COMBAT})
		{
			GenericClientActivityContext context = GenericClientActivityContext.preset(activity);
			assertEquals(GenericClientActivityContext.PERFORMANCE_MOUSE_MOVE_DURATION_MILLIS,
				context.mouseMoveDurationMillis(650));
			assertEquals(GenericClientActivityContext.PERFORMANCE_MOUSE_MOVE_DURATION_MILLIS,
				context.mouseMoveDurationMillis(150));
		}
	}

	@Test
	public void explicitBehaviorBypassDisablesBothIndependentPolicies()
	{
		GenericClientActivityContext context = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.TRAVEL).inIntent();

		assertFalse(context.allowsBreaks());
		assertFalse(context.allowsCursorRelease());
	}

	@Test
	public void onlySkillingCouplesCursorReleaseToMicroBreaks()
	{
		GenericClientActivityContext skilling = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.SKILLING);
		GenericClientActivityContext travel = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.TRAVEL);

		assertEquals(GenericClientBehaviorPolicy.CursorRelease.WITH_BREAK, skilling.policy().cursorRelease);
		assertEquals("with_break", skilling.policy().toMap().get("cursor_release"));
		assertEquals(GenericClientBehaviorPolicy.CursorRelease.INDEPENDENT, travel.policy().cursorRelease);
		assertEquals("independent", travel.policy().toMap().get("cursor_release"));
		assertEquals("none",
			GenericClientActivityContext.preset(GenericClientActivityContext.Activity.SKILLING).inIntent().policy().toMap().get("cursor_release"));
	}
}
