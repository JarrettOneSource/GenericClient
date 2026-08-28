package com.genericclient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.util.Text;

final class GenericClientGameMessageBuffer
{
	private static final int CAPACITY = 100;
	private static final EnumSet<ChatMessageType> CAPTURED_TYPES = EnumSet.complementOf(EnumSet.of(
		ChatMessageType.ITEM_EXAMINE,
		ChatMessageType.NPC_EXAMINE,
		ChatMessageType.OBJECT_EXAMINE,
		ChatMessageType.CONSOLE,
		ChatMessageType.UNKNOWN));

	private final ArrayDeque<Message> messages = new ArrayDeque<>();

	synchronized void add(long gameTick, ChatMessage event)
	{
		if (event == null || !CAPTURED_TYPES.contains(event.getType()))
		{
			return;
		}
		String text = clean(event.getMessage());
		if (text.isEmpty())
		{
			return;
		}
		messages.addLast(new Message(
			gameTick,
			event.getType().name().toLowerCase(java.util.Locale.ROOT),
			clean(event.getName()),
			clean(event.getSender()),
			text));
		while (messages.size() > CAPACITY)
		{
			messages.removeFirst();
		}
	}

	private static String clean(String value)
	{
		return Text.removeTags(Objects.toString(value, "")).trim();
	}

	synchronized List<Message> snapshot()
	{
		return Collections.unmodifiableList(new ArrayList<>(messages));
	}

	synchronized void clear()
	{
		messages.clear();
	}

	static final class Message
	{
		private final long gameTick;
		private final String type;
		private final String name;
		private final String sender;
		private final String text;

		Message(long gameTick, String type, String name, String sender, String text)
		{
			this.gameTick = gameTick;
			this.type = type;
			this.name = name;
			this.sender = sender;
			this.text = text;
		}

		long getGameTick()
		{
			return gameTick;
		}

		String getType()
		{
			return type;
		}

		String getName()
		{
			return name;
		}

		String getText()
		{
			return text;
		}

		String getSender()
		{
			return sender;
		}
	}
}
