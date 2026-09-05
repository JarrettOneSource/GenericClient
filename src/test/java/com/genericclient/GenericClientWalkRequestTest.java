package com.genericclient;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientWalkRequestTest
{
	@Test
	public void rejectsTheRetiredDialogueAliasAndKeepsTheTypedPredicate()
	{
		for (boolean value : new boolean[]{false, true})
		{
			IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () ->
				GenericClientWalkRequest.parse(Map.of("destination", point(3200, 3200, 0),
					"interrupt_on_dialogue", value), 100, GenericClientActivityContext.none()));
			assertTrue(rejected.getMessage().contains("interrupt_on_dialogue"));
		}
		GenericClientWalkRequest request = GenericClientWalkRequest.parse(Map.of(
			"destination", point(3200, 3200, 0), "interrupt_on", Map.of("dialogue", true)), 100,
			GenericClientActivityContext.none());
		assertTrue(request.interrupts.dialogue);
	}

	@Test
	public void explicitArrivalTilesRemainImmutableAndBindTheJourney()
	{
		GenericClientWalkRequest request = GenericClientWalkRequest.parse(Map.of(
			"destination", point(3200, 3200, 0), "within", 2,
			"arrival_tiles", List.of(point(3201, 3200, 0), point(3201, 3200, 0))), 100,
			GenericClientActivityContext.none());
		assertEquals(1, request.arrivalTiles.size());
		assertFalse(request.isArrival(new WorldPoint(3200, 3200, 0)));
		assertTrue(request.isArrival(new WorldPoint(3201, 3200, 0)));
		assertTrue(request.sameJourney(request.withContext(GenericClientActivityContext.preset(GenericClientActivityContext.Activity.GENERAL))));
		assertFalse(request.sameJourney(request.withArrivalTiles(List.of(new WorldPoint(3200, 3201, 0)))));
		assertThrows(UnsupportedOperationException.class, () -> request.arrivalTiles.clear());
		assertThrows(IllegalArgumentException.class, () -> request.withArrivalTiles(List.of()));
		assertThrows(IllegalArgumentException.class, () -> request.withArrivalTiles(List.of(new WorldPoint(3203, 3200, 0))));
		assertThrows(IllegalArgumentException.class, () -> request.withArrivalTiles(List.of(new WorldPoint(3201, 3200, 1))));
	}

	@Test
	public void parsesOrderedViaAndDeduplicatedAvoidTilesWithoutMutatingTheInput()
	{
		Map<String, Object> destination = point(3208, 3208, 0);
		Map<String, Object> via = point(3201, 3208, 0);
		GenericClientWalkRequest request = GenericClientWalkRequest.parse(Map.of(
			"destination", destination, "via", List.of(via), "avoid_tiles", List.of(destination, destination),
			"resume", "continuation"), 200, GenericClientActivityContext.none());
		assertEquals(List.of(new WorldPoint(3201, 3208, 0)), request.via);
		assertEquals(1, request.avoidTiles.size());
		assertEquals("continuation", request.resume);
		assertTrue(request.sameJourney(request.withContext(GenericClientActivityContext.preset(GenericClientActivityContext.Activity.GENERAL))));
	}

	@Test
	public void acceptsAnEmptyLuaTableForAnOptionalPointList()
	{
		GenericClientWalkRequest request = GenericClientWalkRequest.parse(Map.of(
			"destination", point(3200, 3200, 0), "via", Collections.emptyMap()), 100,
			GenericClientActivityContext.none());
		assertTrue(request.via.isEmpty());
	}

	@Test(expected = IllegalArgumentException.class)
	public void refusesFractionalCoordinatesInsteadOfTruncatingThem()
	{
		GenericClientWalkRequest.point(Map.of("x", 3200.5, "y", 3200, "plane", 0), "via");
	}

	private static Map<String, Object> point(int x, int y, int plane)
	{
		return Map.of("x", x, "y", y, "plane", plane);
	}
}
