package com.genericclient;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientLuaPolicyTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void operatorActivityDoesNotReplaceTheStandaloneClockOwner() throws Exception
	{
		CompletableFuture<Void> entered = new CompletableFuture<>();
		CompletableFuture<GenericClientInteractionResult> action = new CompletableFuture<>();
		try (GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporary, "clock-owner")
			.walkRandom(context -> { entered.complete(null); return action; }).build())
		{
			host.publishGameTick(new GenericClientSnapshot(1, "LOGGED_IN", 225,
				new GenericClientWorldSnapshot.PlayerSnapshot("clock-test", 3200, 3200, 0, -1), Collections.emptyList()));
			host.catalog.saveScript("clock", "Clock", "Keep the script active",
				GenericClientTestSupport.script("gc.activity('skilling'); gc.await {ticks=100}"))
				.get(2, TimeUnit.SECONDS);
			host.start("clock").get(2, TimeUnit.SECONDS);
			CompletableFuture<Map<String, Object>> operator = host.evaluate(
				"gc.activity('manual'); return gc.await {action={type='walk.random'}}");
			entered.get(2, TimeUnit.SECONDS);
			assertTrue(host.getRunState().isRunning());
			assertEquals(GenericClientActivityContext.Activity.MANUAL, host.getBehaviorContext().getActivity());
			assertEquals(GenericClientActivityContext.Activity.SKILLING, host.ownedBehaviorContext().getActivity());
			action.complete(GenericClientTestSupport.interaction("walked"));
			operator.get(2, TimeUnit.SECONDS);
			assertEquals(GenericClientActivityContext.Activity.SKILLING, host.ownedBehaviorContext().getActivity());
		}
	}

	@Test
	public void combatCanEnableBreaksWithoutChangingItsMouseOrPrayerOwner() throws Exception
	{
		AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
		try (GenericClientLuaHost host = host(input))
		{
			Map<String, Object> response = host.evaluate("gc.activity('combat', { breaks = true }); " +
				"return gc.await { action = { type = 'combat.set_style', style = 0 }, humanize = true }")
				.get(2, TimeUnit.SECONDS);
			assertEquals("ready", beforeStatus(response));
			assertTrue(input.get().allowsBreaks());
			assertEquals(GenericClientBehaviorPolicy.PrayerOwner.GUARD, input.get().policy().prayerOwner);
			assertFalse(input.get().allowsCursorRelease());
			assertEquals(180, input.get().mouseMoveDurationMillis(550));
		}
	}

	@Test
	public void plainOperatorInputRetainsHazardousRoutePolicy() throws Exception
	{
		AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
		try (GenericClientLuaHost host = host(input))
		{
			host.evaluate("gc.activity('hazardous_travel'); return gc.await { action = { type = 'walk.random' } }")
				.get(2, TimeUnit.SECONDS);
			assertTrue(input.get().refreshesWalkClicks());
			assertEquals(GenericClientBehaviorPolicy.PrayerOwner.GUARD, input.get().policy().prayerOwner);
			assertEquals(550, input.get().mouseMoveDurationMillis(550));
			assertFalse(input.get().allowsBreaks());
			assertFalse(input.get().allowsCursorRelease());
		}
	}

	@Test
	public void declaredActivityWinsOverActionDefaultsAndCanLeaveHazardousTravel() throws Exception
	{
		AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
		try (GenericClientLuaHost host = host(input))
		{
			host.evaluate("return gc.await { action = { type = 'combat.set_style', style = 0 }, humanize = true }")
				.get(2, TimeUnit.SECONDS);
			assertEquals(GenericClientActivityContext.Activity.COMBAT, input.get().getActivity());
			host.evaluate("gc.activity('general'); return gc.await { action = { type = 'combat.set_style', style = 0 }, humanize = true }")
				.get(2, TimeUnit.SECONDS);
			assertEquals(GenericClientActivityContext.Activity.GENERAL, input.get().getActivity());
			assertTrue(input.get().allowsBreaks());
			assertEquals(550, input.get().mouseMoveDurationMillis(550));
			host.evaluate("gc.activity('hazardous_travel'); gc.activity('combat'); " +
				"return gc.await { action = { type = 'walk.random' }, humanize = true }").get(2, TimeUnit.SECONDS);
			assertEquals(GenericClientActivityContext.Activity.COMBAT, input.get().getActivity());
			assertFalse(input.get().refreshesWalkClicks());
		}
	}

	@Test
	public void allAwaitPolicyFieldsAreIndependentAndDoNotLeakToTheNextAction() throws Exception
	{
		AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
		try (GenericClientLuaHost host = host(input))
		{
			Map<String, Object> response = host.evaluate("gc.activity('skilling', { breaks=false, mouse='fast', " +
				"prayer_owner='guard', cursor_release='none', damage_expected=true, walk_refresh=true, fidget='drift' }); " +
				"local first=gc.await { action={type='walk.random'}, humanize=true, policy={breaks=true, mouse='natural', " +
				"prayer_owner='script', cursor_release='with_break', damage_expected=false, walk_refresh=false, fidget='full'} }; " +
				"local second=gc.await { action={type='walk.random'}, humanize=true }; " +
				"return {first=first.behavior_before.policy, second=second.behavior_before.policy}")
				.get(2, TimeUnit.SECONDS);
			Map<?, ?> value = (Map<?, ?>) response.get("value");
			assertEquals(Map.of("breaks", true, "mouse", "natural", "prayer_owner", "script",
				"cursor_release", "with_break", "damage_expected", false, "walk_refresh", false, "fidget", "full"), value.get("first"));
			assertEquals(Map.of("breaks", false, "mouse", "fast", "prayer_owner", "guard",
				"cursor_release", "none", "damage_expected", true, "walk_refresh", true, "fidget", "drift"), value.get("second"));
		}
	}

	@Test
	public void phaseAndWalkHelpersForwardPolicyAndIntentKeepsFastInput() throws Exception
	{
		AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
		try (GenericClientLuaHost host = host(input))
		{
			Map<String, Object> phase = host.evaluate("return gc.phase('test', {humanize=true, " +
				"policy={breaks=false, mouse='fast'}}).policy").get(2, TimeUnit.SECONDS);
			assertEquals("fast", ((Map<?, ?>) phase.get("value")).get("mouse"));
			assertEquals(false, ((Map<?, ?>) phase.get("value")).get("breaks"));
			host.evaluate("return gc.walk.to {destination={x=3201,y=3200,plane=0}, activity='hazardous_travel', " +
				"humanize=true, policy={walk_refresh=false, mouse='natural'}}").get(2, TimeUnit.SECONDS);
			assertFalse(input.get().refreshesWalkClicks());
			assertEquals(550, input.get().mouseMoveDurationMillis(550));
			host.evaluate("gc.activity('combat', {breaks=true}); return gc.intent('setup', function() " +
				"return gc.await {action={type='combat.set_style', style=0}, humanize=true} end)")
				.get(2, TimeUnit.SECONDS);
			assertFalse(input.get().allowsBreaks());
			assertEquals(180, input.get().mouseMoveDurationMillis(550));
		}
	}

	@Test
	public void rejectsLegacyBreakFieldsBeforeDispatchWhileAcceptingExplicitPolicy() throws Exception
	{
		AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
		try (GenericClientLuaHost host = host(input))
		{
			for (String body : new String[]{
				"gc.await {action={type='walk.random'}, breaks=false}",
				"gc.await {action={type='walk.random'}, breaks=true}",
				"gc.phase('legacy', {breaks=false})",
				"gc.walk.to {destination={x=3201,y=3200,plane=0}, breaks=false}"})
			{
				Map<String, Object> rejected = host.evaluate("return " + body).get(2, TimeUnit.SECONDS);
				assertEquals("faulted", rejected.get("status"));
				assertTrue(String.valueOf(rejected).contains("breaks"));
				assertNull(input.get());
			}
			Map<String, Object> accepted = host.evaluate("return gc.await {action={type='walk.random'}, " +
				"humanize=true, policy={breaks=false,cursor_release='none',fidget='none'}}").get(2, TimeUnit.SECONDS);
			assertEquals("completed", accepted.get("status"));
			assertFalse(input.get().allowsBreaks());
			assertFalse(input.get().allowsCursorRelease());
		}
	}

	@Test
	public void invalidPolicyCannotDispatchInputOrReplaceTheLastActivity() throws Exception
	{
		AtomicReference<GenericClientActivityContext> input = new AtomicReference<>();
		try (GenericClientLuaHost host = host(input))
		{
			for (String policy : new String[]{"{breaks='yes'}", "{cursor_release=false}", "{mouse='warp'}", "{typo=true}", "false"})
			{
				Map<String, Object> rejected = host.evaluate("return gc.await {action={type='walk.random'}, policy=" + policy + "}")
					.get(2, TimeUnit.SECONDS);
				assertEquals("faulted", rejected.get("status"));
				assertNull(input.get());
			}
			Map<String, Object> retained = host.evaluate("gc.activity('skilling'); local ok, message=pcall(function() " +
				"gc.activity('combat', {prayer_owner='nobody'}) end); return {ok=ok, message=message, activity=gc.activity()}")
				.get(2, TimeUnit.SECONDS);
			Map<?, ?> value = (Map<?, ?>) retained.get("value");
			assertEquals(false, value.get("ok"));
			assertTrue(String.valueOf(value.get("message")).contains("policy.prayer_owner"));
			assertEquals("skilling", value.get("activity"));
		}
	}

	private GenericClientLuaHost host(AtomicReference<GenericClientActivityContext> input) throws Exception
	{
		GenericClientLuaHost host = GenericClientTestSupport.luaHost(temporary, "policy")
			.combatMode((mode, context) -> {
				input.set(context);
				return CompletableFuture.completedFuture(GenericClientTestSupport.receipt("set"));
			})
			.walkRandom(context -> {
				input.set(context);
				return CompletableFuture.completedFuture(GenericClientTestSupport.interaction("walked"));
			})
			.walkTo((request, clicks) -> {
				input.set(request.activityContext);
				return CompletableFuture.completedFuture(GenericClientTestSupport.receipt("arrived"));
			}).build();
		host.publishGameTick(new GenericClientSnapshot(1, "LOGGED_IN", 225,
			new GenericClientWorldSnapshot.PlayerSnapshot("policy-test", 3200, 3200, 0, -1), Collections.emptyList()));
		return host;
	}

	private static Object beforeStatus(Map<String, Object> response)
	{
		Map<?, ?> receipt = (Map<?, ?>) response.get("value");
		return ((Map<?, ?>) receipt.get("behavior_before")).get("status");
	}
}
