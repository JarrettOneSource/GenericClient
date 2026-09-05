package com.genericclient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Compiles one diagnostic body; its files belong to that invocation. */
final class GenericClientJavaConsole implements AutoCloseable
{
	private final Path directory;
	final GenericClientScriptRegistry.Script definition;

	GenericClientJavaConsole(String body) throws IOException
	{
		directory = Files.createTempDirectory("genericclient-java-");
		String source = "import java.util.*;\n" +
			"import org.dreambot.api.script.*;\n" +
			"import com.genericclient.script.*;\n" +
			"@ScriptManifest(name=\"Java console\",author=\"Operator\",category=Category.MISC,version=1)\n" +
			"public class Diagnostic extends AbstractScript {\n" +
			"  public int onLoop() { ScriptScope.current().result(evaluate()); return -1; }\n" +
			"  private Object evaluate() {\n" + body + "\n}\n}\n";
		try
		{
			GenericClientScriptRegistry registry = new GenericClientScriptRegistry(directory);
			registry.compile("Diagnostic", source);
			definition = registry.get("Diagnostic");
		}
		catch (IOException | RuntimeException failure)
		{
			close();
			throw failure;
		}
	}

	@Override
	public void close() throws IOException
	{
		GenericClientJavaCompiler.deleteDirectory(directory);
	}
}
