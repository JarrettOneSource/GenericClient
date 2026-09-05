package com.genericclient;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientCompilerProcessTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void aRuntimeWithoutTheJdkCompilerRejectsSourceWithoutLeavingArtifacts() throws Exception
    {
        Path directory=temporary.newFolder().toPath();
        java.util.List<String> locations=new java.util.ArrayList<>();
        for (Class<?> type : List.of(getClass(),GenericClientJavaCompiler.class,org.junit.Test.class))
            locations.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        String classpath=String.join(File.pathSeparator,locations);
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "--limit-modules","java.base,java.desktop,java.compiler,java.logging",
            "-cp",classpath,getClass().getName(),directory.toString()).redirectErrorStream(true).start();
        try
        {
            assertTrue("Compiler probe did not exit",process.waitFor(10,TimeUnit.SECONDS));
            assertEquals(new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),0,process.exitValue());
            try (java.util.stream.Stream<Path> files=Files.list(directory)) { assertEquals(0,files.count()); }
        }
        finally { process.destroyForcibly(); }
    }

    public static void main(String[] args) throws Exception
    {
        try (GenericClientJavaCompiler.Compilation compilation=new GenericClientJavaCompiler().compile(
            "Example","public class Example {}",Path.of(args[0])))
        {
            throw new AssertionError("Source compiled without the JDK compiler module: " + compilation.jar);
        }
        catch (IllegalStateException expected)
        {
            if (!expected.getMessage().contains("requires a JDK")) throw expected;
        }
    }
}
