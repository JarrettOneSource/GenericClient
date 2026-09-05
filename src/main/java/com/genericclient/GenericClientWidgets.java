package com.genericclient;

import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.HashTable;
import net.runelite.api.MenuEntry;
import net.runelite.api.WidgetNode;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/** Client-thread traversal of visible widget trees, including attached interfaces. */
final class GenericClientWidgets
{
	private static final int MAX_WIDGETS = 1_024;
	private static final int[][] MINIMAP_DRAW_AREAS = {{548, 22}, {164, 30}, {161, 30}};

	private GenericClientWidgets() { }

	static List<Widget> descendants(Widget root, int limit)
	{
		List<Widget> result = new ArrayList<>();
		collect(root, limit, result);
		return result;
	}

	static Widget visibleMinimap(Client client)
	{
		for (int[] id : MINIMAP_DRAW_AREAS)
		{
			Widget candidate = client.getWidget(id[0], id[1]);
			if (candidate != null && !candidate.isHidden()) return candidate;
		}
		return null;
	}

	static boolean hasAction(Widget widget, String action)
	{
		String[] actions = widget.getActions();
		if (actions == null)
		{
			return false;
		}
		for (String candidate : actions)
		{
			if (candidate != null && candidate.equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}

	static boolean clickable(Widget widget)
	{
		if (!isVisible(widget))
		{
			return false;
		}
		Rectangle bounds = widget.getBounds();
		return bounds != null && bounds.width > 0 && bounds.height > 0;
	}

	private static void collect(Widget widget, int limit, List<Widget> result)
	{
		if (widget == null || result.size() >= limit)
		{
			return;
		}
		result.add(widget);
		collect(widget.getStaticChildren(), limit, result);
		collect(widget.getDynamicChildren(), limit, result);
		collect(widget.getNestedChildren(), limit, result);
	}

	private static void collect(Widget[] children, int limit, List<Widget> result)
	{
		if (children == null)
		{
			return;
		}
		for (Widget child : children)
		{
			collect(child, limit, result);
		}
	}

	static List<Widget> visible(Client client)
	{
		ArrayDeque<Widget> queue = new ArrayDeque<>();
		HashTable<WidgetNode> components = client.getComponentTable();
		if (components != null)
			for (WidgetNode node : components)
				if (node != null)
				{
					Widget root = client.getWidget(node.getId(), 0);
					if (root != null) queue.addLast(root);
				}
		add(queue, client.getWidgetRoots());
		return visible(queue, MAX_WIDGETS);
	}

	static List<Widget> visible(Widget root)
	{
		return visible(root, MAX_WIDGETS);
	}

	static List<Widget> visible(Widget root, int limit)
	{
		ArrayDeque<Widget> queue = new ArrayDeque<>();
		if (root != null) queue.addLast(root);
		return visible(queue, limit);
	}

	private static List<Widget> visible(ArrayDeque<Widget> queue, int limit)
	{
		Set<Widget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Widget> result = new ArrayList<>();
		while (!queue.isEmpty() && seen.size() < limit)
		{
			Widget widget = queue.removeFirst();
			if (!seen.add(widget)) continue;
			if (clickable(widget)) result.add(widget);
			add(queue, widget.getChildren());
			add(queue, widget.getDynamicChildren());
			add(queue, widget.getStaticChildren());
			add(queue, widget.getNestedChildren());
		}
		return result;
	}

	static boolean isVisible(Widget widget)
	{
		for (Widget current = widget; current != null; current = current.getParent())
			if (current.isHidden() || current.isSelfHidden()) return false;
		return widget != null;
	}

	static boolean matchesWidget(MenuEntry entry, Widget target)
	{
		Widget widget = entry.getWidget();
		if (widget != null)
		{
			return widget.getId() == target.getId() && widget.getIndex() == target.getIndex();
		}
		return entry.getParam1() == target.getId() &&
			(target.getIndex() < 0 || entry.getParam0() == target.getIndex());
	}

	static boolean matchesLabel(String label, String text, String name, List<String> actions)
	{
		String wanted = label.toLowerCase(Locale.ROOT);
		return contains(text, wanted) || contains(name, wanted) || actions.stream().anyMatch(action -> contains(action, wanted));
	}

	private static boolean contains(String text, String wanted)
	{
		return text != null && Text.removeTags(text).toLowerCase(Locale.ROOT).contains(wanted);
	}

	private static void add(ArrayDeque<Widget> queue, Widget[] children)
	{
		if (children != null)
			for (Widget child : children)
				if (child != null) queue.addLast(child);
	}
}
