package com.genericclient;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.client.plugins.puzzlesolver.solver.PuzzleState;
import net.runelite.client.plugins.puzzlesolver.solver.heuristics.ManhattanDistance;
import net.runelite.client.plugins.puzzlesolver.solver.pathfinding.IDAStarMM;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

final class GenericClientWidgetSnapshot
{
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
		List<WidgetValue> values = new ArrayList<>();
		for (Widget widget : GenericClientWidgets.visible(client))
			values.add(new WidgetValue(widget, widget.getBounds()));
		values.sort(Comparator.comparingInt(WidgetValue::getId)
			.thenComparingInt(WidgetValue::getIndex));
		return new GenericClientWidgetSnapshot(values);
	}

	boolean contains(int id, String label)
	{
		for (WidgetValue widget : widgets)
			if (label == null ? widget.id == id : (widget.id == id || widget.ancestors.contains(id)) &&
				GenericClientWidgets.matchesLabel(label, widget.text, widget.name, widget.actions)) return true;
		return false;
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

	Map<String, Object> readSlidingPuzzle()
	{
		Map<Rectangle, Integer> positions = new LinkedHashMap<>();
		Map<Integer, Integer> modelAtPosition = new LinkedHashMap<>();
		Set<Integer> modelIds = new LinkedHashSet<>();
		int widgetId = -1;
		for (WidgetValue widget : widgets)
		{
			if (widget.groupId == InterfaceID.TRAIL_SLIDEPUZZLE && "Sliding piece".equals(widget.name) &&
				widget.index >= 0 && widget.index < 25)
			{
				positions.put(widget.bounds, widget.index);
				widgetId = widget.id;
			}
		}
		for (WidgetValue widget : widgets)
		{
			Integer position = positions.get(widget.bounds);
			if (widget.groupId == InterfaceID.TRAIL_SLIDEPUZZLE && widget.type == 6 &&
				widget.modelId >= 0 && position != null)
			{
				modelAtPosition.put(position, widget.modelId);
				modelIds.add(widget.modelId);
			}
		}

		Map<String, Object> value = new LinkedHashMap<>();
		if (positions.size() != 24 || modelAtPosition.size() != 24 || modelIds.size() != 24)
		{
			value.put("available", false);
			return value;
		}
		List<Integer> sortedModels = new ArrayList<>(modelIds);
		Collections.sort(sortedModels);
		Map<Integer, Integer> ranks = new LinkedHashMap<>();
		for (int index = 0; index < sortedModels.size(); index++)
		{
			ranks.put(sortedModels.get(index), index);
		}
		int[] board = new int[25];
		Arrays.fill(board, -1);
		int blank = -1;
		List<Long> boardValue = new ArrayList<>();
		for (int position = 0; position < board.length; position++)
		{
			Integer modelId = modelAtPosition.get(position);
			if (modelId == null)
			{
				blank = position;
			}
			else
			{
				board[position] = ranks.get(modelId);
			}
			boardValue.add((long) board[position]);
		}

		List<PuzzleState> path = new IDAStarMM(new ManhattanDistance())
			.computePath(new PuzzleState(board));
		List<Long> moves = new ArrayList<>();
		for (int index = 1; index < path.size(); index++)
		{
			moves.add((long) path.get(index).getEmptyPiece());
		}
		value.put("available", true);
		value.put("widget_id", (long) widgetId);
		value.put("blank", (long) blank);
		value.put("board", boardValue);
		value.put("moves", moves);
		value.put("move_count", (long) moves.size());
		return value;
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
		private final Set<Integer> ancestors;
		private final int type;
		private final String text;
		private final String name;
		private final List<String> actions;
		private final int itemId;
		private final int modelId;
		private final int spriteId;
		private final Rectangle bounds;

		private WidgetValue(Widget widget, Rectangle bounds)
		{
			this.id = widget.getId();
			this.groupId = id >>> 16;
			this.componentId = id & 0xFFFF;
			this.index = widget.getIndex();
			this.parentId = widget.getParentId();
			this.type = widget.getType();
			this.text = clean(widget.getText());
			this.name = clean(widget.getName());
			String[] rawActions = widget.getActions();
			this.actions = rawActions == null ? List.of() : Arrays.stream(rawActions)
				.map(WidgetValue::clean).filter(action -> !action.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableList());
			this.itemId = widget.getItemId();
			this.modelId = widget.getModelId();
			this.spriteId = widget.getSpriteId();
			this.bounds = new Rectangle(bounds);
			Set<Integer> parents = new LinkedHashSet<>();
			for (Widget parent = widget.getParent(); parent != null; parent = parent.getParent()) parents.add(parent.getId());
			this.ancestors = Set.copyOf(parents);
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
			value.put("sprite_id", spriteId < 0 ? null : (long) spriteId);
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
