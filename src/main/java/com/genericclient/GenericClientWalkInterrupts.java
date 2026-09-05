package com.genericclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;

/** Snapshot predicates declared by Lua; contains no quest zones or upkeep decisions. */
final class GenericClientWalkInterrupts
{
	private static final Set<String> FIELDS = Set.of("area", "dialogue", "poisoned", "missing_item",
		"run_energy_below", "inventory_below", "skill_below", "varbit_equals");
	static final GenericClientWalkInterrupts NONE = new GenericClientWalkInterrupts(Collections.emptyMap());
	final boolean dialogue;
	private final boolean poisoned;
	private final Integer runEnergyBelow;
	private final List<String> missingItems;
	private final List<Area> areas;
	private final Map<Integer, Integer> inventoryBelow;
	private final Map<String, Integer> skillBelow;
	private final Map<Integer, Integer> varbitEquals;

	private GenericClientWalkInterrupts(Map<?, ?> values)
	{
		for (Object key : values.keySet())
			if (!FIELDS.contains(key)) throw invalid("unknown predicate " + key);
		dialogue = bool(values, "dialogue");
		poisoned = bool(values, "poisoned");
		runEnergyBelow = values.containsKey("run_energy_below")
			? integer(values.get("run_energy_below"), "run_energy_below") : null;
		if (runEnergyBelow != null && (runEnergyBelow < 0 || runEnergyBelow > 100))
			throw invalid("run_energy_below must be a percentage between 0 and 100");
		List<String> names = new ArrayList<>();
		for (Object value : list(values.get("missing_item"), "missing_item")) names.add(text(value, "missing_item prefix"));
		missingItems = List.copyOf(names);
		areas = parseAreas(values.get("area"));
		inventoryBelow = integerMap(values.get("inventory_below"), "inventory_below", true);
		varbitEquals = integerMap(values.get("varbit_equals"), "varbit_equals", false);
		Map<String, Integer> skills = new TreeMap<>();
		for (Map.Entry<?, ?> entry : map(values.get("skill_below"), "skill_below").entrySet())
		{
			String name = text(entry.getKey(), "skill name").toLowerCase(Locale.ROOT);
			try { net.runelite.api.Skill.valueOf(name.toUpperCase(Locale.ROOT)); }
			catch (IllegalArgumentException error) { throw invalid("unknown skill " + name); }
			int minimum = integer(entry.getValue(), "skill minimum");
			if (minimum < 0) throw invalid("skill minimum cannot be negative");
			skills.put(name, minimum);
		}
		skillBelow = Collections.unmodifiableMap(new LinkedHashMap<>(skills));
	}

	static GenericClientWalkInterrupts parse(Object value)
	{
		return new GenericClientWalkInterrupts(map(value, "interrupt_on"));
	}

	Match evaluate(GenericClientSnapshot snapshot, boolean ownedDialogue)
	{
		WorldPoint player = snapshot.getPlayerWorldPoint();
		for (Area area : areas) for (Bounds bounds : area.bounds)
			if (bounds.contains(player)) return new Match("interrupted", "area", area.name);
		if (dialogue && !ownedDialogue && snapshot.isDialogueOpen()) return new Match("interrupted", "dialogue", true);
		if (poisoned)
		{
			Integer poison = snapshot.varp(VarPlayerID.POISON);
			if (poison == null) return unavailable("poison");
			if (poison > 0) return new Match("interrupted", "poisoned", poison);
		}
		Match inventory = evaluateInventory(snapshot);
		if (inventory != null) return inventory;
		return evaluateStatus(snapshot);
	}

	private Match evaluateInventory(GenericClientSnapshot snapshot)
	{
		for (String prefix : missingItems)
		{
			Boolean present = snapshot.inventoryContainsPrefix(prefix);
			if (present == null) return unavailable("inventory");
			if (!present) return new Match("interrupted", "missing_item", prefix);
		}
		for (Map.Entry<Integer, Integer> entry : inventoryBelow.entrySet())
		{
			long quantity = snapshot.getInventoryQuantity(entry.getKey());
			if (quantity < 0) return unavailable("inventory");
			if (quantity < entry.getValue()) return new Match("interrupted", "inventory_below",
				Map.of("item_id", entry.getKey(), "quantity", quantity, "minimum", entry.getValue()));
		}
		return null;
	}

