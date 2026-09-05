package com.genericclient;

import static org.junit.Assert.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientScriptRegistryTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test public void discoversManifestMetadataAndNativeControlsFromCompiledJava() throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		GenericClientScriptRegistry registry = new GenericClientScriptRegistry(directory);
		registry.compile("CatalogScript", GenericClientTestSupport.javaScript("CatalogScript",
			"@ScriptSettings(id=\"catalog\", randomEvents={5436,5437}, " +
			"inputs=@ScriptSettings.Input(id=\"mode\",label=\"Mode\",choices={\"one\",\"two\"},defaultValue=\"two\"), " +
			"actions=@ScriptSettings.Button(id=\"finish\",label=\"Finish\"))",
			"public int onLoop() { return -1; }"));
		GenericClientScriptRegistry reloaded = new GenericClientScriptRegistry(directory);
		assertEquals("CatalogScript", reloaded.get("catalog").getName());
		assertEquals(List.of(5436, 5437), reloaded.get("catalog").getRandomEvents());
		assertEquals("catalog", reloaded.findRandomEventSolver(5437).getId());
		assertEquals("two", reloaded.get("catalog").getInputs().get(0).getDefaultValue());
		assertEquals("finish", reloaded.get("catalog").getActions().get(0).getId());
	}

	@Test public void conflictingSolversDoNotReplaceTheLoadedCatalog() throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		GenericClientScriptRegistry registry = new GenericClientScriptRegistry(directory);
		registry.compile("First", GenericClientTestSupport.javaScript("First",
			"@ScriptSettings(id=\"first\",randomEvents={5436})", "public int onLoop() { return -1; }"));
		try { registry.compile("Second", GenericClientTestSupport.javaScript("Second",
			"@ScriptSettings(id=\"second\",randomEvents={5436})", "public int onLoop() { return -1; }")); fail("Conflicting solvers loaded"); }
		catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("Duplicate random-event solver")); }
		assertEquals("first", registry.findRandomEventSolver(5436).getId());
		assertEquals(1, registry.list().size());
		assertEquals("first", new GenericClientScriptRegistry(directory).findRandomEventSolver(5436).getId());
	}

	@Test public void duplicateButtonIdsAreRejectedBeforeInstallation() throws Exception
	{
		GenericClientScriptRegistry registry = new GenericClientScriptRegistry(temporary.newFolder().toPath());
		try
		{
			registry.compile("Buttons", GenericClientTestSupport.javaScript("Buttons",
				"@ScriptSettings(id=\"buttons\",actions={@ScriptSettings.Button(id=\"stop\",label=\"Stop\"),@ScriptSettings.Button(id=\"stop\",label=\"Again\")})",
				"public int onLoop(){return -1;}"));
			fail("Duplicate controls installed");
		}
		catch (IllegalArgumentException expected) { assertEquals("Duplicate script action id: stop",expected.getMessage()); }
		assertTrue(registry.list().isEmpty());
	}

	@Test public void classNamesCannotWriteOutsideTheCompilationDirectory() throws Exception
	{
		try
		{
			new GenericClientScriptRegistry(temporary.newFolder().toPath()).compile("../Outside", "");
			fail("Traversal accepted");
		}
		catch (IllegalArgumentException expected) { assertEquals("A Java class name is required", expected.getMessage()); }
	}

	@Test public void invalidIdsAndUndetectableEventNpcsAreRejectedBeforeInstallation() throws Exception
	{
		for (String settings : List.of("@ScriptSettings(id=\" \" )", "@ScriptSettings(id=\"invalid\",randomEvents={-1})"))
		{
			Path directory=temporary.newFolder().toPath();
			GenericClientScriptRegistry registry=new GenericClientScriptRegistry(directory);
			try
			{
				registry.compile("Invalid",GenericClientTestSupport.javaScript("Invalid",settings,"public int onLoop(){return -1;}"));
				fail("Invalid entry installed: " + settings);
			}
			catch (java.io.IOException expected)
			{
				assertTrue(expected.getMessage(),expected.getMessage().contains("Script id must not be blank") ||
					expected.getMessage().contains("Unsupported random-event NPC"));
			}
			assertTrue(new GenericClientScriptRegistry(directory).list().isEmpty());
		}
	}
}
