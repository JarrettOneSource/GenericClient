package com.genericclient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class GenericClientScriptCheckpointStore
{
	private static final String SCHEMA = "genericclient_script_checkpoints.v1";
	private final Path directory;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	GenericClientScriptCheckpointStore(Path directory) throws IOException
	{
		this.directory = directory;
		Files.createDirectories(directory);
	}

	static boolean supports(String type)
	{
		return "checkpoint.get".equals(type) ||
			"checkpoint.set".equals(type) ||
			"checkpoint.clear".equals(type);
	}

	Map<String, Object> execute(
		String account,
		String script,
		String type,
		Map<String, Object> action) throws IOException
	{
		Object rawKey = action.get("key");
		if (!(rawKey instanceof String))
		{
			throw new IllegalArgumentException(type + " requires a key string");
		}
		String key = (String) rawKey;
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "complete");
		receipt.put("key", key);
		if ("checkpoint.get".equals(type))
		{
			Long stored = get(account, script, key);
			receipt.put("result", "checkpoint_loaded");
			receipt.put("present", stored != null);
			if (stored != null)
			{
				receipt.put("value", stored);
			}
		}
		else if ("checkpoint.set".equals(type))
		{
			long value = checkpointValue(action);
			set(account, script, key, value);
			receipt.put("result", "checkpoint_saved");
			receipt.put("value", value);
		}
		else
		{
			receipt.put("result", "checkpoint_cleared");
			receipt.put("cleared", clear(account, script, key));
		}
		return receipt;
	}

	synchronized Long get(String account, String script, String key) throws IOException
	{
		return load(account, script).values.get(validateKey(key));
	}

	synchronized void set(String account, String script, String key, long value) throws IOException
	{
		if (value < 0)
		{
			throw new IllegalArgumentException("Checkpoint value must be non-negative");
		}
		State state = load(account, script);
		state.values.put(validateKey(key), value);
		write(path(account, script), state);
	}

	synchronized boolean clear(String account, String script, String key) throws IOException
	{
		State state = load(account, script);
		if (state.values.remove(validateKey(key)) == null)
		{
			return false;
		}
		Path path = path(account, script);
		if (state.values.isEmpty())
		{
			Files.deleteIfExists(path);
		}
		else
		{
			write(path, state);
		}
		return true;
	}

	private State load(String account, String script) throws IOException
	{
		String normalizedAccount = normalizeAccount(account);
		validateScript(script);
		Path path = path(normalizedAccount, script);
		if (!Files.isRegularFile(path))
		{
			return State.empty(normalizedAccount, script);
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			State state = gson.fromJson(reader, State.class);
			if (state == null)
			{
				throw new IOException("Checkpoint state is empty: " + path);
			}
			state.validate(normalizedAccount, script);
			return state;
		}
		catch (JsonParseException | IllegalArgumentException exception)
		{
			throw new IOException("Invalid checkpoint state: " + path, exception);
		}
	}

	private void write(Path target, State state) throws IOException
	{
		GenericClientAtomicFile.write(target, gson.toJson(state) + System.lineSeparator());
	}

	private Path path(String account, String script)
	{
		String normalizedAccount = normalizeAccount(account);
		validateScript(script);
		UUID accountId = UUID.nameUUIDFromBytes(
			normalizedAccount.getBytes(StandardCharsets.UTF_8));
		return directory.resolve(accountId + "-" + script + ".json");
	}

	private static String normalizeAccount(String account)
	{
		if (account == null || account.trim().isEmpty())
		{
			throw new IllegalArgumentException("Checkpoint account is required");
		}
		return account.trim().toLowerCase(Locale.ROOT);
	}

	private static void validateScript(String script)
	{
		if (script == null || !script.matches("[A-Za-z0-9_$][A-Za-z0-9_.$-]*"))
		{
			throw new IllegalArgumentException("Checkpoint script id is invalid");
		}
	}

	private static String validateKey(String key)
	{
		if (key == null || !key.matches("[a-z0-9][a-z0-9._-]{0,63}"))
		{
			throw new IllegalArgumentException("Checkpoint key is invalid");
		}
		return key;
	}

	private static long checkpointValue(Map<String, Object> action)
	{
		Object raw = action.get("value");
		if (!(raw instanceof Number))
		{
			throw new IllegalArgumentException("checkpoint.set requires an integer value");
		}
		double number = ((Number) raw).doubleValue();
		long value = ((Number) raw).longValue();
		if (number != value || value < 0)
		{
			throw new IllegalArgumentException(
				"checkpoint.set requires a non-negative integer value");
		}
		return value;
	}


	private static final class State
	{
		private String schema;
		private String account;
		private String script;
		private Map<String, Long> values;

		private static State empty(String account, String script)
		{
			State state = new State();
			state.schema = SCHEMA;
			state.account = account;
			state.script = script;
			state.values = new LinkedHashMap<>();
			return state;
		}

		private void validate(String expectedAccount, String expectedScript)
		{
			if (!SCHEMA.equals(schema) || !expectedAccount.equals(account) ||
				!expectedScript.equals(script) || values == null)
			{
				throw new IllegalArgumentException("Checkpoint identity does not match its file");
			}
			for (Map.Entry<String, Long> entry : values.entrySet())
			{
				validateKey(entry.getKey());
				if (entry.getValue() == null || entry.getValue() < 0)
				{
					throw new IllegalArgumentException("Checkpoint value is invalid");
				}
			}
		}
	}
}
