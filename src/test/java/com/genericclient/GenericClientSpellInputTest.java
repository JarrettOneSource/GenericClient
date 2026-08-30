package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
		assertEquals(InterfaceID.MagicSpellbook.WIND_STRIKE,
			GenericClientSpellInput.Spell.WIND_STRIKE.getWidgetId());
		assertEquals("Water Strike", GenericClientSpellInput.Spell.WATER_STRIKE.getLabel());
		assertEquals(SpriteID.Magicon.EARTH_STRIKE,
			GenericClientSpellInput.Spell.EARTH_STRIKE.getSpriteId());
		assertEquals(InterfaceID.MagicSpellbook.FIRE_BOLT,
			GenericClientSpellInput.Spell.FIRE_BOLT.getWidgetId());
		assertTrue(GenericClientSpellInput.Spell.WATER_STRIKE.isAutocastable());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsUnimplementedSpells()
	{
		GenericClientSpellInput.Spell.fromName("Ice Barrage");
	}
}
