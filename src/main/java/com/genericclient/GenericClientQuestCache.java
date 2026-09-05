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
	// Native quest_progress_get; SDK 1.12.38 has no named constant. See navigation-map-revisions.md.
	private static final int QUEST_PROGRESS_GET = 4024;

	private GenericClientAccountSnapshot.QuestListSnapshot snapshot;
	private long refreshedGameTick = -1;

	GenericClientAccountSnapshot.QuestListSnapshot capture(Client client, long gameTick)
	{
		if (snapshot == null || gameTick - refreshedGameTick >= REFRESH_INTERVAL_TICKS)
		{
			List<GenericClientAccountSnapshot.QuestSnapshot> refreshed = new ArrayList<>();
			for (Quest quest : Quest.values())
			{
				QuestState state = quest.getState(client);
				client.runScript(QUEST_PROGRESS_GET, quest.getId());
				int progress = client.getIntStack()[0];
				refreshed.add(new GenericClientAccountSnapshot.QuestSnapshot(
					quest.name().toLowerCase(Locale.ROOT),
					quest.getId(),
					quest.getName(),
					state.name().toLowerCase(Locale.ROOT), progress));
			}
			refreshedGameTick = gameTick;
			snapshot = new GenericClientAccountSnapshot.QuestListSnapshot(true, gameTick, refreshed);
		}

		return snapshot;
	}

	void clear()
	{
		snapshot = null;
		refreshedGameTick = -1;
	}
}
