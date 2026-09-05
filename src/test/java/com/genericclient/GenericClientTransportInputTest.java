package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.runelite.api.GameObject;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientTransportInputTest
{
	@Rule public TemporaryFolder folders = new TemporaryFolder();

	@Test
	public void resolvesTheCapturedLadderWorldAndActionThroughNativeInput() throws Exception
	{
		WorldPoint standing = new WorldPoint(2906, 3476, 0);
		WorldPoint target = new WorldPoint(2907, 3476, 0);
		Rectangle bounds = new Rectangle(400, 200, 40, 40);
		try (GenericClientNativeInputFixture scene = new GenericClientNativeInputFixture(folders.newFolder().toPath()))
		{
			Tile[][][] tiles = new Tile[1][3][3];
			WorldView view = stub(WorldView.class, Map.of("getId", WorldView.TOPLEVEL,
				"getScene", stub(Scene.class, Map.of("getTiles", tiles))));
			GameObject wanted = object(24718, target, bounds, view, 2, 1);
			GameObject other = object(24718, standing, new Rectangle(100, 100, 40, 40), view, 1, 1);
			tiles[0][1][1] = stub(Tile.class, Map.of("getGameObjects", new GameObject[]{other}),
				"getWallObject", "getGroundObject", "getDecorativeObject");
			tiles[0][2][1] = stub(Tile.class, Map.of("getGameObjects", new GameObject[]{wanted}),
				"getWallObject", "getGroundObject", "getDecorativeObject");
			scene.player = stub(Player.class, Map.of("getWorldLocation", standing, "getWorldView", view,
				"getLocalLocation", new LocalPoint(192, 192, WorldView.TOPLEVEL)));
			scene.objects.put(24718, stub(ObjectComposition.class, Map.of("getActions", new String[]{"Climb-down"},
				"getName", "Ladder"), "getImpostorIds"));
			scene.offerMenu(bounds, stub(MenuEntry.class, Map.of("getIdentifier", 24718, "getOption", "Climb-down",
				"getTarget", "Ladder", "getType", MenuAction.GAME_OBJECT_FIRST_OPTION,
				"getWorldViewId", WorldView.TOPLEVEL, "getParam0", 2, "getParam1", 1)));
			GenericClientTransport.ObjectStep step = new GenericClientTransport.ObjectStep(24718, "Climb-down", new WorldArea(target, 1, 1));
			assertFalse(step.available(snapshot(standing, List.of(), List.of())));
			GenericClientSnapshot captured = snapshot(standing, List.of(), List.of(
				new GenericClientQuestSnapshot.ObjectSnapshot(scene.identities.identify(other),24718, "Ladder", "game", standing.getX(), standing.getY(), 0, 0, List.of("Climb-down")),
				new GenericClientQuestSnapshot.ObjectSnapshot(scene.identities.identify(wanted),24718, "Ladder", "game", target.getX(), target.getY(), 0, 1, List.of("Climb-down"))));
			assertTrue(step.available(captured));
			Map<String, Object> receipt = step.execute(scene.inputs, captured, GenericClientActivityContext.none()).get(3, TimeUnit.SECONDS);
			assertEquals("dispatched", receipt.get("status"));
			assertEquals("Climb-down", receipt.get("action"));
			Map<?, ?> object = (Map<?, ?>) receipt.get("target");
			assertEquals(Map.of("x", 2907L, "y", 3476L, "plane", 0L), object.get("world"));
			assertEquals(1, scene.clicks.get());
			assertTrue(bounds.contains(scene.pressed.get(0)));
			GenericClientActivityContext context = GenericClientActivityContext.none().openInputScope();
			scene.onTarget = context::cancelInput;
			Map<String, Object> cancelled = step.execute(scene.inputs, captured, context).get(3, TimeUnit.SECONDS);
			assertEquals("rejected", cancelled.get("status"));
			assertEquals("action_cancelled", cancelled.get("result"));
			assertEquals(1, scene.clicks.get());
			scene.onTarget = () -> { };
			assertEquals("dispatched", step.execute(scene.inputs, captured, GenericClientActivityContext.none())
				.get(3, TimeUnit.SECONDS).get("status"));
			assertEquals(2, scene.clicks.get());
			net.runelite.api.events.GameObjectDespawned despawned = new net.runelite.api.events.GameObjectDespawned();
			despawned.setTile(tiles[0][2][1]);
			despawned.setGameObject(wanted);
			scene.identities.onGameObjectDespawned(despawned);
			scene.identities.identify(wanted);
			assertEquals("rejected",step.execute(scene.inputs,captured,GenericClientActivityContext.none()).get(3,TimeUnit.SECONDS).get("status"));
			assertEquals("A reused ladder reference must not inherit the captured lifetime",2,scene.clicks.get());
		}
	}

	@Test
	public void resolvesTheObservedGliderCaptainAndRechecksHisAction() throws Exception
	{
		WorldPoint standing = new WorldPoint(2465, 3501, 3);
		Rectangle bounds = new Rectangle(400, 200, 40, 40);
		try (GenericClientNativeInputFixture scene = new GenericClientNativeInputFixture(folders.newFolder().toPath()))
		{
			NPCComposition composition = stub(NPCComposition.class, Map.of("getActions", new String[]{"Talk-to", "Glider"}));
			NPC captain = stub(NPC.class, Map.of("getId", 10467, "getIndex", 3, "getName", "Captain Errdo",
				"getWorldLocation", standing, "getTransformedComposition", composition, "getConvexHull", bounds), "getInteracting");
			scene.identities.identify(captain);
			IndexedObjectSet<NPC> npcs = new IndexedObjectSet<>()
			{
				@Override public Iterator<NPC> iterator() { return List.of(captain).iterator(); }
				@Override public NPC byIndex(int index) { return index == 3 ? captain : null; }
			};
			WorldView view = stub(WorldView.class, Map.of("npcs", npcs));
			scene.player = stub(Player.class, Map.of("getWorldLocation", standing, "getWorldView", view));
			scene.offerMenu(bounds, stub(MenuEntry.class, Map.of("getIdentifier", 3, "getOption", "Glider",
				"getTarget", "Captain Errdo", "getType", MenuAction.NPC_SECOND_OPTION, "getNpc", captain)));
			GenericClientTransport.NpcStep step = new GenericClientTransport.NpcStep(Set.of(10467, 6091), "Glider", new WorldArea(standing, 1, 1));
			assertFalse(step.available(snapshot(standing, List.of(), List.of())));
			assertFalse(step.available(snapshot(standing, List.of(npc(standing, "Talk-to")), List.of())));
			assertFalse(step.available(snapshot(standing, List.of(npc(new WorldPoint(1, 1, 0), "Glider")), List.of())));
			GenericClientSnapshot captured = snapshot(standing, List.of(npc(standing, "Glider")), List.of());
			assertTrue(step.available(captured));
			Map<String, Object> receipt = step.execute(scene.inputs, captured, GenericClientActivityContext.none()).get(3, TimeUnit.SECONDS);
			assertEquals("dispatched", receipt.get("status"));
			assertEquals("Glider", receipt.get("action"));
			assertEquals(10467L, ((Map<?, ?>) receipt.get("target")).get("id"));
			assertEquals(1, scene.clicks.get());
			assertTrue(bounds.contains(scene.pressed.get(0)));
			scene.identities.onNpcDespawned(new net.runelite.api.events.NpcDespawned(captain));
			scene.identities.identify(captain);
			assertEquals("rejected",step.execute(scene.inputs,captured,GenericClientActivityContext.none()).get(3,TimeUnit.SECONDS).get("status"));
			assertEquals("A reused captain reference must not inherit the captured lifetime",1,scene.clicks.get());
		}
	}

	private static GenericClientNpcSnapshot npc(WorldPoint point, String action)
	{
		return new GenericClientNpcSnapshot(1L,3, 10467, "Captain Errdo", point.getX(), point.getY(), point.getPlane(),
			0, 0, -1, null, List.of(action));
	}

	private static GameObject object(int id, WorldPoint world, Rectangle bounds, WorldView view, int sceneX, int sceneY)
	{
		return stub(GameObject.class, Map.of("getId", id, "getWorldLocation", world,
			"getLocalLocation", new LocalPoint(sceneX*128+64, sceneY*128+64, WorldView.TOPLEVEL),
			"getCanvasLocation", new net.runelite.api.Point(bounds.x, bounds.y), "getClickbox", bounds, "getHash", 1L,
			"getWorldView", view, "getSceneMinLocation", new net.runelite.api.Point(sceneX,sceneY)), "getOpOverride");
	}

	private static GenericClientSnapshot snapshot(WorldPoint point, List<GenericClientNpcSnapshot> npcs,
		List<GenericClientQuestSnapshot.ObjectSnapshot> objects)
	{
		return new GenericClientSnapshot(1, "LOGGED_IN", 240,
			new GenericClientPlayerSnapshot(1L,"transport-test", point.getX(), point.getY(), point.getPlane(), 0), npcs,
			GenericClientAccountSnapshot.empty(), new GenericClientQuestSnapshot(true, new int[0], objects,
				GenericClientQuestSnapshot.DialogueSnapshot.closed()));
	}

	private static <T> T stub(Class<T> type, Map<String, Object> facts, String... nullMethods)
	{
		Set<String> nullable = Set.of(nullMethods);
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
			if (facts.containsKey(method.getName())) return facts.get(method.getName());
			if (nullable.contains(method.getName())) return null;
			throw new AssertionError("Unexpected " + type.getSimpleName() + " read: " + method.getName());
		}));
	}
}
