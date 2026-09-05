package com.genericclient;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Independent decisions for discretionary behavior, input and safety ownership. */
final class GenericClientBehaviorPolicy
{
	final boolean breaks;
	final CursorRelease cursorRelease;
	final Mouse mouse;
	final boolean damageExpected;
	final PrayerOwner prayerOwner;
	final boolean walkRefresh;
	final Fidget fidget;

	GenericClientBehaviorPolicy(boolean breaks, CursorRelease cursorRelease, Mouse mouse,
		boolean damageExpected, PrayerOwner prayerOwner, boolean walkRefresh, Fidget fidget)
	{
		this.breaks = breaks;
		this.cursorRelease = cursorRelease;
		this.mouse = mouse;
		this.damageExpected = damageExpected;
		this.prayerOwner = prayerOwner;
		this.walkRefresh = walkRefresh;
		this.fidget = fidget;
	}

	static final GenericClientBehaviorPolicy ROUTINE = new GenericClientBehaviorPolicy(
		true, CursorRelease.WITH_BREAK, Mouse.NATURAL, false, PrayerOwner.GUARD, false, Fidget.FULL);
	static final GenericClientBehaviorPolicy TRAVEL = new GenericClientBehaviorPolicy(
		true, CursorRelease.INDEPENDENT, Mouse.NATURAL, false, PrayerOwner.GUARD, false, Fidget.FULL);
	static final GenericClientBehaviorPolicy SKILLING = new GenericClientBehaviorPolicy(
		true, CursorRelease.WITH_BREAK, Mouse.NATURAL, false, PrayerOwner.SCRIPT, false, Fidget.FULL);
	static final GenericClientBehaviorPolicy COMBAT = new GenericClientBehaviorPolicy(
		false, CursorRelease.NONE, Mouse.FAST, true, PrayerOwner.GUARD, false, Fidget.DRIFT);
	static final GenericClientBehaviorPolicy HAZARDOUS_TRAVEL = new GenericClientBehaviorPolicy(
		false, CursorRelease.NONE, Mouse.FAST, true, PrayerOwner.GUARD, true, Fidget.DRIFT);
	static final GenericClientBehaviorPolicy GUARDED = new GenericClientBehaviorPolicy(
		false, CursorRelease.NONE, Mouse.NATURAL, false, PrayerOwner.GUARD, false, Fidget.NONE);
	static final GenericClientBehaviorPolicy MANUAL = new GenericClientBehaviorPolicy(
		false, CursorRelease.NONE, Mouse.NATURAL, false, PrayerOwner.SCRIPT, false, Fidget.NONE);

	GenericClientBehaviorPolicy withOverrides(Object raw)
	{
		if (raw == null) return this;
		if (!(raw instanceof Map)) throw new IllegalArgumentException("policy must be a map");
		boolean changedBreaks = breaks;
		CursorRelease changedRelease = cursorRelease;
		Mouse changedMouse = mouse;
		boolean changedDamage = damageExpected;
		PrayerOwner changedPrayer = prayerOwner;
		boolean changedRefresh = walkRefresh;
		Fidget changedFidget = fidget;
		for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet())
		{
			String field = String.valueOf(entry.getKey());
			Object value = entry.getValue();
			switch (field)
			{
				case "breaks": changedBreaks = booleanField(field, value); break;
				case "cursor_release": changedRelease = enumField(CursorRelease.class, field, value); break;
				case "mouse": changedMouse = enumField(Mouse.class, field, value); break;
				case "damage_expected": changedDamage = booleanField(field, value); break;
				case "prayer_owner": changedPrayer = enumField(PrayerOwner.class, field, value); break;
				case "walk_refresh": changedRefresh = booleanField(field, value); break;
				case "fidget": changedFidget = enumField(Fidget.class, field, value); break;
				default: throw new IllegalArgumentException("Unknown policy field: " + field);
			}
		}
		return new GenericClientBehaviorPolicy(changedBreaks, changedRelease, changedMouse,
			changedDamage, changedPrayer, changedRefresh, changedFidget);
	}

	GenericClientBehaviorPolicy withoutDiscretionary(boolean naturalMouse)
	{
		return new GenericClientBehaviorPolicy(false, CursorRelease.NONE, naturalMouse ? Mouse.NATURAL : mouse,
			damageExpected, prayerOwner, walkRefresh, Fidget.NONE);
	}

	Map<String, Object> toMap()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("breaks", breaks);
		value.put("cursor_release", cursorRelease.name().toLowerCase(Locale.ROOT));
		value.put("mouse", mouse.name().toLowerCase(Locale.ROOT));
		value.put("damage_expected", damageExpected);
		value.put("prayer_owner", prayerOwner.name().toLowerCase(Locale.ROOT));
		value.put("walk_refresh", walkRefresh);
		value.put("fidget", fidget.name().toLowerCase(Locale.ROOT));
		return value;
	}

	private static boolean booleanField(String field, Object value)
	{
		if (!(value instanceof Boolean)) throw new IllegalArgumentException("policy." + field + " must be true or false");
		return (Boolean) value;
	}

	private static <E extends Enum<E>> E enumField(Class<E> type, String field, Object value)
	{
		if (!(value instanceof String)) throw new IllegalArgumentException("policy." + field + " must be a string");
		try
		{
			return Enum.valueOf(type, ((String) value).trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException exception)
		{
			throw new IllegalArgumentException("Unsupported policy." + field + ": " + value, exception);
		}
	}

	@Override public boolean equals(Object other)
	{
		if (!(other instanceof GenericClientBehaviorPolicy)) return false;
		GenericClientBehaviorPolicy policy = (GenericClientBehaviorPolicy) other;
		return breaks == policy.breaks && cursorRelease == policy.cursorRelease && mouse == policy.mouse &&
			damageExpected == policy.damageExpected && prayerOwner == policy.prayerOwner &&
			walkRefresh == policy.walkRefresh && fidget == policy.fidget;
	}

	@Override public int hashCode()
	{
		return Objects.hash(breaks, cursorRelease, mouse, damageExpected, prayerOwner, walkRefresh, fidget);
	}

	enum CursorRelease { NONE, WITH_BREAK, INDEPENDENT }
	enum Mouse { NATURAL, FAST }
	enum PrayerOwner { SCRIPT, GUARD }
	enum Fidget { NONE, DRIFT, FULL }
}
