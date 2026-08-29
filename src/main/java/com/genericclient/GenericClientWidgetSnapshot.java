package com.genericclient;

import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.HashTable;
import net.runelite.api.WidgetNode;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

final class GenericClientWidgetSnapshot
{
	private static final int MAX_CAPTURED_WIDGETS = 1_024;
	private static final int MAX_QUERY_RESULTS = 100;

	private final List<WidgetValue> widgets;

	private GenericClientWidgetSnapshot(List<WidgetValue> widgets)
	{
		this.widgets = Collections.unmodifiableList(new ArrayList<>(widgets));
	}

	static GenericClientWidgetSnapshot empty()
	{
		return new GenericClientWidgetSnapshot(Collections.emptyList());
	}

	static GenericClientWidgetSnapshot capture(Client client)
	{
		Widget[] roots = client.getWidgetRoots();
		Set<Widget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Widget> queue = new ArrayDeque<>();
		HashTable<WidgetNode> componentTable = client.getComponentTable();
		if (componentTable != null)
		{
			for (WidgetNode node : componentTable)
			{
				if (node != null)
				{
					Widget root = client.getWidget(node.getId(), 0);
					if (root != null)
					{
						queue.addLast(root);
					}
				}
			}
		}
		add(queue, roots);
		List<WidgetValue> values = new ArrayList<>();
		while (!queue.isEmpty() && seen.size() < MAX_CAPTURED_WIDGETS)
		{
			Widget widget = queue.removeFirst();
			if (!seen.add(widget))
			{
				continue;
			}
			Rectangle bounds = widget.getBounds();
			if (!widget.isHidden() && !widget.isSelfHidden() && bounds != null &&
				bounds.width > 0 && bounds.height > 0)
			{
				values.add(WidgetValue.capture(widget, bounds));
			}
			add(queue, widget.getChildren());
			add(queue, widget.getDynamicChildren());
			add(queue, widget.getStaticChildren());
			add(queue, widget.getNestedChildren());
		}
		values.sort(Comparator.comparingInt(WidgetValue::getId)
			.thenComparingInt(WidgetValue::getIndex));
		return new GenericClientWidgetSnapshot(values);
	}

	List<Map<String, Object>> read(Map<?, ?> query)
	{
		int limit = Math.min(
			MAX_QUERY_RESULTS,
			Math.max(0, number(query == null ? null : query.get("limit"), 50)));
		if (limit == 0)
		{
			return Collections.emptyList();
		}
		Set<Integer> ids = ids(query);
		Integer group = query != null && query.get("group") instanceof Number
			? ((Number) query.get("group")).intValue()
			: null;
		String action = text(query == null ? null : query.get("action"));
		String requiredText = null;
		if (query != null && query.get("where") instanceof Map)
		{
			requiredText = text(((Map<?, ?>) query.get("where")).get("text"));
		}
		List<Map<String, Object>> result = new ArrayList<>();
		for (WidgetValue widget : widgets)
		{
			if (!ids.isEmpty() && !ids.contains(widget.id) ||
				group != null && widget.groupId != group ||
				action != null && widget.actions.stream()
					.noneMatch(candidate -> candidate.equalsIgnoreCase(action)) ||
				requiredText != null && !requiredText.equalsIgnoreCase(widget.text))
			{
				continue;
			}
			result.add(widget.toMap());
			if (result.size() == limit)
			{
				break;
			}
		}
		return result;
	}

	private static Set<Integer> ids(Map<?, ?> query)
	{
		if (query == null)
		{
			return Collections.emptySet();
		}
		Set<Integer> values = new LinkedHashSet<>();
		Object single = query.get("id");
		if (single instanceof Number)
		{
			values.add(((Number) single).intValue());
		}
		Object raw = query.get("ids");
		Collection<?> items = raw instanceof Collection
			? (Collection<?>) raw
			: raw instanceof Map ? ((Map<?, ?>) raw).values() : Collections.emptyList();
		for (Object item : items)
		{
			if (!(item instanceof Number))
			{
				throw new IllegalArgumentException("widget ids must be numeric");
			}
			values.add(((Number) item).intValue());
		}
		if (values.size() > MAX_QUERY_RESULTS)
		{
			throw new IllegalArgumentException("widget reads support at most 100 ids");
		}
		return values;
	}

	private static void add(ArrayDeque<Widget> queue, Widget[] widgets)
	{
		if (widgets == null)
		{
			return;
		}
		for (Widget widget : widgets)
		{
			if (widget != null)
			{
				queue.addLast(widget);
			}
		}
	}

	private static int number(Object value, int defaultValue)
	{
		return value instanceof Number ? ((Number) value).intValue() : defaultValue;
	}

	private static String text(Object value)
	{
		return value instanceof String && !((String) value).trim().isEmpty()
			? ((String) value).trim()
			: null;
	}

	private static final class WidgetValue
	{
		private final int id;
		private final int groupId;
		private final int componentId;
		private final int index;
		private final int parentId;
		private final int type;
		private final String text;
		private final String name;
		private final List<String> actions;
		private final int itemId;
		private final int modelId;
		private final Rectangle bounds;

		private WidgetValue(
			int id,
			int index,
			int parentId,
			int type,
			String text,
			String name,
			List<String> actions,
			int itemId,
			int modelId,
			Rectangle bounds)
		{
			this.id = id;
			this.groupId = id >>> 16;
			this.componentId = id & 0xFFFF;
			this.index = index;
			this.parentId = parentId;
			this.type = type;
			this.text = text;
			this.name = name;
			this.actions = actions;
			this.itemId = itemId;
			this.modelId = modelId;
			this.bounds = new Rectangle(bounds);
		}

		private static WidgetValue capture(Widget widget, Rectangle bounds)
		{
			List<String> actions = new ArrayList<>();
			if (widget.getActions() != null)
			{
				for (String action : widget.getActions())
				{
					String clean = clean(action);
					if (!clean.isEmpty())
					{
						actions.add(clean);
					}
				}
			}
			return new WidgetValue(
				widget.getId(),
				widget.getIndex(),
				widget.getParentId(),
				widget.getType(),
				clean(widget.getText()),
				clean(widget.getName()),
				Collections.unmodifiableList(actions),
				widget.getItemId(),
				widget.getModelId(),
				bounds);
		}

		private int getId()
		{
			return id;
		}

		private int getIndex()
		{
			return index;
		}

		private Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("id", (long) id);
			value.put("group_id", (long) groupId);
			value.put("component_id", (long) componentId);
			value.put("index", (long) index);
			value.put("parent_id", (long) parentId);
			value.put("type", (long) type);
			value.put("text", text);
			value.put("name", name);
			value.put("actions", actions);
			value.put("item_id", itemId < 0 ? null : (long) itemId);
			value.put("model_id", modelId < 0 ? null : (long) modelId);
			Map<String, Object> rectangle = new LinkedHashMap<>();
			rectangle.put("x", (long) bounds.x);
			rectangle.put("y", (long) bounds.y);
			rectangle.put("width", (long) bounds.width);
			rectangle.put("height", (long) bounds.height);
			value.put("bounds", rectangle);
			return value;
		}

		private static String clean(String value)
		{
			return value == null ? "" : Text.removeTags(value).trim();
		}
	}
}
