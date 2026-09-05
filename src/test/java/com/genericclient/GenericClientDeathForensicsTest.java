package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientDeathForensicsTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void writesTheRecentAttackerTimelineAndScreenshotWhenDeathIsObserved()
		throws Exception
	{
		Path directory = temporaryFolder.newFolder("forensics").toPath();
		Map<String, Object> screenshot = new LinkedHashMap<>();
		screenshot.put("image_base64", Base64.getEncoder().encodeToString(
			"png".getBytes(StandardCharsets.UTF_8)));
		GenericClientDeathForensics forensics = new GenericClientDeathForensics(
			directory,
			() -> CompletableFuture.completedFuture(screenshot),
			message -> { });

		Map<String, Object> context = new LinkedHashMap<>();
		context.put("active_script", "walker");
		context.put("script_state", "travel_to_ge");
		forensics.record(snapshot(40, 8), context, 0);
		forensics.record(snapshot(41, 0), context, 0);

		Map<String, Object> status = forensics.status();
		Path report = Path.of(String.valueOf(status.get("report")));
		Path image = Path.of(String.valueOf(status.get("screenshot")));
		assertTrue(Files.exists(report));
		assertTrue(Files.exists(image));
		assertEquals("captured", status.get("screenshot_status"));
		String json = Files.readString(report);
		assertTrue(json.contains("White wolf"));
		assertTrue(json.contains("probable_attackers"));
		assertTrue(json.contains("travel_to_ge"));
		assertTrue(json.contains("poison_value"));
	}

	private static GenericClientSnapshot snapshot(long tick, int hitpoints)
	{
		GenericClientNpcSnapshot wolf = new GenericClientNpcSnapshot(142L, 42,
			107,
			"White wolf",
			2850,
			3509,
			0,
			1,
			25,
			1234,
			"Player",
			Collections.singletonList("Attack"));
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientPlayerSnapshot(1L,
				"Player", 2850, 3509, 0, 0, -1, "White wolf",
				hitpoints, 28, 5000, true, null),
			Collections.singletonList(wolf));
	}
}
