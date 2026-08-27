package com.genericclient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;

final class GenericClientQuestCache
{
	private static final int REFRESH_INTERVAL_TICKS = 10;

	private List<GenericClientAccountSnapshot.QuestSnapshot> quests;
	private long refreshedGameTick = -1;

	GenericClientAccountSnapshot.QuestListSnapshot capture(Client client, long gameTick)
	{
		if (quests == null || gameTick - refreshedGameTick >= REFRESH_INTERVAL_TICKS)
		{
			List<GenericClientAccountSnapshot.QuestSnapshot> refreshed = new ArrayList<>();
			for (Quest quest : Quest.values())
			{
				QuestState state = quest.getState(client);
				refreshed.add(new GenericClientAccountSnapshot.QuestSnapshot(
					quest.name().toLowerCase(Locale.ROOT),
					quest.getId(),
					quest.getName(),
					state.name().toLowerCase(Locale.ROOT)));
			}
			quests = refreshed;
			refreshedGameTick = gameTick;
		}

		return new GenericClientAccountSnapshot.QuestListSnapshot(
			true,
			refreshedGameTick,
			quests);
	}

	void clear()
	{
		quests = null;
		refreshedGameTick = -1;
	}
}
