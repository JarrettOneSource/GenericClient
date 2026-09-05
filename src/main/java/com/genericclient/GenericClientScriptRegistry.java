package com.genericclient;

import com.genericclient.script.ScriptSettings;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.dreambot.api.script.AbstractScript;
import org.dreambot.api.script.ScriptManifest;

final class GenericClientScriptRegistry
{
	private final Path directory;
	private volatile List<Script> scripts = Collections.emptyList();
	private volatile long revision;

	GenericClientScriptRegistry(Path directory) throws IOException
	{
		this.directory = directory;
		Files.createDirectories(directory);
		reload();
	}

	List<Script> list() { return scripts; }
	long getRevision() { return revision; }

	Script get(String id)
	{
		return scripts.stream().filter(script -> script.id.equals(id)).findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unknown script: " + id));
	}

	Script findRandomEventSolver(int npcId)
	{
		return scripts.stream().filter(script -> script.randomEvents.contains(npcId)).findFirst().orElse(null);
	}

	synchronized void reload() throws IOException
	{
		List<Path> jars = jarFiles();
		scripts = discoverCatalog(jars, jars);
		revision++;
	}

	private List<Path> jarFiles() throws IOException
	{
		try (Stream<Path> files = Files.list(directory))
		{
			return files.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".jar"))
				.sorted().collect(Collectors.toList());
		}
	}

	private List<Script> discoverCatalog(List<Path> inspectionPaths, List<Path> installedPaths) throws IOException
	{
		URL[] inspection = urls(inspectionPaths);
		URL[] installed = urls(installedPaths);
		Map<String, Script> discovered = new LinkedHashMap<>();
		Map<Integer, String> solvers = new LinkedHashMap<>();
		try (URLClassLoader loader = new URLClassLoader(inspection, AbstractScript.class.getClassLoader()))
		{
			for (int i = 0; i < inspectionPaths.size(); i++)
				discover(inspectionPaths.get(i), installedPaths.get(i), installed, loader, discovered, solvers);
		}
		List<Script> ordered = new ArrayList<>(discovered.values());
		ordered.sort(Comparator.comparing(Script::getName, String.CASE_INSENSITIVE_ORDER));
		return Collections.unmodifiableList(ordered);
	}

	private static URL[] urls(List<Path> paths) throws IOException
	{
		URL[] urls = new URL[paths.size()];
		for (int i = 0; i < paths.size(); i++) urls[i] = paths.get(i).toUri().toURL();
		return urls;
	}

	private void discover(Path path, Path installedPath, URL[] classpath, ClassLoader loader,
		Map<String, Script> discovered, Map<Integer, String> solvers) throws IOException
	{
		try (JarFile jar = new JarFile(path.toFile()))
		{
			List<String> names = jar.stream().map(entry -> entry.getName())
				.filter(name -> name.endsWith(".class") && !name.startsWith("META-INF/") && !name.endsWith("module-info.class"))
				.sorted().collect(Collectors.toList());
			for (String name : names)
			{
				Class<?> type = Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, loader);
				ScriptManifest manifest = type.getAnnotation(ScriptManifest.class);
				if (manifest == null || !AbstractScript.class.isAssignableFrom(type) || Modifier.isAbstract(type.getModifiers())) continue;
				Script script = new Script(type, manifest, installedPath, classpath);
				if (discovered.putIfAbsent(script.id, script) != null)
					throw new IOException("Duplicate script id: " + script.id);
				for (int npc : script.randomEvents)
				{
					if (solvers.putIfAbsent(npc, script.id) != null)
						throw new IOException("Duplicate random-event solver for NPC " + npc);
				}
			}
		}
		catch (ClassNotFoundException | LinkageError error)
		{
			throw new IOException("Cannot load script catalog " + path.getFileName() + ": " + error.getMessage(), error);
		}
	}

	synchronized void compile(String className, String source) throws IOException
	{
		try (GenericClientJavaCompiler.Compilation compiled = new GenericClientJavaCompiler().compile(className, source, directory))
		{
			List<Path> installed = jarFiles();
			if (!installed.contains(compiled.destination)) installed.add(compiled.destination);
			installed.sort(Comparator.naturalOrder());
			List<Path> inspection = new ArrayList<>(installed);
			inspection.set(installed.indexOf(compiled.destination), compiled.jar);
			List<Script> next = discoverCatalog(inspection, installed);
			if (next.stream().noneMatch(script -> script.className.equals(className)))
				throw new IllegalArgumentException("Compiled class must be an annotated AbstractScript: " + className);
			compiled.install();
			scripts = next;
			revision++;
		}
	}

	static final class Script
	{
		private final String id;
		private final String name;
		private final String description;
		private final String className;
		private final Path jar;
		private final URL[] classpath;
		private final List<GenericClientScriptInput> inputs;
		private final List<GenericClientScriptAction> actions;
		private final List<Integer> randomEvents;

		Script(Class<?> type, ScriptManifest manifest, Path jar, URL[] classpath) throws IOException
		{
			try { type.getConstructor(); }
			catch (NoSuchMethodException error) { throw new IOException("Script requires a public no-argument constructor: " + type.getName(), error); }
			ScriptSettings settings = type.getAnnotation(ScriptSettings.class);
			id = settings == null ? type.getName() : settings.id();
			if (id.isBlank()) throw new IOException("Script id must not be blank: " + type.getName());
			name = manifest.name();
			description = manifest.description();
			className = type.getName();
			this.jar = jar;
			this.classpath = classpath.clone();
			inputs = new ArrayList<>();
			actions = new ArrayList<>();
			randomEvents = new ArrayList<>();
			if (settings != null)
			{
				inputs.addAll(GenericClientScriptInput.from(settings.inputs()));
				actions.addAll(GenericClientScriptAction.from(settings.actions()));
				for (int npc : settings.randomEvents())
				{
					if (!GenericClientRandomEventController.isRandomEventNpcId(npc))
						throw new IOException("Unsupported random-event NPC: " + npc);
					randomEvents.add(npc);
				}
			}
		}

		String getId() { return id; }
		String getName() { return name; }
		String getDescription() { return description; }
		List<GenericClientScriptInput> getInputs() { return Collections.unmodifiableList(inputs); }
		List<GenericClientScriptAction> getActions() { return Collections.unmodifiableList(actions); }
		List<Integer> getRandomEvents() { return Collections.unmodifiableList(randomEvents); }

		LoadedScript load() throws IOException
		{
			URLClassLoader loader = new URLClassLoader(classpath, AbstractScript.class.getClassLoader());
			try
			{
				Class<? extends AbstractScript> type = Class.forName(className, false, loader).asSubclass(AbstractScript.class);
				return new LoadedScript(type, loader);
			}
			catch (ReflectiveOperationException | LinkageError error)
			{
				loader.close();
				throw new IOException("Cannot construct script " + id, error);
			}
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("id", id);
			value.put("name", name);
			value.put("description", description);
			value.put("class_name", className);
			value.put("file", jar.getFileName().toString());
			value.put("random_events", randomEvents);
			return value;
		}
	}

	static final class LoadedScript implements AutoCloseable
	{
		volatile AbstractScript script;
		private final Class<? extends AbstractScript> type;
		private final URLClassLoader loader;

		LoadedScript(Class<? extends AbstractScript> type, URLClassLoader loader)
		{
			this.type = type;
			this.loader = loader;
		}

		void instantiate() throws ReflectiveOperationException
		{
			script = type.getDeclaredConstructor().newInstance();
		}

		@Override
		public void close() throws IOException { loader.close(); }
	}
}
