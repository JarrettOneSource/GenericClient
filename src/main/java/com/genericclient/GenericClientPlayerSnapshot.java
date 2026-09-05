package com.genericclient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;
import static com.genericclient.GenericClientWorldSnapshot.worldMap;

final class GenericClientPlayerSnapshot
{
	final long identity;
	final String name;
	final int x;
	final int y;
	final int plane;
	final int worldViewId;
	final int animation;
	final String interacting;
	final int currentHitpoints;
	final int maxHitpoints;
	final int runEnergy;
	final boolean runEnabled;
	final WorldPoint destination;
	final boolean moving;
	final int id;
	final int combatLevel;
	private final List<String> actions;
	private final boolean local;
	private final int healthRatio;
	private final int healthScale;

	GenericClientPlayerSnapshot(long identity, String name, int x, int y, int plane, int worldViewId)
	{
		this(identity,name, x, y, plane, worldViewId, -1, null, 0, 0, 0, false, null);
	}

	GenericClientPlayerSnapshot(
		long identity,
		String name,
		int x,
		int y,
		int plane,
		int worldViewId,
		int animation,
		String interacting)
	{
		this(
			identity,
			name,
			x,
			y,
			plane,
			worldViewId,
			animation,
			interacting,
			0,
			0,
			0,
			false,
			null);
	}

	GenericClientPlayerSnapshot(
		long identity,
		String name,
		int x,
		int y,
		int plane,
		int worldViewId,
		int animation,
		String interacting,
		int currentHitpoints,
		int maxHitpoints,
		int runEnergy,
		boolean runEnabled,
		WorldPoint destination)
	{
		this(identity,name, x, y, plane, worldViewId, animation, interacting, currentHitpoints, maxHitpoints,
			runEnergy, runEnabled, destination, false);
	}

	GenericClientPlayerSnapshot(long identity, String name, int x, int y, int plane, int worldViewId, int animation,
		String interacting, int currentHitpoints, int maxHitpoints, int runEnergy, boolean runEnabled,
		WorldPoint destination, boolean moving)
	{
		this(identity,name,x,y,plane,worldViewId,animation,interacting,currentHitpoints,maxHitpoints,runEnergy,
			runEnabled,destination,moving,-1,-1,List.of(),true,-1,-1);
	}

	private GenericClientPlayerSnapshot(long identity, String name, int x, int y, int plane, int worldViewId, int animation,
		String interacting, int currentHitpoints, int maxHitpoints, int runEnergy, boolean runEnabled,
		WorldPoint destination, boolean moving, int id, int combatLevel, List<String> actions, boolean local,
		int healthRatio, int healthScale)
	{
		this.identity = identity;
		this.name = name;
		this.x = x;
		this.y = y;
		this.plane = plane;
		this.worldViewId = worldViewId;
		this.animation = animation;
		this.interacting = interacting;
		this.currentHitpoints = currentHitpoints;
		this.maxHitpoints = maxHitpoints;
		this.runEnergy = runEnergy;
		this.runEnabled = runEnabled;
		this.destination = destination;
		this.moving = moving;
		this.id = id;
		this.combatLevel = combatLevel;
		this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
		this.local = local;
		this.healthRatio = healthRatio;
		this.healthScale = healthScale;
	}

	static GenericClientPlayerSnapshot capture(Client client, Player player, GenericClientEntityIds identities,
		WorldPoint destination, boolean local)
	{
		WorldPoint point = player.getWorldLocation();
		return new GenericClientPlayerSnapshot(identities.identify(player),local ? Objects.toString(player.getName(),"") : player.getName(),
			point.getX(),point.getY(),point.getPlane(),player.getWorldView().getId(),player.getAnimation(),
			player.getInteracting() == null ? null : player.getInteracting().getName(),
			local ? client.getBoostedSkillLevel(Skill.HITPOINTS) : -1,
			local ? client.getRealSkillLevel(Skill.HITPOINTS) : -1,
			local ? client.getEnergy() : 0, local && client.getVarpValue(VarPlayerID.OPTION_RUN) == 1,
			destination,player.getPoseAnimation() != player.getIdlePoseAnimation(),player.getId(),player.getCombatLevel(),
			Arrays.asList(client.getPlayerOptions()),local,player.getHealthRatio(),player.getHealthScale());
	}

	Map<String, Object> toMap(String gameState)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("identity",identity);
		value.put("id",id);
		value.put("index",id);
		value.put("logged_in", GameState.LOGGED_IN.name().equals(gameState));
		value.put("name", name);
		value.put("combat_level",combatLevel);
		value.put("actions",actions);
		value.put("world", worldMap(x, y, plane));
		value.put("world_view", (long) worldViewId);
		value.put("animation", (long) animation);
		value.put("interacting", interacting);
		value.put("health_ratio",healthRatio);
		value.put("health_scale",healthScale);
		value.put("moving", moving);
		if (local)
		{
			value.put("current_hitpoints", (long) currentHitpoints);
			value.put("max_hitpoints", (long) maxHitpoints);
			value.put("run_energy", (long) runEnergy);
			value.put("run_enabled", runEnabled);
			value.put("destination", destination == null
				? null
				: worldMap(destination.getX(), destination.getY(), destination.getPlane()));
		}
		return value;
	}

	String getName()
	{
		return name;
	}

	String worldPoint()
	{
		return x + "," + y + "," + plane;
	}
}
