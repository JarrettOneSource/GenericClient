package com.genericclient;

import static org.junit.Assert.assertEquals;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientGameInputTest
{
	@Test
	public void nearerClicksUseProjectableReachInsteadOfTheWholeRemainingRoute()
	{
		assertEquals(988, GenericClientGameInput.nearerRouteIndex(980, 1000, 0.6));
		assertEquals(982, GenericClientGameInput.nearerRouteIndex(980, 1000, 0.9));
		assertEquals(980, GenericClientGameInput.nearerRouteIndex(980, 1000, 1.0));
		assertEquals(0, GenericClientGameInput.nearerRouteIndex(0, 1, 0.6));
	}


	@Test
	public void derivesCardinalCameraYawTargets()
	{
		WorldPoint origin = new WorldPoint(3200, 3200, 0);
		assertEquals(0, GenericClientGameInput.yawToward(origin, new WorldPoint(3200, 3210, 0)));
		assertEquals(4096, GenericClientGameInput.yawToward(origin, new WorldPoint(3210, 3200, 0)));
		assertEquals(8192, GenericClientGameInput.yawToward(origin, new WorldPoint(3200, 3190, 0)));
		assertEquals(12288, GenericClientGameInput.yawToward(origin, new WorldPoint(3190, 3200, 0)));
	}

	@Test
	public void measuresCameraDistanceAcrossTheJau14Wraparound()
	{
		assertEquals(8, GenericClientGameInput.angularDistance(16380, 4));
		assertEquals(8192, GenericClientGameInput.angularDistance(0, 8192));
	}

}
