package org.dreambot.api.utilities;

import com.genericclient.script.ScriptScope;
import java.util.concurrent.ThreadLocalRandom;
import org.dreambot.api.utilities.impl.Condition;

public final class Sleep
{
	private static volatile long defaultPoll = 50;

	private Sleep() {}

	public static void sleep(long millis)
	{
		if (millis < 0) throw new IllegalArgumentException("Sleep cannot be negative");
		ScriptScope.current().sleep(millis);
	}

	public static void sleep(long minimum, long maximum)
	{
		sleep(ThreadLocalRandom.current().nextLong(minimum, maximum));
	}

	public static void setDefaultPoll(long polling)
	{
		if (polling <= 0) throw new IllegalArgumentException("Polling must be positive");
		defaultPoll = polling;
	}

	public static boolean sleepUntil(Condition condition, long timeout)
	{
		return sleepUntil(condition, timeout, defaultPoll);
	}

	public static boolean sleepUntil(Condition condition, long timeout, long polling)
	{
		return sleepUntil(condition, () -> false, timeout, polling, 1);
	}

	public static boolean sleepUntil(Condition condition, Condition reset, long timeout, long polling)
	{
		return sleepUntil(condition, reset, timeout, polling, 1);
	}

	public static boolean sleepUntil(Condition condition, Condition reset, long timeout,
		long polling, int successfulPolls)
	{
		if (timeout < 0 || polling <= 0 || successfulPolls <= 0)
			throw new IllegalArgumentException("Invalid wait limits");
		long deadline = ScriptScope.current().activeTimeNanos() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout);
		int consecutive = 0;
		while (true)
		{
			ScriptScope.current().checkpoint();
			consecutive = condition.verify() ? consecutive + 1 : 0;
			if (consecutive >= successfulPolls) return true;
			if (reset.verify())
				deadline = ScriptScope.current().activeTimeNanos() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout);
			long remaining = deadline - ScriptScope.current().activeTimeNanos();
			if (remaining <= 0) return false;
			sleep(Math.min(polling, Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));
		}
	}

	public static boolean sleepWhile(Condition condition, long timeout)
	{
		return sleepWhile(condition, timeout, defaultPoll);
	}

	public static boolean sleepWhile(Condition condition, long timeout, long polling)
	{
		return sleepUntil(() -> !condition.verify(), timeout, polling);
	}

	public static void sleepTicks(int ticks)
	{
		if (ticks < 0) throw new IllegalArgumentException("Ticks cannot be negative");
		ScriptScope.current().sleepTicks(ticks, java.util.Map.of());
	}
}
