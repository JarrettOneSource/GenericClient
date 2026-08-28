package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Canvas;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class GenericClientBreakOverlayTest
{
	@Test
	public void remainsHiddenWhileTheProfileIsReady()
	{
		AtomicReference<Map<String, Object>> status = new AtomicReference<>(status("ready", 0L));
		GenericClientBreakOverlay overlay = new GenericClientBreakOverlay(status::get);
		BufferedImage image = new BufferedImage(220, 40, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			assertNull(overlay.render(graphics));
		}
		finally
		{
			graphics.dispose();
		}
		assertEquals(0, coloredPixels(image));
	}

	@Test
	public void drawsOnlyTheActiveBreakKindAndCountdown()
	{
		AtomicReference<Map<String, Object>> status =
			new AtomicReference<>(status("micro_break", 12_400L));
		GenericClientBreakOverlay overlay = new GenericClientBreakOverlay(status::get);
		BufferedImage image = new BufferedImage(220, 40, BufferedImage.TYPE_INT_ARGB);
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
		assertEquals(28, size.height);
		assertTrue(size.width > 90);
		assertTrue(coloredPixels(image) > 300);
	}

	@Test
	public void closeButtonConsumesTheGameClickAndEndsAMicroBreak()
	{
		AtomicReference<Map<String, Object>> status =
			new AtomicReference<>(status("micro_break", 8_000L));
		AtomicInteger ended = new AtomicInteger();
		GenericClientBreakOverlay overlay = new GenericClientBreakOverlay(status::get, () ->
		{
			ended.incrementAndGet();
			status.set(status("ready", 0L));
			return CompletableFuture.completedFuture(Collections.emptyMap());
		});
		BufferedImage image = new BufferedImage(240, 40, BufferedImage.TYPE_INT_ARGB);
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
		overlay.getBounds().setBounds(40, 10, size.width, size.height);
		MouseEvent click = new MouseEvent(
			new Canvas(),
			MouseEvent.MOUSE_PRESSED,
			System.currentTimeMillis(),
			0,
			40 + size.width - 16,
			20,
			1,
			false,
			MouseEvent.BUTTON1);

		overlay.getMouseListener().mousePressed(click);

		assertTrue(click.isConsumed());
		assertEquals(1, ended.get());
	}

	private static Map<String, Object> status(String state, long remainingMillis)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("state", state);
		value.put("break_remaining_millis", remainingMillis);
		return value;
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
