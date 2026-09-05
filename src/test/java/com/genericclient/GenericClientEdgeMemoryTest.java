package com.genericclient;

import static org.junit.Assert.*;
import com.genericclient.GenericClientEdgeMemory.Reason;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientEdgeMemoryTest
{
	@Rule public TemporaryFolder folders = new TemporaryFolder();
	private final AtomicLong clock = new AtomicLong(1_000_000);
	private final List<String> reports = new ArrayList<>();
	private final WorldPoint from = new WorldPoint(3202, 3428, 0);
	private final WorldPoint to = new WorldPoint(3203, 3428, 0);

	@Test
	public void persistsTheReasonAndExpiryAcrossRestartsWithoutCrossingAccounts() throws Exception
	{
		Path directory = folders.newFolder().toPath();
		GenericClientEdgeMemory memory = memory(directory, 42);
		memory.observeQuestState("quest:in_progress:2");
		memory.record(door(), Reason.LOCKED, "The door is securely locked.");
		assertTrue(memory.capture().blocks(to, from));
		assertEquals(15_400_000L, memory.capture().entries.get(0).toMap().get("expires_at"));
		assertEquals("The door is securely locked.", memory.capture().entries.get(0).toMap().get("detail"));
		GenericClientEdgeMemory.View currentPlan = memory.capture();
		memory.activateAccount(42);
		assertSame("Repeated account activation must preserve pending plans", currentPlan, memory.capture());

		GenericClientEdgeMemory restarted = memory(directory, 42);
		restarted.observeQuestState("quest:in_progress:2");
		assertEquals(memory.capture().blockedEdges, restarted.capture().blockedEdges);
		restarted.activateAccount(99);
		assertTrue(restarted.capture().entries.isEmpty());
		restarted.record(door(), Reason.CLEARED, null);
		assertTrue(restarted.capture().blockedEdges.isEmpty());
		restarted.activateAccount(42);
		assertEquals(Reason.LOCKED, restarted.capture().entries.get(0).reason);
		assertTrue(reports.isEmpty());
	}

	@Test
	public void expiresDynamicBlocksBeforeLockedDoorsAndKeepsCapturedPlansImmutable() throws Exception
	{
		Path directory = folders.newFolder().toPath();
		GenericClientEdgeMemory memory = memory(directory, 42);
		memory.record(door(), Reason.LOCKED, null);
		GenericClientEdgeMemory.View captured = memory.capture();
		clock.addAndGet(5 * 60_000L);
		assertEquals(1, memory.capture().blockedEdges.size());
		clock.addAndGet(4 * 60 * 60_000L - 5 * 60_000L);
		assertTrue(memory.capture().entries.isEmpty());
		assertEquals(0, new JsonParser().parse(Files.readString(file(directory, 42))).getAsJsonObject().getAsJsonArray("entries").size());
		assertEquals(1, captured.blockedEdges.size());
		assertNotSame(captured, memory.capture());

		for (Reason reason : List.of(Reason.SOLID, Reason.INTERACTION_LIMIT, Reason.INTERACTION_FAILED))
		{
			memory.record(door(), reason, null);
			clock.addAndGet(5 * 60_000L - 1);
			assertEquals(1, memory.capture().blockedEdges.size());
			clock.incrementAndGet();
			assertTrue(memory.capture().entries.isEmpty());
		}
	}

	@Test
	public void aMidquestStageChangeClearsDoorFailuresButUnavailableStateDoesNot() throws Exception
	{
		Path directory = folders.newFolder().toPath();
		GenericClientEdgeMemory memory = memory(directory, 42);
		memory.observeQuestState(questSnapshot(2).questStateKey());
		memory.record(door(), Reason.LOCKED, null);
		memory.observeQuestState(null);
		memory.observeQuestState(questSnapshot(2).questStateKey());
		assertEquals(1, memory.capture().blockedEdges.size());
		WorldPoint next = new WorldPoint(3204, 3428, 0);
		memory.record(GenericClientWalkTestFixtures.solidWallSnapshot(1, to, to, next)
			.findRouteBlock(List.of(to, next), 0, 1), Reason.SOLID, null);
		memory.observeQuestState(questSnapshot(3).questStateKey());
		assertFalse(memory.capture().blocks(from, to));
		assertTrue(memory.capture().blocks(to, next));
		assertEquals(Reason.SOLID, memory(directory, 42).capture().entries.get(0).reason);
		assertEquals(List.of("WALK_EDGE_MEMORY_QUEST_CHANGED cleared=1"), reports);

		memory.observeQuestState("quest:finished:10");
		assertEquals(1, memory.capture().blockedEdges.size());
	}

	@Test
	public void aClearedObstacleReplacesTheBlockAndRetainsItsOutcomeUntilExpiry() throws Exception
	{
		Path directory = folders.newFolder().toPath();
		GenericClientEdgeMemory memory = memory(directory, 42);
		memory.record(door(), Reason.INTERACTION_FAILED, "not_found");
		memory.record(door(), Reason.CLEARED, null);
		assertTrue(memory.capture().blockedEdges.isEmpty());
		assertTrue(memory.capture().blockedReceipts().isEmpty());
		assertEquals("cleared", memory.capture().entries.get(0).toMap().get("status"));
		memory.observeQuestState("quest:finished:10");
		assertEquals(Reason.CLEARED, memory(directory, 42).capture().entries.get(0).reason);
		clock.addAndGet(24 * 60 * 60_000L);
		assertTrue(memory.capture().entries.isEmpty());
	}

	@Test
	public void invalidNewAccountDataCannotRetainOldKnowledgeOrPreventRepair() throws Exception
	{
		Path directory = folders.newFolder().toPath();
		GenericClientEdgeMemory memory = memory(directory, 42);
		memory.record(door(), Reason.LOCKED, null);
		Files.writeString(file(directory, 99), "{");
		assertThrows(IOException.class, () -> memory.activateAccount(99));
		assertFalse(memory.isAvailable());
		assertTrue(memory.capture().entries.isEmpty());
		assertThrows(IllegalStateException.class, () -> memory.record(door(), Reason.SOLID, null));
		Files.delete(file(directory, 99));
		memory.activateAccount(99);
		assertTrue(memory.isAvailable());
		memory.activateAccount(42);
		assertEquals(Reason.LOCKED, memory.capture().entries.get(0).reason);
		memory.clearAccount();
		memory.observeQuestState("quest:finished:10");
		assertFalse(memory.isAvailable());
	}

	@Test
	public void rejectsInvalidHeadersAndObservationsFromDisk() throws Exception
	{
		Path directory = folders.newFolder().toPath();
		GenericClientEdgeMemory memory = memory(directory, 42);
		memory.record(door(), Reason.LOCKED, null);
		String saved = Files.readString(file(directory, 42));
		for (Consumer<JsonObject> corrupt : List.<Consumer<JsonObject>>of(
			state -> state.remove("schema"),
			state -> state.addProperty("profileId", "other"),
			state -> state.remove("entries"),
			state -> state.getAsJsonArray("entries").add(state.getAsJsonArray("entries").get(0).deepCopy()),
			state -> state.getAsJsonArray("entries").add(com.google.gson.JsonNull.INSTANCE),
			state -> entry(state).addProperty("reason", "UNRECOGNIZED"),
			state -> entry(state).addProperty("learnedAt", -1),
			state -> entry(state).addProperty("expiresAt", 0),
			state -> entry(state).addProperty("learnedAt", Long.MAX_VALUE),
			state -> entry(state).addProperty("objectId", -2),
			state -> entry(state).remove("from"),
			state -> entry(state).remove("to"),
			state -> entry(state).getAsJsonObject("from").addProperty("x", -1),
			state -> entry(state).getAsJsonObject("from").addProperty("x", 32768),
			state -> entry(state).getAsJsonObject("from").addProperty("y", -1),
			state -> entry(state).getAsJsonObject("from").addProperty("y", 32768),
			state -> entry(state).getAsJsonObject("from").addProperty("plane", -1),
			state -> entry(state).getAsJsonObject("from").addProperty("plane", 4),
			state -> entry(state).getAsJsonObject("to").addProperty("x", 3206),
			state -> entry(state).getAsJsonObject("to").addProperty("plane", 2)))
		{
			JsonObject state = new JsonParser().parse(saved).getAsJsonObject();
			corrupt.accept(state);
			Files.writeString(file(directory, 42), state.toString());
			memory.clearAccount();
			assertThrows(IOException.class, () -> memory.activateAccount(42));
			assertFalse(memory.isAvailable());
		}
		Files.writeString(file(directory, 42), "null");
		assertThrows(IOException.class, () -> memory.activateAccount(42));
	}

	@Test
	public void reloadsEdgesAtWorldAndTimestampBoundariesWithOptionalObjectMetadata() throws Exception
	{
		Path directory = folders.newFolder().toPath();
		clock.set(0);
		GenericClientEdgeMemory memory = memory(directory, 42);
		memory.record(door(), Reason.LOCKED, null);
		String saved = Files.readString(file(directory, 42));
		List<WorldPoint[]> edges = List.of(
			new WorldPoint[]{new WorldPoint(0, 0, 0), new WorldPoint(1, 0, 0)},
			new WorldPoint[]{new WorldPoint(32767, 32767, 3), new WorldPoint(32766, 32767, 3)});
		for (WorldPoint[] edge : edges)
		{
			JsonObject state = new JsonParser().parse(saved).getAsJsonObject();
			com.google.gson.Gson gson = new com.google.gson.Gson();
			entry(state).add("from", gson.toJsonTree(edge[0]));
			entry(state).add("to", gson.toJsonTree(edge[1]));
			entry(state).remove("action");
			entry(state).addProperty("objectId", 0);
			Files.writeString(file(directory, 42), state.toString());
			memory.clearAccount();
			memory.activateAccount(42);
			assertTrue(memory.capture().blocks(edge[1], edge[0]));
			assertFalse(memory.capture().blocks(from, to));
			java.util.Map<String, Object> receipt = memory.capture().entries.get(0).toMap();
			assertEquals(0L, receipt.get("learned_at"));
			assertEquals(0, receipt.get("object_id"));
			assertFalse(receipt.containsKey("action"));
		}
	}

	@Test
	public void reportsFailedPersistenceWhileKeepingTheObservationForThisProcess() throws Exception
	{
		Path directory = folders.newFolder().toPath();
		GenericClientEdgeMemory memory = memory(directory, 42);
		Files.createDirectory(file(directory, 42));
		memory.record(door(), Reason.LOCKED, null);
		assertEquals(1, memory.capture().blockedEdges.size());
		assertTrue(reports.get(0).startsWith("WALK_EDGE_MEMORY_SAVE_FAILED"));
	}

	private GenericClientEdgeMemory memory(Path directory, long account) throws IOException
	{
		GenericClientEdgeMemory memory = new GenericClientEdgeMemory(directory, clock::get, reports::add);
		memory.activateAccount(account);
		return memory;
	}

	private GenericClientSnapshot.RouteBlock door()
	{
		return GenericClientWalkTestFixtures.doorSnapshot(1, from, from, to, true)
			.findRouteBlock(List.of(from, to), 0, 1);
	}

	private static Path file(Path directory, long account)
	{
		return directory.resolve("edges-" + GenericClientBehaviorProfile.fromAccountHash(account).getId() + ".json");
	}

	private static JsonObject entry(JsonObject state)
	{
		return state.getAsJsonArray("entries").get(0).getAsJsonObject();
	}

	private static GenericClientSnapshot questSnapshot(int progress)
	{
		GenericClientAccountSnapshot account = new GenericClientAccountSnapshot(true, 0, List.of(),
			GenericClientAccountSnapshot.ContainerSnapshot.unavailable(),
			GenericClientAccountSnapshot.ContainerSnapshot.unavailable(),
			GenericClientAccountSnapshot.BankSnapshot.unknown(),
			new GenericClientAccountSnapshot.QuestListSnapshot(true, 1, List.of(
				new GenericClientAccountSnapshot.QuestSnapshot("quest", 1, "Quest", "in_progress", progress))));
		return new GenericClientSnapshot(1, "LOGGED_IN", 240, null, List.of(), account, GenericClientQuestSnapshot.empty());
	}
}