	private Match evaluateStatus(GenericClientSnapshot snapshot)
	{
		for (Map.Entry<String, Integer> entry : skillBelow.entrySet())
		{
			Integer value = snapshot.boostedSkill(entry.getKey());
			if (value == null) return unavailable("skills");
			if (value < entry.getValue()) return new Match("interrupted", "skill_below",
				Map.of("skill", entry.getKey(), "value", value, "minimum", entry.getValue()));
		}
		for (Map.Entry<Integer, Integer> entry : varbitEquals.entrySet())
		{
			Integer value = snapshot.varbit(entry.getKey());
			if (value == null) return unavailable("varbit_" + entry.getKey());
			if (value.equals(entry.getValue())) return new Match("interrupted", "varbit_equals",
				Map.of("id", entry.getKey(), "value", value));
		}
		if (runEnergyBelow != null && snapshot.getRunEnergy() < runEnergyBelow * 100)
			return new Match("interrupted", "run_energy_below", snapshot.getRunEnergy() / 100.0);
		return null;
	}

	private static Match unavailable(String subject)
	{
		return new Match("unavailable", subject + "_snapshot_unavailable", subject);
	}

	private static List<Area> parseAreas(Object value)
	{
		List<?> raw = value instanceof Map && ((Map<?, ?>) value).containsKey("name")
			? List.of(value) : list(value, "area");
		List<Area> result = new ArrayList<>();
		for (Object entry : raw)
		{
			Map<?, ?> area = map(entry, "area");
			String name = text(area.get("name"), "area name");
			List<Bounds> bounds = new ArrayList<>();
			for (Object rectangle : list(area.get("bounds"), "area bounds")) bounds.add(new Bounds(map(rectangle, "rectangle")));
			if (bounds.isEmpty()) throw invalid("area bounds cannot be empty");
			result.add(new Area(name, List.copyOf(bounds)));
		}
		return List.copyOf(result);
	}

	private static Map<Integer, Integer> integerMap(Object value, String label, boolean minimum)
	{
		Map<Integer, Integer> result = new TreeMap<>();
		for (Object raw : list(value, label))
		{
			Map<?, ?> entry = map(raw, label + " entry");
			int id = integer(entry.get("id"), label + " id");
			int number = integer(entry.get(minimum ? "quantity" : "value"), label + " value");
			if (id < 0 || minimum && number < 1) throw invalid(label + " requires nonnegative ids and positive quantities");
			if (result.put(id, number) != null) throw invalid("duplicate " + label + " id " + id);
		}
		return Collections.unmodifiableMap(new LinkedHashMap<>(result));
	}

	private static boolean bool(Map<?, ?> values, String key)
	{
		if (!values.containsKey(key)) return false;
		if (!(values.get(key) instanceof Boolean)) throw invalid(key + " must be true or false");
		return (Boolean) values.get(key);
	}

	private static Map<?, ?> map(Object value, String label)
	{
		if (value == null) return Collections.emptyMap();
		if (!(value instanceof Map)) throw invalid(label + " must be a table");
		return (Map<?, ?>) value;
	}

	private static List<?> list(Object value, String label)
	{
		if (value == null || value instanceof Map && ((Map<?, ?>) value).isEmpty()) return Collections.emptyList();
		if (!(value instanceof List)) throw invalid(label + " must be an array");
		if (((List<?>) value).size() > 512) throw invalid(label + " contains more than 512 entries");
		return (List<?>) value;
	}

	private static String text(Object value, String label)
	{
		if (!(value instanceof String) || ((String) value).isBlank()) throw invalid(label + " must be a nonempty string");
		return ((String) value).trim();
	}

	private static int integer(Object value, String label) { return GenericClientWalkRequest.integer(value, "interrupt_on " + label); }
	private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("walk.to interrupt_on " + message); }

	static final class Match
	{
		final String status;
		final String reason;
		final Object detail;
		Match(String status, String reason, Object detail) { this.status = status; this.reason = reason; this.detail = detail; }
	}

	private static final class Area
	{
		private final String name;
		private final List<Bounds> bounds;
		private Area(String name, List<Bounds> bounds) { this.name = name; this.bounds = bounds; }
	}

	private static final class Bounds
	{
		private final int x1, y1, x2, y2, plane;
		private Bounds(Map<?, ?> value)
		{
			x1 = integer(value.get("x1"), "x1"); y1 = integer(value.get("y1"), "y1");
			x2 = integer(value.get("x2"), "x2"); y2 = integer(value.get("y2"), "y2");
			plane = integer(value.get("plane"), "plane");
			if (x1 < 0 || y1 < 0 || x2 > 0x7FFF || y2 > 0x7FFF || x1 > x2 || y1 > y2 || plane < 0 || plane > 3)
				throw invalid("area rectangle is outside world bounds");
		}
		private boolean contains(WorldPoint point)
		{
			return point != null && point.getPlane() == plane && point.getX() >= x1 && point.getX() <= x2 &&
				point.getY() >= y1 && point.getY() <= y2;
		}
	}
}
