package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;
import java.util.Map;
import org.junit.Test;

public class GenericClientBehaviorProfileTest
{
	@Test
	public void reportsSeededRestRatesAndPreservesThemAcrossUnrelatedOverrides()
	{
		GenericClientBehaviorProfile generated = GenericClientBehaviorProfile.fromAccountHash(123);
		Map<String, Object> profile = generated.toMap();
		assertTrue("Rest rates must be part of the account profile", profile.get("cursor_rest") instanceof Map);
		GenericClientBehaviorProfile custom = generated.withOverrides(new GenericClientBehaviorOverrides(
			0.5, 0.5, 5, 0.02, 100, 20, 2, GenericClientBehaviorProfile.LongBreakMode.AFK,
			0.1, GenericClientBehaviorProfile.Edge.LEFT, 500, 60, 50, GenericClientBehaviorProfile.DialogueInputMode.MOUSE));
		assertEquals(profile.get("cursor_rest"), custom.toMap().get("cursor_rest"));
		assertNotEquals(profile.get("cursor_rest"), GenericClientBehaviorProfile.fromAccountHash(456).toMap().get("cursor_rest"));
	}

	@Test
	public void derivesAStableProfileWithoutExposingTheAccountHash()
	{
		long accountHash = 0x123456789ABCDEFL;
		GenericClientBehaviorProfile first = GenericClientBehaviorProfile.fromAccountHash(accountHash);
		GenericClientBehaviorProfile second = GenericClientBehaviorProfile.fromAccountHash(accountHash);

		assertEquals(first.toMap(), second.toMap());
		assertEquals(16, first.getId().length());
		assertNotEquals(Long.toString(accountHash), first.getId());
		assertFalse(first.getSummary().contains(Long.toString(accountHash)));
		assertFalse(first.toMap().toString().contains(Long.toString(accountHash)));
		assertTrue(first.getTitle().contains(";"));
		assertTrue(first.getSummary().contains("per active hour"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsThePreLoginAccountSentinel()
	{
		GenericClientBehaviorProfile.fromAccountHash(-1L);
	}

	@Test
	public void everyDerivedValueStaysInsideTheApprovedEnvelope()
	{
		EnumSet<GenericClientBehaviorProfile.Edge> edges =
			EnumSet.noneOf(GenericClientBehaviorProfile.Edge.class);
		for (long accountHash = 0; accountHash < 20_000; accountHash++)
		{
			GenericClientBehaviorProfile profile = GenericClientBehaviorProfile.fromAccountHash(accountHash);
			assertBetween(profile.getMicroBreakProbability(), 0.02, 1.0);
			assertBetween(profile.getCursorReleaseProbability(), 0.15, 0.95);
			assertBetween(profile.getShortBodyMedianSeconds(), 2.0, 6.0);
			assertBetween(profile.getShortTailProbability(), 0.01, 0.04);
			assertBetween(profile.getLongCadenceMinutes(), 39.9, 300.1);
			assertBetween(profile.getLongRefractoryMinutes(), 10.0, 60.0);
			assertBetween(profile.getLongMedianMinutes(), 7.0, 22.0);
			assertBetween(profile.getPhaseShortChances(), 1.0, 4.0);
			assertBetween(profile.getPhaseLongBonusMaximum(), 0.0, 1.5);
			assertBetween(profile.getOppositeLongBreakProbability(), 0.02, 0.15);
			assertBetween(profile.getMouseMoveDurationMillis(), 300, 650);
			assertBetween(profile.getTypingWordsPerMinute(), 35, 100);
			assertBetween(profile.getDialogueReadingPercent(), 0, 100);
			assertBetween(profile.getReferenceDowntimePercent(), 0.0, 50.0);
			assertBetween(profile.getWalkClickTypicalSeconds(), 2.0, 6.0);
			assertBetween(profile.getWalkNearClickProbability(), 0.10, 0.30);
			GenericClientBehaviorProfile.CursorStyle rest = profile.getCursorStyle();
			assertBetween(rest.fidgetsPerMinute, 1, 8);
			assertBetween(rest.driftPixels, 2, 8);
			assertBetween(rest.relocationShare, 0.05, 0.25);
			assertBetween(rest.anticipationProbability, 0.15, 0.55);
			edges.add(profile.getIdleEdge());
		}
		assertEquals(EnumSet.allOf(GenericClientBehaviorProfile.Edge.class), edges);
	}

	@Test
	public void softCorrelationStillProducesAllHumanPlausibleQuadrants()
	{
		int frequentShortFrequentLong = 0;
		int frequentShortRareLong = 0;
		int rareShortFrequentLong = 0;
		int rareShortRareLong = 0;
		for (long accountHash = 50_000; accountHash < 150_000; accountHash++)
		{
			GenericClientBehaviorProfile profile = GenericClientBehaviorProfile.fromAccountHash(accountHash);
			boolean frequentShort = profile.getMicroBreakProbability() >= 0.55;
			boolean rareShort = profile.getMicroBreakProbability() < 0.15;
			boolean frequentLong = profile.getLongCadenceMinutes() < 80.0;
			boolean rareLong = profile.getLongCadenceMinutes() >= 160.0;
			if (frequentShort && frequentLong)
			{
				frequentShortFrequentLong++;
			}
			if (frequentShort && rareLong)
			{
				frequentShortRareLong++;
			}
			if (rareShort && frequentLong)
			{
				rareShortFrequentLong++;
			}
			if (rareShort && rareLong)
			{
				rareShortRareLong++;
			}
		}

		assertTrue(frequentShortFrequentLong > 500);
		assertTrue(frequentShortRareLong > 500);
		assertTrue(rareShortFrequentLong > 500);
		assertTrue(rareShortRareLong > 500);
	}

	@Test
	public void structuredDescriptionMatchesTheNumericProfile()
	{
		GenericClientBehaviorProfile profile = GenericClientBehaviorProfile.fromAccountHash(987654321L);
		Map<String, Object> value = profile.toMap();

		assertEquals(GenericClientBehaviorProfile.SCHEMA, value.get("schema"));
		assertEquals(profile.getTitle(), value.get("title"));
		assertEquals(profile.getSummary(), value.get("summary"));
		assertEquals(profile.getIdleEdge().name().toLowerCase(), value.get("idle_edge"));
		assertEquals(profile.getMicroBreakProbability(),
			(Double) value.get("micro_break_probability"), 0.0);
		assertEquals(profile.getCursorReleaseProbability(),
			(Double) value.get("cursor_release_probability"), 0.0);
		assertEquals(profile.getLongCadenceMinutes(),
			(Double) value.get("long_cadence_minutes"), 0.0);
		assertEquals((long) profile.getMouseMoveDurationMillis(),
			value.get("mouse_move_duration_millis"));
		assertEquals((long) profile.getTypingWordsPerMinute(),
			value.get("typing_words_per_minute"));
		assertEquals((long) profile.getDialogueReadingPercent(),
			value.get("dialogue_reading_percent"));
		assertEquals(profile.getDialogueReadingStyle(), value.get("dialogue_reading_style"));
		assertEquals((long) profile.getDialogueWordsPerMinute(),
			value.get("dialogue_words_per_minute"));
		assertEquals(profile.getDialogueInputMode().name().toLowerCase(java.util.Locale.ROOT),
			value.get("dialogue_input_mode"));
	}

	@Test
	public void manualOverridesRecomputeEveryDerivedValue()
	{
		GenericClientBehaviorProfile generated = GenericClientBehaviorProfile.fromAccountHash(123L);
		GenericClientBehaviorProfile custom = generated.withOverrides(new GenericClientBehaviorOverrides(
			0.90,
			0.70,
			10.0,
			0.25,
			200.0,
			30.0,
			2.5,
			GenericClientBehaviorProfile.LongBreakMode.LOGOUT,
			0.20,
			GenericClientBehaviorProfile.Edge.BOTTOM,
			775,
			90,
			90,
			GenericClientBehaviorProfile.DialogueInputMode.KEYBOARD));

		assertTrue(custom.isCustomized());
		assertEquals(generated.getId(), custom.getId());
		assertEquals(generated.getWalkClickTypicalSeconds(), custom.getWalkClickTypicalSeconds(), 0.0);
		assertEquals(generated.getWalkNearClickProbability(), custom.getWalkNearClickProbability(), 0.0);
		assertEquals(0.90, custom.getMicroBreakProbability(), 0.0);
		assertEquals(GenericClientBehaviorProfile.DialogueInputMode.KEYBOARD,
			custom.getDialogueInputMode());
		assertEquals(0.70, custom.getCursorReleaseProbability(), 0.0);
		assertEquals(10.0, custom.getShortBodyMedianSeconds(), 0.0);
		assertEquals(0.25, custom.getShortTailProbability(), 0.0);
		assertEquals(200.0, custom.getLongCadenceMinutes(), 0.0);
		assertEquals(60.0, custom.getLongRefractoryMinutes(), 0.0);
		assertEquals(30.0, custom.getLongMedianMinutes(), 0.0);
		assertEquals(2.5, custom.getPhaseShortChances(), 0.0);
		assertEquals(0.75, custom.getPhaseLongBonusMaximum(), 0.0);
		assertEquals(GenericClientBehaviorProfile.LongBreakMode.LOGOUT,
			custom.getFavoredLongBreakMode());
		assertEquals(GenericClientBehaviorProfile.Edge.BOTTOM, custom.getIdleEdge());
		assertEquals(775, custom.getMouseMoveDurationMillis());
		assertEquals(90, custom.getTypingWordsPerMinute());
		assertEquals(90, custom.getDialogueReadingPercent());
		assertEquals("slow reader", custom.getDialogueReadingStyle());
		assertTrue((Boolean) custom.toMap().get("customized"));
		assertTrue(custom.getSummary().contains("32.4 micro breaks per active hour"));
		assertTrue(custom.getSummary().contains("200 active minutes"));
	}

	private static void assertBetween(double value, double minimum, double maximum)
	{
		assertTrue("Expected " + value + " >= " + minimum, value >= minimum);
		assertTrue("Expected " + value + " <= " + maximum, value <= maximum);
	}
}
