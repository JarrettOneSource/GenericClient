package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientBehaviorStoreTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void atomicallyPersistsProgressBreaksAndPhaseHistory() throws Exception
	{
		Path directory = temporaryFolder.newFolder("behavior").toPath();
		GenericClientBehaviorStore store = new GenericClientBehaviorStore(directory);
		String profileId = GenericClientBehaviorProfile.fromAccountHash(1234L).getId();
		GenericClientBehaviorState state = new GenericClientBehaviorState(profileId, 0.75, 1.25);
		state.addActiveMillis(90_000L);
		state.addMicroPressure(0.6);
		state.recordPhase("banking.complete");
		state.startBreak("long", "logout", 1_234_500_000L, 1_234_567_890L);

		store.save(state, 1_200_000_000L);
		GenericClientBehaviorState loaded = store.load(profileId, () -> 1.0);

		assertNotNull(loaded);
		assertEquals(state.toMap(), loaded.toMap());
		assertEquals(90_000L, loaded.getTotalActiveMillis());
		assertEquals(0.6, loaded.getMicroPressure(), 0.0);
		assertEquals(1.25, loaded.getMicroBudget(), 0.0);
		assertEquals(90_000L, loaded.getLastGlobalPhaseActiveMillis());
		assertEquals(90_000L, loaded.getLastPhaseActiveMillis("banking.complete").longValue());
		assertEquals(1_200_000_000L, loaded.getSavedAtEpochMillis());
		assertFalse(Files.exists(directory.resolve("state-" + profileId + ".json.tmp")));
	}

	@Test
	public void persistsDeferredLongBreakWithoutSpendingItsBudget() throws Exception
	{
		GenericClientBehaviorStore store = new GenericClientBehaviorStore(temporaryFolder.newFolder("deferred").toPath());
		String id = GenericClientBehaviorProfile.fromAccountHash(11L).getId();
		GenericClientBehaviorState state = new GenericClientBehaviorState(id, 0.5, 1.0);
		state.addActiveMillis(90_000L);
		state.deferLongBreak(600_000L);
		store.save(state, 1_000_000L);
		GenericClientBehaviorState restored = store.load(id, () -> 1.0);
		assertTrue(restored.isLongBreakDeferred());
		assertEquals(690_000L, restored.getLongDeferredUntilActiveMillis());
		assertEquals(90_000L, restored.getActiveMillisSinceLongBreak());
		assertEquals(0.5, restored.getLongHazardBudget(), 0.0);
	}

	@Test
	public void migratesV1ProgressWithoutInventingADeferredBreak() throws Exception
	{
		Path directory = temporaryFolder.newFolder("v1-state").toPath();
		String id = GenericClientBehaviorProfile.fromAccountHash(12L).getId();
		Files.writeString(directory.resolve("state-" + id + ".json"),
			"{\"schema\":\"genericclient_behavior_state.v1\",\"profileId\":\"" + id +
			"\",\"totalActiveMillis\":90000,\"activeMillisSinceLongBreak\":90000,\"longHazardBudget\":0.5}");
		GenericClientBehaviorState restored = new GenericClientBehaviorStore(directory).load(id, () -> 1.0);
		assertEquals(GenericClientBehaviorState.SCHEMA, restored.toMap().get("schema"));
		assertEquals(90_000L, restored.getActiveMillisSinceLongBreak());
		assertFalse(restored.isLongBreakDeferred());
	}

	@Test
	public void returnsNullWhenAProfileHasNoState() throws Exception
	{
		GenericClientBehaviorStore store = new GenericClientBehaviorStore(
			temporaryFolder.newFolder("missing").toPath());
		String profileId = GenericClientBehaviorProfile.fromAccountHash(99L).getId();

		assertNull(store.load(profileId, () -> 1.0));
	}

	@Test
	public void v2MigrationStartsAtZeroAndPersistsTheSampledBudget() throws Exception
	{
		Path directory = temporaryFolder.newFolder("v2-state").toPath();
		String id = GenericClientBehaviorProfile.fromAccountHash(12L).getId();
		Files.writeString(directory.resolve("state-" + id + ".json"),
			"{\"schema\":\"genericclient_behavior_state.v2\",\"profileId\":\"" + id +
			"\",\"totalActiveMillis\":90000,\"activeMillisSinceLongBreak\":90000,\"longHazardBudget\":0.5," +
			"\"longBreakDeferred\":true,\"longDeferredUntilActiveMillis\":690000}");
		GenericClientBehaviorStore store = new GenericClientBehaviorStore(directory);
		GenericClientBehaviorState migrated = store.load(id, () -> 0.125);
		assertEquals(0.0, migrated.getMicroPressure(), 0.0);
		assertEquals(0.125, migrated.getMicroBudget(), 0.0);
		assertEquals(690_000, migrated.getLongDeferredUntilActiveMillis());
		assertTrue(migrated.isLongBreakDeferred());
		store.save(migrated, 1_000_000);
		GenericClientBehaviorState restored = store.load(id, () -> { throw new AssertionError("Stored budget must not be resampled"); });
		assertEquals(migrated.toMap(), restored.toMap());
	}

	@Test
	public void currentSchemaRejectsMissingBudgetAndInvalidPressure() throws Exception
	{
		Path directory = temporaryFolder.newFolder("invalid-micro").toPath();
		String id = GenericClientBehaviorProfile.fromAccountHash(13L).getId();
		GenericClientBehaviorStore store = new GenericClientBehaviorStore(directory);
		for (String fields : new String[] {"", ",\"microBudget\":0", ",\"microBudget\":1,\"microPressure\":-0.1"})
		{
			Files.writeString(directory.resolve("state-" + id + ".json"),
				"{\"schema\":\"genericclient_behavior_state.v3\",\"profileId\":\"" + id +
				"\",\"longHazardBudget\":0.5" + fields + "}");
			assertInvalid(store, id);
		}
	}

	@Test
	public void rejectsMalformedJson() throws Exception
	{
		Path directory = temporaryFolder.newFolder("malformed").toPath();
		GenericClientBehaviorStore store = new GenericClientBehaviorStore(directory);
		String profileId = GenericClientBehaviorProfile.fromAccountHash(100L).getId();
		Files.writeString(directory.resolve("state-" + profileId + ".json"), "{broken", StandardCharsets.UTF_8);

		assertInvalid(store, profileId);
	}

	@Test
	public void rejectsStateCopiedFromAnotherProfile() throws Exception
	{
		Path directory = temporaryFolder.newFolder("mismatch").toPath();
		GenericClientBehaviorStore store = new GenericClientBehaviorStore(directory);
		String first = GenericClientBehaviorProfile.fromAccountHash(1L).getId();
		String second = GenericClientBehaviorProfile.fromAccountHash(2L).getId();
		store.save(new GenericClientBehaviorState(first, 1.0, 1.0), 5L);
		Files.copy(
			directory.resolve("state-" + first + ".json"),
			directory.resolve("state-" + second + ".json"));

		assertInvalid(store, second);
	}

	@Test
	public void longResetKeepsLifetimePhaseClockMonotonic()
	{
		GenericClientBehaviorState state = new GenericClientBehaviorState(
			GenericClientBehaviorProfile.fromAccountHash(3L).getId(), 0.5, 1.0);
		state.addActiveMillis(10_000L);
		state.recordPhase("first");
		state.resetLongClock(1.5);
		state.addActiveMillis(2_000L);

		assertEquals(12_000L, state.getTotalActiveMillis());
		assertEquals(2_000L, state.getActiveMillisSinceLongBreak());
		assertEquals(10_000L, state.getLastPhaseActiveMillis("first").longValue());
		assertEquals(1.5, state.getLongHazardBudget(), 0.0);
	}

	@Test
	public void persistsAndDeletesPerProfileOverrides() throws Exception
	{
		Path directory = temporaryFolder.newFolder("overrides").toPath();
		GenericClientBehaviorStore store = new GenericClientBehaviorStore(directory);
		GenericClientBehaviorProfile profile = GenericClientBehaviorProfile.fromAccountHash(77L);
		GenericClientBehaviorOverrides overrides = new GenericClientBehaviorOverrides(
			0.8,
			0.7,
			8.0,
			0.2,
			180.0,
			25.0,
			3.0,
			GenericClientBehaviorProfile.LongBreakMode.LOGOUT,
			0.15,
			GenericClientBehaviorProfile.Edge.LEFT,
			525,
			65,
			65,
			GenericClientBehaviorProfile.DialogueInputMode.MOUSE);

		store.saveOverrides(profile.getId(), overrides);
		assertEquals(overrides.toMap(), store.loadOverrides(profile.getId()).toMap());
		assertTrue(Files.isRegularFile(directory.resolve("overrides-" + profile.getId() + ".json")));

		store.deleteOverrides(profile.getId());
		assertNull(store.loadOverrides(profile.getId()));
	}

	@Test
	public void loadsLegacyMicroProbabilityAsTheInitialCursorReleaseChance() throws Exception
	{
		Path directory = temporaryFolder.newFolder("legacy-overrides").toPath();
		GenericClientBehaviorStore store = new GenericClientBehaviorStore(directory);
		GenericClientBehaviorProfile profile = GenericClientBehaviorProfile.fromAccountHash(78L);
		String legacy = "{\n" +
			"  \"schema\": \"genericclient_behavior_overrides.v1\",\n" +
			"  \"shortReleaseProbability\": 0.42,\n" +
			"  \"shortBodyMedianSeconds\": 5.0,\n" +
			"  \"shortTailProbability\": 0.03,\n" +
			"  \"longCadenceMinutes\": 120.0,\n" +
			"  \"longMedianMinutes\": 15.0,\n" +
			"  \"phaseShortChances\": 2.0,\n" +
			"  \"favoredLongBreakMode\": \"AFK\",\n" +
			"  \"oppositeLongBreakProbability\": 0.1,\n" +
			"  \"idleEdge\": \"RIGHT\",\n" +
			"  \"mouseMoveDurationMillis\": 550,\n" +
			"  \"typingWordsPerMinute\": 70\n" +
			"}\n";
		Files.writeString(
			directory.resolve("overrides-" + profile.getId() + ".json"),
			legacy,
			StandardCharsets.UTF_8);

		GenericClientBehaviorOverrides loaded = store.loadOverrides(profile.getId());
		assertEquals(0.42, loaded.getMicroBreakProbability(), 0.0);
		assertEquals(0.42, loaded.getCursorReleaseProbability(), 0.0);
		assertNull(loaded.getDialogueReadingPercent());
		assertEquals(
			profile.getDialogueReadingPercent(),
			profile.withOverrides(loaded).getDialogueReadingPercent());
	}

	private static void assertInvalid(GenericClientBehaviorStore store, String profileId) throws Exception
	{
		try
		{
			store.load(profileId, () -> 1.0);
			throw new AssertionError("Expected invalid behavior state");
		}
		catch (IOException expected)
		{
			assertTrue(expected.getMessage().contains("Invalid behavior state"));
		}
	}
}
