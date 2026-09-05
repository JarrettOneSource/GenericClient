package com.genericclient;

import static org.junit.Assert.*;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientCollisionMapTest
{
	@Test
	public void reportsBothMapGenerationsWhenTheLiveRevisionDiffers()
	{
		java.util.List<String> warnings = new java.util.ArrayList<>();
		GenericClientCollisionMap.reportRevisionDrift(240, warnings::add);
		assertTrue(warnings.isEmpty());
		GenericClientCollisionMap.reportRevisionDrift(241, warnings::add);
		assertEquals(java.util.List.of(
			"NAVIGATION_MAP_REVISION_DRIFT map=collision live=241 bundled=240 cache=2664",
			"NAVIGATION_MAP_REVISION_DRIFT map=doors live=241 bundled=240 cache=2686"), warnings);
		warnings.clear();
		GenericClientCollisionMap.reportRevisionDrift(0, warnings::add);
		assertEquals(java.util.List.of("NAVIGATION_MAP_REVISION_UNAVAILABLE live=0"), warnings);
	}

	@Test
	public void indexesExtremeRegionsWithoutAliasingPlanesOrWrappingWorldCoordinates() throws Exception
	{
		GenericClientCollisionMap map = GenericClientCollisionMap.loadBundled();
		map.addTraversalEdge(0, 0, 0, 1, 3);
		map.addTraversalEdge(32766, 32767, 32767, 32767, 3);
		assertTrue(map.canMove(0, 0, 3, 0, 1));
		assertFalse(map.canMove(0, 0, 2, 0, 1));
		assertTrue(map.canMove(32766, 32767, 3, 1, 0));
		assertFalse(map.crossesDoor(32768, 32767, 3, 1, 0));
	}

	@Test
	public void supplementsTheObservedStrongholdCrossingInBothDirections() throws Exception
	{
		GenericClientCollisionMap map = GenericClientCollisionMap.loadBundled();
		assertTrue(map.canMove(2461, 3383, 0, 0, 1));
		assertTrue(map.canMove(2461, 3384, 0, 0, -1));
		assertTrue(map.crossesDoor(2461, 3383, 0, 0, 1));
		assertTrue(map.crossesDoor(2461, 3384, 0, 0, -1));
		assertTrue(map.canMove(2461, 3382, 0, 0, 1));
		assertTrue(map.canMove(2461, 3385, 0, 0, -1));
		assertFalse(map.canMove(2460, 3383, 0, 0, 1));
		assertEquals(GenericClientPathfinder.Status.FOUND, new GenericClientPathfinder(map)
			.find(new WorldPoint(2463, 3376, 0), new WorldPoint(2461, 3445, 0), 2).getStatus());
	}

	@Test(expected = IllegalArgumentException.class)
	public void refusesDiagonalSupplementEdges() throws Exception
	{
		GenericClientCollisionMap.loadBundled().addTraversalEdge(2461, 3383, 2462, 3384, 0);
	}

	@Test
	public void restoresTheObservedGrandTreeDoorwayWithoutOpeningTheRestOfTheFacade() throws Exception
	{
		GenericClientCollisionMap map = GenericClientCollisionMap.loadBundled();
		for (int y = 3491; y < 3493; y++)
		{
			assertTrue(map.canMove(2465, y, 0, 0, 1));
			assertTrue(map.canMove(2465, y + 1, 0, 0, -1));
			assertTrue(map.crossesDoor(2465, y, 0, 0, 1));
		}
		assertFalse(map.canMove(2466, 3491, 0, 0, 1));
		assertFalse(map.canMove(2464, 3491, 0, 0, 1));
		assertEquals(GenericClientPathfinder.Status.FOUND, new GenericClientPathfinder(map)
			.find(new WorldPoint(2461, 3444, 0), new WorldPoint(2466, 3494, 0), 0).getStatus());
	}

	@Test(expected = IllegalArgumentException.class)
	public void refusesNonadjacentSupplementEdges() throws Exception
	{
		GenericClientCollisionMap.loadBundled().addTraversalEdge(2465, 3491, 2465, 3493, 0);
	}
}
