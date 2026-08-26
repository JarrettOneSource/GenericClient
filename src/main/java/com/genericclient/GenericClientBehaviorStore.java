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

final class GenericClientBehaviorStore
{
	private final Path directory;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	GenericClientBehaviorStore(Path directory) throws IOException
	{
		this.directory = directory;
		Files.createDirectories(directory);
	}

	GenericClientBehaviorState load(String profileId) throws IOException
	{
		Path path = path(profileId);
		if (!Files.isRegularFile(path))
		{
			return null;
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			GenericClientBehaviorState state = gson.fromJson(reader, GenericClientBehaviorState.class);
			if (state == null)
			{
				throw new IOException("Behavior state is empty: " + path);
			}
			state.validate(profileId);
			return state;
		}
		catch (JsonParseException | IllegalArgumentException exception)
		{
			throw new IOException("Invalid behavior state: " + path, exception);
		}
	}

	void save(GenericClientBehaviorState state, long epochMillis) throws IOException
	{
		state.validate(state.getProfileId());
		state.markSaved(epochMillis);
		String json = gson.toJson(state) + System.lineSeparator();
		Path target = path(state.getProfileId());
		Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(temporary, json, StandardCharsets.UTF_8);
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

	private Path path(String profileId)
	{
		if (profileId == null || !profileId.matches("[0-9a-f]{16}"))
		{
			throw new IllegalArgumentException("Behavior profile id must be 16 lowercase hexadecimal characters");
		}
		return directory.resolve("state-" + profileId + ".json");
	}
}
