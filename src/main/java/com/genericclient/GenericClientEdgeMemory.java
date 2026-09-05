package com.genericclient;

import static com.genericclient.GenericClientWalkJourney.worldMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.runelite.api.coords.WorldPoint;

/** Account-scoped observations. Successful door use never overrides collision data. */
final class GenericClientEdgeMemory
{
	private static final String SCHEMA = "genericclient-edge-memory-v1";
	private final Path directory;
	private final LongSupplier clock;
	private final Consumer<String> reporter;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final Map<Set<WorldPoint>, Entry> entries = new LinkedHashMap<>();
	private String profileId;
	private String questState;
	private View view;

	GenericClientEdgeMemory(Path directory, LongSupplier clock, Consumer<String> reporter) throws IOException
	{
		this.directory = directory;
		this.clock = clock;
		this.reporter = reporter;
		Files.createDirectories(directory);
	}

	synchronized void activateAccount(long accountHash) throws IOException
	{
		String id = GenericClientBehaviorProfile.fromAccountHash(accountHash).getId();
		if (id.equals(profileId)) return;
		clearAccount();
		State loaded = load(id);
		for (Entry entry : loaded.entries) entries.put(entry.key(), entry);
		questState = loaded.questState;
		profileId = id;
	}

	synchronized void clearAccount()
	{
		profileId = null;
		questState = null;
		entries.clear();
		view = null;
	}

	synchronized boolean isAvailable()
	{
		return profileId != null;
	}

	synchronized void observeQuestState(String current)
	{
		if (profileId == null || current == null || current.equals(questState)) return;
		int previousSize = entries.size();
		entries.values().removeIf(entry -> entry.reason.questSensitive);
		questState = current;
		view = null;
		save();
		if (entries.size() != previousSize)
			reporter.accept("WALK_EDGE_MEMORY_QUEST_CHANGED cleared=" + (previousSize - entries.size()));
	}

	synchronized View capture()
	{
		prune();
		if (view == null) view = new View(profileId, List.copyOf(entries.values()));
		return view;
	}

	synchronized Entry record(GenericClientSnapshot.RouteBlock block, Reason reason, String detail)
	{
		if (profileId == null) throw new IllegalStateException("Navigation account is unavailable");
		Entry entry = new Entry(block, reason, detail, clock.getAsLong());
		entries.put(entry.key(), entry);
		view = null;
		save();
		return entry;
	}

