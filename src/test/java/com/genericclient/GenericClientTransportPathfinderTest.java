package com.genericclient;

import static org.junit.Assert.assertEquals;
import static com.genericclient.GenericClientTestSupport.transport;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientTransportPathfinderTest
{
	@Test
	public void crossesPlanesAndRetainsTheChosenAction() throws Exception
	{
		WorldPoint start = point(100, 0);
		WorldPoint upstairs = point(100, 1);
		GenericClientTransport ladder = transport("ladder", start, upstairs, 20);
		GenericClientPathfinder.Result route = pathfinder().find(start, point(103, 1), 0, corridor(), List.of(ladder));
		assertEquals(GenericClientPathfinder.Status.FOUND, route.getStatus());
		assertEquals(List.of(start, upstairs, point(101, 1), point(102, 1), point(103, 1)), route.getPath());
		assertEquals(Map.of(0, ladder), route.getTransports());
	}

	@Test
	public void cheapTransportAwayFromTheDestinationBeatsDirectWalking() throws Exception
	{
		WorldPoint start = point(100, 0);
		WorldPoint destination = point(200, 0);
		GenericClientTransport ferry = transport("ferry", point(95, 0), destination, 20);
		GenericClientPathfinder.Result route = pathfinder().find(start, destination, 0, corridor(), List.of(ferry));
		assertEquals(GenericClientPathfinder.Status.FOUND, route.getStatus());
		assertEquals(List.of(start, point(99, 0), point(98, 0), point(97, 0), point(96, 0), point(95, 0), destination), route.getPath());
		assertEquals(Map.of(5, ferry), route.getTransports());
		assertTrue(route.getExpandedNodes() < 20);
	}

	@Test
	public void findsAChainThatReturnsToTheOriginalPlane() throws Exception
	{
		GenericClientTransport up = transport("up", point(95, 0), point(95, 2), 20);
		GenericClientTransport down = transport("down", point(96, 2), point(200, 0), 20);
		GenericClientPathfinder.Result route = pathfinder().find(point(100, 0), point(200, 0), 0, corridor(), List.of(up, down));
		assertEquals(GenericClientPathfinder.Status.FOUND, route.getStatus());
		assertEquals(Map.of(5, up, 7, down), route.getTransports());
		assertEquals(point(96, 2), route.getPath().get(7));
		assertTrue(route.getExpandedNodes() < 20);
	}

	@Test
	public void expensiveParallelTransportDoesNotReplaceWalkingOrTheCheaperAction() throws Exception
	{
		WorldPoint start = point(100, 0);
		WorldPoint end = point(103, 0);
		GenericClientTransport expensive = transport("expensive", start, end, 40);
		GenericClientPathfinder.Result walked = pathfinder().find(start, end, 0, corridor(), List.of(expensive));
		assertEquals(4, walked.getPath().size());
		assertTrue(walked.getTransports().isEmpty());
		GenericClientTransport cheap = transport("cheap", start, end, 20);
		GenericClientPathfinder.Result transported = pathfinder().find(start, end, 0, corridor(), List.of(expensive, cheap));
		assertEquals(List.of(start, end), transported.getPath());
		assertEquals(Map.of(0, cheap), transported.getTransports());
	}

	@Test
	public void carriesTransportActionsThroughViaSegmentsAndRejoin() throws Exception
	{
		GenericClientTransport up = transport("up", point(100, 0), point(100, 1), 20);
		GenericClientTransport down = transport("down", point(105, 1), point(200, 0), 20);
		GenericClientWalkRequest request = new GenericClientWalkRequest(point(205, 0), 0, 100,
			GenericClientActivityContext.preset(GenericClientActivityContext.Activity.TRAVEL).plain(), false, List.of(), GenericClientWalkInterrupts.NONE,
			List.of(point(103, 1)), null);
		GenericClientPathfinder.Result route = pathfinder().findThrough(point(100, 0), request, 0, corridor(), List.of(up, down));
		assertEquals(GenericClientPathfinder.Status.FOUND, route.getStatus());
		assertEquals(Map.of(0, up, 6, down), route.getTransports());
		assertEquals(List.of(2), route.getViaIndices());
		GenericClientPathfinder.Result rejoined = pathfinder().rejoin(point(106, 1), route.getPath(), 3,
			List.of(), route.getTransports(), corridor());
		assertEquals(GenericClientPathfinder.Status.FOUND, rejoined.getStatus());
		assertEquals(Map.of(1, down), rejoined.getTransports());
		assertEquals(point(105, 1), rejoined.getPath().get(1));
		assertEquals(point(200, 0), rejoined.getPath().get(2));
	}

	@Test
	public void rejoinCannotSkipAnUnexecutedTransportOnTheSamePlane() throws Exception
	{
		GenericClientTransport gate = transport("gate", point(100, 0), point(110, 0), 20);
		GenericClientPathfinder.Result route = pathfinder().find(point(99, 0), point(115, 0), 0, corridor(), List.of(gate));
		GenericClientPathfinder.Result rejoined = pathfinder().rejoin(point(114, 0), route.getPath(), 0,
			List.of(), route.getTransports(), corridor());
		assertEquals(GenericClientPathfinder.Status.FOUND, rejoined.getStatus());
		int gateIndex = rejoined.getTransports().keySet().iterator().next();
		assertEquals(14, gateIndex);
		assertEquals(point(100, 0), rejoined.getPath().get(gateIndex));
		assertEquals(gate, rejoined.getTransports().get(gateIndex));
	}

	@Test
	public void retainsTheTransportWhenTheSparseSearchGrows() throws Exception
	{
		GenericClientTransport ladder = transport("ladder", point(100, 0), point(100, 3), 20);
		GenericClientPathfinder.Result route = pathfinder().find(point(100, 0), point(700, 3), 0,
			(x, y, plane, dx, dy, allowed) -> y + dy == 100 && x + dx >= 100 && x + dx <= 700, List.of(ladder));
		assertEquals(GenericClientPathfinder.Status.FOUND, route.getStatus());
		assertEquals(602, route.getPath().size());
		assertEquals(Map.of(0, ladder), route.getTransports());
	}

	@Test
	public void ignoresATransportIntoAPlaneWithNoReturnConnection() throws Exception
	{
		GenericClientTransport trap = transport("trap", point(100, 0), point(200, 2), 1);
		GenericClientPathfinder.Result route = pathfinder().find(point(100, 0), point(103, 0), 1, corridor(), List.of(trap));
		assertEquals(GenericClientPathfinder.Status.FOUND, route.getStatus());
		assertEquals(List.of(point(100, 0), point(101, 0), point(102, 0)), route.getPath());
		assertTrue(route.getTransports().isEmpty());
		GenericClientPathfinder.Result unavailable = pathfinder().find(point(100, 0), point(103, 3), 0, corridor(), List.of(trap));
		assertEquals(GenericClientPathfinder.Status.UNSUPPORTED_PLANE, unavailable.getStatus());
		assertEquals(0, unavailable.getExpandedNodes());
	}

	@Test
	public void resolvesAChainWhoseLastConnectionIsListedFirst() throws Exception
	{
		GenericClientTransport last = transport("last", point(100, 1), point(100, 0), 1);
		GenericClientTransport middle = transport("middle", point(100, 2), point(100, 1), 1);
		GenericClientTransport first = transport("first", point(100, 3), point(100, 2), 1);
		GenericClientPathfinder.Result route = pathfinder().find(point(100, 3), point(100, 0), 0, corridor(), List.of(last, middle, first));
		assertEquals(List.of(point(100, 3), point(100, 2), point(100, 1), point(100, 0)), route.getPath());
		assertEquals(Map.of(0, first, 1, middle, 2, last), route.getTransports());
	}

	@Test
	public void treatsTheWholeArrivalRadiusAsAValidCheaperTransportLanding() throws Exception
	{
		WorldPoint start = point(100, 0);
		WorldPoint goal = point(200, 0);
		GenericClientTransport near = transport("near", point(99, 0), new WorldPoint(202, 102, 0), 9);
		GenericClientTransport exact = transport("exact", start, goal, 20);
		GenericClientPathfinder.Result route = pathfinder().find(start, goal, 2, corridor(), List.of(near, exact));
		assertEquals(List.of(start, point(99, 0), near.destination), route.getPath());
		assertEquals(Map.of(1, near), route.getTransports());
	}

	private static GenericClientPathfinder pathfinder() throws Exception
	{
		return new GenericClientPathfinder(GenericClientCollisionMap.loadBundled());
	}

	private static GenericClientPathfinder.EdgePolicy corridor()
	{
		return (x, y, plane, dx, dy, allowed) -> y + dy == 100 && x + dx >= 90 && x + dx <= 210;
	}

	private static WorldPoint point(int x, int plane) { return new WorldPoint(x, 100, plane); }
}
