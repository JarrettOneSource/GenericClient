package com.genericclient;

import static org.junit.Assert.*;

import java.awt.Canvas;
import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Actor;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import org.junit.Test;

public class GenericClientCursorAnchorsTest
{
	@Test
	public void combatOffersTheVisiblePrayerOrbAndCurrentTarget()
	{
		Fixture fixture = new Fixture();
		fixture.widgets.put(InterfaceID.Orbs.PRAYERBUTTON, widget(new Rectangle(700, 200, 30, 30), -1, false));
		fixture.target = (Actor) Proxy.newProxyInstance(Actor.class.getClassLoader(), new Class<?>[]{Actor.class},
			(proxy, method, arguments) -> {
				if (method.getName().equals("getCanvasTilePoly"))
					return new java.awt.Polygon(new int[]{300, 330, 330, 300}, new int[]{200, 200, 230, 230}, 4);
				throw new AssertionError("Unexpected target read: " + method.getName());
			});
		List<GenericClientCursorBehavior.Anchor> anchors = fixture.anchors.read(GenericClientActivityContext.Activity.COMBAT, null);
		assertEquals(2, anchors.size());
		assertEquals("prayer_orb", anchors.get(0).name);
		assertEquals(new Point(715, 215), anchors.get(0).point);
		assertEquals("combat_target", anchors.get(1).name);
		assertEquals(new Point(315, 215), anchors.get(1).point);
		fixture.target = null;
		fixture.widgets.clear();
		assertTrue(fixture.anchors.read(GenericClientActivityContext.Activity.COMBAT, null).isEmpty());
	}

	@Test
	public void travelUsesTheVisibleMinimapAndPreservesItsLastClickedAnchor()
	{
		Fixture fixture = new Fixture();
		fixture.widgets.put(548 << 16 | 22, widget(new Rectangle(600, 20, 170, 170), -1, true));
		fixture.widgets.put(164 << 16 | 30, widget(new Rectangle(600, 20, 170, 170), -1, false));
		List<GenericClientCursorBehavior.Anchor> anchors = fixture.anchors.read(
			GenericClientActivityContext.Activity.TRAVEL, new Point(650, 100));
		assertEquals(1, anchors.size());
		assertEquals("minimap", anchors.get(0).name);
		assertEquals(new Point(650, 100), anchors.get(0).point);
		assertEquals(new Point(685, 105), fixture.anchors.read(
			GenericClientActivityContext.Activity.TRAVEL, new Point(20, 20)).get(0).point);
	}

	@Test
	public void skillingUsesTheClickedSpellAndAnOccupiedInventorySlot()
	{
		Fixture fixture = new Fixture();
		fixture.widgets.put(InterfaceID.Inventory.ITEMS, widget(new Rectangle(500, 300, 150, 280), -1, false,
			widget(new Rectangle(500, 300, 32, 32), 379, false)));
		fixture.widgets.put(InterfaceID.MagicSpellbook.UNIVERSE, widget(new Rectangle(650, 300, 150, 280), -1, false,
			widget(new Rectangle(650, 300, 32, 32), -1, false)));
		List<GenericClientCursorBehavior.Anchor> anchors = fixture.anchors.read(
			GenericClientActivityContext.Activity.SKILLING, new Point(660, 310));
		assertEquals(2, anchors.size());
		assertEquals("inventory", anchors.get(0).name);
		assertEquals(new Point(516, 316), anchors.get(0).point);
		assertEquals("spell", anchors.get(1).name);
		assertEquals(new Point(660, 310), anchors.get(1).point);
	}

	@Test
	public void menusHaveNoRestAnchorAndGeneralUsesOnlyAnOnscreenLastClick()
	{
		Fixture fixture = new Fixture();
		for (GenericClientActivityContext.Activity activity : List.of(GenericClientActivityContext.Activity.BANKING,
			GenericClientActivityContext.Activity.TRADING, GenericClientActivityContext.Activity.DIALOGUE,
			GenericClientActivityContext.Activity.MANUAL)) assertTrue(fixture.anchors.read(activity, new Point(20, 20)).isEmpty());
		assertTrue(fixture.anchors.read(GenericClientActivityContext.Activity.GENERAL, null).isEmpty());
		assertTrue(fixture.anchors.read(GenericClientActivityContext.Activity.GENERAL, new Point(-10, 20)).isEmpty());
		assertEquals(new Point(20, 20), fixture.anchors.read(GenericClientActivityContext.Activity.GENERAL, new Point(20, 20)).get(0).point);
	}

