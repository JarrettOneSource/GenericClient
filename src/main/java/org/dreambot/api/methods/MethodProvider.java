package org.dreambot.api.methods;

import com.genericclient.script.ScriptScope;
import org.dreambot.api.utilities.Sleep;
import org.dreambot.api.utilities.impl.Condition;

public abstract class MethodProvider
{
	public static void log(Object message) { ScriptScope.current().log(message); }
	public static void sleep(long millis) { Sleep.sleep(millis); }
	public static void sleep(long minimum, long maximum) { Sleep.sleep(minimum, maximum); }
	public static boolean sleepUntil(Condition condition, long timeout) { return Sleep.sleepUntil(condition, timeout); }
	public static boolean sleepUntil(Condition condition, long timeout, long polling)
	{
		return Sleep.sleepUntil(condition, timeout, polling);
	}
	public static boolean sleepWhile(Condition condition, long timeout) { return Sleep.sleepWhile(condition, timeout); }
}
