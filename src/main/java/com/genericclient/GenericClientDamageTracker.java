package com.genericclient;

import java.util.Set;
import net.runelite.api.HitsplatID;
import net.runelite.api.gameval.VarPlayerID;

/** Matches copied hitsplat evidence to consecutive HP and poison observations. */
final class GenericClientDamageTracker
{
	private static final Set<Integer> HP_DAMAGE = Set.of(
		HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_OTHER, HitsplatID.DAMAGE_ME_CYAN, HitsplatID.DAMAGE_OTHER_CYAN,
		HitsplatID.DAMAGE_ME_ORANGE, HitsplatID.DAMAGE_OTHER_ORANGE, HitsplatID.DAMAGE_ME_YELLOW,
		HitsplatID.DAMAGE_OTHER_YELLOW, HitsplatID.DAMAGE_ME_WHITE, HitsplatID.DAMAGE_OTHER_WHITE,
		HitsplatID.DAMAGE_MAX_ME, HitsplatID.DAMAGE_MAX_ME_CYAN, HitsplatID.DAMAGE_MAX_ME_ORANGE,
		HitsplatID.DAMAGE_MAX_ME_YELLOW, HitsplatID.DAMAGE_MAX_ME_WHITE, HitsplatID.DAMAGE_ME_POISE,
		HitsplatID.DAMAGE_OTHER_POISE, HitsplatID.DAMAGE_MAX_ME_POISE, HitsplatID.BLEED, HitsplatID.BURN);
	private static final Set<Integer> NON_DAMAGE = Set.of(HitsplatID.HEAL, HitsplatID.CORRUPTION,
		HitsplatID.PRAYER_DRAIN, HitsplatID.SANITY_DRAIN, HitsplatID.SANITY_RESTORE);
	private GenericClientSnapshot previous;
	private boolean previousThreat;
	private boolean ordinaryHit;
	private boolean unclassifiedHit;
	private long poisonDamage;
	private long venomDamage;

	void record(int type, int amount)
	{
		if (amount <= 0) return;
		if (HP_DAMAGE.contains(type)) ordinaryHit = true;
		else if (type == HitsplatID.POISON) poisonDamage += amount;
		else if (type == HitsplatID.VENOM) venomDamage += amount;
		else if (!NON_DAMAGE.contains(type)) unclassifiedHit = true;
	}

	Damage observe(GenericClientSnapshot snapshot, boolean threatsPresent)
	{
		if (snapshot == null || !snapshot.isLoggedIn() || snapshot.getPlayer() == null ||
			snapshot.getPlayer().getName() == null || snapshot.getCurrentHitpoints() <= 0)
		{
			reset();
			return Damage.NONE;
		}
		if (previous != null && (!snapshot.getPlayer().getName().equals(previous.getPlayer().getName()) ||
			snapshot.getPlayer().getWorldViewId() != previous.getPlayer().getWorldViewId())) previous = null;
		Damage result = classify(snapshot, threatsPresent);
		previous = snapshot;
		previousThreat = threatsPresent;
		clearHits();
		return result;
	}

	void reset()
	{
		previous = null;
		previousThreat = false;
		clearHits();
	}

	private Damage classify(GenericClientSnapshot snapshot, boolean threatsPresent)
	{
		if (ordinaryHit) return Damage.ORDINARY_HIT;
		if (previous == null) return Damage.NONE;
		int loss = previous.getCurrentHitpoints() - snapshot.getCurrentHitpoints();
		if (loss <= 0) return Damage.NONE;
		if (threatsPresent || previousThreat || unclassifiedHit ||
			snapshot.getGameTick() != previous.getGameTick() + 1)
			return Damage.UNEXPLAINED_HP_LOSS;
		return matchingPoison(snapshot, loss);
	}

	private Damage matchingPoison(GenericClientSnapshot snapshot, int loss)
	{
		Integer before = previous.varp(VarPlayerID.POISON);
		Integer after = snapshot.varp(VarPlayerID.POISON);
		if (before == null || after == null) return Damage.UNEXPLAINED_HP_LOSS;
		if (before >= 1 && before <= 100 && after == before - 1 && loss == (before + 4) / 5 &&
			poisonDamage == loss && venomDamage == 0) return Damage.EXPECTED_POISON;
		if (before >= 1_000_000 && after == before + 1L &&
			loss == Math.min(20L, 6L + 2L * (before - 1_000_000L)) &&
			venomDamage == loss && poisonDamage == 0) return Damage.EXPECTED_VENOM;
		return Damage.UNEXPLAINED_HP_LOSS;
	}

	private void clearHits()
	{
		ordinaryHit = false;
		unclassifiedHit = false;
		poisonDamage = 0;
		venomDamage = 0;
	}

	enum Damage
	{
		NONE(false), EXPECTED_POISON(false), EXPECTED_VENOM(false), ORDINARY_HIT(true), UNEXPLAINED_HP_LOSS(true);

		final boolean unexpected;
		Damage(boolean unexpected) { this.unexpected = unexpected; }
	}
}
