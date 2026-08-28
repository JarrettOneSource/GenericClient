package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.util.List;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import org.junit.Test;

public class GenericClientGameMessageBufferTest
{
	@Test
	public void keepsSystemAndPlayerMessagesWhileIgnoringExamineNoise()
	{
		GenericClientGameMessageBuffer buffer = new GenericClientGameMessageBuffer();
		buffer.add(11, message(ChatMessageType.ITEM_EXAMINE, "", "", "A rune."));
		buffer.add(12, message(ChatMessageType.PUBLICCHAT, "Player", "", "hello"));
		buffer.add(13, message(ChatMessageType.GAMEMESSAGE, "", "server", "I can't reach that."));

		List<GenericClientGameMessageBuffer.Message> messages = buffer.snapshot();

		assertEquals(2, messages.size());
		assertEquals(12L, messages.get(0).getGameTick());
		assertEquals("publicchat", messages.get(0).getType());
		assertEquals("Player", messages.get(0).getName());
		assertEquals("server", messages.get(1).getSender());
		assertEquals("I can't reach that.", messages.get(1).getText());
	}

	private static ChatMessage message(
		ChatMessageType type,
		String name,
		String sender,
		String text)
	{
		return new ChatMessage(null, type, name, text, sender, 0);
	}
}
