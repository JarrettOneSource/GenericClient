package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Canvas;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientMouseProfileTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void loadsBundledRecordedDataAndGeneratesAnExactTimedPath() throws Exception
	{
		Path profileFile = temporaryFolder.newFile("default.json").toPath();
		try (InputStream input = getClass().getResourceAsStream("/com/genericclient/mouse/default.json"))
		{
			Files.copy(input, profileFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}

		GenericClientMouseProfile profile = GenericClientMouseProfile.load(profileFile);
		List<GenericClientMouseMatcher.PathPoint> path = GenericClientMouseMatcher.generate(
			profile,
			new Point(100, 200),
			new Point(700, 500),
			new Rectangle(0, 0, 1920, 1080),
			432,
			new Random(7));

		assertEquals("default-2dc51a50", profile.getProfileId());
		assertEquals(6069, profile.getTemplateCount());
		assertEquals(128, path.size());
		assertEquals(0.0, path.get(0).timeMillis, 0.0);
		assertEquals(100.0, path.get(0).x, 0.0);
		assertEquals(200.0, path.get(0).y, 0.0);
		assertEquals(432.0, path.get(path.size() - 1).timeMillis, 0.0);
		assertEquals(700.0, path.get(path.size() - 1).x, 0.0);
		assertEquals(500.0, path.get(path.size() - 1).y, 0.0);

		double previousTime = -1.0;
		StringBuilder parityOutput = new StringBuilder();
		for (GenericClientMouseMatcher.PathPoint point : path)
		{
			assertTrue(Double.isFinite(point.x));
			assertTrue(Double.isFinite(point.y));
			assertTrue(point.timeMillis >= previousTime);
			previousTime = point.timeMillis;
			parityOutput.append(Double.toHexString(point.timeMillis)).append(',')
				.append(Double.toHexString(point.x)).append(',')
				.append(Double.toHexString(point.y)).append('\n');
		}
		assertEquals(
			"7005b20b44a9e2561718c0f07eebeb29ed75f642ee4e3a611faaee8b5ffaa15b",
			sha256(parityOutput.toString()));
	}

	@Test
	public void savesAndReloadsARecordedProfile() throws Exception
	{
		double[] path = new double[64];
		double[] time = new double[32];
		for (int index = 0; index < 32; index++)
		{
			double progress = index / 31.0;
			path[index * 2] = progress;
			time[index] = progress;
		}
		GenericClientMouseProfile profile = GenericClientMouseProfile.recorded(
			"recorded-test",
			Collections.singletonList(new GenericClientMouseProfile.Template(
				300.0, 450.0, 0.0, path, time, 0.1, 0.2, 0.8, 0.7, true)));
		Path file = temporaryFolder.getRoot().toPath().resolve("recorded.json");

		profile.save(file);
		GenericClientMouseProfile loaded = GenericClientMouseProfile.load(file);

		assertEquals("recorded-test", loaded.getProfileId());
		assertEquals(1, loaded.getTemplateCount());
	}

	@Test
	public void recordsManualCanvasMovementIntoAProfile() throws Exception
	{
		Canvas canvas = new Canvas();
		canvas.setSize(800, 600);
		GenericClientMouseRecorder recorder = new GenericClientMouseRecorder(canvas, () -> false);
		try
		{
			recorder.start();
			MouseMotionListener motion = canvas.getMouseMotionListeners()[0];
			for (int index = 0; index < 10; index++)
			{
				motion.mouseMoved(mouseEvent(canvas, MouseEvent.MOUSE_MOVED, 100 + index * 25, 200 + index * 5));
				Thread.sleep(1);
			}
			MouseListener mouse = canvas.getMouseListeners()[0];
			mouse.mousePressed(mouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 325, 245));

			GenericClientMouseProfile profile = recorder.stop("recorded-canvas");

			assertEquals("recorded-canvas", profile.getProfileId());
			assertEquals(1, profile.getTemplateCount());
		}
		finally
		{
			recorder.close();
		}
	}

	private static MouseEvent mouseEvent(Canvas canvas, int id, int x, int y)
	{
		return new MouseEvent(
			canvas,
			id,
			System.currentTimeMillis(),
			0,
			x,
			y,
			1,
			false,
			id == MouseEvent.MOUSE_PRESSED ? MouseEvent.BUTTON1 : MouseEvent.NOBUTTON);
	}

	private static String sha256(String value) throws Exception
	{
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		StringBuilder hexadecimal = new StringBuilder(digest.length * 2);
		for (byte item : digest)
		{
			hexadecimal.append(String.format("%02x", item & 0xFF));
		}
		return hexadecimal.toString();
	}
}
