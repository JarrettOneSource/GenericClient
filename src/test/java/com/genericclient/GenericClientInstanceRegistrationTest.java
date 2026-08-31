package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientInstanceRegistrationTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	@SuppressWarnings("unchecked")
	public void publishesUpdatesAndRemovesOwnedDescriptor() throws Exception
	{
		Path directory = temporaryFolder.newFolder("instances").toPath();
		GenericClientInstanceRegistration registration =
			GenericClientInstanceRegistration.forTest(
				directory, "instance-one", 1234L, 5678L, true, "poc-one");

		Map<String, Object> first = registration.publish(
			"http://127.0.0.1:49152", "running", null, null);
		Path descriptor = registration.getDescriptorPath();
		assertTrue(Files.isRegularFile(descriptor));
		assertEquals(GenericClientInstanceRegistration.SCHEMA, first.get("schema"));
		assertEquals("instance-one", first.get("instance_id"));
		assertEquals(1234L, first.get("pid"));
		assertEquals(true, first.get("dense"));
		assertNull(first.get("launcher_display_name"));

		Map<String, Object> second = registration.publish(
			"http://127.0.0.1:49152", "login_screen", "Poc Character", "profile-a");
		assertEquals("login_screen", second.get("lifecycle"));
		assertEquals("Poc Character", second.get("launcher_display_name"));
		assertEquals("profile-a", second.get("account_profile_id"));

		Map<String, Object> disk = new Gson().fromJson(
			Files.readString(descriptor, StandardCharsets.UTF_8), Map.class);
		assertEquals("login_screen", disk.get("lifecycle"));
		assertFalse(Files.exists(directory.resolve("instance-one.json.tmp-1234")));

		registration.close();
		assertFalse(Files.exists(descriptor));
	}

	@Test
	public void doesNotDeleteAReplacementOwnedByAnotherProcess() throws Exception
	{
		Path directory = temporaryFolder.newFolder("replacement").toPath();
		GenericClientInstanceRegistration first =
			GenericClientInstanceRegistration.forTest(
				directory, "shared-id", 100L, 1L, false, null);
		GenericClientInstanceRegistration second =
			GenericClientInstanceRegistration.forTest(
				directory, "shared-id", 200L, 2L, false, null);
		first.publish("http://127.0.0.1:41000", "running", null, null);
		second.publish("http://127.0.0.1:42000", "running", null, null);

		first.close();
		assertTrue(Files.exists(second.getDescriptorPath()));

		second.close();
		assertFalse(Files.exists(second.getDescriptorPath()));
	}

	@Test
	public void rejectsNonLoopbackControlUrls() throws Exception
	{
		GenericClientInstanceRegistration registration =
			GenericClientInstanceRegistration.forTest(
				temporaryFolder.newFolder("invalid").toPath(),
				"instance-invalid", 300L, 3L, false, null);
		try
		{
			registration.publish("http://0.0.0.0:17343", "running", null, null);
			fail("Expected non-loopback URL rejection");
		}
		catch (IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage().contains("loopback"));
		}
		finally
		{
			registration.close();
		}
	}
}
