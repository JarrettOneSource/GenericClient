package com.genericclient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import party.iroiro.luajava.JFunction;
import party.iroiro.luajava.Lua;
import party.iroiro.luajava.lua54.Lua54;

final class GenericClientLuaScript implements AutoCloseable
{
	private static final long RESUME_BUDGET_NANOS = 20_000_000L;
	private static final int HOOK_INSTRUCTION_INTERVAL = 1_000;
	private static final int DEFAULT_RANDOM_ACTION_TIMEOUT_TICKS = 8;
	private static final int DEFAULT_WALK_ACTION_TIMEOUT_TICKS = 600;

	private final GenericClientLuaHost host;
	private final String name;
	private final Lua lua;
	private Lua coroutine;
	private int hookInstallerReference;
	private int coroutineReference;
	private long deadlineNanos = Long.MAX_VALUE;
	private boolean budgetExceeded;
	private boolean activated;
	private boolean finished = true;
	private String terminalStatus = "COMPLETED";
	private String faultMessage;
	private Object returnValue;
	private GenericClientSnapshot pinnedSnapshot;
	private Wait wait;
	private long nextRequestId;

	GenericClientLuaScript(GenericClientLuaHost host, String name, String source)
	{
		this.host = host;
		this.name = name;
		this.lua = new Lua54();

		try
		{
			initializeRuntime();
			startSource(source);
		}
		catch (RuntimeException exception)
		{
			lua.close();
			throw exception;
		}
	}

	void activate()
	{
		activated = true;
		dispatchActionIfReady();
	}

	void onGameTick(GenericClientSnapshot snapshot)
	{
		pinnedSnapshot = snapshot;
		if (finished || wait == null)
		{
			return;
		}

		switch (wait.kind)
		{
			case GAME_TICK:
				resume(valueResponse(eventValue(snapshot)));
				break;
			case TICKS:
				wait.remainingTicks--;
				if (wait.remainingTicks == 0)
				{
					Map<String, Object> value = new LinkedHashMap<>();
					value.put("kind", "ticks");
					value.put("game_tick", snapshot.getGameTick());
					resume(valueResponse(value));
				}
				break;
			case ACTION:
				wait.remainingTicks--;
				if (wait.remainingTicks == 0)
				{
					Map<String, Object> receipt = new LinkedHashMap<>();
					receipt.put("status", "timed_out");
					receipt.put("result", "action did not complete before timeout");
					wait = null;
					resume(valueResponse(receipt));
				}
				break;
		}
	}

	void completeAction(long requestId, Map<String, Object> receipt, GenericClientSnapshot snapshot)
	{
		if (finished || wait == null || wait.kind != WaitKind.ACTION || wait.requestId != requestId)
		{
			return;
		}
		pinnedSnapshot = snapshot;
		resume(valueResponse(receipt));
	}

	boolean isFinished()
	{
		return finished;
	}

	String getTerminalStatus()
	{
		return terminalStatus;
	}

	String getFaultMessage()
	{
		return faultMessage;
	}

	Object getReturnValue()
	{
		return returnValue;
	}

	void pinSnapshot(GenericClientSnapshot snapshot)
	{
		pinnedSnapshot = snapshot;
	}

	void startSource(String source)
	{
		if (!finished)
		{
			throw new IllegalStateException("Lua execution is already running");
		}
		releaseCoroutine();
		finished = false;
		terminalStatus = "COMPLETED";
		faultMessage = null;
		returnValue = null;
		wait = null;
		nextRequestId = 0;

		try
		{
			beginBudget();
			lua.load(source);
			lua.pCall(0, 1);
			checkBudget();
			if (!lua.isFunction(-1))
			{
				throw new IllegalArgumentException("Lua script must return one root function");
			}
			int rootFunctionReference = lua.ref();

			coroutine = lua.newThread();
			coroutineReference = lua.ref();
			lua.refGet(rootFunctionReference);
			lua.xMove(coroutine, 1);
			lua.unref(rootFunctionReference);

			lua.refGet(hookInstallerReference);
			lua.refGet(coroutineReference);
			lua.pCall(1, 0);
			resume(null);
		}
		catch (RuntimeException exception)
		{
			finished = true;
			terminalStatus = "FAULTED";
			faultMessage = exception.getMessage();
			releaseCoroutine();
			throw exception;
		}
	}

