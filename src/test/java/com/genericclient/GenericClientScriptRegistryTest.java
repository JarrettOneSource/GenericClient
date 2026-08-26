package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientScriptRegistryTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void installsAndLoadsTheBundledManifest() throws Exception
	{
		GenericClientScriptRegistry registry = new GenericClientScriptRegistry(
			temporaryFolder.newFolder("scripts").toPath());

		assertEquals(3, registry.list().size());
		assertEquals("Walk to Varrock", registry.get("lumbridge-varrock").getName());
		assertTrue(registry.readSource("npc-diagnostics").contains("gc.read(\"npcs\""));
	}

	@Test
	public void savesAStandaloneScriptAndRegistersItInTheManifest() throws Exception
	{
		Path directory = temporaryFolder.newFolder("saved-scripts").toPath();
		GenericClientScriptRegistry registry = new GenericClientScriptRegistry(directory);

		registry.save(
			"where-am-i",
			"Where am I?",
			"Return the current player snapshot.",
			"return function() return gc.read('player') end\n");
		GenericClientScriptRegistry reloaded = new GenericClientScriptRegistry(directory);

		assertEquals("Where am I?", reloaded.get("where-am-i").getName());
		assertEquals("return function() return gc.read('player') end\n",
			reloaded.readSource("where-am-i"));
		assertTrue(java.nio.file.Files.readString(directory.resolve("manifest.json"))
			.contains("\"id\": \"where-am-i\""));
	}
}
