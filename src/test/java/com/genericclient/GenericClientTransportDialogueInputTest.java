package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientTransportDialogueInputTest
{
	@Rule public TemporaryFolder folders = new TemporaryFolder();

	@Test
	public void continuesTheExpectedSpeakerAndRejectsAReplacementPageBeforeClicking() throws Exception
	{
		try (GenericClientNativeInputFixture scene = new GenericClientNativeInputFixture(folders.newFolder().toPath()))
		{
			GenericClientNativeInputFixture.Element speaker = element(scene, InterfaceID.ChatLeft.NAME, "Waydar");
			element(scene, InterfaceID.ChatLeft.TEXT, "Ready?");
			element(scene, InterfaceID.ChatLeft.CONTINUE, "Click here to continue");
			GenericClientTransport.ConversationStep step = new GenericClientTransport.ConversationStep("Waydar", Set.of("Yes", "Yes."));
			GenericClientSnapshot captured = snapshot(scene);
			assertTrue(step.available(captured));
			Map<String, Object> receipt = step.execute(scene.inputs, captured, GenericClientActivityContext.none()).get(5, TimeUnit.SECONDS);
			assertEquals("dispatched", receipt.get("status"));
			assertEquals("continue", ((Map<?, ?>) receipt.get("target")).get("type"));
			assertEquals(1, scene.clicks.get());
			scene.onTarget = () -> speaker.text = "Drunken dwarf";
			Map<String, Object> replaced = step.execute(scene.inputs, captured, GenericClientActivityContext.none()).get(5, TimeUnit.SECONDS);
			assertEquals("dialogue_changed", replaced.get("result"));
			assertEquals(1, scene.clicks.get());
			assertFalse(step.available(snapshot(scene)));
			speaker.text = "transport-test";
			assertTrue(step.available(snapshot(scene)));
		}
	}

	@Test
	public void selectsOnlyThePermittedFlightAnswerAndRejectsAChangedChoiceList() throws Exception
	{
		try (GenericClientNativeInputFixture scene = new GenericClientNativeInputFixture(folders.newFolder().toPath()))
		{
			GenericClientNativeInputFixture.Element menu = element(scene, InterfaceID.Chatmenu.OPTIONS, "");
			GenericClientNativeInputFixture.Element yes = new GenericClientNativeInputFixture.Element(menu.id, 1, "Yes.");
			GenericClientNativeInputFixture.Element no = new GenericClientNativeInputFixture.Element(menu.id, 2, "No.");
			menu.children(yes, no);
			GenericClientTransport.ConversationStep step = new GenericClientTransport.ConversationStep("Waydar", Set.of("Yes", "Yes."));
			GenericClientSnapshot captured = snapshot(scene);
			assertTrue(step.available(captured));
			Map<String, Object> receipt = step.execute(scene.inputs, captured, GenericClientActivityContext.none()).get(5, TimeUnit.SECONDS);
			assertEquals("dispatched", receipt.get("status"));
			assertEquals("Yes.", ((Map<?, ?>) receipt.get("target")).get("text"));
			assertEquals(1, scene.clicks.get());
			scene.onTarget = () -> no.text = "Pay 1000 coins";
			Map<String, Object> changed = step.execute(scene.inputs, captured, GenericClientActivityContext.none()).get(5, TimeUnit.SECONDS);
			assertEquals("dialogue_changed", changed.get("result"));
			assertEquals(1, scene.clicks.get());
			assertFalse(new GenericClientTransport.ConversationStep("Lumdo", Set.of()).available(snapshot(scene)));
			yes.selfHidden = true;
			assertFalse(step.available(snapshot(scene)));
			menu.hidden = true;
			assertFalse(step.available(snapshot(scene)));
		}
	}

	private static GenericClientNativeInputFixture.Element element(GenericClientNativeInputFixture scene, int id, String text)
	{
		GenericClientNativeInputFixture.Element element = new GenericClientNativeInputFixture.Element(id, -1, text);
		scene.roots.put(id, element.widget);
		return element;
	}

	private static GenericClientSnapshot snapshot(GenericClientNativeInputFixture scene)
	{
		return new GenericClientSnapshot(1, "LOGGED_IN", 240,
			new GenericClientPlayerSnapshot(1L,"transport-test", 2649, 4518, 0, 0), List.of(),
			GenericClientAccountSnapshot.empty(), new GenericClientQuestSnapshot(true, new int[0], List.of(),
				GenericClientQuestSnapshot.captureDialogue(scene.client)));
	}
}
