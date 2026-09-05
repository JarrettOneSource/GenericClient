package com.genericclient;

import static com.genericclient.GenericClientWidgets.clickable;
import static com.genericclient.GenericClientWidgets.descendants;

import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/** Reads current rest geometry on the client thread; it never dispatches input. */
final class GenericClientCursorAnchors
{
	private final Client client;

	GenericClientCursorAnchors(Client client) { this.client = client; }

	List<GenericClientCursorBehavior.Anchor> read(GenericClientActivityContext.Activity activity, Point lastClick)
	{
		List<GenericClientCursorBehavior.Anchor> anchors = new ArrayList<>();
		switch (activity)
		{
			case BANKING:
			case TRADING:
			case DIALOGUE:
			case MANUAL:
				break;
			case TRAVEL:
			case HAZARDOUS_TRAVEL:
				addWidget(anchors, "minimap", GenericClientWidgets.visibleMinimap(client), lastClick);
				break;
			case SKILLING:
				addSlots(anchors, client.getWidget(InterfaceID.Inventory.ITEMS), lastClick, "inventory", true);
				addSlots(anchors, client.getWidget(InterfaceID.MagicSpellbook.UNIVERSE), lastClick, "spell", false);
				break;
			case COMBAT:
				addWidget(anchors, "prayer_orb", client.getWidget(InterfaceID.Orbs.PRAYERBUTTON), lastClick);
				addTarget(anchors);
				break;
			default:
				if (lastClick != null && viewport().contains(lastClick))
					anchors.add(new GenericClientCursorBehavior.Anchor("last_click", lastClick));
		}
		return anchors;
	}

	Point anticipate(WorldPoint waypoint)
	{
		if (waypoint == null) return null;
		Player player = client.getLocalPlayer();
		if (player == null || player.getWorldLocation() == null ||
			player.getWorldLocation().getPlane() != waypoint.getPlane()) return null;
		LocalPoint local = LocalPoint.fromWorld(player.getWorldView(), waypoint);
		if (local == null) return null;
		net.runelite.api.Point point = Perspective.localToMinimap(client, local);
		Widget minimap = GenericClientWidgets.visibleMinimap(client);
		if (point == null || !clickable(minimap) || !minimap.getBounds().contains(point.getX(), point.getY())) return null;
		return new Point(point.getX(), point.getY());
	}

	private void addSlots(List<GenericClientCursorBehavior.Anchor> anchors, Widget root,
		Point lastClick, String name, boolean inventory)
	{
		if (!clickable(root)) return;
		Widget alternate = null;
		boolean clicked = false;
		for (Widget child : descendants(root, 512))
		{
			if (child == root || !clickable(child)) continue;
			if (inventory ? child.getItemId() <= 0 : !GenericClientWidgets.hasAction(child, "Cast")) continue;
			if (!clicked && lastClick != null && child.getBounds().contains(lastClick))
			{
				anchors.add(new GenericClientCursorBehavior.Anchor(name, lastClick));
				clicked = true;
			}
			else if (inventory && alternate == null) alternate = child;
		}
		if (alternate != null) addWidget(anchors, name, alternate, null);
	}

	private void addWidget(List<GenericClientCursorBehavior.Anchor> anchors, String name, Widget widget, Point preferred)
	{
		if (!clickable(widget)) return;
		Rectangle bounds = widget.getBounds().intersection(viewport());
		if (bounds.isEmpty()) return;
		Point point = preferred != null && bounds.contains(preferred) ? preferred
			: new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
		anchors.add(new GenericClientCursorBehavior.Anchor(name, point));
	}

	private void addTarget(List<GenericClientCursorBehavior.Anchor> anchors)
	{
		Player player = client.getLocalPlayer();
		Actor target = player == null ? null : player.getInteracting();
		Polygon polygon = target == null ? null : target.getCanvasTilePoly();
		if (polygon == null) return;
		Rectangle bounds = polygon.getBounds().intersection(viewport());
		if (!bounds.isEmpty()) anchors.add(new GenericClientCursorBehavior.Anchor("combat_target",
			new Point((int) bounds.getCenterX(), (int) bounds.getCenterY())));
	}

	private Rectangle viewport() { return new Rectangle(0, 0, client.getCanvas().getWidth(), client.getCanvas().getHeight()); }
}
