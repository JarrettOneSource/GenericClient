package com.genericclient;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.rules.TemporaryFolder;

/** Load the external catalog through the same registry and Lua VM as the client. */
public final class GenericClientScriptCatalogAudit
{
	public static void main(String[] args) throws Exception
	{
		Path scripts = Path.of(args[0]).resolve("scripts");
		if (!Files.isRegularFile(scripts.resolve("manifest.json")))
			throw new IllegalArgumentException("Script catalog manifest is missing: " + scripts);
		GenericClientScriptRegistry registry = new GenericClientScriptRegistry(scripts);
		TemporaryFolder temporary = new TemporaryFolder();
		temporary.create();
		try (GenericClientBehaviorController behavior = GenericClientTestSupport.behavior(
			temporary.newFolder("behavior").toPath());
			GenericClientLuaHost host = GenericClientTestSupport.luaHost(
				temporary.newFolder("host").toPath().resolve("scripts"), behavior).build())
		{
			int inputs = 0;
			int actions = 0;
			int modules = 0;
			for (GenericClientScriptRegistry.Script definition : registry.list())
			{
				try (GenericClientLuaScript script = new GenericClientLuaScript(
					host, definition.getId(), registry.readExecutableSource(definition.getId())))
				{
					inputs += script.getInputs().size();
					actions += script.getActions().size();
					modules += registry.readModuleSources(definition.getId()).size();
				}
				catch (RuntimeException exception)
				{
					throw new IllegalStateException("Cannot load catalog script " + definition.getId(), exception);
				}
			}
			System.out.println("Loaded catalog scripts=" + registry.list().size() + " module_instances=" + modules +
				" inputs=" + inputs + " actions=" + actions + " (descriptor loading; no gameplay actions)");
		}
		finally
		{
			temporary.delete();
		}
	}
}
