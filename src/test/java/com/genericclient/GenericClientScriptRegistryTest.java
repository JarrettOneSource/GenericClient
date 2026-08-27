package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
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
		assertEquals("Walker", registry.get("walker").getName());
		assertTrue(registry.readSource("walker").contains("id = \"destination\""));
		assertTrue(registry.readSource("walker").contains("varrock_center"));
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
			"return { run = function(input) return gc.read('player') end }\n");
		GenericClientScriptRegistry reloaded = new GenericClientScriptRegistry(directory);

		assertEquals("Where am I?", reloaded.get("where-am-i").getName());
		assertEquals("return { run = function(input) return gc.read('player') end }\n",
			reloaded.readSource("where-am-i"));
		assertTrue(Files.readString(directory.resolve("manifest.json"))
			.contains("\"id\": \"where-am-i\""));
	}

	@Test
	public void migratesTheOldBundledScriptsWithoutRemovingCustomScripts() throws Exception
	{
		Path directory = temporaryFolder.newFolder("legacy-scripts").toPath();
		Files.writeString(directory.resolve("manifest.json"),
			"{\n" +
			"  \"schema\": \"genericclient_scripts.v1\",\n" +
			"  \"scripts\": [\n" +
			"    { \"id\": \"lumbridge-varrock\", \"name\": \"Walk to Varrock\", " +
				"\"description\": \"Old route\", \"file\": \"lumbridge-varrock.lua\" },\n" +
			"    { \"id\": \"npc-diagnostics\", \"name\": \"NPCs\", " +
				"\"description\": \"Old NPCs\", \"file\": \"npc-diagnostics.lua\" },\n" +
			"    { \"id\": \"walk-stress\", \"name\": \"Stress\", " +
				"\"description\": \"Old stress\", \"file\": \"walk-stress.lua\" },\n" +
			"    { \"id\": \"custom\", \"name\": \"Custom\", " +
				"\"description\": \"Keep me\", \"file\": \"custom.lua\" }\n" +
			"  ]\n" +
			"}\n");
		Files.writeString(directory.resolve("lumbridge-varrock.lua"), "return function() end\n");
		Files.writeString(directory.resolve("npc-diagnostics.lua"), "return function() end\n");
		Files.writeString(directory.resolve("walk-stress.lua"), "return function() end\n");
		Files.writeString(directory.resolve("custom.lua"),
			"return { run = function(input) return 'custom' end }\n");

		GenericClientScriptRegistry registry = new GenericClientScriptRegistry(directory);

		assertEquals(4, registry.list().size());
		assertEquals("Walker", registry.get("walker").getName());
		assertEquals("Custom", registry.get("custom").getName());
		assertEquals("return { run = function(input) return 'custom' end }\n",
			registry.readSource("custom"));
		assertTrue(registry.readSource("npc-diagnostics").contains("run = function(input)"));
		assertTrue(Files.readString(directory.resolve("manifest.json"))
			.contains("genericclient_scripts.v3"));
	}

	@Test
	public void refreshesVersionTwoBundledScriptsAndKeepsCustomEntries() throws Exception
	{
		Path directory = temporaryFolder.newFolder("version-two-scripts").toPath();
		Files.writeString(directory.resolve("manifest.json"),
			"{\n" +
			"  \"schema\": \"genericclient_scripts.v2\",\n" +
			"  \"scripts\": [\n" +
			"    { \"id\": \"walker\", \"name\": \"Walker\", " +
				"\"description\": \"Old Walker\", \"file\": \"walker.lua\" },\n" +
			"    { \"id\": \"custom\", \"name\": \"Custom\", " +
				"\"description\": \"Keep me\", \"file\": \"custom.lua\" }\n" +
			"  ]\n" +
			"}\n");
		Files.writeString(directory.resolve("walker.lua"), "return { run = function(input) end }\n");
		Files.writeString(directory.resolve("custom.lua"),
			"return { run = function(input) return 'custom' end }\n");

		GenericClientScriptRegistry registry = new GenericClientScriptRegistry(directory);

		assertEquals("Custom", registry.get("custom").getName());
		assertTrue(registry.readSource("walker").contains("gc.overlay"));
		assertEquals("return { run = function(input) return 'custom' end }\n",
			registry.readSource("custom"));
		assertTrue(Files.readString(directory.resolve("manifest.json"))
			.contains("genericclient_scripts.v3"));
	}
}
