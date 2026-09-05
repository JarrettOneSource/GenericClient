package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.util.List;
import net.runelite.api.HitsplatID;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Test;

public class GenericClientDamageTrackerTest
{
	@Test
	public void naturalPoisonAndVenomRequireAlignedTypedDamageAndSeverityProgression()
	{
		int[][] cases = {{6, 5, 2, HitsplatID.POISON}, {1, 0, 1, HitsplatID.POISON},
			{100, 99, 20, HitsplatID.POISON}, {1_000_000, 1_000_001, 6, HitsplatID.VENOM},
			{Integer.MAX_VALUE - 1, Integer.MAX_VALUE, 20, HitsplatID.VENOM}};
		for (int[] example : cases)
		{
			GenericClientDamageTracker tracker = new GenericClientDamageTracker();
			tracker.observe(snapshot(10, 80, example[0]), false);
			tracker.record(example[3], example[2]);
			assertEquals(example[3] == HitsplatID.POISON
				? GenericClientDamageTracker.Damage.EXPECTED_POISON : GenericClientDamageTracker.Damage.EXPECTED_VENOM,
				tracker.observe(snapshot(11, 80 - example[2], example[1]), false));
		}
	}

	@Test
	public void severityAloneNeverExemptsAnHpLoss()
	{
		GenericClientDamageTracker tracker = new GenericClientDamageTracker();
		tracker.observe(snapshot(10, 80, 6), false);
		assertEquals(GenericClientDamageTracker.Damage.UNEXPLAINED_HP_LOSS,
			tracker.observe(snapshot(11, 78, 5), false));
	}

	@Test
	public void ordinaryDamageCannotBeHiddenBySimultaneousPoisonAndHealing()
	{
		for (int hp : new int[]{78, 80, 90})
		{
			GenericClientDamageTracker tracker = new GenericClientDamageTracker();
			tracker.observe(snapshot(10, 80, 6), false);
			tracker.record(HitsplatID.POISON, 2);
			tracker.record(HitsplatID.DAMAGE_ME, 3);
			tracker.record(HitsplatID.HEAL, hp - 75);
			assertEquals(GenericClientDamageTracker.Damage.ORDINARY_HIT,
				tracker.observe(snapshot(11, hp, 5), false));
		}
	}

	@Test
	public void unknownAndDiseaseHitsVetoPoisonAttribution()
	{
		for (int type : new int[]{HitsplatID.DISEASE, HitsplatID.DISEASE_BLOCKED,
			HitsplatID.BLOCK_ME, HitsplatID.DOOM, 12345})
		{
			GenericClientDamageTracker tracker = new GenericClientDamageTracker();
			tracker.observe(snapshot(10, 80, 6), false);
			tracker.record(HitsplatID.POISON, 2);
			tracker.record(type, 1);
			assertEquals(GenericClientDamageTracker.Damage.UNEXPLAINED_HP_LOSS,
				tracker.observe(snapshot(11, 78, 5), false));
		}
	}

	@Test
	public void blockedAndResourceHitsDoNotBecomeHpDamage()
	{
		GenericClientDamageTracker tracker = new GenericClientDamageTracker();
		tracker.observe(snapshot(10, 80, 6), false);
		for (int type : new int[]{HitsplatID.HEAL, HitsplatID.CORRUPTION, HitsplatID.PRAYER_DRAIN,
			HitsplatID.SANITY_DRAIN, HitsplatID.SANITY_RESTORE}) tracker.record(type, 5);
		tracker.record(HitsplatID.BLOCK_ME, 0);
		tracker.record(HitsplatID.DAMAGE_ME, 0);
		assertEquals(GenericClientDamageTracker.Damage.NONE, tracker.observe(snapshot(11, 80, 6), false));
	}

	@Test
	public void attackersInEitherFramePreventAClassificationAsPoisonOnly()
	{
		for (boolean before : new boolean[]{true, false})
		{
			GenericClientDamageTracker tracker = new GenericClientDamageTracker();
			tracker.observe(snapshot(10, 80, 6), before);
			tracker.record(HitsplatID.POISON, 2);
			assertEquals(GenericClientDamageTracker.Damage.UNEXPLAINED_HP_LOSS,
				tracker.observe(snapshot(11, 78, 5), !before));
		}
	}

