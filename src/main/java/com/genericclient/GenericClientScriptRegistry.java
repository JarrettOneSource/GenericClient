package com.genericclient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
	private static final String SCHEMA = "genericclient_scripts";
	private static final String MANIFEST_FILE = "manifest.json";
	private static final String RESOURCE_DIRECTORY = "/com/genericclient/scripts/";
	private static final Pattern SCRIPT_ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");
	private static final Pattern MODULE_FILE =
		Pattern.compile("[a-z0-9][a-z0-9_/-]*\\.lua");
	private static final String[] BUNDLED_SCRIPT_FILES =
	{
		"account-auditor.lua",
		"aio-melee.lua",
		"aio-magic.lua",
		"aio-magic/config.lua",
		"aio-magic/preparation.lua",
		"aio-magic/progress.lua",
		"aio-magic/supplies.lua",
		"aio-magic/training.lua",
		"capt-arnav.lua",
		"quest-runner.lua",
		"quest-runner/shared/preparation.lua",
		"quest-runner/shared/state.lua",
		"quest-runner/shared/travel.lua",
		"quest-runner/witchs_house/combat.lua",
		"quest-runner/witchs_house/completion.lua",
		"quest-runner/witchs_house/config.lua",
		"quest-runner/witchs_house/experiment.lua",
		"quest-runner/witchs_house/garden.lua",
		"quest-runner/witchs_house/quest.lua",
		"quest-runner/witchs_house/state.lua",
		"quest-runner/waterfall/config.lua",
		"quest-runner/waterfall/navigation.lua",
		"quest-runner/waterfall/preparation.lua",
		"quest-runner/waterfall/quest.lua",
		"quest-runner/waterfall/ritual.lua",
		"quest-runner/waterfall/tomb.lua",
		"quest-runner/waterfall/state.lua",
		"walker.lua",
		"walk-stress.lua"
	};
	private static final String[] REMOVED_BUNDLED_SCRIPT_FILES =
	{
		"npc-diagnostics.lua",
		"quest-runner/combat.lua",
		"quest-runner/completion.lua",
		"quest-runner/config.lua",
		"quest-runner/experiment.lua",
		"quest-runner/garden.lua",
		"quest-runner/preparation.lua",
		"quest-runner/state.lua",
		"quest-runner/travel.lua",
		"quest-runner/witch.lua"
	};
	private static final Set<String> BUNDLED_IDS = Set.of(
		"account-auditor",
		"aio-magic",
		"aio-melee",
		"capt-arnav",
		"lumbridge-varrock",
		"npc-diagnostics",
		"quest-runner",
		"walk-stress",
		"walker");

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

	Script findRandomEventSolver(int npcId)
	{
		return state.randomEventSolvers.get(npcId);
	}

	String readSource(String id) throws IOException
	{
		return Files.readString(sourcePath(get(id)), StandardCharsets.UTF_8);
	}

	String readExecutableSource(String id) throws IOException
	{
		Script script = get(id);
		if (script.modules.isEmpty())
		{
			return readSource(id);
		}
		StringBuilder source = new StringBuilder(modulePrelude());
		for (Map.Entry<String, String> module : script.modules.entrySet())
		{
			source.append("__gc_module_loaders[\"")
				.append(module.getKey())
				.append("\"] = function()\n")
				.append(Files.readString(directory.resolve(module.getValue()), StandardCharsets.UTF_8))
				.append("\nend\n");
		}
		source.append(readSource(id));
		return source.toString();
	}

	Map<String, String> readModuleSources(String id) throws IOException
	{
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<String, String> module : get(id).modules.entrySet())
		{
			result.put(module.getKey(),
				Files.readString(directory.resolve(module.getValue()), StandardCharsets.UTF_8));
		}
		return Collections.unmodifiableMap(result);
	}

	synchronized Script save(String id, String name, String description, String source) throws IOException
	{
		return save(id, name, description, source, Collections.emptyList());
	}

	synchronized Script save(
		String id,
		String name,
		String description,
		String source,
		List<Integer> randomEvents) throws IOException
	{
		validateId(id);
		String cleanName = requireText(name, "Script name");
		String cleanDescription = requireText(description, "Script description");
		if (source == null || source.trim().isEmpty())
		{
			throw new IllegalArgumentException("Script source cannot be empty");
		}

		String file = id + ".lua";
		Script saved = new Script(
			id,
			cleanName,
			cleanDescription,
			file,
			Collections.emptyMap(),
			validateRandomEvents(randomEvents));
		for (int npcId : saved.randomEvents)
		{
			Script existing = state.randomEventSolvers.get(npcId);
			if (existing != null && !existing.id.equals(id))
			{
				throw new IllegalArgumentException(
					"Random-event NPC " + npcId + " is already handled by script " + existing.id);
			}
		}
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
		Path path = directory.resolve(MANIFEST_FILE);
		ManifestFile manifest = readManifest(path);

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
		Map<Integer, String> randomEventOwners = new HashMap<>();
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
			Map<String, String> modules = validateModules(entry.modules);
			List<Integer> randomEvents = validateRandomEvents(entry.randomEvents);
			for (int npcId : randomEvents)
			{
				String existing = randomEventOwners.putIfAbsent(npcId, entry.id);
				if (existing != null)
				{
					throw new IOException("Random-event NPC " + npcId +
						" is already handled by script " + existing);
				}
			}
			Script script = new Script(entry.id, name, description, file, modules, randomEvents);
			if (!Files.isRegularFile(sourcePath(script)))
			{
				throw new IOException("Script file does not exist: " + file);
			}
			scripts.add(script);
			for (String moduleFile : modules.values())
			{
				if (!Files.isRegularFile(directory.resolve(moduleFile)))
				{
					throw new IOException("Script module does not exist: " + moduleFile);
				}
			}
		}

		scripts.sort(Comparator.comparing(Script::getName, String.CASE_INSENSITIVE_ORDER));
		state = buildState(scripts);
		revision++;
	}

	private void installBundledFiles() throws IOException
	{
		Files.createDirectories(directory);
		for (String file : REMOVED_BUNDLED_SCRIPT_FILES)
		{
			Files.deleteIfExists(directory.resolve(file));
		}
		Path manifestPath = directory.resolve(MANIFEST_FILE);
		if (!Files.exists(manifestPath))
		{
			copyBundledResource(MANIFEST_FILE, false);
			for (String file : BUNDLED_SCRIPT_FILES)
			{
				if (!Files.exists(directory.resolve(file)))
				{
					copyBundledResource(file, false);
				}
			}
			return;
		}

		refreshBundledManifest(readManifest(manifestPath));
	}

	private void refreshBundledManifest(ManifestFile existing) throws IOException
	{
		if (existing.scripts == null)
		{
			throw new IOException("Script manifest has no scripts array: " + directory.resolve(MANIFEST_FILE));
		}

		ManifestFile bundled = readBundledManifest();
		List<Script> scripts = new ArrayList<>();
		for (ManifestScript entry : bundled.scripts)
		{
			scripts.add(toScript(entry));
		}
		for (ManifestScript entry : existing.scripts)
		{
			if (entry != null && !BUNDLED_IDS.contains(entry.id))
			{
				scripts.add(toScript(entry));
			}
		}
		for (String file : BUNDLED_SCRIPT_FILES)
		{
			copyBundledResource(file, true);
		}
		scripts.sort(Comparator.comparing(Script::getName, String.CASE_INSENSITIVE_ORDER));
		writeManifest(scripts);
	}

	private Script toScript(ManifestScript entry) throws IOException
	{
		if (entry == null)
		{
			throw new IOException("Script manifest contains a null entry");
		}
		validateId(entry.id);
		return new Script(
			entry.id,
			requireText(entry.name, "Script name"),
			requireText(entry.description, "Script description"),
			validateFileName(entry.file),
			validateModules(entry.modules),
			validateRandomEvents(entry.randomEvents));
	}

	private static ManifestFile readManifest(Path path) throws IOException
	{
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			ManifestFile manifest = new Gson().fromJson(reader, ManifestFile.class);
			if (manifest == null)
			{
				throw new IOException("Script manifest is empty: " + path);
			}
			return manifest;
		}
		catch (JsonParseException exception)
		{
			throw new IOException("Invalid script manifest JSON: " + path, exception);
		}
	}

	private static ManifestFile readBundledManifest() throws IOException
	{
		try (InputStream input = GenericClientScriptRegistry.class.getResourceAsStream(
			RESOURCE_DIRECTORY + MANIFEST_FILE))
		{
			if (input == null)
			{
				throw new IOException("Missing bundled script resource: " + MANIFEST_FILE);
			}
			ManifestFile manifest = new Gson().fromJson(
				new InputStreamReader(input, StandardCharsets.UTF_8),
				ManifestFile.class);
			if (manifest == null || manifest.scripts == null)
			{
				throw new IOException("Bundled script manifest is empty");
			}
			return manifest;
		}
		catch (JsonParseException exception)
		{
			throw new IOException("Invalid bundled script manifest", exception);
		}
	}

	private void copyBundledResource(String file, boolean replace) throws IOException
	{
		try (InputStream input = GenericClientScriptRegistry.class.getResourceAsStream(
			RESOURCE_DIRECTORY + file))
		{
			if (input == null)
			{
				throw new IOException("Missing bundled script resource: " + file);
			}
			Path target = directory.resolve(file);
			Files.createDirectories(target.getParent());
			if (replace)
			{
				Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
			}
			else
			{
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
			entry.modules = script.modules.isEmpty()
				? null
				: new LinkedHashMap<>(script.modules);
			entry.randomEvents = script.randomEvents.isEmpty()
				? null
				: new ArrayList<>(script.randomEvents);
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
		Map<Integer, Script> randomEventSolvers = new HashMap<>();
		for (Script script : immutable)
		{
			byId.put(script.id, script);
			for (int npcId : script.randomEvents)
			{
				randomEventSolvers.put(npcId, script);
			}
		}
		return new State(
			immutable,
			Collections.unmodifiableMap(byId),
			Collections.unmodifiableMap(randomEventSolvers));
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

	private static Map<String, String> validateModules(Map<String, String> raw)
	{
		if (raw == null || raw.isEmpty())
		{
			return Collections.emptyMap();
		}
		Map<String, String> modules = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : raw.entrySet())
		{
			validateId(entry.getKey());
			String file = requireText(entry.getValue(), "Module file");
			if (!MODULE_FILE.matcher(file).matches() || file.contains("//") || file.contains(".."))
			{
				throw new IllegalArgumentException(
					"Module file must be a relative .lua path inside the scripts directory");
			}
			modules.put(entry.getKey(), file);
		}
		return Collections.unmodifiableMap(modules);
	}

	private static List<Integer> validateRandomEvents(List<Integer> raw)
	{
		if (raw == null || raw.isEmpty())
		{
			return Collections.emptyList();
		}
		List<Integer> randomEvents = new ArrayList<>(raw.size());
		Set<Integer> unique = new HashSet<>();
		for (Integer npcId : raw)
		{
			if (npcId == null || !GenericClientRandomEventController.isRandomEventNpcId(npcId))
			{
				throw new IllegalArgumentException(
					"NPC id " + npcId + " is not a supported random-event NPC");
			}
			if (!unique.add(npcId))
			{
				throw new IllegalArgumentException("Duplicate random-event NPC id: " + npcId);
			}
			randomEvents.add(npcId);
		}
		return Collections.unmodifiableList(randomEvents);
	}

	private static String modulePrelude()
	{
		return "local __gc_module_loaders = {}\n" +
			"local __gc_module_cache = {}\n" +
			"local __gc_module_loading = {}\n" +
			"gc.require = function(name)\n" +
			"  local cached = __gc_module_cache[name]\n" +
			"  if cached ~= nil then return cached end\n" +
			"  local loader = __gc_module_loaders[name]\n" +
			"  if not loader then error('Unknown script module: ' .. tostring(name), 2) end\n" +
			"  if __gc_module_loading[name] then error('Circular script module: ' .. name, 2) end\n" +
			"  __gc_module_loading[name] = true\n" +
			"  local value = loader()\n" +
			"  __gc_module_loading[name] = nil\n" +
			"  if value == nil then error('Script module returned nil: ' .. name, 2) end\n" +
			"  __gc_module_cache[name] = value\n" +
			"  return value\n" +
			"end\n";
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
		private final Map<String, String> modules;
		private final List<Integer> randomEvents;

		private Script(
			String id,
			String name,
			String description,
			String file,
			Map<String, String> modules,
			List<Integer> randomEvents)
		{
			this.id = id;
			this.name = name;
			this.description = description;
			this.file = file;
			this.modules = Collections.unmodifiableMap(new LinkedHashMap<>(modules));
			this.randomEvents = Collections.unmodifiableList(new ArrayList<>(randomEvents));
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

		List<Integer> getRandomEvents()
		{
			return randomEvents;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("id", id);
			value.put("name", name);
			value.put("description", description);
			value.put("file", file);
			if (!modules.isEmpty())
			{
				value.put("modules", new LinkedHashMap<>(modules));
			}
			if (!randomEvents.isEmpty())
			{
				value.put("random_events", new ArrayList<>(randomEvents));
			}
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
		private final Map<Integer, Script> randomEventSolvers;

		private State(
			List<Script> scripts,
			Map<String, Script> byId,
			Map<Integer, Script> randomEventSolvers)
		{
			this.scripts = scripts;
			this.byId = byId;
			this.randomEventSolvers = randomEventSolvers;
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
		private Map<String, String> modules;
		@SerializedName("random_events")
		private List<Integer> randomEvents;
	}
}
