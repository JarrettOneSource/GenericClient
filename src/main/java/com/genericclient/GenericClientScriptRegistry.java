package com.genericclient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class GenericClientScriptRegistry
{
	private static final String SCHEMA = "genericclient_scripts.v1";
	private static final String MANIFEST_FILE = "manifest.json";
	private static final String RESOURCE_DIRECTORY = "/com/genericclient/scripts/";
	private static final Pattern SCRIPT_ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");
	private static final String[] BUNDLED_FILES =
	{
		MANIFEST_FILE,
		"lumbridge-varrock.lua",
		"npc-diagnostics.lua",
		"walk-stress.lua"
	};

	private final Path directory;
	private volatile State state;
	private volatile long revision;

	GenericClientScriptRegistry(Path directory) throws IOException
	{
		this.directory = directory;
		installBundledFiles();
		reload();
	}

	List<Script> list()
	{
		return state.scripts;
	}

	long getRevision()
	{
		return revision;
	}

	Script get(String id)
	{
		Script script = state.byId.get(id);
		if (script == null)
		{
			throw new IllegalArgumentException("Unknown script id: " + id);
		}
		return script;
	}

	String readSource(String id) throws IOException
	{
		return Files.readString(sourcePath(get(id)), StandardCharsets.UTF_8);
	}

	synchronized Script save(String id, String name, String description, String source) throws IOException
	{
		validateId(id);
		String cleanName = requireText(name, "Script name");
		String cleanDescription = requireText(description, "Script description");
		if (source == null || source.trim().isEmpty())
		{
			throw new IllegalArgumentException("Script source cannot be empty");
		}

		String file = id + ".lua";
		Script saved = new Script(id, cleanName, cleanDescription, file);
		writeAtomically(directory.resolve(file), source);

		List<Script> scripts = new ArrayList<>(state.scripts);
		scripts.removeIf(existing -> existing.id.equals(id));
		scripts.add(saved);
		scripts.sort(Comparator.comparing(Script::getName, String.CASE_INSENSITIVE_ORDER));
		writeManifest(scripts);
		state = buildState(scripts);
		revision++;
		return saved;
	}

	synchronized void reload() throws IOException
	{
		ManifestFile manifest;
		Path path = directory.resolve(MANIFEST_FILE);
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			manifest = new Gson().fromJson(reader, ManifestFile.class);
		}
		catch (JsonParseException exception)
		{
			throw new IOException("Invalid script manifest JSON: " + path, exception);
		}

		if (manifest == null || !SCHEMA.equals(manifest.schema))
		{
			throw new IOException("Unsupported script manifest schema: " + path);
		}
		if (manifest.scripts == null)
		{
			throw new IOException("Script manifest has no scripts array: " + path);
		}

		List<Script> scripts = new ArrayList<>(manifest.scripts.size());
		Set<String> ids = new HashSet<>();
		Set<String> files = new HashSet<>();
		for (int index = 0; index < manifest.scripts.size(); index++)
		{
			ManifestScript entry = manifest.scripts.get(index);
			if (entry == null)
			{
				throw new IOException("Script manifest entry " + index + " is null");
			}
			validateId(entry.id);
			String name = requireText(entry.name, "Script name");
			String description = requireText(entry.description, "Script description");
			String file = validateFileName(entry.file);
			if (!ids.add(entry.id))
			{
				throw new IOException("Duplicate script id: " + entry.id);
			}
			if (!files.add(file))
			{
				throw new IOException("Duplicate script file: " + file);
			}
			Script script = new Script(entry.id, name, description, file);
			if (!Files.isRegularFile(sourcePath(script)))
			{
				throw new IOException("Script file does not exist: " + file);
			}
			scripts.add(script);
		}

		scripts.sort(Comparator.comparing(Script::getName, String.CASE_INSENSITIVE_ORDER));
		state = buildState(scripts);
		revision++;
	}

	private void installBundledFiles() throws IOException
	{
		Files.createDirectories(directory);
		for (String file : BUNDLED_FILES)
		{
			Path target = directory.resolve(file);
			if (Files.exists(target))
			{
				continue;
			}
			try (InputStream input = GenericClientScriptRegistry.class.getResourceAsStream(RESOURCE_DIRECTORY + file))
			{
				if (input == null)
				{
					throw new IOException("Missing bundled script resource: " + file);
				}
				Files.copy(input, target);
			}
		}
	}

	private void writeManifest(List<Script> scripts) throws IOException
	{
		ManifestFile manifest = new ManifestFile();
		manifest.schema = SCHEMA;
		manifest.scripts = new ArrayList<>(scripts.size());
		for (Script script : scripts)
		{
			ManifestScript entry = new ManifestScript();
			entry.id = script.id;
			entry.name = script.name;
			entry.description = script.description;
			entry.file = script.file;
			manifest.scripts.add(entry);
		}
		String json = new GsonBuilder().setPrettyPrinting().create().toJson(manifest) + System.lineSeparator();
		writeAtomically(directory.resolve(MANIFEST_FILE), json);
	}

	private Path sourcePath(Script script)
	{
		return directory.resolve(script.file);
	}

	private static State buildState(List<Script> scripts)
	{
		List<Script> immutable = Collections.unmodifiableList(new ArrayList<>(scripts));
		Map<String, Script> byId = new HashMap<>();
		for (Script script : immutable)
		{
			byId.put(script.id, script);
		}
		return new State(immutable, Collections.unmodifiableMap(byId));
	}

	private static void writeAtomically(Path path, String value) throws IOException
	{
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
		Files.writeString(temporary, value, StandardCharsets.UTF_8);
		try
		{
			Files.move(temporary, path,
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException exception)
		{
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void validateId(String id)
	{
		if (id == null || !SCRIPT_ID.matcher(id).matches())
		{
			throw new IllegalArgumentException("Script id must use lowercase letters, numbers, hyphens, or underscores");
		}
	}

	private static String validateFileName(String file)
	{
		String clean = requireText(file, "Script file");
		Path name = Path.of(clean).getFileName();
		if (!name.toString().equals(clean) || !clean.endsWith(".lua"))
		{
			throw new IllegalArgumentException("Script file must be a .lua filename inside the scripts directory");
		}
		return clean;
	}

	private static String requireText(String value, String label)
	{
		if (value == null || value.trim().isEmpty())
		{
			throw new IllegalArgumentException(label + " cannot be empty");
		}
		return value.trim();
	}

	static final class Script
	{
		private final String id;
		private final String name;
		private final String description;
		private final String file;

		private Script(String id, String name, String description, String file)
		{
			this.id = id;
			this.name = name;
			this.description = description;
			this.file = file;
		}

		String getId()
		{
			return id;
		}

		String getName()
		{
			return name;
		}

		String getDescription()
		{
			return description;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("id", id);
			value.put("name", name);
			value.put("description", description);
			value.put("file", file);
			return value;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	private static final class State
	{
		private final List<Script> scripts;
		private final Map<String, Script> byId;

		private State(List<Script> scripts, Map<String, Script> byId)
		{
			this.scripts = scripts;
			this.byId = byId;
		}
	}

	private static final class ManifestFile
	{
		private String schema;
		private List<ManifestScript> scripts;
	}

	private static final class ManifestScript
	{
		private String id;
		private String name;
		private String description;
		private String file;
	}
}
