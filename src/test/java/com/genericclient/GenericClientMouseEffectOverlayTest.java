package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientMouseEffectOverlayTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void drawsAndExpiresThePmouseStyleTrail()
	{
		AtomicReference<GenericClientMouseEffect> effect =
			new AtomicReference<>(GenericClientMouseEffect.TRAIL);
		AtomicLong clock = new AtomicLong(1_000L);
		GenericClientMouseEffectOverlay overlay = new GenericClientMouseEffectOverlay(
			effect::get,
			() -> 300,
			() -> 200,
			clock::get);
		overlay.updateCursor(new Point(150, 100), false);
		int cursorPixels = coloredPixels(render(overlay));
		overlay.recordPoint(new Point(20, 20));
		clock.addAndGet(20L);
		overlay.recordPoint(new Point(80, 60));

		BufferedImage visible = render(overlay);
		assertTrue(coloredPixels(visible) > cursorPixels + 20);

		clock.addAndGet(1_801L);
		BufferedImage expired = render(overlay);
		assertEquals(cursorPixels, coloredPixels(expired));
	}

	@Test
	public void drawsThePlannedPathAndCompletedProgress() throws Exception
	{
		AtomicReference<GenericClientMouseEffect> effect =
			new AtomicReference<>(GenericClientMouseEffect.PATH);
		GenericClientMouseEffectOverlay overlay = new GenericClientMouseEffectOverlay(
			effect::get,
			() -> 300,
			() -> 200,
			System::currentTimeMillis);
		overlay.updateCursor(new Point(150, 100), false);
		int cursorPixels = coloredPixels(render(overlay));
		List<GenericClientMouseMatcher.PathPoint> path = GenericClientMouseMatcher.generate(
			loadProfile(),
			new Point(20, 30),
			new Point(260, 160),
			new Rectangle(0, 0, 300, 200),
			100,
			new Random(7));
		overlay.beginPath(path);
		overlay.advancePath(path.size() / 2);

		BufferedImage image = render(overlay);
		assertTrue(countGreen(image) > 10);
		assertTrue(countBlue(image) > 10);

		overlay.endPath();
		assertEquals(cursorPixels, coloredPixels(render(overlay)));
	}

	@Test
	public void offModeClearsExistingEffects()
	{
		AtomicReference<GenericClientMouseEffect> effect =
			new AtomicReference<>(GenericClientMouseEffect.TRAIL);
		AtomicLong clock = new AtomicLong(1_000L);
		GenericClientMouseEffectOverlay overlay = new GenericClientMouseEffectOverlay(
			effect::get,
			() -> 300,
			() -> 200,
			clock::get);
		overlay.updateCursor(new Point(150, 100), false);
		int cursorPixels = coloredPixels(render(overlay));
		overlay.recordPoint(new Point(20, 20));
		clock.addAndGet(20L);
		overlay.recordPoint(new Point(80, 60));
		assertTrue(coloredPixels(render(overlay)) > 0);

		effect.set(GenericClientMouseEffect.OFF);
		assertEquals(cursorPixels, coloredPixels(render(overlay)));
		effect.set(GenericClientMouseEffect.TRAIL);
		assertEquals(cursorPixels, coloredPixels(render(overlay)));
	}

	@Test
	public void showsAnEdgeIndicatorWhenTheSyntheticCursorIsOffscreen()
	{
		GenericClientMouseEffectOverlay overlay = new GenericClientMouseEffectOverlay(
			() -> GenericClientMouseEffect.OFF,
			() -> 300,
			() -> 200,
			System::currentTimeMillis);
		overlay.updateCursor(new Point(-40, 80), true);

		BufferedImage image = render(overlay);
		assertTrue(coloredPixels(image) > 40);
		assertTrue(coloredPixels(image.getSubimage(0, 60, 25, 40)) > 40);
	}

	private GenericClientMouseProfile loadProfile() throws Exception
	{
		Path file = temporaryFolder.newFile().toPath();
		try (InputStream input = getClass().getResourceAsStream("/com/genericclient/mouse/default.json"))
		{
			Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		return GenericClientMouseProfile.load(file);
	}

	private static BufferedImage render(GenericClientMouseEffectOverlay overlay)
	{
		BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			overlay.render(graphics);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
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

	private static int countGreen(BufferedImage image)
	{
		return countColor(image, true);
	}

	private static int countBlue(BufferedImage image)
	{
		return countColor(image, false);
	}

	private static int countColor(BufferedImage image, boolean green)
	{
		int count = 0;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				int value = image.getRGB(x, y);
				int red = value >>> 16 & 0xFF;
				int greenValue = value >>> 8 & 0xFF;
				int blue = value & 0xFF;
				if ((value >>> 24) != 0 &&
					(green ? greenValue > blue && greenValue > red : blue > greenValue && blue > red))
				{
					count++;
				}
			}
		}
		return count;
	}
}