	private void initializeRuntime()
	{
		for (String library : new String[]{"base", "coroutine", "string", "table", "math", "utf8", "debug"})
		{
			lua.openLibrary(library);
		}

		lua.push((JFunction) this::read);
		lua.setGlobal("__gc_read");
		lua.push((JFunction) this::writeLog);
		lua.setGlobal("__gc_log");
		lua.push((JFunction) this::budgetHook);
		lua.setGlobal("__gc_budget_hook");

		lua.run("debug.sethook(__gc_budget_hook, '', " + HOOK_INSTRUCTION_INTERVAL + ")");
		lua.load(
			"local sethook = debug.sethook\n" +
			"local hook = __gc_budget_hook\n" +
			"return function(thread) sethook(thread, hook, '', " + HOOK_INSTRUCTION_INTERVAL + ") end");
		lua.pCall(0, 1);
		hookInstallerReference = lua.ref();

		lua.run(
			"local host_read = __gc_read\n" +
			"local host_log = __gc_log\n" +
			"local host_yield = coroutine.yield\n" +
			"gc = {}\n" +
			"gc.read = host_read\n" +
			"gc.log = host_log\n" +
			"gc.await = function(request)\n" +
			"  local response = host_yield({ protocol = 'gc.await.v1', request = request })\n" +
			"  if response and response.host_error then error(response.host_error, 2) end\n" +
			"  return response and response.value or nil\n" +
			"end\n" +
			"java = nil\n" +
			"package = nil\n" +
			"io = nil\n" +
			"os = nil\n" +
			"debug = nil\n" +
			"coroutine = nil\n" +
			"load = nil\n" +
			"loadfile = nil\n" +
			"dofile = nil\n" +
			"collectgarbage = nil\n" +
			"__gc_read = nil\n" +
			"__gc_log = nil\n" +
			"__gc_budget_hook = nil");

	}

	private int read(Lua state)
	{
		try
		{
			String subject = state.toString(1);
			if (subject == null)
			{
				throw new IllegalArgumentException("gc.read requires a subject string");
			}
			Map<?, ?> query = state.getTop() >= 2 && state.isTable(2) ? state.toMap(2) : null;
			Object value = pinnedSnapshot == null ? null : pinnedSnapshot.read(subject, query);
			pushValue(state, value);
			return 1;
		}
		catch (RuntimeException exception)
		{
			state.push(exception.getMessage());
			return -1;
		}
	}

	private int writeLog(Lua state)
	{
		String level = state.toString(1);
		String event = state.toString(2);
		if (level == null || event == null)
		{
			state.push("gc.log requires level and event strings");
			return -1;
		}
		Object fields = state.getTop() >= 3 ? state.toObject(3) : null;
		host.scriptLog(name, level, event, normalizeLuaValue(fields));
		return 0;
	}

	private int budgetHook(Lua state)
	{
		if (System.nanoTime() <= deadlineNanos)
		{
			return 0;
		}
		budgetExceeded = true;
		state.push("Lua resume exceeded its execution budget");
		return -1;
	}

	private void resume(Map<String, Object> response)
	{
		if (finished)
		{
			return;
		}

		try
		{
			beginBudget();
			int arguments = 0;
			if (response != null)
			{
				coroutine.push(response);
				arguments = 1;
			}
			boolean yielded = coroutine.resume(arguments);
			checkBudget();
			wait = null;

			if (!yielded)
			{
				int returnCount = coroutine.getTop();
				returnValue = returnCount == 0 ? null : normalizeLuaValue(coroutine.toObject(-1));
				coroutine.pop(returnCount);
				finished = true;
				terminalStatus = "COMPLETED";
				releaseCoroutine();
				return;
			}

			Object yieldedValue = coroutine.toObject(-1);
			coroutine.pop(1);
			wait = parseWait(yieldedValue);
			dispatchActionIfReady();
		}
		catch (RuntimeException exception)
		{
			finished = true;
			terminalStatus = "FAULTED";
			faultMessage = exception.getMessage();
			host.scriptLog(name, "error", "script-fault", exception.getMessage());
			releaseCoroutine();
		}
	}

