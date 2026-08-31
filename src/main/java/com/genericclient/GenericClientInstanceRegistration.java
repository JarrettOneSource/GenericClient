package com.genericclient;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class GenericClientInstanceRegistration implements AutoCloseable
{
	static final String SCHEMA = "genericclient_instance.v1";

	private final Gson gson = new Gson();
	private final Path descriptorPath;
	private final Path temporaryPath;
	private final String instanceId;
	private final long pid;
	private final long startedEpochMillis;
	private final boolean dense;
	private final String runeLiteProfile;
	private Map<String, Object> lastValue;

	private GenericClientInstanceRegistration(
		Path directory,
		String instanceId,
		long pid,
		long startedEpochMillis,
		boolean dense,
		String runeLiteProfile)
	{
		this.descriptorPath = directory.resolve(instanceId + ".json");
		this.temporaryPath = directory.resolve(instanceId + ".json.tmp-" + pid);
		this.instanceId = instanceId;
		this.pid = pid;
		this.startedEpochMillis = startedEpochMillis;
		this.dense = dense;
		this.runeLiteProfile = runeLiteProfile;
	}

	static GenericClientInstanceRegistration create(GenericClientRuntimeOptions options)
	{
		ProcessHandle process = ProcessHandle.current();
		long started = process.info().startInstant()
			.orElseGet(Instant::now)
			.toEpochMilli();
		return new GenericClientInstanceRegistration(
			options.getInstanceDirectory(),
			options.getInstanceId(),
			process.pid(),
			started,
			options.isDense(),
			options.getRuneLiteProfile());
	}

	static GenericClientInstanceRegistration forTest(
		Path directory,
		String instanceId,
		long pid,
		long startedEpochMillis,
		boolean dense,
		String runeLiteProfile)
	{
		return new GenericClientInstanceRegistration(
			directory, instanceId, pid, startedEpochMillis, dense, runeLiteProfile);
	}

	synchronized Map<String, Object> publish(
		String controlUrl,
		String lifecycle,
		String launcherDisplayName,
		String accountProfileId) throws IOException
	{
		if (controlUrl == null || !controlUrl.startsWith("http://127.0.0.1:"))
		{
			throw new IllegalArgumentException("controlUrl must use IPv4 loopback");
		}
		if (lifecycle == null || lifecycle.trim().isEmpty())
		{
			throw new IllegalArgumentException("lifecycle is required");
		}

		Map<String, Object> value = new LinkedHashMap<>();
		value.put("schema", SCHEMA);
		value.put("instance_id", instanceId);
		value.put("pid", pid);
		value.put("started_epoch_millis", startedEpochMillis);
		value.put("control_url", controlUrl);
		value.put("lifecycle", lifecycle);
		value.put("dense", dense);
		value.put("runelite_profile", runeLiteProfile);
		value.put("launcher_display_name", emptyToNull(launcherDisplayName));
		value.put("account_profile_id", emptyToNull(accountProfileId));

		Files.createDirectories(descriptorPath.getParent());
		Files.writeString(
			temporaryPath,
			gson.toJson(value) + System.lineSeparator(),
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE);
		try
		{
			Files.move(
				temporaryPath,
				descriptorPath,
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException exception)
		{
			Files.move(
				temporaryPath,
				descriptorPath,
				StandardCopyOption.REPLACE_EXISTING);
		}
		lastValue = new LinkedHashMap<>(value);
		return new LinkedHashMap<>(value);
	}

	synchronized Map<String, Object> metadata()
	{
		if (lastValue != null)
		{
			return new LinkedHashMap<>(lastValue);
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("schema", SCHEMA);
		value.put("instance_id", instanceId);
		value.put("pid", pid);
		value.put("started_epoch_millis", startedEpochMillis);
		value.put("dense", dense);
		value.put("runelite_profile", runeLiteProfile);
		return value;
	}

	Path getDescriptorPath()
	{
		return descriptorPath;
	}

	@Override
	public synchronized void close() throws IOException
	{
		Files.deleteIfExists(temporaryPath);
		if (Files.exists(descriptorPath) && ownsDescriptor())
		{
			Files.delete(descriptorPath);
		}
		lastValue = null;
	}

	private boolean ownsDescriptor()
	{
		try
		{
			Descriptor descriptor = gson.fromJson(
				Files.readString(descriptorPath, StandardCharsets.UTF_8),
				Descriptor.class);
			return descriptor != null &&
				instanceId.equals(descriptor.instance_id) &&
				pid == descriptor.pid;
		}
		catch (IOException | RuntimeException exception)
		{
			return false;
		}
	}

	private static String emptyToNull(String value)
	{
		if (value == null)
		{
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static final class Descriptor
	{
		private String instance_id;
		private long pid;
	}
}
