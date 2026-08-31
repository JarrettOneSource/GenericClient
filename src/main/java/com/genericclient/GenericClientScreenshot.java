package com.genericclient;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import net.runelite.client.ui.DrawManager;

final class GenericClientScreenshot implements AutoCloseable
{
	private static final long CAPTURE_TIMEOUT_SECONDS = 10L;
	private static final int MAX_BLANK_FRAMES = 2;

	private final DrawManager drawManager;
	private final ScheduledExecutorService executor;
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile CompletableFuture<Map<String, Object>> activeCapture;
	private volatile boolean closed;

	GenericClientScreenshot(DrawManager drawManager, ScheduledExecutorService executor)
	{
		this.drawManager = drawManager;
		this.executor = executor;
	}

	CompletableFuture<Map<String, Object>> capture()
	{
		if (closed)
		{
			return failed("Screenshot capture is closed");
		}
		if (!running.compareAndSet(false, true))
		{
			return failed("Another screenshot capture is already pending");
		}

		CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
		activeCapture = completion;
		ScheduledFuture<?> timeout = executor.schedule(
			() -> completion.completeExceptionally(
				new IllegalStateException("RuneLite did not render a frame within 10 seconds")),
			CAPTURE_TIMEOUT_SECONDS,
			TimeUnit.SECONDS);
		completion.whenComplete((ignored, error) ->
		{
			timeout.cancel(false);
			activeCapture = null;
			running.set(false);
		});

		requestFrame(completion, 0);
		return completion;
	}

	private void requestFrame(
		CompletableFuture<Map<String, Object>> completion,
		int blankFrames)
	{
		drawManager.requestNextFrameListener(image ->
		{
			if (completion.isDone())
			{
				return;
			}
			BufferedImage buffered;
			try
			{
				buffered = copy(image);
			}
			catch (IOException | RuntimeException exception)
			{
				completion.completeExceptionally(exception);
				return;
			}
			if (blankFrames + 1 < MAX_BLANK_FRAMES && isBlank(buffered))
			{
				executor.execute(() -> requestFrame(completion, blankFrames + 1));
				return;
			}
			executor.execute(() ->
			{
				try
				{
					completion.complete(encode(buffered));
				}
				catch (IOException | RuntimeException exception)
				{
					completion.completeExceptionally(exception);
				}
			});
		});
	}

	static Map<String, Object> encode(Image image) throws IOException
	{
		return encode(copy(image));
	}

	private static BufferedImage copy(Image image) throws IOException
	{
		if (image == null || image.getWidth(null) <= 0 || image.getHeight(null) <= 0)
		{
			throw new IOException("RuneLite returned an empty screenshot frame");
		}
		int width = image.getWidth(null);
		int height = image.getHeight(null);
		BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = buffered.createGraphics();
		graphics.drawImage(image, 0, 0, null);
		graphics.dispose();
		return buffered;
	}

	private static Map<String, Object> encode(BufferedImage buffered) throws IOException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		if (!ImageIO.write(buffered, "PNG", output))
		{
			throw new IOException("No PNG encoder is available");
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("mime_type", "image/png");
		value.put("image_base64", Base64.getEncoder().encodeToString(output.toByteArray()));
		value.put("width", (long) buffered.getWidth());
		value.put("height", (long) buffered.getHeight());
		value.put("captured_at_epoch_millis", System.currentTimeMillis());
		return value;
	}

	private static boolean isBlank(BufferedImage image)
	{
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) & 0x00FFFFFF) != 0)
				{
					return false;
				}
			}
		}
		return true;
	}

	private static <T> CompletableFuture<T> failed(String message)
	{
		CompletableFuture<T> future = new CompletableFuture<>();
		future.completeExceptionally(new IllegalStateException(message));
		return future;
	}

	@Override
	public void close()
	{
		closed = true;
		CompletableFuture<Map<String, Object>> capture = activeCapture;
		if (capture != null)
		{
			capture.completeExceptionally(new IllegalStateException("Screenshot capture stopped"));
		}
	}
}
