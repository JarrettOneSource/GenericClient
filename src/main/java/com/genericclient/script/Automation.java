package com.genericclient.script;

import java.util.Map;

/** GenericClient controls used by the maintained catalog alongside DreamBot methods. */
public final class Automation
{
	private Automation() {}
	public static String input(String id) { return (String) ScriptScope.current().inputs().get(id); }
	public static String nextAction() { return ScriptScope.current().nextAction(); }
	public static Map<String, Object> phase(String phase) { return phase(phase, Map.of()); }
	public static Map<String, Object> phase(String phase, Map<String, Object> options) { return ScriptScope.current().phase(phase, options); }
	public static void activity(String activity) { activity(activity, Map.of()); }
	public static void activity(String activity, Map<String, Object> policy) { ScriptScope.current().activity(activity, policy); }
	public static void sleepTicks(int ticks, Map<String, Object> options) { ScriptScope.current().sleepTicks(ticks, options); }
	/** Group nested operations under one behavior boundary on the script worker. */
	public static <T> T intent(String name, java.util.function.Supplier<T> body) { return ScriptScope.current().intent(name, body); }
	public static void finish(Object result) { ScriptScope.current().result(result); }
	public static void overlay(Map<String, String> rows) { ScriptScope.current().overlay(rows); }
	public static void markers(java.util.List<Map<String, Object>> markers) { ScriptScope.current().markers(markers); }
	public static Long checkpoint(String key)
	{
		Map<String, Object> result = ScriptScope.current().execute("checkpoint.get", Map.of("key", key), 5000);
		return Boolean.TRUE.equals(result.get("present")) ? ((Number) result.get("value")).longValue() : null;
	}
	public static void checkpoint(String key, long value)
	{
		ScriptScope.current().execute("checkpoint.set", Map.of("key", key, "value", value), 5000);
	}
	public static void clearCheckpoint(String key)
	{
		ScriptScope.current().execute("checkpoint.clear", Map.of("key", key), 5000);
	}
}
