package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientTransportConversationTest
{
	private static final WorldPoint HANGAR = new WorldPoint(2649, 4518, 0);
	private static final WorldPoint CRASH_ISLAND = new WorldPoint(2894, 2726, 0);

	@Test
	public void handlesOnlyTheSelectedFlightConversationAndWaitsForClosedDialogueAfterLanding() throws Exception
	{
		List<GenericClientTransport.Step> inputs = new ArrayList<>();
		try (GenericClientWalker walker = walker(inputs))
		{
			CompletableFuture<Map<String, Object>> completion = begin(walker, inputs, true);
			walker.publishGameTick(frame(2, HANGAR, dialogue("Waydar", "Are you ready to leave?")));
			assertEquals(2, inputs.size());
			assertFalse(completion.isDone());
			walker.publishGameTick(frame(3, HANGAR, GenericClientQuestSnapshot.DialogueSnapshot.choice(List.of("Yes.", "No."))));
			assertEquals(3, inputs.size());
			walker.publishGameTick(frame(4, CRASH_ISLAND, dialogue("Waydar", "We have arrived.")));
			assertEquals(4, inputs.size());
			assertFalse(completion.isDone());
			walker.publishGameTick(frame(5, CRASH_ISLAND, GenericClientQuestSnapshot.DialogueSnapshot.closed()));
			assertFalse(completion.isDone());
			walker.publishGameTick(frame(6, CRASH_ISLAND, GenericClientQuestSnapshot.DialogueSnapshot.closed()));
			Map<String, Object> receipt = completion.get(3, TimeUnit.SECONDS);
			assertEquals("arrived", receipt.get("status"));
			Map<?, ?> transport = (Map<?, ?>) ((List<?>) receipt.get("transports")).get(0);
			assertEquals("waydar_crash_island", transport.get("id"));
			assertEquals("arrived", transport.get("status"));
			assertEquals(4, ((List<?>) transport.get("actions")).size());
		}
	}

	@Test
	public void foreignDialogueInterruptsTheServiceEvenWithoutAGeneralDialoguePredicate() throws Exception
	{
		List<GenericClientTransport.Step> inputs = new ArrayList<>();
		try (GenericClientWalker walker = walker(inputs))
		{
			CompletableFuture<Map<String, Object>> completion = begin(walker, inputs, false);
			walker.publishGameTick(frame(2, HANGAR, dialogue("The monkey in your backpack...", "Are we nearly there yet?")));
			Map<String, Object> receipt = completion.get(3, TimeUnit.SECONDS);
			assertEquals("interrupted", receipt.get("status"));
			assertEquals("dialogue", receipt.get("reason"));
			assertTrue(receipt.get("continuation") instanceof String);
			assertEquals(1, inputs.size());
		}
	}

	@Test
	public void anUnexpectedChoiceIsReturnedToTheQuestHandlerWithoutASelection() throws Exception
	{
		List<GenericClientTransport.Step> inputs = new ArrayList<>();
		try (GenericClientWalker walker = walker(inputs))
		{
			CompletableFuture<Map<String, Object>> completion = begin(walker, inputs, true);
			walker.publishGameTick(frame(2, HANGAR, GenericClientQuestSnapshot.DialogueSnapshot.choice(List.of("Pay 1000 coins", "No"))));
			Map<String, Object> receipt = completion.get(3, TimeUnit.SECONDS);
			assertEquals("interrupted", receipt.get("status"));
			assertEquals("dialogue", receipt.get("reason"));
			assertEquals(1, inputs.size());
		}
	}

	@Test
	public void aContinuationFinishesTheOwnedConversationAfterTheTransportHasLanded() throws Exception
	{
		List<GenericClientTransport.Step> inputs = new ArrayList<>();
		try (GenericClientWalker walker = walker(inputs))
		{
			CompletableFuture<Map<String, Object>> first = begin(walker, inputs, true);
			walker.publishGameTick(frame(2, CRASH_ISLAND, dialogue("The monkey in your backpack...", "Are we nearly there yet?")));
			Map<String, Object> interrupted = first.get(3, TimeUnit.SECONDS);
			walker.publishGameTick(frame(3, CRASH_ISLAND, dialogue("Waydar", "We have arrived.")));
			CompletableFuture<Map<String, Object>> resumed = walker.walkTo(new GenericClientWalkRequest(CRASH_ISLAND, 0, 100,
				GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.parse(java.util.Map.of("dialogue", true)), List.of(), (String) interrupted.get("continuation")));
			assertFalse(resumed.isDone());
			walker.publishGameTick(frame(4, CRASH_ISLAND, dialogue("Waydar", "We have arrived.")));
			assertEquals(2, inputs.size());
			walker.publishGameTick(frame(5, CRASH_ISLAND, GenericClientQuestSnapshot.DialogueSnapshot.closed()));
			assertFalse(resumed.isDone());
			walker.publishGameTick(frame(6, CRASH_ISLAND, GenericClientQuestSnapshot.DialogueSnapshot.closed()));
			assertEquals("arrived", resumed.get(3, TimeUnit.SECONDS).get("status"));
		}
	}

	private static GenericClientWalker walker(List<GenericClientTransport.Step> inputs) throws Exception
	{
		return GenericClientTestSupport.walkerWithTransitions(new GenericClientWalkTestFixtures.FakeWalkInput(),
			(step, frame, context) -> {
				inputs.add(step);
				return CompletableFuture.completedFuture(Map.of("status", "dispatched"));
			});
	}

	private static CompletableFuture<Map<String, Object>> begin(GenericClientWalker walker,
		List<GenericClientTransport.Step> inputs, boolean interruptDialogue) throws Exception
	{
		walker.publishGameTick(frame(0, HANGAR, GenericClientQuestSnapshot.DialogueSnapshot.closed()));
		CompletableFuture<Map<String, Object>> completion = walker.walkTo(new GenericClientWalkRequest(CRASH_ISLAND, 0, 100,
			GenericClientActivityContext.none(), false, List.of(),
			GenericClientWalkInterrupts.parse(Map.of("dialogue", interruptDialogue)), List.of(), null));
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (inputs.isEmpty() && !completion.isDone() && System.nanoTime() < deadline)
		{
			walker.publishGameTick(frame(1, HANGAR, GenericClientQuestSnapshot.DialogueSnapshot.closed()));
			Thread.sleep(5);
		}
		assertEquals("Flight did not start: " + completion.getNow(Map.of()), 1, inputs.size());
		return completion;
	}

	private static GenericClientQuestSnapshot.DialogueSnapshot dialogue(String speaker, String text)
	{
		return GenericClientQuestSnapshot.DialogueSnapshot.continueDialogue(speaker, text);
	}

	private static GenericClientSnapshot frame(long tick, WorldPoint player, GenericClientQuestSnapshot.DialogueSnapshot dialogue)
	{
		GenericClientNpcSnapshot waydar = new GenericClientNpcSnapshot(2L,3,1446, "Waydar",
			2649, 4519, 0, 1, 0, -1, null, List.of("Talk-to"));
		return new GenericClientSnapshot(tick, "LOGGED_IN", 240,
			new GenericClientPlayerSnapshot(1L,"transport-test", player.getX(), player.getY(), player.getPlane(), 0),
			List.of(waydar), GenericClientAccountSnapshot.empty(), new GenericClientQuestSnapshot(true, new int[0], Map.of(123, 7),
				List.of(), dialogue));
	}
}
