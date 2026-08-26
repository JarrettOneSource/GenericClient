package com.genericclient;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(GenericClientConfig.GROUP)
public interface GenericClientConfig extends Config
{
	String GROUP = "genericclient";

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show status overlay",
		description = "Show GenericClient status in an overlay"
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatNotifications",
		name = "Show chat diagnostics",
		description = "Write GenericClient events to the in-game chat"
	)
	default boolean chatNotifications()
	{
		return true;
	}

	@Range(min = 1, max = 50)
	@ConfigItem(
		keyName = "npcLogRadius",
		name = "NPC diagnostic radius",
		description = "Maximum tile distance used by the nearby NPC diagnostic snapshot"
	)
	default int npcLogRadius()
	{
		return 15;
	}

	@ConfigItem(
		keyName = "mouseProfileFile",
		name = "Mouse profile file",
		description = "Profile filename inside ~/.runelite/genericclient/mouse-profiles"
	)
	default String mouseProfileFile()
	{
		return GenericClientMouseProfile.DEFAULT_FILE_NAME;
	}

	@Range(min = 25, max = 5000)
	@ConfigItem(
		keyName = "mouseDurationMillis",
		name = "Mouse move duration",
		description = "Milliseconds used to play each matched mouse trajectory"
	)
	default int mouseDurationMillis()
	{
		return 432;
	}
}
