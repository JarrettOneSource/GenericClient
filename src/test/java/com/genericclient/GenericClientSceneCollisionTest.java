package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Map;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientSceneCollisionTest
{
	@Test
	@SuppressWarnings("unchecked")
	public void reportsAClosedEastwardEdgeAndItsFlags()
	{
		int[][] flags = new int[5][5];
		flags[2][1] = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		GenericClientSceneCollision collision =
			new GenericClientSceneCollision(true, 100, 200, 0, flags);
		WorldPoint from = new WorldPoint(101, 201, 0);
		WorldPoint to = new WorldPoint(102, 201, 0);

		Map<String, Object> inspection = collision.inspect(from, to);
		Map<String, Object> target = (Map<String, Object>) inspection.get("to");

		assertEquals(false, inspection.get("can_move"));
		assertEquals(true, target.get("loaded"));
		assertEquals((long) CollisionDataFlag.BLOCK_MOVEMENT_WEST, target.get("flags"));
	}

	@Test
	public void reportsAnOpenEastwardEdge()
	{
		GenericClientSceneCollision collision =
			new GenericClientSceneCollision(true, 100, 200, 0, new int[5][5]);

		assertEquals(true, collision.canMove(
			new WorldPoint(101, 201, 0),
			new WorldPoint(102, 201, 0)));
	}

	@Test
	public void usesRuneLiteDestinationSideWallMasks()
	{
		int[][] flags = new int[5][5];
		flags[1][1] = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		GenericClientSceneCollision collision =
			new GenericClientSceneCollision(true, 100, 200, 0, flags);

		assertEquals(true, collision.canMove(
			new WorldPoint(101, 201, 0),
			new WorldPoint(102, 201, 0)));
	}

	@Test
	public void defersToTheGlobalMapOutsideLoadedSceneTiles()
	{
		int[][] flags = new int[5][5];
		flags[2][1] = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		boolean[][] loaded = new boolean[5][5];
		loaded[1][1] = true;
		GenericClientSceneCollision collision =
			new GenericClientSceneCollision(true, 100, 200, 0, flags, loaded);

		assertNull(collision.canMove(
			new WorldPoint(101, 201, 0),
			new WorldPoint(102, 201, 0)));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void defersAtSceneBorderAndUninitializedSentinels()
	{
		int[][] flags = new int[5][5];
		flags[2][0] = 0xFFFFFF;
		flags[3][2] = 0x1000000;
		GenericClientSceneCollision collision =
			new GenericClientSceneCollision(true, 100, 200, 0, flags);

		assertNull(collision.canMove(
			new WorldPoint(102, 201, 0),
			new WorldPoint(102, 200, 0)));
		assertNull(collision.canMove(
			new WorldPoint(102, 202, 0),
			new WorldPoint(103, 202, 0)));
		Map<String, Object> inspection = collision.inspect(
			new WorldPoint(102, 201, 0),
			new WorldPoint(102, 200, 0));
		assertEquals(false, ((Map<String, Object>) inspection.get("to")).get("loaded"));
	}
}
