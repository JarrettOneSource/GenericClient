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
		GenericClientBehaviorState state = new GenericClientBehaviorState(profileId, 0.75);
		state.addActiveMillis(90_000L);
		state.recordPhase("banking.complete");
		state.startBreak("long", "logout", 1_234_567_890L);

		store.save(state, 1_200_000_000L);
		GenericClientBehaviorState loaded = store.load(profileId);

		assertNotNull(loaded);
		assertEquals(state.toMap(), loaded.toMap());
		assertEquals(90_000L, loaded.getTotalActiveMillis());
		assertEquals(90_000L, loaded.getLastGlobalPhaseActiveMillis());
		assertEquals(Long.valueOf(90_000L), loaded.getLastPhaseActiveMillis("banking.complete"));
		assertEquals(1_200_000_000L, loaded.getSavedAtEpochMillis());
		assertFalse(Files.exists(directory.resolve("state-" + profileId + ".json.tmp")));
	}

	@Test
	public void returnsNullWhenAProfileHasNoState() throws Exception
	{
		GenericClientBehaviorStore store = new GenericClientBehaviorStore(
			temporaryFolder.newFolder("missing").toPath());
		String profileId = GenericClientBehaviorProfile.fromAccountHash(99L).getId();

		assertNull(store.load(profileId));
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
		store.save(new GenericClientBehaviorState(first, 1.0), 5L);
		Files.copy(
			directory.resolve("state-" + first + ".json"),
			directory.resolve("state-" + second + ".json"));

		assertInvalid(store, second);
	}

	@Test
	public void longResetKeepsLifetimePhaseClockMonotonic()
	{
		GenericClientBehaviorState state = new GenericClientBehaviorState(
			GenericClientBehaviorProfile.fromAccountHash(3L).getId(), 0.5);
		state.addActiveMillis(10_000L);
		state.recordPhase("first");
		state.resetLongClock(1.5);
		state.addActiveMillis(2_000L);

		assertEquals(12_000L, state.getTotalActiveMillis());
		assertEquals(2_000L, state.getActiveMillisSinceLongBreak());
		assertEquals(Long.valueOf(10_000L), state.getLastPhaseActiveMillis("first"));
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
			8.0,
			0.2,
			180.0,
			25.0,
			3.0,
			GenericClientBehaviorProfile.LongBreakMode.LOGOUT,
			0.15,
			GenericClientBehaviorProfile.Edge.LEFT,
			525,
			65);

		store.saveOverrides(profile.getId(), overrides);
		assertEquals(overrides.toMap(), store.loadOverrides(profile.getId()).toMap());
		assertTrue(Files.isRegularFile(directory.resolve("overrides-" + profile.getId() + ".json")));

		store.deleteOverrides(profile.getId());
		assertNull(store.loadOverrides(profile.getId()));
	}

	private static void assertInvalid(GenericClientBehaviorStore store, String profileId) throws Exception
	{
		try
		{
			store.load(profileId);
			throw new AssertionError("Expected invalid behavior state");
		}
		catch (IOException expected)
		{
			assertTrue(expected.getMessage().contains("Invalid behavior state"));
		}
	}
}
