package com.genericclient;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;

final class GenericClientRuntimeOptions
{
	static final String DENSE_PROPERTY = "genericclient.dense";
	static final String INSTANCE_ID_PROPERTY = "genericclient.instanceId";
	static final String CONTROL_PORT_PROPERTY = "genericclient.controlPort";
	static final String INSTANCE_DIRECTORY_PROPERTY = "genericclient.instanceDirectory";
	static final String RUNELITE_PROFILE_PROPERTY = "genericclient.runeliteProfile";

	private final boolean dense;
	private final String instanceId;
	private final int controlPort;
	private final Path instanceDirectory;
	private final String runeLiteProfile;

	private GenericClientRuntimeOptions(
		boolean dense,
		String instanceId,
		int controlPort,
		Path instanceDirectory,
		String runeLiteProfile)
	{
		this.dense = dense;
		this.instanceId = instanceId;
		this.controlPort = controlPort;
		this.instanceDirectory = instanceDirectory;
		this.runeLiteProfile = runeLiteProfile;
	}

	static GenericClientRuntimeOptions load(int configuredPort, Path runeLiteDirectory)
	{
		return from(System.getProperties(), configuredPort, runeLiteDirectory,
			() -> UUID.randomUUID().toString());
	}

	static GenericClientRuntimeOptions from(
		Properties properties,
		int configuredPort,
		Path runeLiteDirectory,
		Supplier<String> instanceIdSupplier)
	{
		Objects.requireNonNull(properties, "properties");
		Objects.requireNonNull(runeLiteDirectory, "runeLiteDirectory");
		Objects.requireNonNull(instanceIdSupplier, "instanceIdSupplier");

		boolean dense = booleanProperty(properties, DENSE_PROPERTY, false);
		String instanceId = optional(properties, INSTANCE_ID_PROPERTY);
		if (instanceId == null)
		{
			instanceId = instanceIdSupplier.get();
		}
		validateInstanceId(instanceId);

		int controlPort = intProperty(
			properties, CONTROL_PORT_PROPERTY, configuredPort, 0, 65_535);
		String directory = optional(properties, INSTANCE_DIRECTORY_PROPERTY);
		Path instanceDirectory = directory == null
			? runeLiteDirectory.resolve("genericclient").resolve("instances")
			: Path.of(directory).toAbsolutePath().normalize();
		String runeLiteProfile = optional(properties, RUNELITE_PROFILE_PROPERTY);

		return new GenericClientRuntimeOptions(
			dense,
			instanceId,
			controlPort,
			instanceDirectory,
			runeLiteProfile);
	}

	boolean isDense()
	{
		return dense;
	}

	boolean isPresentationEnabled()
	{
		return !dense;
	}

	String getInstanceId()
	{
		return instanceId;
	}

	int getControlPort()
	{
		return controlPort;
	}

	Path getInstanceDirectory()
	{
		return instanceDirectory;
	}

	String getRuneLiteProfile()
	{
		return runeLiteProfile;
	}

	private static boolean booleanProperty(
		Properties properties,
		String name,
		boolean defaultValue)
	{
		String value = optional(properties, name);
		if (value == null)
		{
			return defaultValue;
		}
		if ("true".equalsIgnoreCase(value))
		{
			return true;
		}
		if ("false".equalsIgnoreCase(value))
		{
			return false;
		}
		throw new IllegalArgumentException(name + " must be true or false");
	}

	private static int intProperty(
		Properties properties,
		String name,
		int defaultValue,
		int minimum,
		int maximum)
	{
		String value = optional(properties, name);
		int parsed = defaultValue;
		if (value != null)
		{
			try
			{
				parsed = Integer.parseInt(value);
			}
			catch (NumberFormatException exception)
			{
				throw new IllegalArgumentException(name + " must be an integer", exception);
			}
		}
		if (parsed < minimum || parsed > maximum)
		{
			throw new IllegalArgumentException(
				name + " must be between " + minimum + " and " + maximum);
		}
		return parsed;
	}

	private static String optional(Properties properties, String name)
	{
		String value = properties.getProperty(name);
		if (value == null)
		{
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static void validateInstanceId(String instanceId)
	{
		if (instanceId == null ||
			instanceId.length() > 128 ||
			!instanceId.matches("[A-Za-z0-9][A-Za-z0-9._-]*"))
		{
			throw new IllegalArgumentException(
				INSTANCE_ID_PROPERTY + " must be 1-128 safe identifier characters");
		}
	}
}