	private State load(String id) throws IOException
	{
		Path file = path(id);
		if (!Files.exists(file)) return new State(id, null, List.of());
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			State loaded = gson.fromJson(reader, State.class);
			if (loaded == null || !SCHEMA.equals(loaded.schema) || !id.equals(loaded.profileId) || loaded.entries == null)
				throw new IllegalArgumentException("Invalid navigation memory header");
			Set<Set<WorldPoint>> seen = new LinkedHashSet<>();
			for (Entry entry : loaded.entries)
			{
				if (entry == null) throw new IllegalArgumentException("Missing navigation observation");
				entry.validate();
				if (!seen.add(entry.key())) throw new IllegalArgumentException("Duplicate navigation edge");
			}
			return loaded;
		}
		catch (JsonParseException | IllegalArgumentException | ArithmeticException exception)
		{
			throw new IOException("Invalid navigation memory: " + file, exception);
		}
	}

	private void prune()
	{
		long now = clock.getAsLong();
		if (entries.values().removeIf(entry -> entry.expiresAt <= now))
		{
			view = null;
			save();
		}
	}

	private void save()
	{
		try
		{
			GenericClientAtomicFile.write(path(profileId),
				gson.toJson(new State(profileId, questState, new ArrayList<>(entries.values()))) + System.lineSeparator());
		}
		catch (IOException exception)
		{
			reporter.accept("WALK_EDGE_MEMORY_SAVE_FAILED message=" + exception.getMessage());
		}
	}

	private Path path(String id)
	{
		return directory.resolve("edges-" + id + ".json");
	}

	enum Reason
	{
		SOLID(5 * 60_000L, false),
		LOCKED(4 * 60 * 60_000L, true),
		INTERACTION_LIMIT(5 * 60_000L, true),
		INTERACTION_FAILED(5 * 60_000L, true),
		CLEARED(24 * 60 * 60_000L, false);

		final long lifetimeMillis;
		final boolean questSensitive;

		Reason(long lifetimeMillis, boolean questSensitive)
		{
			this.lifetimeMillis = lifetimeMillis;
			this.questSensitive = questSensitive;
		}
	}

	static final class View
	{
		final String profileId;
		final List<Entry> entries;
		final Set<Set<WorldPoint>> blockedEdges;

		private View(String profileId, List<Entry> entries)
		{
			this.profileId = profileId;
			this.entries = entries;
			Set<Set<WorldPoint>> blocked = new LinkedHashSet<>();
			for (Entry entry : entries)
				if (entry.reason != Reason.CLEARED) blocked.add(entry.key());
			blockedEdges = Set.copyOf(blocked);
		}

		boolean blocks(WorldPoint from, WorldPoint to)
		{
			return blockedEdges.contains(Set.of(from, to));
		}

		List<Map<String, Object>> blockedReceipts()
		{
			List<Map<String, Object>> result = new ArrayList<>();
			for (Entry entry : entries)
				if (entry.reason != Reason.CLEARED) result.add(entry.edgeMap());
			return result;
		}

	}

	static final class Entry
	{
		private final WorldPoint from;
		private final WorldPoint to;
		final Reason reason;
		private final String detail;
		private final int objectId;
		private final String action;
		private final long learnedAt;
		private final long expiresAt;

		private Entry(GenericClientSnapshot.RouteBlock block, Reason reason, String detail, long now)
		{
			from = block.getFrom();
			to = block.getTo();
			this.reason = reason;
			this.detail = detail;
			objectId = block.getObjectId();
			action = block.getAction();
			learnedAt = now;
			expiresAt = now + reason.lifetimeMillis;
		}

		private void validate()
		{
			if (reason == null || learnedAt < 0 || objectId < -1 ||
				expiresAt != Math.addExact(learnedAt, reason.lifetimeMillis))
				throw new IllegalArgumentException("Invalid navigation observation");
			if (!validPoint(from) || !validPoint(to) || GenericClientWalkJourney.distance(from, to) != 1)
				throw new IllegalArgumentException("Navigation edge must connect neighboring tiles on one plane");
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = edgeMap();
			value.put("status", reason == Reason.CLEARED ? "cleared" : "blocked");
			value.put("reason", reason.name().toLowerCase(Locale.ROOT));
			if (detail != null) value.put("detail", detail);
			if (objectId >= 0) value.put("object_id", objectId);
			if (action != null) value.put("action", action);
			value.put("learned_at", learnedAt);
			value.put("expires_at", expiresAt);
			value.put("quest_sensitive", reason.questSensitive);
			return value;
		}

		Set<WorldPoint> key()
		{
			return Set.of(from, to);
		}

		private Map<String, Object> edgeMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("from", worldMap(from));
			value.put("to", worldMap(to));
			return value;
		}

		private static boolean validPoint(WorldPoint point)
		{
			return point != null && point.getPlane() >= 0 && point.getPlane() <= 3 &&
				point.getX() >= 0 && point.getX() <= 32767 && point.getY() >= 0 && point.getY() <= 32767;
		}

	}

	private static final class State
	{
		private final String schema;
		private final String profileId;
		private final String questState;
		private final List<Entry> entries;

		private State(String profileId, String questState, List<Entry> entries)
		{
			this.schema = SCHEMA;
			this.profileId = profileId;
			this.questState = questState;
			this.entries = entries;
		}
	}

}
