package org.dreambot.api.methods.magic;

public enum Normal implements Spell
{
	HOME_TELEPORT("home_teleport", 0, 0),
	WIND_STRIKE("wind_strike", 1, 5.5),
	WATER_STRIKE("water_strike", 5, 7.5),
	EARTH_STRIKE("earth_strike", 9, 9.5),
	FIRE_STRIKE("fire_strike", 13, 11.5),
	EARTH_BOLT("earth_bolt", 29, 19.5),
	FIRE_BOLT("fire_bolt", 35, 22.5),
	LOW_LEVEL_ALCHEMY("low_alchemy", 21, 31),
	SUPERHEAT_ITEM("superheat_item", 43, 53);

	final String action;
	private final int level;
	private final double experience;
	Normal(String action, int level, double experience)
	{
		this.action = action; this.level = level; this.experience = experience;
	}
	@Override public int getLevel() { return level; }
	@Override public double getExperience() { return experience; }
}
