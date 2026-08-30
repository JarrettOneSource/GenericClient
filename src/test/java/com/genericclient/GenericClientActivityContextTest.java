package com.genericclient;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GenericClientActivityContextTest
{
	@Test
	public void activityPoliciesMatchTheirInteractionRisk()
	{
		for (GenericClientActivityContext.Activity activity : new GenericClientActivityContext.Activity[]{
			GenericClientActivityContext.Activity.GENERAL,
			GenericClientActivityContext.Activity.QUESTING,
			GenericClientActivityContext.Activity.TRAVEL,
			GenericClientActivityContext.Activity.SKILLING})
		{
			GenericClientActivityContext context = GenericClientActivityContext.of(activity, true);
			assertTrue(context.allowsBreaks());
			assertTrue(context.allowsCursorRelease());
		}

		for (GenericClientActivityContext.Activity activity : new GenericClientActivityContext.Activity[]{
			GenericClientActivityContext.Activity.DIALOGUE,
			GenericClientActivityContext.Activity.COMBAT,
			GenericClientActivityContext.Activity.BANKING,
			GenericClientActivityContext.Activity.TRADING})
		{
			GenericClientActivityContext context = GenericClientActivityContext.of(activity, true);
			assertFalse(context.allowsBreaks());
			assertFalse(context.allowsCursorRelease());
		}
	}

	@Test
	public void explicitBehaviorBypassDisablesBothIndependentPolicies()
	{
		GenericClientActivityContext context = GenericClientActivityContext.of(
			GenericClientActivityContext.Activity.TRAVEL,
			false);

		assertFalse(context.allowsBreaks());
		assertFalse(context.allowsCursorRelease());
	}
}
