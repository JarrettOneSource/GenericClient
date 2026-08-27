package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class GenericClientScriptOverlayTest
{
	@Test
	public void hidesWithoutARunningScript()
	{
		GenericClientScriptOverlay overlay = new GenericClientScriptOverlay(GenericClientActiveScript::none);
		BufferedImage image = new BufferedImage(240, 100, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			assertNull(overlay.render(graphics));
		}
		finally
		{
			graphics.dispose();
		}
	}

	@Test
	public void rendersACompactHeaderRuntimeAndRows()
	{
		List<GenericClientOverlayRow> rows = GenericClientOverlayRow.parse(java.util.Arrays.asList(
			row("Destination", "Varrock"),
			row("State", "Walking")));
		AtomicReference<GenericClientActiveScript> script = new AtomicReference<>(
			new GenericClientActiveScript(
				"walker", "Walker", "Walk somewhere.", "WAITING", 65_000L,
				Collections.emptyList(), Collections.emptyMap(), Collections.emptyList(), rows));
		GenericClientScriptOverlay overlay = new GenericClientScriptOverlay(script::get);
		BufferedImage image = new BufferedImage(260, 100, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		Dimension size;
		try
		{
			size = overlay.render(graphics);
		}
		finally
		{
			graphics.dispose();
		}

		assertNotNull(size);
		assertEquals(53, size.height);
		assertTrue(size.width >= 112);
		assertTrue(coloredPixels(image) > 500);
		assertEquals("1:05", GenericClientScriptOverlay.formatRuntime(65_000L));
	}

	private static Map<String, Object> row(String label, String value)
	{
		Map<String, Object> row = new java.util.LinkedHashMap<>();
		row.put("label", label);
		row.put("value", value);
		return row;
	}

	private static int coloredPixels(BufferedImage image)
	{
		int count = 0;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) >>> 24) != 0)
				{
					count++;
				}
			}
		}
		return count;
	}
}
