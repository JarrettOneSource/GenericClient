package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class GenericClientObjectInputTest
{
	@Test
	public void retriesFourCameraQuadrantsAroundTheTarget()
	{
		assertEquals(100, GenericClientObjectInput.cameraYawForAttempt(100, 0));
		assertEquals(4196, GenericClientObjectInput.cameraYawForAttempt(100, 1));
		assertEquals(8292, GenericClientObjectInput.cameraYawForAttempt(100, 2));
		assertEquals(12388, GenericClientObjectInput.cameraYawForAttempt(100, 3));
	}

	@Test
	public void prefersARealClickboxPointOverTheObjectsCanvasAnchor()
	{
		Rectangle clickbox = new Rectangle(200, 150, 40, 30);
		Rectangle viewport = new Rectangle(0, 0, 220, 180);
		net.runelite.api.Point canvasAnchor = new net.runelite.api.Point(600, 200);

		Point result = GenericClientObjectInput.clickPoint(
			clickbox, canvasAnchor, viewport);

		assertTrue(clickbox.contains(result));
		assertTrue(viewport.contains(result));
	}

	@Test
	public void rejectsAnObjectCanvasAnchorHiddenBehindFixedModeInterfaces()
	{
		Rectangle viewport = new Rectangle(0, 0, 517, 340);

		assertNull(GenericClientObjectInput.clickPoint(
			null, new net.runelite.api.Point(552, 186), viewport));
		assertNull(GenericClientObjectInput.clickPoint(
			null, new net.runelite.api.Point(273, 496), viewport));
	}

	@Test
	public void facesAndRetriesWhenAProjectedClickboxHasNoMenuAction()
	{
		assertTrue(GenericClientObjectInput.shouldFaceAndRetry(
			receipt("hover_has_no_matching_action")));
		assertTrue(GenericClientObjectInput.shouldFaceAndRetry(
			receipt("context_menu_has_no_matching_action")));
		assertTrue(GenericClientObjectInput.shouldFaceAndRetry(
			receipt("object_not_visible")));
		assertFalse(GenericClientObjectInput.shouldFaceAndRetry(
			receipt("matching_object_not_found")));
	}

	private static Map<String, Object> receipt(String result)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("result", result);
		return receipt;
	}
}
