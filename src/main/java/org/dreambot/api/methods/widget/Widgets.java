package org.dreambot.api.methods.widget;

import com.genericclient.script.SnapshotData;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.dreambot.api.wrappers.widgets.WidgetChild;

public final class Widgets
{
	private Widgets() {}
	public static WidgetChild get(int... ids)
	{
		if (ids.length != 2 && ids.length != 3) throw new IllegalArgumentException("Widget requires a group, child, and optional subchild");
		WidgetChild widget = new WidgetChild((ids[0] << 16) | ids[1], ids.length == 3 ? ids[2] : -1);
		return widget.exists() ? widget : null;
	}
	public static boolean isVisible(int... ids)
	{
		WidgetChild widget = get(ids);
		return widget != null && widget.isVisible();
	}
	public static WidgetChild getWidgetChild(int group, int child) { return get(group,child); }
	public static WidgetChild getWidgetChild(int group, int child, int subchild) { return get(group,child,subchild); }
	public static Widget getWidget(int group)
	{
		Widget widget = new Widget(group);
		return widget.getChildren().isEmpty() ? null : widget;
	}
	public static List<WidgetChild> getAllContainingText(String text)
	{
		return SnapshotData.rows("widgets",Map.of("limit",Integer.MAX_VALUE,"include_hidden",true)).stream()
			.filter(row -> ((String)row.get("text")).contains(text))
			.map(row -> new WidgetChild(SnapshotData.integer(row,"id"),SnapshotData.integer(row,"index"))).collect(Collectors.toList());
	}
	public static boolean closeAll() { return SnapshotData.action("ui.close",Map.of()); }
}
