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
		description = "Profile filename inside ~/.runelite/genericclient/mouse-profiles",
		hidden = true
	)
	default String mouseProfileFile()
	{
		return GenericClientMouseProfile.DEFAULT_FILE_NAME;
	}

	@ConfigItem(
		keyName = "mouseEffect",
		name = "Mouse effect",
		description = "Draw a fading cursor trail or the active generated path",
		hidden = true
	)
	default GenericClientMouseEffect mouseEffect()
	{
		return GenericClientMouseEffect.TRAIL;
	}

	@Range(min = 1024, max = 65535)
	@ConfigItem(
		keyName = "controlPort",
		name = "MCP bridge port",
		description = "Loopback port used by the GenericClient MCP server"
	)
	default int controlPort()
	{
		return 17343;
	}
}