	@Test
	public void wrongAmountTypeSeverityAndTickNeverMatch()
	{
		int[][] cases = {{10, 78, 5, HitsplatID.POISON, 2}, {12, 78, 5, HitsplatID.POISON, 2},
			{11, 77, 5, HitsplatID.POISON, 2}, {11, 78, 6, HitsplatID.POISON, 2},
			{11, 78, 7, HitsplatID.POISON, 2}, {11, 78, 5, HitsplatID.VENOM, 2},
			{11, 78, 5, HitsplatID.POISON, 1}};
		for (int[] example : cases)
		{
			GenericClientDamageTracker tracker = new GenericClientDamageTracker();
			tracker.observe(snapshot(10, 80, 6), false);
			tracker.record(example[3], example[4]);
			assertEquals(GenericClientDamageTracker.Damage.UNEXPLAINED_HP_LOSS,
				tracker.observe(snapshot(example[0], example[1], example[2]), false));
		}
	}

	@Test
	public void infectionCureAndUndocumentedSeverityHaveNoAutomaticExemption()
	{
		for (int severity : new int[]{-1, 0, 101, 999999})
		{
			GenericClientDamageTracker tracker = new GenericClientDamageTracker();
			tracker.observe(snapshot(10, 80, severity), false);
			tracker.record(HitsplatID.POISON, 1);
			assertEquals(GenericClientDamageTracker.Damage.UNEXPLAINED_HP_LOSS,
				tracker.observe(snapshot(11, 79, severity - 1), false));
		}
	}

	@Test
	public void missingFramesAndResetDiscardPreviousEventsAndHp()
	{
		GenericClientDamageTracker tracker = new GenericClientDamageTracker();
		tracker.observe(snapshot(10, 80, 6), false);
		tracker.record(HitsplatID.DAMAGE_ME, 4);
		tracker.observe(null, false);
		assertEquals(GenericClientDamageTracker.Damage.NONE, tracker.observe(snapshot(11, 76, 6), false));
		tracker.record(HitsplatID.DAMAGE_ME, 3);
		tracker.reset();
		assertEquals(GenericClientDamageTracker.Damage.NONE, tracker.observe(snapshot(12, 73, 6), false));
	}

	@Test
	public void playerAndWorldChangesStartANewHpBaseline()
	{
		GenericClientDamageTracker tracker = new GenericClientDamageTracker();
		tracker.observe(snapshot(10, 80, 6), false);
		tracker.record(HitsplatID.POISON, 2);
		assertEquals(GenericClientDamageTracker.Damage.NONE,
			tracker.observe(snapshot(11, 78, 5, "different-player", -1), false));
		tracker.record(HitsplatID.POISON, 1);
		assertEquals(GenericClientDamageTracker.Damage.NONE,
			tracker.observe(snapshot(12, 77, 4, "different-player", 3), false));
		tracker.record(HitsplatID.DAMAGE_ME, 3);
		assertEquals(GenericClientDamageTracker.Damage.ORDINARY_HIT,
			tracker.observe(snapshot(13, 74, 4, "different-player", 4), false));
	}

	static GenericClientSnapshot snapshot(long tick, int hp, int poison)
	{
		return snapshot(tick, hp, poison, "guard-test", -1);
	}

	private static GenericClientSnapshot snapshot(long tick, int hp, int poison, String name, int worldView)
	{
		int[] varps = new int[VarPlayerID.POISON + 1];
		varps[VarPlayerID.POISON] = poison;
		return new GenericClientSnapshot(tick, "LOGGED_IN", 240,
			new GenericClientWorldSnapshot.PlayerSnapshot(name, 3200, 3200, 0, worldView,
				-1, null, hp, 99, 10000, true, null), List.of(), GenericClientAccountSnapshot.empty(),
			new GenericClientQuestSnapshot(true, varps, List.of(), GenericClientQuestSnapshot.DialogueSnapshot.closed()));
	}
}
