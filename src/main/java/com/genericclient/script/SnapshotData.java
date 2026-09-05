package com.genericclient.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Converts the engine's snapshot wire values at the SDK seam. */
public final class SnapshotData
{
	private SnapshotData() {}

	public static Map<?, ?> read(String subject)
	{
		return map(ScriptScope.current().read(subject, Collections.emptyMap()));
	}
	public static Map<?, ?> map(Object value)
	{
		return value == null ? Collections.emptyMap() : (Map<?, ?>) value;
	}
	public static List<Map<?, ?>> rows(String subject, Map<String, Object> query)
	{
		Object value = ScriptScope.current().read(subject, query);
		if (value == null) return Collections.emptyList();
		if (value instanceof Map) value = ((Map<?, ?>) value).get("items");
		List<Map<?, ?>> result = new ArrayList<>();
		for (Object row : (List<?>) value) result.add((Map<?, ?>) row);
		return result;
	}
	public static int integer(Map<?, ?> value, String key)
	{
		return ((Number) value.get(key)).intValue();
	}
	public static boolean action(String type, Map<String, Object> arguments)
	{
		String status = String.valueOf(ScriptScope.current().execute(type, arguments, 120_000).get("status"));
		return status.equals("dispatched") || status.equals("set") || status.equals("unchanged") ||
			status.equals("complete") || status.equals("completed") || status.equals("arrived") || status.equals("cast");
	}
	public static String[] strings(Object value)
	{
		return ((List<?>) value).stream().map(String.class::cast).toArray(String[]::new);
	}
}
