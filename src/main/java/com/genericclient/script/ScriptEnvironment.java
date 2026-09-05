package com.genericclient.script;

import java.util.Map;

/** Host contract shared by the SDK and one script worker. */
public interface ScriptEnvironment
{
	Object read(String subject, Map<String, Object> query);
	Map<String, Object> execute(String action, Map<String, Object> arguments, long timeoutMillis);
	void sleep(long millis);
	void sleepTicks(int ticks, Map<String, Object> options);
	long tick();
	long activeTimeNanos();
	void checkpoint();
	void stop();
	boolean isRunning();
	boolean isPaused();
	void log(Object message);
	Map<String, Object> inputs();
	String nextAction();
	Map<String, Object> phase(String name, Map<String, Object> options);
	void activity(String name, Map<String, Object> policy);
	<T> T intent(String name, java.util.function.Supplier<T> body);
	void result(Object value);
	void overlay(Map<String, String> rows);
	void markers(java.util.List<Map<String, Object>> markers);
}
