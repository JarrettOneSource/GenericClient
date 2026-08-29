package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.runelite.api.gameval.NpcID;
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

		assertEquals(8, registry.list().size());
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
		assertTrue(registry.readExecutableSource("quest-runner").contains("pillar_count_unexpected"));
		assertTrue(registry.readExecutableSource("quest-runner").contains("equipment.interact"));
		assertTrue(registry.readExecutableSource("quest-runner").contains("glarial_tomb_exit_ladder_not_observed"));
		assertFalse(registry.readModuleSources("quest-runner").get("waterfall_config")
			.contains("3867"));
		assertTrue(registry.readSource("aio-melee").contains("combat.set_style"));
		assertTrue(registry.readSource("account-auditor").contains("gc.read(\"account\")"));
		assertEquals("capt-arnav", registry.findRandomEventSolver(5426).getId());
		assertTrue(registry.readSource("capt-arnav").contains("gc.read(\"widgets\","));
		assertEquals("pinball", registry.findRandomEventSolver(NpcID.PINBALL_INVITATION).getId());
		assertTrue(registry.readSource("pinball").contains("VARBITS.score"));
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
	public void refreshesBundledScriptsAndKeepsCustomScripts() throws Exception
	{
		Path directory = temporaryFolder.newFolder("refresh-scripts").toPath();
		Files.writeString(directory.resolve("manifest.json"),
			"{\n" +
			"  \"schema\": \"genericclient_scripts\",\n" +
			"  \"scripts\": [\n" +
			"    { \"id\": \"quest-runner\", \"name\": \"Quest Runner\", " +
				"\"description\": \"Stale bundled script\", \"file\": \"quest-runner.lua\" },\n" +
			"    { \"id\": \"custom\", \"name\": \"Custom\", " +
				"\"description\": \"Keep me\", \"file\": \"custom.lua\" }\n" +
			"  ]\n" +
			"}\n");
		Files.writeString(directory.resolve("quest-runner.lua"),
			"return { run = function() return 'stale' end }\n");
		Files.writeString(directory.resolve("npc-diagnostics.lua"), "return function() end\n");
		Files.createDirectories(directory.resolve("quest-runner"));
		Files.writeString(directory.resolve("quest-runner/combat.lua"),
			"return { execute = function() return 'stale' end }\n");
		Files.writeString(directory.resolve("custom.lua"),
			"return { run = function(input) return 'custom' end }\n");

		GenericClientScriptRegistry registry = new GenericClientScriptRegistry(directory);

		assertEquals(9, registry.list().size());
		assertEquals("Custom", registry.get("custom").getName());
		assertTrue(registry.readExecutableSource("quest-runner")
			.contains("local approach = walk(pillar.world, 3, 120)"));
		assertEquals("capt-arnav", registry.findRandomEventSolver(5426).getId());
		assertEquals("pinball", registry.findRandomEventSolver(NpcID.PINBALL_INVITATION).getId());
		assertEquals("return { run = function(input) return 'custom' end }\n",
			registry.readSource("custom"));
		assertFalse(Files.exists(directory.resolve("npc-diagnostics.lua")));
		assertFalse(Files.exists(directory.resolve("quest-runner/combat.lua")));
		assertTrue(Files.readString(directory.resolve("manifest.json"))
			.contains("\"schema\": \"genericclient_scripts\""));
	}

	@Test
	public void composesOnlyManifestDeclaredModules() throws Exception
	{
		Path directory = temporaryFolder.newFolder("module-scripts").toPath();
		Files.createDirectories(directory.resolve("example"));
		Files.writeString(directory.resolve("manifest.json"),
			"{\n" +
			"  \"schema\": \"genericclient_scripts\",\n" +
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

	@Test
	public void registersAStandaloneSolverByRandomEventNpcId() throws Exception
	{
		Path directory = temporaryFolder.newFolder("random-event-solver").toPath();
		GenericClientScriptRegistry registry = new GenericClientScriptRegistry(directory);

		registry.save(
			"miles-solver",
			"Miles Solver",
			"Solve the Miles random event from observed dialogue state.",
			"return { run = function() return gc.read('random_event') end }\n",
			List.of(NpcID.MACRO_MILES, NpcID.MACRO_MILES_UNDERWATER));
		GenericClientScriptRegistry reloaded = new GenericClientScriptRegistry(directory);

		assertEquals("miles-solver", reloaded.findRandomEventSolver(NpcID.MACRO_MILES).getId());
		assertEquals("miles-solver", reloaded.findRandomEventSolver(NpcID.MACRO_MILES_UNDERWATER).getId());
		assertEquals(
			List.of(NpcID.MACRO_MILES, NpcID.MACRO_MILES_UNDERWATER),
			reloaded.get("miles-solver").getRandomEvents());
		assertTrue(Files.readString(directory.resolve("manifest.json")).contains("\"random_events\""));
		try
		{
			reloaded.save(
				"another-miles-solver",
				"Another Miles Solver",
				"Conflicting registration.",
				"return { run = function() end }\n",
				List.of(NpcID.MACRO_MILES));
			throw new AssertionError("Expected duplicate solver registration to fail");
		}
		catch (IllegalArgumentException exception)
		{
			assertTrue(exception.getMessage().contains("already handled"));
		}
	}

	@Test
	public void rejectsDuplicateOrUnknownRandomEventNpcIds() throws Exception
	{
		Path directory = temporaryFolder.newFolder("duplicate-random-events").toPath();
		Files.writeString(directory.resolve("manifest.json"),
			"{\n" +
			"  \"schema\": \"genericclient_scripts\",\n" +
			"  \"scripts\": [\n" +
			"    { \"id\": \"one\", \"name\": \"One\", \"description\": \"First\", " +
				"\"file\": \"one.lua\", \"random_events\": [" + NpcID.MACRO_MILES + "] },\n" +
			"    { \"id\": \"two\", \"name\": \"Two\", \"description\": \"Second\", " +
				"\"file\": \"two.lua\", \"random_events\": [" + NpcID.MACRO_MILES + "] }\n" +
			"  ]\n" +
			"}\n");
		Files.writeString(directory.resolve("one.lua"), "return { run = function() end }\n");
		Files.writeString(directory.resolve("two.lua"), "return { run = function() end }\n");

		try
		{
			new GenericClientScriptRegistry(directory);
			throw new AssertionError("Expected duplicate random-event mapping to fail");
		}
		catch (java.io.IOException exception)
		{
			assertTrue(exception.getMessage().contains("already handled"));
		}

		GenericClientScriptRegistry registry = new GenericClientScriptRegistry(
			temporaryFolder.newFolder("unknown-random-event").toPath());
		try
		{
			registry.save(
				"not-random",
				"Not random",
				"Invalid registration.",
				"return { run = function() end }\n",
				List.of(1));
			throw new AssertionError("Expected unknown random-event NPC id to fail");
		}
		catch (IllegalArgumentException exception)
		{
			assertTrue(exception.getMessage().contains("not a supported random-event NPC"));
		}
	}
}
