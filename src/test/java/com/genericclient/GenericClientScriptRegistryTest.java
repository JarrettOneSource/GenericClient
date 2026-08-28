package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

		assertEquals(6, registry.list().size());
		assertEquals("Account Auditor", registry.get("account-auditor").getName());
		assertEquals("AIO Melee Trainer", registry.get("aio-melee").getName());
		assertEquals("AIO Magic Trainer", registry.get("aio-magic").getName());
		assertTrue(registry.readSource("aio-magic").contains("combat.cast"));
		assertTrue(registry.readExecutableSource("aio-magic").contains("Unknown script module"));
		assertTrue(registry.readExecutableSource("aio-magic").contains("Port Sarim jail corridor"));
		assertTrue(Files.isRegularFile(
			temporaryFolder.getRoot().toPath().resolve("scripts/aio-magic/config.lua")));
		assertEquals("Quest Runner", registry.get("quest-runner").getName());
		assertTrue(registry.readSource("quest-runner").contains("shed_ready_checkpoint"));
		assertTrue(registry.readExecutableSource("quest-runner").contains("garden_key_obtained"));
		assertTrue(registry.readExecutableSource("quest-runner").contains("north_displacement_exhausted"));
		assertTrue(registry.readExecutableSource("quest-runner").contains("witchs_house_complete"));
		assertTrue(registry.readSource("aio-melee").contains("combat.set_style"));
		assertTrue(registry.readSource("account-auditor").contains("gc.read(\"account\")"));
		assertEquals("Walker", registry.get("walker").getName());
		assertTrue(registry.readSource("walker").contains("id = \"destination\""));
		assertTrue(registry.readSource("walker").contains("varrock_center"));
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
		Files.createDirectories(directory.resolve("quest-runner"));
		Files.writeString(directory.resolve("quest-runner/combat.lua"),
			"return { execute = function() return 'stale' end }\n");
		Files.writeString(directory.resolve("custom.lua"),
			"return { run = function(input) return 'custom' end }\n");

		GenericClientScriptRegistry registry = new GenericClientScriptRegistry(directory);

		assertEquals(7, registry.list().size());
		assertEquals("Walker", registry.get("walker").getName());
		assertEquals("Custom", registry.get("custom").getName());
		assertEquals("return { run = function(input) return 'custom' end }\n",
			registry.readSource("custom"));
		assertFalse(Files.exists(directory.resolve("npc-diagnostics.lua")));
		assertFalse(Files.exists(directory.resolve("quest-runner/combat.lua")));
		assertTrue(Files.isRegularFile(
			directory.resolve("quest-runner/witchs_house/combat.lua")));
		assertTrue(Files.readString(directory.resolve("manifest.json"))
			.contains("genericclient_scripts.v35"));
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
			.contains("genericclient_scripts.v35"));
	}

	@Test
	public void refreshesVersionTenQuestAndMagicScripts() throws Exception
	{
		Path directory = temporaryFolder.newFolder("version-ten-scripts").toPath();
		Files.writeString(directory.resolve("manifest.json"),
			"{\n" +
			"  \"schema\": \"genericclient_scripts.v10\",\n" +
			"  \"scripts\": [\n" +
			"    { \"id\": \"aio-magic\", \"name\": \"AIO Magic Trainer\", " +
				"\"description\": \"Old Magic\", \"file\": \"aio-magic.lua\" },\n" +
			"    { \"id\": \"quest-runner\", \"name\": \"Quest Runner\", " +
				"\"description\": \"Old quests\", \"file\": \"quest-runner.lua\" },\n" +
			"    { \"id\": \"custom\", \"name\": \"Custom\", " +
				"\"description\": \"Keep me\", \"file\": \"custom.lua\" }\n" +
			"  ]\n" +
			"}\n");
		Files.writeString(directory.resolve("aio-magic.lua"),
			"return { run = function(input) return 'stale' end }\n");
		Files.writeString(directory.resolve("quest-runner.lua"),
			"return { run = function(input) return 'stale' end }\n");
		Files.writeString(directory.resolve("custom.lua"),
			"return { run = function(input) return 'custom' end }\n");

		GenericClientScriptRegistry registry = new GenericClientScriptRegistry(directory);

		assertTrue(registry.readSource("aio-magic").contains("safety.configure"));
		assertTrue(registry.readSource("quest-runner").contains("preparation.prepare"));
		assertEquals("return { run = function(input) return 'custom' end }\n",
			registry.readSource("custom"));
		assertTrue(Files.readString(directory.resolve("manifest.json"))
			.contains("genericclient_scripts.v35"));
	}

	@Test
	public void composesOnlyManifestDeclaredModules() throws Exception
	{
		Path directory = temporaryFolder.newFolder("module-scripts").toPath();
		Files.createDirectories(directory.resolve("example"));
		Files.writeString(directory.resolve("manifest.json"),
			"{\n" +
			"  \"schema\": \"genericclient_scripts.v35\",\n" +
			"  \"scripts\": [{\n" +
			"    \"id\": \"example\", \"name\": \"Example\",\n" +
			"    \"description\": \"Modular example\", \"file\": \"example.lua\",\n" +
			"    \"modules\": { \"maths\": \"example/maths.lua\" }\n" +
			"  }]\n" +
			"}\n");
		Files.writeString(directory.resolve("example.lua"),
			"local maths = gc.require('maths')\n" +
			"return { run = function() return maths.answer end }\n");
		Files.writeString(directory.resolve("example/maths.lua"),
			"return { answer = 42 }\n");

		GenericClientScriptRegistry registry = new GenericClientScriptRegistry(directory);
		String executable = registry.readExecutableSource("example");

		assertTrue(executable.contains("__gc_module_loaders[\"maths\"]"));
		assertTrue(executable.contains("return { answer = 42 }"));
		assertEquals("return { answer = 42 }\n",
			registry.readModuleSources("example").get("maths"));
	}
}
