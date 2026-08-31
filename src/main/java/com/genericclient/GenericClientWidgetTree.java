package com.genericclient;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.widgets.Widget;

final class GenericClientWidgetTree
{
	private GenericClientWidgetTree()
	{
	}

	static List<Widget> descendants(Widget root, int limit)
	{
		List<Widget> result = new ArrayList<>();
		collect(root, limit, result);
		return result;
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
		if (widget == null || widget.isHidden() || widget.isSelfHidden())
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
}
