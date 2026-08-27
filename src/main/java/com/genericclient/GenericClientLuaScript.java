package com.genericclient;

import java.util.ArrayDeque;
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
	private String currentPhase;
	private List<GenericClientScriptInput> inputs = Collections.emptyList();
	private List<GenericClientScriptAction> actions = Collections.emptyList();
	private Map<String, Object> resolvedInputs = Collections.emptyMap();
	private volatile List<GenericClientOverlayRow> overlayRows = Collections.emptyList();
	private final ArrayDeque<String> pendingActions = new ArrayDeque<>();

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

	void activate(Map<String, Object> suppliedInputs)
	{
		if (activated)
		{
			throw new IllegalStateException("Lua script is already activated");
		}
		activated = true;
		resolvedInputs = GenericClientScriptInput.resolve(inputs, suppliedInputs);
		resume(resolvedInputs);
	}

	List<GenericClientScriptInput> getInputs()
	{
		return inputs;
	}

	Map<String, Object> getResolvedInputs()
	{
		return resolvedInputs;
	}

	List<GenericClientScriptAction> getActions()
	{
		return actions;
	}

	List<GenericClientOverlayRow> getOverlayRows()
	{
		return overlayRows;
	}

	String queueAction(String actionId)
	{
		if (finished)
		{
			throw new IllegalStateException("Lua script is not running");
		}
		boolean declared = false;
		for (GenericClientScriptAction action : actions)
		{
			if (action.getId().equals(actionId))
			{
				declared = true;
				break;
			}
		}
		if (!declared)
		{
			throw new IllegalArgumentException("Unknown active script action: " + actionId);
		}
		if (pendingActions.contains(actionId))
		{
			return "already_queued";
		}
		if (pendingActions.size() >= 8)
		{
			throw new IllegalStateException("Active script action queue is full");
		}
		pendingActions.addLast(actionId);
		return "queued";
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
				if (host.isBehaviorPaused())
				{
					break;
				}
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
			case PHASE:
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

	void completePhase(long requestId, Map<String, Object> receipt, GenericClientSnapshot snapshot)
	{
		if (finished || wait == null || wait.kind != WaitKind.PHASE || wait.requestId != requestId)
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
		currentPhase = null;
		activated = false;
		inputs = Collections.emptyList();
		actions = Collections.emptyList();
		resolvedInputs = Collections.emptyMap();
		overlayRows = Collections.emptyList();
		pendingActions.clear();

		try
		{
			beginBudget();
			lua.load(source);
			lua.pCall(0, 1);
			checkBudget();
			if (!lua.isTable(-1))
			{
				throw new IllegalArgumentException("Lua script must return one descriptor table");
			}
			lua.getField(-1, "inputs");
			Object rawInputs = lua.isNil(-1) ? null : normalizeLuaValue(lua.toObject(-1));
			lua.pop(1);
			inputs = GenericClientScriptInput.parse(rawInputs);
			lua.getField(-1, "actions");
			Object rawActions = lua.isNil(-1) ? null : normalizeLuaValue(lua.toObject(-1));
			lua.pop(1);
			actions = GenericClientScriptAction.parse(rawActions);

			lua.getField(-1, "run");
			if (!lua.isFunction(-1))
			{
				throw new IllegalArgumentException("Lua script descriptor requires a run function");
			}
			int rootFunctionReference = lua.ref();
			lua.pop(1);

			coroutine = lua.newThread();
			coroutineReference = lua.ref();
			lua.refGet(rootFunctionReference);
			lua.xMove(coroutine, 1);
			lua.unref(rootFunctionReference);

			lua.refGet(hookInstallerReference);
			lua.refGet(coroutineReference);
			lua.pCall(1, 0);
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
		lua.push((JFunction) this::writeOverlay);
		lua.setGlobal("__gc_overlay");
		lua.push((JFunction) this::nextAction);
		lua.setGlobal("__gc_next_action");
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
			"local host_overlay = __gc_overlay\n" +
			"local host_next_action = __gc_next_action\n" +
			"local host_yield = coroutine.yield\n" +
			"gc = {}\n" +
			"gc.read = host_read\n" +
			"gc.log = host_log\n" +
			"gc.overlay = host_overlay\n" +
			"gc.next_action = host_next_action\n" +
			"gc.await = function(request)\n" +
			"  local response = host_yield({ protocol = 'gc.await.v1', request = request })\n" +
			"  if response and response.host_error then error(response.host_error, 2) end\n" +
			"  return response and response.value or nil\n" +
			"end\n" +
			"gc.phase = function(name, options)\n" +
			"  local request = { phase = name }\n" +
			"  if options and options.breaks ~= nil then request.breaks = options.breaks end\n" +
			"  return gc.await(request)\n" +
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
			"__gc_overlay = nil\n" +
			"__gc_next_action = nil\n" +
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
			Object value = host.readSnapshot(pinnedSnapshot, subject, query);
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

	private int writeOverlay(Lua state)
	{
		try
		{
			Object value = null;
			if (state.getTop() >= 1 && !state.isNil(1))
			{
				if (!state.isTable(1))
				{
					throw new IllegalArgumentException("gc.overlay requires a row array or nil");
				}
				value = normalizeLuaValue(state.toObject(1));
			}
			overlayRows = GenericClientOverlayRow.parse(value);
			return 0;
		}
		catch (RuntimeException exception)
		{
			state.push(exception.getMessage());
			return -1;
		}
	}

	private int nextAction(Lua state)
	{
		String action = pendingActions.pollFirst();
		if (action == null)
		{
			state.pushNil();
		}
		else
		{
			state.push(action);
		}
		return 1;
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
			dispatchWaitIfReady();
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
		if (request.containsKey("breaks") && !(request.get("breaks") instanceof Boolean))
		{
			throw new IllegalArgumentException("breaks must be true or false");
		}
		boolean breaksEnabled = !(request.get("breaks") instanceof Boolean) ||
			(Boolean) request.get("breaks");
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
				return Wait.randomAction(++nextRequestId, timeout, breaksEnabled);
			}
			if ("mouse.offscreen".equals(type))
			{
				return Wait.mouseOffscreen(++nextRequestId, timeout);
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
					within,
					breaksEnabled);
			}
			throw new IllegalArgumentException("Unsupported action: " + type);
		}

		if (request.get("phase") instanceof String)
		{
			String phase = ((String) request.get("phase")).trim();
			if (!phase.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"))
			{
				throw new IllegalArgumentException(
					"Phase name must be 1-64 letters, numbers, dots, underscores, or hyphens");
			}
			return Wait.phase(++nextRequestId, phase, breaksEnabled);
		}

		throw new IllegalArgumentException("Await request must contain ticks, event, action, or phase");
	}

	private void dispatchWaitIfReady()
	{
		if (!activated || finished || wait == null || wait.dispatched)
		{
			return;
		}
		if (wait.kind != WaitKind.ACTION && wait.kind != WaitKind.PHASE)
		{
			return;
		}
		wait.dispatched = true;
		if (wait.kind == WaitKind.PHASE)
		{
			if (wait.phaseName.equals(currentPhase))
			{
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "unchanged");
				receipt.put("phase", currentPhase);
				completePhase(wait.requestId, receipt, pinnedSnapshot);
				return;
			}
			currentPhase = wait.phaseName;
			host.submitPhase(this, wait.requestId, wait.phaseName, wait.breaksEnabled);
		}
		else if ("walk.random".equals(wait.actionType))
		{
			host.submitWalkRandom(this, wait.requestId, wait.breaksEnabled);
		}
		else if ("mouse.offscreen".equals(wait.actionType))
		{
			host.submitMouseOffscreen(this, wait.requestId);
		}
		else
		{
			host.submitWalkTo(
				this,
				wait.requestId,
				wait.destination,
				wait.within,
				wait.remainingTicks,
				wait.breaksEnabled);
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

	static Object normalizeLuaValue(Object value)
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
		ACTION,
		PHASE
	}

	private static final class Wait
	{
		private final WaitKind kind;
		private final long requestId;
		private final String actionType;
		private final WorldPoint destination;
		private final int within;
		private final boolean breaksEnabled;
		private final String phaseName;
		private int remainingTicks;
		private boolean dispatched;

		private Wait(
			WaitKind kind,
			long requestId,
			int remainingTicks,
			String actionType,
			WorldPoint destination,
			int within,
			boolean breaksEnabled,
			String phaseName)
		{
			this.kind = kind;
			this.requestId = requestId;
			this.remainingTicks = remainingTicks;
			this.actionType = actionType;
			this.destination = destination;
			this.within = within;
			this.breaksEnabled = breaksEnabled;
			this.phaseName = phaseName;
		}

		private static Wait gameTick()
		{
			return new Wait(WaitKind.GAME_TICK, 0, 0, null, null, 0, true, null);
		}

		private static Wait ticks(int ticks)
		{
			return new Wait(WaitKind.TICKS, 0, ticks, null, null, 0, true, null);
		}

		private static Wait randomAction(long requestId, int timeoutTicks, boolean breaksEnabled)
		{
			return new Wait(
				WaitKind.ACTION, requestId, timeoutTicks, "walk.random", null, 0, breaksEnabled, null);
		}

		private static Wait walkAction(
			long requestId,
			int timeoutTicks,
			WorldPoint destination,
			int within,
			boolean breaksEnabled)
		{
			return new Wait(
				WaitKind.ACTION, requestId, timeoutTicks, "walk.to", destination, within, breaksEnabled, null);
		}

		private static Wait mouseOffscreen(long requestId, int timeoutTicks)
		{
			return new Wait(
				WaitKind.ACTION, requestId, timeoutTicks, "mouse.offscreen", null, 0, false, null);
		}

		private static Wait phase(long requestId, String name, boolean breaksEnabled)
		{
			return new Wait(WaitKind.PHASE, requestId, 0, null, null, 0, breaksEnabled, name);
		}
	}
}
