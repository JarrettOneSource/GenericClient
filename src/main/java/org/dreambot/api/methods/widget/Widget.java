package org.dreambot.api.methods.widget;

import com.genericclient.script.SnapshotData;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.dreambot.api.wrappers.widgets.WidgetChild;

public class Widget
{
	private final int id;
	public Widget(int id) { this.id = id; }
	public WidgetChild getChild(int child) { return Widgets.get(id,child); }
	public boolean isVisible() { return !SnapshotData.rows("widgets",Map.of("group",id,"limit",1)).isEmpty(); }
	public List<WidgetChild> getChildren()
	{
		return SnapshotData.rows("widgets",Map.of("group",id,"limit",Integer.MAX_VALUE,"include_hidden",true)).stream()
			.filter(row -> SnapshotData.integer(row,"index") == -1)
			.map(row -> new WidgetChild(SnapshotData.integer(row,"id"),-1)).collect(Collectors.toList());
	}
}