	private Wait parseWait(Object yieldedValue)
	{
		if (!(yieldedValue instanceof Map))
		{
			throw new IllegalArgumentException("Script yielded an invalid await request");
		}
		Map<?, ?> envelope = (Map<?, ?>) yieldedValue;
		if (!"gc.await.v1".equals(envelope.get("protocol")) || !(envelope.get("request") instanceof Map))
		{
			throw new IllegalArgumentException("Script yielded an invalid await envelope");
		}

		Map<?, ?> request = (Map<?, ?>) envelope.get("request");
		if (request.get("ticks") instanceof Number)
		{
			int ticks = ((Number) request.get("ticks")).intValue();
			if (ticks < 1)
			{
				throw new IllegalArgumentException("Tick wait must be positive");
			}
			return Wait.ticks(ticks);
		}

		if (request.get("event") instanceof String)
		{
			String event = (String) request.get("event");
			if (!"game.tick".equals(event))
			{
				throw new IllegalArgumentException("Unsupported event: " + event);
			}
			return Wait.gameTick();
		}

		if (request.get("action") instanceof Map)
		{
			Map<?, ?> action = (Map<?, ?>) request.get("action");
			Object typeValue = action.get("type");
			if (!(typeValue instanceof String))
			{
				throw new IllegalArgumentException("Action requires a type string");
			}
			String type = (String) typeValue;
			int timeout = "walk.to".equals(type)
				? DEFAULT_WALK_ACTION_TIMEOUT_TICKS
				: DEFAULT_RANDOM_ACTION_TIMEOUT_TICKS;
			if (request.get("timeout") instanceof Map &&
				((Map<?, ?>) request.get("timeout")).get("game_ticks") instanceof Number)
			{
				timeout = ((Number) ((Map<?, ?>) request.get("timeout")).get("game_ticks")).intValue();
			}
			if (timeout < 1)
			{
				throw new IllegalArgumentException("Action timeout must be positive");
			}

			if ("walk.random".equals(type))
			{
				return Wait.randomAction(++nextRequestId, timeout);
			}
			if ("walk.to".equals(type))
			{
				if (!(action.get("destination") instanceof Map))
				{
					throw new IllegalArgumentException("walk.to requires a destination table");
				}
				Map<?, ?> destination = (Map<?, ?>) action.get("destination");
				int x = requiredInt(destination, "x");
				int y = requiredInt(destination, "y");
				int plane = requiredInt(destination, "plane");
				if (x < 0 || x > 0x7FFF || y < 0 || y > 0x7FFF || plane < 0 || plane > 3)
				{
					throw new IllegalArgumentException("walk.to destination is outside world coordinate bounds");
				}
				int within = action.get("within") instanceof Number
					? ((Number) action.get("within")).intValue()
					: 1;
				if (within < 0 || within > 10)
				{
					throw new IllegalArgumentException("walk.to within must be between 0 and 10");
				}
				return Wait.walkAction(
					++nextRequestId,
					timeout,
					new WorldPoint(x, y, plane),
					within);
			}
			throw new IllegalArgumentException("Unsupported action: " + type);
		}

		throw new IllegalArgumentException("Await request must contain ticks, event, or action");
	}

	private void dispatchActionIfReady()
	{
		if (!activated || finished || wait == null || wait.kind != WaitKind.ACTION || wait.dispatched)
		{
			return;
		}
		wait.dispatched = true;
		if ("walk.random".equals(wait.actionType))
		{
			host.submitWalkRandom(this, wait.requestId);
		}
		else
		{
			host.submitWalkTo(this, wait.requestId, wait.destination, wait.within, wait.remainingTicks);
		}
	}

	private static int requiredInt(Map<?, ?> value, String key)
	{
		Object number = value.get(key);
		if (!(number instanceof Number))
		{
			throw new IllegalArgumentException("walk.to destination requires numeric " + key);
		}
		return ((Number) number).intValue();
	}

	private void beginBudget()
	{
		budgetExceeded = false;
		deadlineNanos = System.nanoTime() + RESUME_BUDGET_NANOS;
	}

