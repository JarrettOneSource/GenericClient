package com.genericclient;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import static com.genericclient.GenericClientWorldSnapshot.worldMap;
import static com.genericclient.GenericClientWorldSnapshot.canvasMap;

final class GenericClientNpcSnapshot
{
	final long identity;
	final int index;
	final int id;
	final String name;
	final int x;
	final int y;
	final int plane;
	final int distance;
	final int combatLevel;
	final int animation;
	final boolean moving;
	final String interacting;
	final int size;
	final List<String> actions;
	final boolean inScene;
	final boolean clickable;
	final boolean lineOfSight;
	final boolean dead;
	final int healthRatio;
	final int healthScale;
	final Point canvasPoint;
	final Rectangle canvasBounds;

	GenericClientNpcSnapshot(
		long identity,
		int index,
		int id,
		String name,
		int x,
		int y,
		int plane,
		int distance,
		int combatLevel,
		int animation,
		String interacting,
		List<String> actions)
	{
		this(
			identity,
			index,
			id,
			name,
			x,
			y,
			plane,
			distance,
			combatLevel,
			animation,
			interacting,
			1,
			actions,
			false,
			false,
			false,
			false,
			-1,
			-1,
			null,
			null);
	}

	GenericClientNpcSnapshot(
		long identity,
		int index,
		int id,
		String name,
		int x,
		int y,
		int plane,
		int distance,
		int combatLevel,
		int animation,
		String interacting,
		int size,
		List<String> actions)
{
	this(
			identity,
		index,
		id,
		name,
		x,
		y,
		plane,
		distance,
		combatLevel,
		animation,
		interacting,
		size,
		actions,
		false,
		false,
		false,
		false,
		-1,
		-1,
		null,
		null);
}

	GenericClientNpcSnapshot(
		long identity,
		int index,
		int id,
		String name,
		int x,
		int y,
		int plane,
		int distance,
		int combatLevel,
		int animation,
		String interacting,
		int size,
		List<String> actions,
		boolean inScene,
		boolean clickable,
		boolean lineOfSight,
		boolean dead,
		int healthRatio,
		int healthScale,
		Point canvasPoint,
		Rectangle canvasBounds)
	{
		this(identity,index,id,name,x,y,plane,distance,combatLevel,animation,interacting,size,actions,
			inScene,clickable,lineOfSight,dead,healthRatio,healthScale,canvasPoint,canvasBounds,false);
	}

	GenericClientNpcSnapshot(
		long identity,
		int index,
		int id,
		String name,
		int x,
		int y,
		int plane,
		int distance,
		int combatLevel,
		int animation,
		String interacting,
		int size,
		List<String> actions,
		boolean inScene,
		boolean clickable,
		boolean lineOfSight,
		boolean dead,
		int healthRatio,
		int healthScale,
		Point canvasPoint,
		Rectangle canvasBounds,
		boolean moving)
	{
		this.identity = identity;
		this.index = index;
		this.id = id;
		this.name = name;
		this.x = x;
		this.y = y;
		this.plane = plane;
		this.distance = distance;
		this.combatLevel = combatLevel;
		this.animation = animation;
		this.moving = moving;
		this.interacting = interacting;
		this.size = size;
		this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
		this.inScene = inScene;
		this.clickable = clickable;
		this.lineOfSight = lineOfSight;
		this.dead = dead;
		this.healthRatio = healthRatio;
		this.healthScale = healthScale;
		this.canvasPoint = canvasPoint == null ? null : new Point(canvasPoint);
		this.canvasBounds = canvasBounds == null ? null : new Rectangle(canvasBounds);
	}

	int getIndex()
	{
		return index;
	}

	int getDistance()
	{
		return distance;
	}

	int getId()
	{
		return id;
	}

	String getName()
	{
		return name;
	}

	int getCombatLevel()
	{
		return combatLevel;
	}

	int getAnimation()
	{
		return animation;
	}

	String getInteracting()
	{
		return interacting;
	}

	int getSize()
	{
		return size;
	}

	WorldPoint getWorldPoint()
	{
		return new WorldPoint(x, y, plane);
	}

	boolean isDead()
	{
		return dead;
	}

	Map<String, Object> toMap()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("identity",identity);
		value.put("index", (long) index);
		value.put("id", (long) id);
		value.put("name", name);
		value.put("world", worldMap(x, y, plane));
		value.put("distance", (long) distance);
		value.put("combat_level", (long) combatLevel);
		value.put("animation", (long) animation);
		value.put("moving",moving);
		value.put("interacting", interacting);
		value.put("size", (long) size);
		value.put("actions", actions);
		value.put("in_scene", inScene);
		value.put("clickable", clickable);
		value.put("line_of_sight", lineOfSight);
		value.put("dead", dead);
		value.put("health_ratio", (long) healthRatio);
		value.put("health_scale", (long) healthScale);
		value.put("canvas", canvasMap(canvasPoint, canvasBounds));
		return value;
	}

	String worldPoint()
	{
		return x + "," + y + "," + plane;
	}
}
