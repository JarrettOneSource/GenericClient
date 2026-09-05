package com.genericclient;

import java.util.List;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.coords.WorldPoint;

/** Explicit account states for offline planner workloads; these are not live account observations. */
enum GenericClientNavigationAccount
{
	UNKNOWN(GenericClientAccountSnapshot.empty(), GenericClientQuestSnapshot.empty()),
	QUEST_ROUTES(questAccount(), new GenericClientQuestSnapshot(true, new int[0], Map.of(123, 7, 125, 3),
		List.of(), GenericClientQuestSnapshot.DialogueSnapshot.closed()));

	private final GenericClientAccountSnapshot account;
	private final GenericClientQuestSnapshot quest;

	GenericClientNavigationAccount(GenericClientAccountSnapshot account, GenericClientQuestSnapshot quest)
	{
		this.account = account;
		this.quest = quest;
	}

	GenericClientSnapshot snapshot(WorldPoint point)
	{
		return new GenericClientSnapshot(0, "LOGGED_IN", GenericClientCollisionMap.SOURCE_GAME_REVISION,
			new GenericClientWorldSnapshot.PlayerSnapshot("navigation-audit", point.getX(), point.getY(), point.getPlane(), 0),
			List.of(), account, quest);
	}

	private static GenericClientAccountSnapshot questAccount()
	{
		return new GenericClientAccountSnapshot(true, 1, List.of(),
			GenericClientAccountSnapshot.ContainerSnapshot.unavailable(), GenericClientAccountSnapshot.ContainerSnapshot.unavailable(),
			GenericClientAccountSnapshot.BankSnapshot.unknown(), new GenericClientAccountSnapshot.QuestListSnapshot(true, 0, List.of(
				new GenericClientAccountSnapshot.QuestSnapshot("tree_gnome_village", Quest.TREE_GNOME_VILLAGE.getId(), "Tree Gnome Village", "finished", -1),
				new GenericClientAccountSnapshot.QuestSnapshot("the_grand_tree", Quest.THE_GRAND_TREE.getId(), "The Grand Tree", "finished", -1),
				new GenericClientAccountSnapshot.QuestSnapshot("waterfall_quest", Quest.WATERFALL_QUEST.getId(), "Waterfall Quest", "in_progress", -1))));
	}
}
