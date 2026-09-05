package com.genericclient;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

@Slf4j
final class GenericClientMouseProfiles implements java.util.function.Supplier<GenericClientMouseProfile>, AutoCloseable
{
	private final GenericClientConfig config;
	private final ConfigManager configManager;
	private final ScheduledExecutorService executor;
	private final java.util.function.Consumer<String> reporter;
	private final Path mouseProfilesDirectory;
	private volatile GenericClientMouseProfile mouseProfile;
	private GenericClientMouseRecorder mouseRecorder;

	GenericClientMouseProfiles(GenericClientConfig config, ConfigManager configManager,
		ScheduledExecutorService executor, java.util.function.Consumer<String> reporter) throws IOException
	{
		this.config = config;
		this.configManager = configManager;
		this.executor = executor;
		this.reporter = reporter;
		mouseProfilesDirectory = net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("genericclient").resolve("mouse-profiles");
		GenericClientMouseProfile.installDefault(mouseProfilesDirectory);
		loadConfiguredMouseProfile();
	}

	void attachRecorder(java.awt.Canvas canvas, java.util.function.BooleanSupplier inputActive)
	{
		mouseRecorder = new GenericClientMouseRecorder(canvas,inputActive);
	}
	@Override public GenericClientMouseProfile get() { return mouseProfile; }
	boolean isRecording() { return mouseRecorder.isRecording(); }
	int getTemplateCount() { return mouseRecorder.getTemplateCount(); }
	@Override public void close() { if (mouseRecorder != null) mouseRecorder.close(); }

	void reload()
	{
		executor.execute(() ->
		{
			try
			{
				loadConfiguredMouseProfile();
				reporter.accept("MOUSE_PROFILE_LOADED file=" + config.mouseProfileFile() +
					" profile=" + mouseProfile.getProfileId() +
					" templates=" + mouseProfile.getTemplateCount());
			}
			catch (IOException | RuntimeException exception)
			{
				reporter.accept("MOUSE_PROFILE_LOAD_FAILED file=" + config.mouseProfileFile() +
					" message=" + exception.getMessage());
			}
		});
	}

	private void loadConfiguredMouseProfile() throws IOException
	{
		String configured = config.mouseProfileFile().trim();
		Path name = Path.of(configured).getFileName();
		if (configured.isEmpty() || !name.toString().equals(configured))
		{
			throw new IOException("Mouse profile must be a filename inside " + mouseProfilesDirectory);
		}
		mouseProfile = GenericClientMouseProfile.load(mouseProfilesDirectory.resolve(name));
	}

	void startRecording()
	{
		try
		{
			mouseRecorder.start();
			reporter.accept("MOUSE_RECORDING_STARTED");
		}
		catch (RuntimeException exception)
		{
			reporter.accept("MOUSE_RECORDING_FAILED message=" + exception.getMessage());
		}
	}

	void stopRecording()
	{
		final GenericClientMouseProfile recorded;
		final String profileId = "recorded-" + Instant.now().toEpochMilli();
		try
		{
			recorded = mouseRecorder.stop(profileId);
			reporter.accept("MOUSE_RECORDING_STOPPED templates=" + recorded.getTemplateCount());
		}
		catch (RuntimeException exception)
		{
			reporter.accept("MOUSE_RECORDING_FAILED message=" + exception.getMessage());
			return;
		}

		executor.execute(() ->
		{
			String fileName = profileId + ".json";
			try
			{
				recorded.save(mouseProfilesDirectory.resolve(fileName));
				mouseProfile = recorded;
				configManager.setConfiguration(GenericClientConfig.GROUP, "mouseProfileFile", fileName);
				reporter.accept("MOUSE_PROFILE_RECORDED file=" + fileName +
					" templates=" + recorded.getTemplateCount());
			}
			catch (IOException exception)
			{
				reporter.accept("MOUSE_RECORDING_SAVE_FAILED message=" + exception.getMessage());
			}
		});
	}

	List<String> list()
	{
		if (mouseProfilesDirectory == null)
		{
			return Collections.emptyList();
		}
		List<String> files = new ArrayList<>();
		try (java.util.stream.Stream<Path> paths = java.nio.file.Files.list(mouseProfilesDirectory))
		{
			paths.filter(java.nio.file.Files::isRegularFile)
				.map(path -> path.getFileName().toString())
				.filter(name -> name.endsWith(".json"))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.forEach(files::add);
		}
		catch (IOException exception)
		{
			log.warn("Unable to list mouse profiles", exception);
		}
		return files;
	}
}
