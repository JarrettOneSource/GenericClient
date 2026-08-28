package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import net.runelite.client.ui.DrawManager;
import org.junit.Test;

public class GenericClientScreenshotTest
{
	@Test
	public void capturesTheNextRenderedFrameAsPng() throws Exception
	{
		BufferedImage frame = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
		frame.setRGB(1, 1, Color.MAGENTA.getRGB());
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		GenericClientScreenshot screenshot = new GenericClientScreenshot(
			new ImmediateDrawManager(frame), executor);
		try
		{
			Map<String, Object> result = screenshot.capture().get(2, TimeUnit.SECONDS);
			BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(
				Base64.getDecoder().decode((String) result.get("image_base64"))));

			assertEquals("image/png", result.get("mime_type"));
			assertEquals(3L, result.get("width"));
			assertEquals(2L, result.get("height"));
			assertEquals(Color.MAGENTA.getRGB(), decoded.getRGB(1, 1));
		}
		finally
		{
			screenshot.close();
			executor.shutdownNow();
		}
	}

	private static final class ImmediateDrawManager extends DrawManager
	{
		private final Image image;

		private ImmediateDrawManager(Image image)
		{
			this.image = image;
		}

		@Override
		public void requestNextFrameListener(Consumer<Image> listener)
		{
			listener.accept(image);
		}
	}
}
