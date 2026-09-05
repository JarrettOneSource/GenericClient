package com.genericclient;

import java.nio.file.Files;
import java.nio.file.Path;

/** Inspect the packaged Java catalog through the client registry without starting scripts. */
public final class GenericClientScriptCatalogAudit
{
	public static void main(String[] args) throws Exception
	{
		Path jars = Path.of(args[0]).resolve("build/libs");
		Path artifact = jars.resolve("GenericClientScripts.jar");
		if (!Files.isRegularFile(artifact))
			throw new IllegalArgumentException("Build the Java catalog before auditing it: " + artifact);
		GenericClientScriptRegistry registry = new GenericClientScriptRegistry(jars);
		if (registry.list().isEmpty()) throw new IllegalStateException("The packaged catalog has no script entries");
		int inputs = 0;
		int actions = 0;
		int eventIds = 0;
		for (GenericClientScriptRegistry.Script script : registry.list())
		{
			inputs += script.getInputs().size();
			actions += script.getActions().size();
			eventIds += script.getRandomEvents().size();
		}
		System.out.println("Loaded Java catalog scripts=" + registry.list().size() + " inputs=" + inputs +
			" actions=" + actions + " random_event_ids=" + eventIds + " (metadata loading; no gameplay actions)");
	}
}
