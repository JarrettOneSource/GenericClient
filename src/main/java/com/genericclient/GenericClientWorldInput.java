package com.genericclient;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.World;
import net.runelite.api.WorldType;
import net.runelite.client.callback.ClientThread;

final class GenericClientWorldInput
{
	private final Client client;
	private final ClientThread clientThread;

	GenericClientWorldInput(Client client, ClientThread clientThread)
	{
		this.client = client;
		this.clientThread = clientThread;
	}

	CompletableFuture<Map<String, Object>> select(int worldId, boolean requireMembers, GenericClientActivityContext context)
	{
		if (worldId < 300)
		{
			throw new IllegalArgumentException("World id must be at least 300");
		}
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			if (!context.isInputAllowed())
			{
				result.complete(receipt("rejected", "action_cancelled", null, false));
				return;
			}
			if (client.getGameState() != GameState.LOGIN_SCREEN)
			{
				result.complete(receipt(
					"rejected", "world_selection_requires_login_screen", null, false));
				return;
			}
			World target = findWorld(worldId);
			boolean verifiedFromList = target != null;
			if (target == null && client.getWorldList() == null)
			{
				target = exactWorld(worldId, requireMembers);
			}
			if (target == null)
			{
				result.complete(receipt("rejected", "world_not_loaded", null, false));
				return;
			}
			boolean members = target.getTypes().contains(WorldType.MEMBERS);
			if (requireMembers && !members)
			{
				result.complete(receipt(
					"rejected", "members_world_required", target, verifiedFromList));
				return;
			}
			if (client.getWorld() == worldId)
			{
				result.complete(receipt(
					"unchanged", "world_already_selected", target, verifiedFromList));
				return;
			}
			client.changeWorld(target);
			result.complete(receipt("set", "world_selected", target, verifiedFromList));
		});
		return result;
	}

	private World exactWorld(int worldId, boolean members)
	{
		World world = client.createWorld();
		world.setId(worldId);
		world.setAddress("oldschool" + worldId + ".runescape.com");
		world.setActivity("");
		world.setLocation(0);
		world.setPlayerCount(0);
		world.setTypes(members
			? EnumSet.of(WorldType.MEMBERS)
			: EnumSet.noneOf(WorldType.class));
		return world;
	}

	private World findWorld(int worldId)
	{
		World[] worlds = client.getWorldList();
		if (worlds == null)
		{
			return null;
		}
		for (World world : worlds)
		{
			if (world != null && world.getId() == worldId)
			{
				return world;
			}
		}
		return null;
	}

	private static Map<String, Object> receipt(
		String status,
		String reason,
		World world,
		boolean verifiedFromList)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("status", status);
		value.put("result", reason);
		value.put("click_count", 0L);
		value.put("verified_from_list", verifiedFromList);
		if (world != null)
		{
			value.put("world", (long) world.getId());
			value.put("members", world.getTypes().contains(WorldType.MEMBERS));
			List<String> types = new ArrayList<>();
			for (WorldType type : world.getTypes())
			{
				types.add(type.name().toLowerCase(java.util.Locale.ROOT));
			}
			value.put("types", types);
		}
		return value;
	}
}
