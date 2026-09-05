package com.genericclient;


import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;

final class GenericClientWalkTestFixtures
{
	private GenericClientWalkTestFixtures() { }

	static void waitForFirstClick(
		GenericClientWalker walker,
		FakeWalkInput input,
		WorldPoint player) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (input.targets.isEmpty() && System.nanoTime() < deadline)
		{
			walker.publishGameTick(snapshot(1, player));
			Thread.sleep(10L);
		}
		assertEquals(1, input.targets.size());
	}

	static void waitForClickCount(
		GenericClientWalker walker,
		FakeWalkInput input,
		WorldPoint player,
		int count,
		long tick) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (input.targets.size() < count && System.nanoTime() < deadline)
		{
			walker.publishGameTick(snapshot(tick++, player));
			Thread.sleep(10L);
		}
		assertEquals(count, input.targets.size());
	}

	static GenericClientSnapshot snapshot(long tick, WorldPoint player)
	{
		return snapshot(tick, player, Collections.emptyList());
	}

	static GenericClientSnapshot snapshot(
		long tick,
		WorldPoint player,
		List<GenericClientWorldSnapshot.NpcSnapshot> npcs)
	{
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientWorldSnapshot.PlayerSnapshot(
				"walker-test",
				player.getX(),
				player.getY(),
				player.getPlane(),
				0),
			npcs);
	}

	static GenericClientSnapshot dialogueSnapshot(long tick, WorldPoint player)
	{
		GenericClientQuestSnapshot quest = new GenericClientQuestSnapshot(
			true,
			new int[0],
			Collections.emptyList(),
			GenericClientQuestSnapshot.DialogueSnapshot.continueDialogue(
				"The monkey in your backpack...",
				"Are we nearly there yet?"));
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientWorldSnapshot.PlayerSnapshot(
				"walker-test", player.getX(), player.getY(), player.getPlane(), 0),
			Collections.emptyList(),
			GenericClientAccountSnapshot.empty(),
			quest);
	}

	static GenericClientWorldSnapshot.NpcSnapshot npc(int index, WorldPoint world)
	{
		return new GenericClientWorldSnapshot.NpcSnapshot(
			index,
			5237,
			"Blocking NPC",
			world.getX(),
			world.getY(),
			world.getPlane(),
			0,
			1,
			-1,
			null,
			Collections.singletonList("Attack"));
	}

	static GenericClientSnapshot runSnapshot(
		long tick,
		WorldPoint player,
		int runEnergy,
		boolean runEnabled)
	{
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientWorldSnapshot.PlayerSnapshot(
				"walker-test",
				player.getX(),
				player.getY(),
				player.getPlane(),
				0,
				-1,
				null,
				10,
				10,
				runEnergy,
				runEnabled,
				null),
			Collections.emptyList());
	}

	static GenericClientSnapshot openSceneSnapshot(long tick, WorldPoint player)
	{
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientWorldSnapshot.PlayerSnapshot(
				"walker-test", player.getX(), player.getY(), player.getPlane(), 0),
			Collections.emptyList(),
			GenericClientAccountSnapshot.empty(),
			GenericClientQuestSnapshot.empty(),
			Collections.emptyList(),
			new GenericClientSceneCollision(
				true,
				player.getX() - 52,
				player.getY() - 52,
				player.getPlane(),
				new int[104][104]));
	}

	static GenericClientSnapshot doorSnapshot(
		long tick,
		WorldPoint player,
		WorldPoint beforeDoor,
		WorldPoint door,
		boolean closed)
	{
		return doorSnapshot(
			tick, player, beforeDoor, door, closed, Collections.emptyList());
	}

	static GenericClientSnapshot pairedGateSnapshot(
		long tick,
		WorldPoint player,
		WorldPoint beforeGate,
		WorldPoint afterGate,
		WorldPoint gate,
		int gateOrientation,
		boolean closed)
	{
		int baseX = Math.min(beforeGate.getX(), afterGate.getX()) - 10;
		int baseY = Math.min(beforeGate.getY(), afterGate.getY()) - 10;
		int[][] flags = new int[64][64];
		if (closed)
		{
			flags[afterGate.getX() - baseX][afterGate.getY() - baseY] =
				incomingWall(beforeGate, afterGate);
		}
		GenericClientQuestSnapshot quest = new GenericClientQuestSnapshot(
			true,
			new int[0],
			Collections.singletonList(new GenericClientQuestSnapshot.ObjectSnapshot(
				11767,
				"Gate",
				"wall",
				gate.getX(),
				gate.getY(),
				gate.getPlane(),
				distance(player, gate),
				Collections.singletonList(closed ? "Open" : "Close"),
				gateOrientation,
				0, 1, 1)),
			GenericClientQuestSnapshot.DialogueSnapshot.closed());
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientWorldSnapshot.PlayerSnapshot(
				"walker-test", player.getX(), player.getY(), player.getPlane(), 0),
			Collections.emptyList(),
			GenericClientAccountSnapshot.empty(),
			quest,
			Collections.emptyList(),
			new GenericClientSceneCollision(
				true, baseX, baseY, gate.getPlane(), flags));
	}

	static GenericClientSnapshot solidWallSnapshot(
		long tick,
		WorldPoint player,
		WorldPoint beforeWall,
		WorldPoint wall)
	{
		int baseX = Math.min(beforeWall.getX(), wall.getX()) - 10;
		int baseY = Math.min(beforeWall.getY(), wall.getY()) - 10;
		int[][] flags = new int[64][64];
		flags[wall.getX() - baseX][wall.getY() - baseY] = incomingWall(beforeWall, wall);
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientWorldSnapshot.PlayerSnapshot(
				"walker-test", player.getX(), player.getY(), player.getPlane(), 0),
			Collections.emptyList(),
			GenericClientAccountSnapshot.empty(),
			GenericClientQuestSnapshot.empty(),
			Collections.emptyList(),
			new GenericClientSceneCollision(true, baseX, baseY, wall.getPlane(), flags));
	}

	static GenericClientSnapshot doorSnapshot(
		long tick,
		WorldPoint player,
		WorldPoint beforeDoor,
		WorldPoint door,
		boolean closed,
		List<GenericClientGameMessageBuffer.Message> messages)
	{
		int baseX = Math.min(beforeDoor.getX(), door.getX()) - 10;
		int baseY = Math.min(beforeDoor.getY(), door.getY()) - 10;
		int[][] flags = new int[64][64];
		if (closed)
		{
			flags[door.getX() - baseX][door.getY() - baseY] = incomingWall(beforeDoor, door);
		}
		GenericClientQuestSnapshot quest = new GenericClientQuestSnapshot(
			true,
			new int[0],
			Collections.singletonList(new GenericClientQuestSnapshot.ObjectSnapshot(
				2000,
				"Test door",
				"wall",
				door.getX(),
				door.getY(),
				door.getPlane(),
				distance(player, door),
				Collections.singletonList(closed ? "Open" : "Close"))),
			GenericClientQuestSnapshot.DialogueSnapshot.closed());
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientWorldSnapshot.PlayerSnapshot(
				"walker-test", player.getX(), player.getY(), player.getPlane(), 0),
			Collections.emptyList(),
			GenericClientAccountSnapshot.empty(),
			quest,
			messages,
			new GenericClientSceneCollision(true, baseX, baseY, door.getPlane(), flags));
	}

	static int firstCardinalEdge(List<WorldPoint> route)
	{
		for (int index = 1; index < Math.min(route.size(), 12); index++)
		{
			WorldPoint before = route.get(index - 1);
			WorldPoint after = route.get(index);
			if (before.getX() == after.getX() ^ before.getY() == after.getY())
			{
				return index;
			}
		}
		throw new AssertionError("Test route has no cardinal edge");
	}
	static int incomingWall(WorldPoint from, WorldPoint to)
	{
		if (to.getX() > from.getX())
		{
			return CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		}
		if (to.getX() < from.getX())
		{
			return CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		}
		if (to.getY() > from.getY())
		{
			return CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
		}
		return CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
	}

	static WorldPoint stepToward(WorldPoint from, WorldPoint to)
	{
		return new WorldPoint(
			from.getX() + Integer.signum(to.getX() - from.getX()),
			from.getY() + Integer.signum(to.getY() - from.getY()),
			from.getPlane());
	}

	static int distance(WorldPoint first, WorldPoint second)
	{
		return Math.max(
			Math.abs(first.getX() - second.getX()),
			Math.abs(first.getY() - second.getY()));
	}

	static GenericClientActivityContext context(boolean enabled)
	{
		GenericClientActivityContext travel = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.TRAVEL);
		return enabled ? travel : travel.inIntent();
	}

	static GenericClientActivityContext hazardousContext()
	{
		return GenericClientActivityContext.preset(GenericClientActivityContext.Activity.HAZARDOUS_TRAVEL).inIntent();
	}

	static final class FakeWalkInput implements GenericClientWalker.WalkInput
	{
		final int maximumProjectedTiles;
		final List<WorldPoint> targets = new ArrayList<>();
		final List<List<WorldPoint>> candidateBatches = new ArrayList<>();
		final List<Boolean> breakPolicies = new ArrayList<>();

		FakeWalkInput()
		{
			this(10);
		}

		FakeWalkInput(int maximumProjectedTiles)
		{
			this.maximumProjectedTiles = maximumProjectedTiles;
		}

		@Override
		public CompletableFuture<GenericClientInteractionResult> walkToFarthest(
			List<WorldPoint> candidates,
			GenericClientActivityContext activityContext, double reachFraction)
		{
			candidateBatches.add(new ArrayList<>(candidates));
			breakPolicies.add(activityContext.allowsBreaks());
			int projectedTiles = Math.min(maximumProjectedTiles, candidates.size());
			WorldPoint target = candidates.get(candidates.size() - projectedTiles);
			targets.add(target);
			return CompletableFuture.completedFuture(new GenericClientInteractionResult(
				target,
				"WALK_TILE_CLICK_EXECUTED test",
				true,
				Collections.emptyMap(),
				Collections.emptyMap()));
		}

		@Override
		public void cancelWalkToTile(GenericClientActivityContext owner)
		{
		}
	}

	static final class DeferredWalkInput implements GenericClientWalker.WalkInput
	{
		final List<CompletableFuture<GenericClientInteractionResult>> requests =
			new ArrayList<>();
		int calls;
		int cancellations;

		@Override
		public CompletableFuture<GenericClientInteractionResult> walkToFarthest(
			List<WorldPoint> candidates,
			GenericClientActivityContext activityContext, double reachFraction)
		{
			calls++;
			CompletableFuture<GenericClientInteractionResult> request = new CompletableFuture<>();
			requests.add(request);
			return request;
		}

		@Override
		public void cancelWalkToTile(GenericClientActivityContext owner)
		{
			cancellations++;
		}

		void completeFirstAsCancelled()
		{
			requests.get(0).complete(new GenericClientInteractionResult(
				null,
				"WALK_CLICK_FAILED reason=cancelled",
				false,
				Collections.emptyMap(),
				Collections.emptyMap()));
		}
	}

	static final class FakeObstacleInput implements GenericClientWalker.ObstacleInput
	{
		@Override
		public CompletableFuture<Map<String, Object>> interact(
			int objectId,
			String action,
			WorldPoint world,
			int within,
			GenericClientActivityContext activityContext)
		{
			Map<String, Object> receipt = new LinkedHashMap<>();
			receipt.put("status", "dispatched");
			receipt.put("result", "menu_action_executed");
			return CompletableFuture.completedFuture(receipt);
		}

		@Override
		public void cancel(String reason, GenericClientActivityContext owner)
		{
		}
	}

	static final class RecordingObstacleInput implements GenericClientWalker.ObstacleInput
	{
		int interactions;
		int objectId;
		String action;
		WorldPoint world;
		boolean breaksEnabled;

		@Override
		public CompletableFuture<Map<String, Object>> interact(
			int objectId,
			String action,
			WorldPoint world,
			int within,
			GenericClientActivityContext activityContext)
		{
			interactions++;
			this.objectId = objectId;
			this.action = action;
			this.world = world;
			this.breaksEnabled = activityContext.allowsBreaks();
			Map<String, Object> receipt = new LinkedHashMap<>();
			receipt.put("status", "dispatched");
			receipt.put("result", "menu_action_executed");
			return CompletableFuture.completedFuture(receipt);
		}

		@Override
		public void cancel(String reason, GenericClientActivityContext owner)
		{
		}
	}
}
