package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import org.junit.Test;

public class GenericClientSpellInputTest
{
	@Test
	public void resolvesSupportedSpellNamesWithoutExposingWidgetIdsToLua()
	{
		assertEquals(GenericClientSpellInput.Spell.WIND_STRIKE,
			GenericClientSpellInput.Spell.fromName("Wind Strike"));
		assertEquals(GenericClientSpellInput.Spell.WATER_STRIKE,
			GenericClientSpellInput.Spell.fromName("water_strike"));
		assertEquals(GenericClientSpellInput.Spell.EARTH_STRIKE,
			GenericClientSpellInput.Spell.fromName("Earth Strike"));
		assertEquals(GenericClientSpellInput.Spell.FIRE_STRIKE,
			GenericClientSpellInput.Spell.fromName("fire_strike"));
		assertEquals(GenericClientSpellInput.Spell.EARTH_BOLT,
			GenericClientSpellInput.Spell.fromName("Earth Bolt"));
		assertEquals(GenericClientSpellInput.Spell.SUPERHEAT_ITEM,
			GenericClientSpellInput.Spell.fromName("Superheat Item"));
		assertEquals(GenericClientSpellInput.Spell.LOW_ALCHEMY,
			GenericClientSpellInput.Spell.fromName("low_alchemy"));
		assertEquals(InterfaceID.MagicSpellbook.WIND_STRIKE,
			GenericClientSpellInput.Spell.WIND_STRIKE.getWidgetId());
		assertEquals("Water Strike", GenericClientSpellInput.Spell.WATER_STRIKE.getLabel());
		assertEquals(SpriteID.Magicon.EARTH_STRIKE,
			GenericClientSpellInput.Spell.EARTH_STRIKE.getSpriteId());
		assertEquals(InterfaceID.MagicSpellbook.FIRE_BOLT,
			GenericClientSpellInput.Spell.FIRE_BOLT.getWidgetId());
		assertEquals(InterfaceID.MagicSpellbook.SUPERHEAT,
			GenericClientSpellInput.Spell.SUPERHEAT_ITEM.getWidgetId());
		assertEquals(SpriteID.Magicon.SUPERHEAT_ITEM,
			GenericClientSpellInput.Spell.SUPERHEAT_ITEM.getSpriteId());
		assertEquals(InterfaceID.MagicSpellbook.LOW_ALCHEMY,
			GenericClientSpellInput.Spell.LOW_ALCHEMY.getWidgetId());
		assertTrue(GenericClientSpellInput.Spell.WATER_STRIKE.isAutocastable());
		assertFalse(GenericClientSpellInput.Spell.LOW_ALCHEMY.isAutocastable());
		assertFalse(GenericClientSpellInput.Spell.SUPERHEAT_ITEM.isAutocastable());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsUnimplementedSpells()
	{
		GenericClientSpellInput.Spell.fromName("Ice Barrage");
	}

	@Test
	public void recognisesTheLumbridgeHomeTeleportArrivalArea()
	{
		assertTrue(GenericClientSpellInput.isLumbridge(new WorldPoint(3222, 3218, 0)));
		assertFalse(GenericClientSpellInput.isLumbridge(new WorldPoint(3164, 3487, 0)));
		assertFalse(GenericClientSpellInput.isLumbridge(new WorldPoint(3222, 3218, 1)));
	}

	@Test
	public void targetsAnExistingSelectionEvenWhenTheSpellbookIsHidden()
	{
		assertEquals(GenericClientSpellInput.SpellbookPath.TARGET_SELECTED,
			GenericClientSpellInput.spellbookPath(false, true));
		assertEquals(GenericClientSpellInput.SpellbookPath.TARGET_SELECTED,
			GenericClientSpellInput.spellbookPath(true, true));
		assertEquals(GenericClientSpellInput.SpellbookPath.OPEN,
			GenericClientSpellInput.spellbookPath(false, false));
		assertEquals(GenericClientSpellInput.SpellbookPath.SELECT,
			GenericClientSpellInput.spellbookPath(true, false));
	}
}
