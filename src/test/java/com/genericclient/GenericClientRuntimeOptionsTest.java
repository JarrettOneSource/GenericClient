package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.util.Properties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientRuntimeOptionsTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void usesConfiguredDefaultsAndGeneratedIdentity() throws Exception
	{
		Path runeLite = temporaryFolder.newFolder("runelite-default").toPath();
		GenericClientRuntimeOptions options = GenericClientRuntimeOptions.from(
			new Properties(), 17_343, runeLite, () -> "generated-instance");

		assertFalse(options.isDense());
		assertTrue(options.isPresentationEnabled());
		assertEquals("generated-instance", options.getInstanceId());
		assertEquals(17_343, options.getControlPort());
		assertEquals(
			runeLite.resolve("genericclient").resolve("instances"),
			options.getInstanceDirectory());
		assertNull(options.getRuneLiteProfile());
	}

	@Test
	public void acceptsDenseEphemeralAndExplicitOverrides() throws Exception
	{
		Path runeLite = temporaryFolder.newFolder("runelite-explicit").toPath();
		Path descriptors = temporaryFolder.newFolder("descriptors").toPath();
		Properties properties = new Properties();
		properties.setProperty(GenericClientRuntimeOptions.DENSE_PROPERTY, "TRUE");
		properties.setProperty(GenericClientRuntimeOptions.INSTANCE_ID_PROPERTY, "poc-client_1");
		properties.setProperty(GenericClientRuntimeOptions.CONTROL_PORT_PROPERTY, "0");
		properties.setProperty(
			GenericClientRuntimeOptions.INSTANCE_DIRECTORY_PROPERTY,
			descriptors.toString());
		properties.setProperty(GenericClientRuntimeOptions.RUNELITE_PROFILE_PROPERTY, "poc-one");

		GenericClientRuntimeOptions options = GenericClientRuntimeOptions.from(
			properties, 17_343, runeLite, () -> "unused");

		assertTrue(options.isDense());
		assertFalse(options.isPresentationEnabled());
		assertEquals("poc-client_1", options.getInstanceId());
		assertEquals(0, options.getControlPort());
		assertEquals(descriptors.toAbsolutePath(), options.getInstanceDirectory());
		assertEquals("poc-one", options.getRuneLiteProfile());
	}

	@Test
	public void rejectsInvalidValues() throws Exception
	{
		Path runeLite = temporaryFolder.newFolder("runelite-invalid").toPath();
		assertInvalid(runeLite, GenericClientRuntimeOptions.DENSE_PROPERTY, "sometimes");
		assertInvalid(runeLite, GenericClientRuntimeOptions.INSTANCE_ID_PROPERTY, "bad/id");
		assertInvalid(runeLite, GenericClientRuntimeOptions.CONTROL_PORT_PROPERTY, "65536");
		assertInvalid(runeLite, GenericClientRuntimeOptions.CONTROL_PORT_PROPERTY, "auto");
	}

	private static void assertInvalid(Path runeLite, String key, String value)
	{
		Properties properties = new Properties();
		properties.setProperty(key, value);
		try
		{
			GenericClientRuntimeOptions.from(
				properties, 17_343, runeLite, () -> "generated-instance");
			fail("Expected invalid property: " + key);
		}
		catch (IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage().contains(key));
		}
	}
}
