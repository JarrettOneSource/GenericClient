package org.dreambot.api.wrappers.widgets;

import com.genericclient.script.SnapshotData;
import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WidgetChild
{
	private final int id;
	private final int index;
	public WidgetChild(int id, int index) { this.id = id; this.index = index; }
	public int getID() { return getChildId(); }
	public int getRawId() { return id; }
	public int getWidgetId() { return id >>> 16; }
	public int getGrandChildId() { return index; }
	public int getIndex() { return index; }
	public int getParentID() { return id >>> 16; }
	public int getChildId() { return id & 0xffff; }
	private Map<?,?> current()
	{
		return SnapshotData.rows("widgets",Map.of("ids",List.of(id),"limit",Integer.MAX_VALUE,"include_hidden",true)).stream()
			.filter(row -> ((Number)row.get("index")).intValue() == index).findFirst().orElse(null);
	}
	private Map<?,?> present()
	{
		Map<?,?> row = current();
		if (row == null) throw new IllegalStateException("Widget is not available: " + id + ":" + index);
		return row;
	}
	public boolean exists() { return current() != null; }
	public boolean isVisible()
	{
		Map<?,?> row = current();
		return row != null && Boolean.TRUE.equals(row.get("visible"));
	}
	public String getText() { return (String) present().get("text"); }
	public String getName() { return (String) present().get("name"); }
	public int getType() { return SnapshotData.integer(present(),"type"); }
	public String[] getActions() { return SnapshotData.strings(present().get("actions")); }
	public int getModelId() { Number model = (Number)present().get("model_id"); return model == null ? -1 : model.intValue(); }
	public int getItemId() { Number item = (Number)present().get("item_id"); return item == null ? -1 : item.intValue(); }
	public Rectangle getRectangle()
	{
		Map<?,?> bounds = SnapshotData.map(present().get("bounds"));
		return new Rectangle(SnapshotData.integer(bounds,"x"),SnapshotData.integer(bounds,"y"),
			SnapshotData.integer(bounds,"width"),SnapshotData.integer(bounds,"height"));
	}
	public WidgetChild getChild(int child)
	{
		WidgetChild widget = new WidgetChild(id,child);
		return widget.exists() ? widget : null;
	}
	public boolean interact() { return interact(null); }
	public boolean interact(String action)
	{
		if (!isVisible()) return false;
		Map<String,Object> parameters = new LinkedHashMap<>();
		parameters.put("widget_id",id);
		if (index >= 0) parameters.put("widget_index",index);
		if (action != null) parameters.put("action",action);
		return SnapshotData.action("ui.click",parameters);
	}
}
