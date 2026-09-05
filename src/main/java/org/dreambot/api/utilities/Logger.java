package org.dreambot.api.utilities;

import com.genericclient.script.ScriptScope;

public final class Logger
{
	private Logger() {}
	public static void log(Object message) { ScriptScope.current().log(message); }
	public static void info(Object message) { log(message); }
	public static void debug(Object message) { log(message); }
	public static void error(Object message) { log(message); }
	public static void warn(Object message) { log(message); }
}
