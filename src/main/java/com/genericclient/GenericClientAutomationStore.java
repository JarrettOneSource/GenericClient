package com.genericclient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

final class GenericClientAutomationStore
{
	private static final String STATE_SCHEMA = "genericclient_automation_state.v1";
	private final Path directory;
	private final String defaultZone;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	GenericClientAutomationStore(Path directory, String defaultZone) throws IOException
	{
		this.directory = directory;
		this.defaultZone = ZoneId.of(defaultZone).getId();
		Files.createDirectories(directory);
	}

	GenericClientAutomationConfig loadConfig(String profileId) throws IOException
	{
		Path path = configPath(profileId);
		if (!Files.isRegularFile(path))
		{
			return GenericClientAutomationConfig.empty(defaultZone);
		}
		try
		{
			return GenericClientAutomationConfig.fromJson(Files.readString(path, StandardCharsets.UTF_8));
		}
		catch (JsonParseException | IllegalArgumentException exception)
		{
			throw new IOException("Invalid automation config: " + path, exception);
		}
	}

	void saveConfig(String profileId, GenericClientAutomationConfig config) throws IOException
	{
		writeAtomically(configPath(profileId), gson.toJson(config) + System.lineSeparator());
	}

	State loadState(String profileId) throws IOException
	{
		Path path = statePath(profileId);
		if (!Files.isRegularFile(path))
		{
			return State.empty();
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			State state = gson.fromJson(reader, State.class);
			if (state == null)
			{
				throw new IOException("Automation state is empty: " + path);
			}
			state.validate();
			return state;
		}
		catch (JsonParseException | IllegalArgumentException exception)
		{
			throw new IOException("Invalid automation state: " + path, exception);
		}
	}

	void saveState(String profileId, State state, long nowEpochMillis) throws IOException
	{
		state.updatedEpochMillis = nowEpochMillis;
		state.validate();
		writeAtomically(statePath(profileId), gson.toJson(state) + System.lineSeparator());
	}

	Path configPath(String profileId)
	{
		validateProfileId(profileId);
		return directory.resolve("rules-" + profileId + ".json");
	}

	Path statePath(String profileId)
	{
		validateProfileId(profileId);
		return directory.resolve("state-" + profileId + ".json");
	}

	private static void writeAtomically(Path target, String value) throws IOException
	{
		Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(temporary, value, StandardCharsets.UTF_8);
		try
		{
			Files.move(temporary, target,
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException exception)
		{
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void validateProfileId(String profileId)
	{
		if (profileId == null || !profileId.matches("[0-9a-f]{16}"))
		{
			throw new IllegalArgumentException(
				"Automation profile id must be 16 lowercase hexadecimal characters");
		}
	}

	static final class State
	{
		private String schema;
		private boolean paused;
		private Map<String, Long> cooldownUntilEpochMillis;
		private String activeRule;
		private long handledRunId;
		private String lastEvent;
		private long updatedEpochMillis;

		private State()
		{
		}

		static State empty()
		{
			State value = new State();
			value.schema = STATE_SCHEMA;
			value.cooldownUntilEpochMillis = new LinkedHashMap<>();
			value.handledRunId = -1L;
			value.lastEvent = "waiting";
			return value;
		}

		private void validate()
		{
			if (!STATE_SCHEMA.equals(schema))
			{
				throw new IllegalArgumentException("Unsupported automation state schema: " + schema);
			}
			if (cooldownUntilEpochMillis == null)
			{
				cooldownUntilEpochMillis = new LinkedHashMap<>();
			}
			else
			{
				Map<String, Long> clean = new LinkedHashMap<>();
				for (Map.Entry<String, Long> entry : cooldownUntilEpochMillis.entrySet())
				{
					if (entry.getKey() == null || !entry.getKey().matches("[a-z0-9][a-z0-9_-]{0,63}") ||
						entry.getValue() == null || entry.getValue() < 0L)
					{
						throw new IllegalArgumentException("Invalid automation cooldown entry");
					}
					clean.put(entry.getKey(), entry.getValue());
				}
				cooldownUntilEpochMillis = clean;
			}
			if (handledRunId < -1L || updatedEpochMillis < 0L)
			{
				throw new IllegalArgumentException("Invalid automation state counters");
			}
			if (activeRule != null && !activeRule.matches("[a-z0-9][a-z0-9_-]{0,63}"))
			{
				throw new IllegalArgumentException("Invalid active automation rule id");
			}
			if (lastEvent == null)
			{
				lastEvent = "waiting";
			}
		}

		boolean isPaused()
		{
			return paused;
		}

		void setPaused(boolean paused)
		{
			this.paused = paused;
		}

		Map<String, Long> getCooldowns()
		{
			return new LinkedHashMap<>(cooldownUntilEpochMillis);
		}

		void setCooldown(String ruleId, long untilEpochMillis)
		{
			cooldownUntilEpochMillis.put(ruleId, untilEpochMillis);
		}

		void clearExpiredCooldowns(long nowEpochMillis)
		{
			cooldownUntilEpochMillis.entrySet().removeIf(entry -> entry.getValue() <= nowEpochMillis);
		}

		String getActiveRule()
		{
			return activeRule;
		}

		void setActiveRule(String activeRule)
		{
			this.activeRule = activeRule;
		}

		long getHandledRunId()
		{
			return handledRunId;
		}

		void setHandledRunId(long handledRunId)
		{
			this.handledRunId = handledRunId;
		}

		String getLastEvent()
		{
			return lastEvent;
		}

		void setLastEvent(String lastEvent)
		{
			this.lastEvent = lastEvent;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("paused", paused);
			value.put("active_rule", activeRule);
			value.put("cooldowns", new LinkedHashMap<>(cooldownUntilEpochMillis));
			value.put("handled_run_id", handledRunId);
			value.put("last_event", lastEvent);
			value.put("updated_epoch_millis", updatedEpochMillis);
			return value;
		}
	}
}