	private void checkBudget()
	{
		deadlineNanos = Long.MAX_VALUE;
		if (budgetExceeded)
		{
			throw new IllegalStateException("Lua resume exceeded its execution budget");
		}
	}

	private static Map<String, Object> eventValue(GenericClientSnapshot snapshot)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "event");
		value.put("type", "game.tick");
		value.put("game_tick", snapshot.getGameTick());
		return value;
	}

	private static Map<String, Object> valueResponse(Object value)
	{
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("value", value);
		return response;
	}

	private static void pushValue(Lua state, Object value)
	{
		if (value == null)
		{
			state.pushNil();
		}
		else if (value instanceof Map)
		{
			state.push((Map<?, ?>) value);
		}
		else if (value instanceof Collection)
		{
			state.push((Collection<?>) value);
		}
		else if (value instanceof String)
		{
			state.push((String) value);
		}
		else if (value instanceof Boolean)
		{
			state.push((Boolean) value);
		}
		else if (value instanceof Number)
		{
			state.push((Number) value);
		}
		else
		{
			throw new IllegalArgumentException("Unsupported Lua value: " + value.getClass().getName());
		}
	}

	private static Object normalizeLuaValue(Object value)
	{
		if (value instanceof Collection)
		{
			List<Object> result = new ArrayList<>(((Collection<?>) value).size());
			for (Object item : (Collection<?>) value)
			{
				result.add(normalizeLuaValue(item));
			}
			return result;
		}
		if (!(value instanceof Map))
		{
			return value;
		}

		Map<?, ?> table = (Map<?, ?>) value;
		List<Object> array = new ArrayList<>(Collections.nCopies(table.size(), null));
		boolean sequential = !table.isEmpty();
		for (Map.Entry<?, ?> entry : table.entrySet())
		{
			if (!(entry.getKey() instanceof Number))
			{
				sequential = false;
				break;
			}
			double numericKey = ((Number) entry.getKey()).doubleValue();
			int index = (int) numericKey;
			if (numericKey != index || index < 1 || index > table.size() || array.get(index - 1) != null)
			{
				sequential = false;
				break;
			}
			array.set(index - 1, normalizeLuaValue(entry.getValue()));
		}
		if (sequential && !array.contains(null))
		{
			return array;
		}

		Map<Object, Object> result = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : table.entrySet())
		{
			result.put(entry.getKey(), normalizeLuaValue(entry.getValue()));
		}
		return result;
	}

	@Override
	public void close()
	{
		finished = true;
		releaseCoroutine();
		if (hookInstallerReference != 0)
		{
			lua.unref(hookInstallerReference);
			hookInstallerReference = 0;
		}
		lua.close();
	}

	private void releaseCoroutine()
	{
		if (coroutineReference != 0)
		{
			lua.unref(coroutineReference);
			coroutineReference = 0;
		}
		coroutine = null;
	}

	private enum WaitKind
	{
		GAME_TICK,
		TICKS,
		ACTION
	}

	private static final class Wait
	{
		private final WaitKind kind;
		private final long requestId;
		private final String actionType;
		private final WorldPoint destination;
		private final int within;
		private int remainingTicks;
		private boolean dispatched;

		private Wait(
			WaitKind kind,
			long requestId,
			int remainingTicks,
			String actionType,
			WorldPoint destination,
			int within)
		{
			this.kind = kind;
			this.requestId = requestId;
			this.remainingTicks = remainingTicks;
			this.actionType = actionType;
			this.destination = destination;
			this.within = within;
		}

		private static Wait gameTick()
		{
			return new Wait(WaitKind.GAME_TICK, 0, 0, null, null, 0);
		}

		private static Wait ticks(int ticks)
		{
			return new Wait(WaitKind.TICKS, 0, ticks, null, null, 0);
		}

		private static Wait randomAction(long requestId, int timeoutTicks)
		{
			return new Wait(WaitKind.ACTION, requestId, timeoutTicks, "walk.random", null, 0);
		}

		private static Wait walkAction(
			long requestId,
			int timeoutTicks,
			WorldPoint destination,
			int within)
		{
			return new Wait(WaitKind.ACTION, requestId, timeoutTicks, "walk.to", destination, within);
		}
	}
}
