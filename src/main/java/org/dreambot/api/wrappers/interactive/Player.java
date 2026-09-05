package org.dreambot.api.wrappers.interactive;

import com.genericclient.script.SnapshotData;
import java.util.Map;
import java.util.Arrays;

public class Player extends Character
{
	public Player(Map<?, ?> snapshot) { super(snapshot,"player"); }
	@Override public boolean hasAction(String... actions)
	{
		java.util.List<String> available = Arrays.asList(getActions());
		return Arrays.stream(actions).anyMatch(available::contains);
	}
	public int getHealthPercent()
	{
		Map<?, ?> player = data();
		if (player.containsKey("current_hitpoints"))
			return 100 * SnapshotData.integer(player,"current_hitpoints") / SnapshotData.integer(player,"max_hitpoints");
		int scale = SnapshotData.integer(player,"health_scale");
		return scale <= 0 ? 100 : 100 * SnapshotData.integer(player,"health_ratio") / scale;
	}
}
