package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientAutomationStoreTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void keepsRulesAndRuntimeStateIsolatedByProfile() throws Exception
	{
		Path directory = temporaryFolder.newFolder("automation-store").toPath();
		GenericClientAutomationStore store = new GenericClientAutomationStore(directory, "UTC");
		GenericClientAutomationConfig enabled = config(true);
		store.saveConfig("0123456789abcdef", enabled);
		GenericClientAutomationStore.State state = GenericClientAutomationStore.State.empty();
		state.setPaused(true);
		state.setActiveRule("train");
		state.setCooldown("train", 5_000L);
		state.setHandledRunId(7L);
		state.setLastEvent("completed:train");
		store.saveState("0123456789abcdef", state, 1_000L);

		assertTrue(store.loadConfig("0123456789abcdef").isEnabled());
		GenericClientAutomationStore.State loaded = store.loadState("0123456789abcdef");
		assertTrue(loaded.isPaused());
		assertEquals("train", loaded.getActiveRule());
		assertEquals(Long.valueOf(5_000L), loaded.getCooldowns().get("train"));
		assertEquals(7L, loaded.getHandledRunId());

		assertFalse(store.loadConfig("fedcba9876543210").isEnabled());
		assertFalse(store.loadState("fedcba9876543210").isPaused());
		assertTrue(Files.readString(store.configPath("0123456789abcdef")).contains(
			"genericclient_automation.v1"));
	}

	@Test(expected = IOException.class)
	public void rejectsInvalidPersistedConfig() throws Exception
	{
		Path directory = temporaryFolder.newFolder("invalid-automation-store").toPath();
		GenericClientAutomationStore store = new GenericClientAutomationStore(directory, "UTC");
		Files.writeString(store.configPath("0123456789abcdef"), "{\"schema\":\"wrong\"}");

		store.loadConfig("0123456789abcdef");
	}

	@SuppressWarnings("unchecked")
	private static GenericClientAutomationConfig config(boolean enabled)
	{
		String json = "{\"schema\":\"genericclient_automation.v1\",\"zone\":\"UTC\"," +
			"\"enabled\":" + enabled + ",\"schedules\":{},\"rules\":[]}";
		return GenericClientAutomationConfig.fromMap(new Gson().fromJson(json, Map.class));
	}
}
