package com.genericclient;

import static com.genericclient.GenericClientErrors.rootMessage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class GenericClientDeathForensics
{
	private static final int FRAME_LIMIT = 20;
	private static final int NEARBY_NPC_LIMIT = 30;
	private static final int NEARBY_NPC_RADIUS = 16;

	private final Path directory;
	private final Supplier<CompletableFuture<Map<String, Object>>> screenshotAction;
	private final Consumer<String> reporter;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final ArrayDeque<Map<String, Object>> frames = new ArrayDeque<>();

	private int previousHitpoints = -1;
	private long lastDeathTick = -1L;
	private Path lastReport;
	private Path lastScreenshot;
	private String screenshotStatus = "none";

	GenericClientDeathForensics(
		Path directory,
		Supplier<CompletableFuture<Map<String, Object>>> screenshotAction,
		Consumer<String> reporter)
	{
		this.directory = directory;
		this.screenshotAction = screenshotAction;
		this.reporter = reporter;
	}

	synchronized void record(
		GenericClientSnapshot snapshot,
		Map<String, Object> context,
		int poisonValue)
	{
		if (snapshot == null || snapshot.getCurrentHitpoints() < 0)
		{
			previousHitpoints = -1;
			frames.clear();
			return;
		}

		Map<String, Object> frame = frame(snapshot, context, poisonValue);
		frames.addLast(frame);
		while (frames.size() > FRAME_LIMIT)
		{
			frames.removeFirst();
		}

		int hitpoints = snapshot.getCurrentHitpoints();
		if (hitpoints <= 0 && previousHitpoints > 0)
		{
			persist(snapshot, context);
		}
		previousHitpoints = hitpoints;
	}

	synchronized void reset()
	{
		previousHitpoints = -1;
		frames.clear();
	}

	synchronized Map<String, Object> status()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("available", true);
		value.put("directory", directory.toString());
		value.put("last_death_tick", lastDeathTick < 0 ? null : lastDeathTick);
		value.put("report", lastReport == null ? null : lastReport.toString());
		value.put("screenshot", lastScreenshot == null ? null : lastScreenshot.toString());
		value.put("screenshot_status", screenshotStatus);
		value.put("buffered_frames", (long) frames.size());
		return value;
	}

	private Map<String, Object> frame(
		GenericClientSnapshot snapshot,
		Map<String, Object> context,
		int poisonValue)
	{
		Map<String, Object> npcQuery = new LinkedHashMap<>();
		npcQuery.put("within", (long) NEARBY_NPC_RADIUS);
		npcQuery.put("limit", (long) NEARBY_NPC_LIMIT);

		Map<String, Object> value = new LinkedHashMap<>();
		value.put("game_tick", snapshot.getGameTick());
		value.put("player", snapshot.read("player", null));
		value.put("poison_value", (long) poisonValue);
		value.put("nearby_npcs", snapshot.read("npcs", npcQuery));
		value.put("context", new LinkedHashMap<>(context));
		return value;
	}

	private void persist(GenericClientSnapshot snapshot, Map<String, Object> context)
	{
		long deathTick = snapshot.getGameTick();
		String timestamp = Instant.now().toString().replace(':', '-');
		String stem = "death-" + timestamp + "-tick-" + deathTick;
		Path reportPath = directory.resolve(stem + ".json");
		Path screenshotPath = directory.resolve(stem + ".png");

		Map<String, Object> messageQuery = new LinkedHashMap<>();
		messageQuery.put("limit", 20L);
		List<Map<String, Object>> recordedFrames = new ArrayList<>(frames);
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("schema", "genericclient_death_forensics");
		report.put("captured_at", Instant.now().toString());
		report.put("death_tick", deathTick);
		report.put("probable_attackers", probableAttackers(recordedFrames));
		report.put("recent_messages", snapshot.read("messages", messageQuery));
		report.put("death_context", new LinkedHashMap<>(context));
		report.put("frames", recordedFrames);
		report.put("screenshot", screenshotPath.toString());

		try
		{
			Files.createDirectories(directory);
			Files.writeString(reportPath, gson.toJson(report), StandardCharsets.UTF_8);
			lastDeathTick = deathTick;
			lastReport = reportPath;
			lastScreenshot = screenshotPath;
			screenshotStatus = "pending";
			reporter.accept("DEATH_FORENSICS_WRITTEN tick=" + deathTick +
				" report=" + reportPath + " attackers=" +
				((List<?>) report.get("probable_attackers")).size());
		}
		catch (IOException exception)
		{
			screenshotStatus = "report_failed:" + exception.getMessage();
			reporter.accept("DEATH_FORENSICS_FAILED tick=" + deathTick +
				" message=" + exception.getMessage());
			return;
		}

		screenshotAction.get().whenComplete((image, error) ->
		{
			if (error != null)
			{
				setScreenshotStatus("failed:" + rootMessage(error));
				reporter.accept("DEATH_FORENSICS_SCREENSHOT_FAILED tick=" + deathTick +
					" message=" + rootMessage(error));
				return;
			}
			try
			{
				Object encoded = image == null ? null : image.get("image_base64");
				if (!(encoded instanceof String))
				{
					throw new IOException("Screenshot response did not contain image_base64");
				}
				Files.write(screenshotPath, Base64.getDecoder().decode((String) encoded));
				setScreenshotStatus("captured");
				reporter.accept("DEATH_FORENSICS_SCREENSHOT_WRITTEN tick=" + deathTick +
					" screenshot=" + screenshotPath);
			}
			catch (IOException | IllegalArgumentException exception)
			{
				setScreenshotStatus("failed:" + exception.getMessage());
				reporter.accept("DEATH_FORENSICS_SCREENSHOT_FAILED tick=" + deathTick +
					" message=" + exception.getMessage());
			}
		});
	}

	private static List<Map<String, Object>> probableAttackers(
		List<Map<String, Object>> recordedFrames)
	{
		Map<String, Map<String, Object>> attackers = new LinkedHashMap<>();
		for (Map<String, Object> frame : recordedFrames)
		{
			Object playerValue = frame.get("player");
			if (!(playerValue instanceof Map))
			{
				continue;
			}
			String playerName = String.valueOf(((Map<?, ?>) playerValue).get("name"));
			Object npcValue = frame.get("nearby_npcs");
			if (!(npcValue instanceof List))
			{
				continue;
			}
			for (Object value : (List<?>) npcValue)
			{
				if (!(value instanceof Map))
				{
					continue;
				}
				Map<?, ?> npc = (Map<?, ?>) value;
				if (!playerName.equals(String.valueOf(npc.get("interacting"))))
				{
					continue;
				}
				String key = npc.get("id") + ":" + npc.get("index");
				Map<String, Object> attacker = attackers.get(key);
				if (attacker == null)
				{
					attacker = new LinkedHashMap<>();
					attacker.put("id", npc.get("id"));
					attacker.put("index", npc.get("index"));
					attacker.put("name", npc.get("name"));
					attacker.put("combat_level", npc.get("combat_level"));
					attacker.put("observed_ticks", 0L);
					attackers.put(key, attacker);
				}
				attacker.put("observed_ticks",
					((Number) attacker.get("observed_ticks")).longValue() + 1L);
				attacker.put("last_game_tick", frame.get("game_tick"));
				attacker.put("last_world", npc.get("world"));
				attacker.put("last_distance", npc.get("distance"));
				attacker.put("last_animation", npc.get("animation"));
			}
		}
		return new ArrayList<>(attackers.values());
	}

	private synchronized void setScreenshotStatus(String status)
	{
		screenshotStatus = status;
	}

}
