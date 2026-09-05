package com.genericclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/** Immutable journey constraints, independent of a plan or a particular input attempt. */
final class GenericClientWalkRequest
{
	private static final int MAX_POINTS = 512;
	private static final Set<String> FIELDS = Set.of(
		"type", "destination", "within", "run", "via", "avoid_tiles", "arrival_tiles", "interrupt_on", "resume");
	final WorldPoint destination;
	final int within;
	final int timeoutTicks;
	final GenericClientActivityContext activityContext;
	final boolean useRun;
	final List<WorldPoint> avoidTiles;
	final GenericClientWalkInterrupts interrupts;
	final List<WorldPoint> via;
	final List<WorldPoint> arrivalTiles;
	final String resume;

	GenericClientWalkRequest(WorldPoint destination, int within, int timeoutTicks,
		GenericClientActivityContext context, boolean useRun, List<WorldPoint> avoidTiles,
		GenericClientWalkInterrupts interrupts, List<WorldPoint> via, String resume)
	{
		this(destination, within, timeoutTicks, context, useRun, avoidTiles, interrupts, via, resume, List.of());
	}

	private GenericClientWalkRequest(WorldPoint destination, int within, int timeoutTicks,
		GenericClientActivityContext context, boolean useRun, List<WorldPoint> avoidTiles,
		GenericClientWalkInterrupts interrupts, List<WorldPoint> via, String resume, List<WorldPoint> arrivalTiles)
	{
		this.destination = Objects.requireNonNull(destination, "Walk destination is required");
		requirePoint(destination, "destination");
		if (within < 0 || within > 10) throw new IllegalArgumentException("Walk arrival radius must be between 0 and 10");
		if (timeoutTicks < 1) throw new IllegalArgumentException("Walk timeout must be positive");
		this.within = within;
		this.timeoutTicks = timeoutTicks;
		this.activityContext = Objects.requireNonNull(context, "Walk input context is required");
		this.useRun = useRun;
		this.avoidTiles = Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(avoidTiles)));
		this.via = List.copyOf(via);
		this.arrivalTiles = List.copyOf(new LinkedHashSet<>(arrivalTiles));
		this.interrupts = Objects.requireNonNull(interrupts, "Walk interrupts are required");
		this.resume = resume;
		for (WorldPoint point : this.avoidTiles) requirePoint(point, "avoid_tiles");
		for (WorldPoint point : this.via) requirePoint(point, "via");
		for (WorldPoint point : this.arrivalTiles)
		{
			requirePlane(point, destination.getPlane(), "arrival_tiles");
			if (Math.max(Math.abs(point.getX() - destination.getX()), Math.abs(point.getY() - destination.getY())) > within)
				throw new IllegalArgumentException("arrival_tiles must be inside the arrival radius");
		}
	}

	GenericClientWalkRequest withContext(GenericClientActivityContext context)
	{
		return new GenericClientWalkRequest(destination, within, timeoutTicks, context, useRun,
			avoidTiles, interrupts, via, resume, arrivalTiles);
	}

	GenericClientWalkRequest withArrivalTiles(List<WorldPoint> tiles)
	{
		if (tiles.isEmpty()) throw new IllegalArgumentException("arrival_tiles must contain at least one tile");
		return new GenericClientWalkRequest(destination, within, timeoutTicks, activityContext, useRun,
			avoidTiles, interrupts, via, resume, tiles);
	}

	boolean isArrival(WorldPoint point)
	{
		return point.getPlane() == destination.getPlane() &&
			Math.max(Math.abs(point.getX() - destination.getX()), Math.abs(point.getY() - destination.getY())) <= within &&
			(arrivalTiles.isEmpty() || arrivalTiles.contains(point));
	}

	boolean sameJourney(GenericClientWalkRequest other)
	{
		return destination.equals(other.destination) && within == other.within && via.equals(other.via) &&
			arrivalTiles.equals(other.arrivalTiles);
	}

	static GenericClientWalkRequest parse(Map<String, Object> action, int timeout,
		GenericClientActivityContext context)
	{
		for (String field : action.keySet())
			if (!FIELDS.contains(field)) throw new IllegalArgumentException("Unknown walk.to field: " + field);
		WorldPoint destination = point(action.get("destination"), "destination");
		int within = action.containsKey("within") ? integer(action.get("within"), "within") : 1;
		boolean run = bool(action, "run", true);
		Object resume = action.get("resume");
		if (resume != null && (!(resume instanceof String) || ((String) resume).isBlank()))
			throw new IllegalArgumentException("walk.to resume must be a continuation token");
		GenericClientWalkRequest request = new GenericClientWalkRequest(destination, within, timeout, context, run,
			points(action.get("avoid_tiles"), "avoid_tiles"),
			GenericClientWalkInterrupts.parse(action.get("interrupt_on")),
			points(action.get("via"), "via"), (String) resume);
		return action.containsKey("arrival_tiles") ? request.withArrivalTiles(
			points(action.get("arrival_tiles"), "arrival_tiles")) : request;
	}

	static WorldPoint point(Object value, String label)
	{
		if (!(value instanceof Map)) throw new IllegalArgumentException("walk.to " + label + " must be a point table");
		Map<?, ?> point = (Map<?, ?>) value;
		int x = integer(point.get("x"), label + " x");
		int y = integer(point.get("y"), label + " y");
		int plane = integer(point.get("plane"), label + " plane");
		WorldPoint result = new WorldPoint(x, y, plane);
		requirePoint(result, label);
		return result;
	}

	static int integer(Object value, String label)
	{
		if (!(value instanceof Number)) throw new IllegalArgumentException("walk.to " + label + " requires an integer");
		double number = ((Number) value).doubleValue();
		if (!Double.isFinite(number) || number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)
			throw new IllegalArgumentException("walk.to " + label + " requires an integer");
		return (int) number;
	}

	private static boolean bool(Map<String, Object> action, String key, boolean defaultValue)
	{
		if (!action.containsKey(key)) return defaultValue;
		if (!(action.get(key) instanceof Boolean)) throw new IllegalArgumentException("walk.to " + key + " must be true or false");
		return (Boolean) action.get(key);
	}

	private static List<WorldPoint> points(Object value, String label)
	{
		if (value == null || value instanceof Map && ((Map<?, ?>) value).isEmpty()) return Collections.emptyList();
		if (!(value instanceof List)) throw new IllegalArgumentException("walk.to " + label + " must be an array of point tables");
		List<?> values = (List<?>) value;
		if (values.size() > MAX_POINTS) throw new IllegalArgumentException("walk.to " + label + " cannot contain more than " + MAX_POINTS + " points");
		List<WorldPoint> result = new ArrayList<>();
		for (Object entry : values)
		{
			result.add(point(entry, label));
		}
		return result;
	}

	private static void requirePlane(WorldPoint point, int plane, String label)
	{
		requirePoint(point, label);
		if (point.getPlane() != plane)
			throw new IllegalArgumentException("walk.to " + label + " must be on the destination plane");
	}

	private static void requirePoint(WorldPoint point, String label)
	{
		if (point == null || point.getX() < 0 || point.getX() > 0x7FFF || point.getY() < 0 ||
			point.getY() > 0x7FFF || point.getPlane() < 0 || point.getPlane() > 3)
			throw new IllegalArgumentException("walk.to " + label + " is outside world coordinate bounds");
	}
}