	@Test
	public void anticipatedWaypointsMustProjectInsideTheCurrentVisibleMinimap()
	{
		Fixture fixture = new Fixture();
		fixture.widgets.put(548 << 16 | 22, widget(new Rectangle(600, 20, 170, 170), -1, false));
		assertEquals(new Point(697, 105), fixture.anchors.anticipate(new WorldPoint(3253, 3250, 0)));
		assertNull(fixture.anchors.anticipate(new WorldPoint(3253, 3250, 1)));
		assertNull(fixture.anchors.anticipate(new WorldPoint(3500, 3500, 0)));
		assertNull(fixture.anchors.anticipate(new WorldPoint(3280, 3250, 0)));
		fixture.widgets.clear();
		assertNull(fixture.anchors.anticipate(new WorldPoint(3253, 3250, 0)));
		fixture.player = null;
		assertNull(fixture.anchors.anticipate(new WorldPoint(3253, 3250, 0)));
		assertNull(fixture.anchors.anticipate(null));
	}

	private static Widget widget(Rectangle bounds, int itemId, boolean hidden, Widget... children)
	{
		return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
			(proxy, method, arguments) -> {
				switch (method.getName())
				{
					case "getBounds": return new Rectangle(bounds);
					case "getCanvasLocation": return new net.runelite.api.Point(bounds.x, bounds.y);
					case "getWidth": return bounds.width;
					case "getHeight": return bounds.height;
					case "getItemId": return itemId;
					case "getActions": return new String[]{"Cast"};
					case "isHidden": return hidden;
					case "isSelfHidden": return false;
					case "getStaticChildren": return children;
					case "getDynamicChildren":
					case "getNestedChildren": return null;
				case "getParent": return null;
				default: throw new AssertionError("Unexpected widget read: " + method.getName());
				}
			});
	}

	private static final class Fixture
	{
		private final Canvas canvas = new Canvas();
		private final Map<Integer, Widget> widgets = new HashMap<>();
		private Actor target;
		private final WorldView world = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[]{WorldView.class},
			(proxy, method, arguments) -> {
				switch (method.getName())
				{
					case "getId":
					case "getPlane": return 0;
					case "getBaseX":
					case "getBaseY": return 3200;
					case "getSizeX":
					case "getSizeY": return 104;
					default: throw new AssertionError("Unexpected world read: " + method.getName());
				}
			});
		private Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
			(proxy, method, arguments) -> {
				switch (method.getName())
				{
					case "getWorldView": return world;
					case "getInteracting": return target;
					case "getWorldLocation": return new WorldPoint(3250, 3250, 0);
					case "getCameraFocus": return LocalPoint.fromScene(50, 50, world);
					default: throw new AssertionError("Unexpected player read: " + method.getName());
				}
			});
		private final GenericClientCursorAnchors anchors;

		@SuppressWarnings("deprecation")
		Fixture()
		{
			canvas.setSize(800, 600);
			Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
				(proxy, method, arguments) -> {
					switch (method.getName())
					{
						case "getCanvas": return canvas;
						case "getLocalPlayer":
						case "getCameraFocusEntity": return player;
						case "isResized": return false;
						case "getMinimapZoom": return 4.0;
						case "getCameraYawTarget": return 0;
						case "getWidget":
							int id = arguments.length == 2 ? ((Integer) arguments[0] << 16) | (Integer) arguments[1]
								: arguments[0] instanceof WidgetInfo ? ((WidgetInfo) arguments[0]).getId() : (Integer) arguments[0];
							return widgets.get(id);
						default: throw new AssertionError("Unexpected client read: " + method.getName());
					}
				});
			anchors = new GenericClientCursorAnchors(client);
		}
	}
}
