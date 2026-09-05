package org.dreambot.api.methods.settings;

import com.genericclient.script.ScriptScope;
import com.genericclient.script.SnapshotData;
import java.util.List;
import java.util.Map;

public final class PlayerSettings
{
	private PlayerSettings() {}
	public static int getConfig(int id) { return read("varps", id); }
	public static int getBitValue(int id) { return read("varbits", id); }
	private static int read(String kind, int id)
	{
		Map<?, ?> values = SnapshotData.map(ScriptScope.current().read("vars", Map.of(kind, List.of(id))));
		if (!Boolean.TRUE.equals(values.get("available"))) throw new IllegalStateException("Player variables are unavailable");
		return ((Number) SnapshotData.map(values.get(kind)).get((long) id)).intValue();
	}
}
