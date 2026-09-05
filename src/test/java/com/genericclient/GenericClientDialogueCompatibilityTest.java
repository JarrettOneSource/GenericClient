package com.genericclient;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientDialogueCompatibilityTest
{
	@Rule public TemporaryFolder folders = new TemporaryFolder();
	private static final String API = "org.dreambot.api.methods.dialogues.Dialogues.";

	@Test public void conversationTextRetainsMarkupAndClosedDialogueReturnsAnEmptyString() throws Exception
	{
		try (GenericClientNativeInputFixture scene = new GenericClientNativeInputFixture(folders.newFolder().toPath());
			GenericClientScriptHost host = GenericClientTestSupport.scriptHost(folders.newFolder().toPath()))
		{
			host.publishGameTick(snapshot(scene));
			assertEquals("", value(host, "return " + API + "getNPCDialogue();"));
			assertEquals(List.of(false, false, List.of()), value(host, "return List.of(" + API + "inDialogue()," +
				API + "canContinue(),Arrays.asList(" + API + "getOptions()));"));
			element(scene, InterfaceID.ChatLeft.NAME, "<col=ffff00>Waydar</col>");
			GenericClientNativeInputFixture.Element text = element(scene, InterfaceID.ChatLeft.TEXT,
				"<col=ff0000>Ready?</col><br>  Let us go.");
			element(scene, InterfaceID.ChatLeft.CONTINUE, "Click here to continue");
			GenericClientSnapshot captured = snapshot(scene);
			host.publishGameTick(captured);
			assertEquals(text.text, value(host, "return " + API + "getNPCDialogue();"));
			Map<?, ?> dialogue = (Map<?, ?>) captured.read("dialogue", Map.of());
			assertEquals("Waydar", dialogue.get("speaker"));
			assertEquals("Ready?  Let us go.", dialogue.get("text"));
			assertEquals(List.of(true, true), value(host, "return List.of(" + API + "inDialogue()," + API + "canContinue());"));
			text.text = "A different page";
			assertEquals("<col=ff0000>Ready?</col><br>  Let us go.", value(host, "return " + API + "getNPCDialogue();"));
			host.publishGameTick(snapshot(scene));
			assertEquals("A different page", value(host, "return " + API + "getNPCDialogue();"));
			scene.roots.clear();
			element(scene, InterfaceID.Objectbox.TEXT, "<col=00ff00>You find a key.</col>");
			host.publishGameTick(snapshot(scene));
			assertEquals("<col=00ff00>You find a key.</col>", value(host, "return " + API + "getNPCDialogue();"));
		}
	}

	@Test public void numberedChoicesClickTheRequestedDuplicateAndRejectChangedLabels() throws Exception
	{
		try (GenericClientNativeInputFixture scene = new GenericClientNativeInputFixture(folders.newFolder().toPath()))
		{
			scene.behavior.saveOverrides(new GenericClientBehaviorOverrides(0, 0, 5, 0, 120, 10, 1,
				GenericClientBehaviorProfile.LongBreakMode.LOGOUT, 0, GenericClientBehaviorProfile.Edge.TOP,
				0, 0, 0, GenericClientBehaviorProfile.DialogueInputMode.MOUSE));
			GenericClientNativeInputFixture.Element menu = element(scene, InterfaceID.Chatmenu.OPTIONS, "");
			GenericClientNativeInputFixture.Element first = new GenericClientNativeInputFixture.Element(menu.id, 1, "Yes.");
			GenericClientNativeInputFixture.Element second = new GenericClientNativeInputFixture.Element(menu.id, 2, "Yes.");
			second.bounds.translate(0, 80);
			menu.children(first, second);
			GenericClientQuestActions actions = new GenericClientQuestActions(null, null, null, null, null,
				scene.inputs.dialogueInput, null, null, null, null, null, null, null, null, null, null, null);
			try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(folders, "dialogue-input")
				.questAction(actions::execute).build())
			{
				host.publishGameTick(snapshot(scene));
				assertEquals(List.of("Yes.", "Yes."), value(host, "return Arrays.asList(" + API + "getOptions());"));
				assertEquals(false, value(host, "return " + API + "continueDialogue();"));
				assertEquals(true, value(host, "return " + API + "chooseOption(2);"));
				assertEquals(1, scene.clicks.get());
				assertTrue("The second identically named option must receive the click", second.bounds.contains(scene.pressed.get(0)));
				assertEquals(true, value(host, "return " + API + "chooseOption(\"yES.\");"));
				assertEquals(2, scene.clicks.get());
				assertTrue(first.bounds.contains(scene.pressed.get(1)));
				assertEquals(List.of(false, false, false, false), value(host, "return List.of(" + API + "chooseOption(0)," +
					API + "chooseOption(-1)," + API + "chooseOption(3)," + API + "chooseOption(\"Missing\"));"));
				scene.onTarget = () -> second.text = "Pay 1000 coins";
				assertEquals(false, value(host, "return " + API + "chooseOption(2);"));
				assertEquals(2, scene.clicks.get());
				scene.onTarget = () -> first.text = "No.";
				assertEquals(false, value(host, "return " + API + "chooseOption(\"yes.\");"));
				assertEquals(2, scene.clicks.get());
				scene.onTarget = () -> { };
				first.text = "Yes.";
				second.text = "Yes.";
				Map<String, Object> keyboard = actions.execute("dialogue.choose",
					Map.of("text", "Yes.", "index", 2, "keyboard", true, "reading", false),
					GenericClientActivityContext.none()).get(5, TimeUnit.SECONDS);
				assertEquals("2", keyboard.get("key"));
				assertEquals("dispatched", keyboard.get("status"));
				assertEquals(List.of(2), scene.continuedWidgetIndices);
				assertEquals(2, scene.clicks.get());
				scene.roots.clear();
				GenericClientNativeInputFixture.Element next = element(scene, InterfaceID.ChatLeft.CONTINUE, "Click here to continue");
				host.publishGameTick(snapshot(scene));
				assertEquals(true, value(host, "return " + API + "continueDialogue();"));
				assertEquals(3, scene.clicks.get());
				assertTrue(next.bounds.contains(scene.pressed.get(2)));
				scene.roots.clear();
				assertEquals(false, value(host, "return " + API + "continueDialogue();"));
				assertEquals(3, scene.clicks.get());
			}
		}
	}

	private static Object value(GenericClientScriptHost host, String body) throws Exception
	{
		return host.evaluate(body).get(5, TimeUnit.SECONDS).get("value");
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
			new GenericClientPlayerSnapshot(1L, "Player", 3200, 3200, 0, -1), List.of(),
			GenericClientAccountSnapshot.empty(), new GenericClientQuestSnapshot(true, new int[0], List.of(),
				GenericClientQuestSnapshot.captureDialogue(scene.client)));
	}
}
