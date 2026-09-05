package com.genericclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.dreambot.api.script.AbstractScript;

final class GenericClientJavaCompiler
{
	Compilation compile(String className, String source, Path directory) throws IOException
	{
		if (!className.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*"))
			throw new IllegalArgumentException("A Java class name is required");
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null)
			throw new IllegalStateException("Source compilation requires a JDK; install a compiled script JAR instead");
		Files.createDirectories(directory);
		Path temporary = Files.createTempDirectory(directory, ".compile-");
		try
		{
			Path input = temporary.resolve("source/" + className.replace('.', '/') + ".java");
			Path classes = Files.createDirectories(temporary.resolve("classes"));
			Files.createDirectories(input.getParent());
			Files.writeString(input, source, StandardCharsets.UTF_8);
			compileSource(compiler, input, classes, directory);
			Path jar = temporary.resolve("script.jar");
			writeJar(classes, jar);
			return new Compilation(temporary, jar, directory.resolve(className + ".jar"));
		}
		catch (IOException | RuntimeException failure)
		{
			deleteDirectory(temporary);
			throw failure;
		}
	}

	static void deleteDirectory(Path directory) throws IOException
	{
		try (Stream<Path> paths = Files.walk(directory))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(path);
		}
	}

	static final class Compilation implements AutoCloseable
	{
		final Path jar;
		final Path destination;
		private final Path temporary;

		Compilation(Path temporary, Path jar, Path destination)
		{
			this.temporary = temporary;
			this.jar = jar;
			this.destination = destination;
		}

		void install() throws IOException
		{
			Files.move(jar, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
				java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		}

		@Override public void close() throws IOException { deleteDirectory(temporary); }
	}

	private void compileSource(JavaCompiler compiler, Path source, Path classes, Path directory) throws IOException
	{
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8))
		{
			List<String> classpath = new ArrayList<>();
			classpath.add(System.getProperty("java.class.path"));
			try
			{
				classpath.add(Path.of(AbstractScript.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
			}
			catch (java.net.URISyntaxException error)
			{
				throw new IOException("Cannot locate the script SDK", error);
			}
			try (Stream<Path> jars = Files.list(directory))
			{
				jars.filter(path -> path.toString().endsWith(".jar")).sorted()
					.forEach(path -> classpath.add(path.toString()));
			}
			List<String> options = List.of("--release", "11", "-proc:none", "-classpath",
				String.join(java.io.File.pathSeparator, classpath), "-d", classes.toString());
			boolean compiled = compiler.getTask(null, files, diagnostics, options, null,
				files.getJavaFileObjects(source.toFile())).call();
			if (!compiled)
			{
				String errors = diagnostics.getDiagnostics().stream()
					.map(value -> "line " + value.getLineNumber() + ": " + value.getMessage(java.util.Locale.ROOT))
					.collect(Collectors.joining("\n"));
				throw new IllegalArgumentException(errors);
			}
		}
	}

	private void writeJar(Path classes, Path destination) throws IOException
	{
		try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(destination));
			Stream<Path> files = Files.walk(classes))
		{
			for (Path path : files.filter(Files::isRegularFile).sorted().collect(Collectors.toList()))
			{
				jar.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\', '/')));
				Files.copy(path, jar);
			}
		}
	}
}
