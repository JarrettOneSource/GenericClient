package com.genericclient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import party.iroiro.luajava.JFunction;
import party.iroiro.luajava.Lua;
import party.iroiro.luajava.lua54.Lua54;

final class GenericClientLuaScript implements AutoCloseable
{
	private static final long RESUME_BUDGET_NANOS = 20_000_000L;
	private static final long SOURCE_LOAD_BUDGET_NANOS = 100_000_000L;
	private static final int HOOK_INSTRUCTION_INTERVAL = 1_000;

	private final GenericClientLuaHost host;
	private final String name;
	private final Lua lua;
	private Lua coroutine;
	private int hookInstallerReference;
	private int coroutineReference;
	private long deadlineNanos = Long.MAX_VALUE;
	private boolean budgetExceeded;
	private boolean activated;
	private long startedNanos;
	private boolean finished = true;
	private String terminalStatus = "COMPLETED";
	private String faultMessage;
	private Object returnValue;
	private GenericClientSnapshot pinnedSnapshot;
	private volatile GenericClientLuaAwait wait;
	private final GenericClientLuaAwait.Parser awaitParser = new GenericClientLuaAwait.Parser();
	final GenericClientLuaIntent intents;
	private String currentPhase;
	private volatile GenericClientActivityContext currentActivity;
	private List<GenericClientScriptInput> inputs = Collections.emptyList();
	private List<GenericClientScriptAction> actions = Collections.emptyList();
	private Map<String, Object> resolvedInputs = Collections.emptyMap();
	private volatile List<GenericClientOverlayRow> overlayRows = Collections.emptyList();
	private volatile List<GenericClientSceneMarker> sceneMarkers = Collections.emptyList();
	private final ArrayDeque<String> pendingActions = new ArrayDeque<>();

	GenericClientLuaScript(GenericClientLuaHost host, String name, String source)
	{
		this.host = host;
		this.name = name;
		this.lua = new Lua54();
		this.intents = new GenericClientLuaIntent(host::nowNanos,
			(level, message) -> host.scriptLog(name, level, "intent", message));

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
		startedNanos = host.nowNanos();
		resolvedInputs = GenericClientScriptInput.resolve(inputs, suppliedInputs);
		resume(resolvedInputs);
	}

	long getStartedNanos()
	{
		return startedNanos;
	}

	long getRuntimeMillis(long nowNanos)
	{
		if (!activated)
		{
			return 0L;
		}
		return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
			Math.max(0L, nowNanos - startedNanos));
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

	List<GenericClientSceneMarker> getSceneMarkers()
	{
		return sceneMarkers;
	}

	String getName()
	{
		return name;
	}

	String getActivity()
	{
		return getActivityContext().getActivity().getValue();
	}

	long quietMillis(GenericClientActionBoundary.Ticket walkOwner, long walkQuietMillis)
	{
		GenericClientLuaAwait current = wait;
		if (current != null && "walk.to".equals(current.actionType) && current.ticket == walkOwner)
			return walkQuietMillis;
		return current != null && current.kind == GenericClientLuaAwait.Kind.TICKS
			? Math.max(0, current.remainingTicks - 1L) * 600 : 0;
	}

	GenericClientActivityContext getActivityContext()
	{
		GenericClientLuaAwait current = wait;
		if (current != null)
		{
			return intents.context(current.activityContext).withTicket(current.ticket);
		}
		GenericClientActivityContext declared = currentActivity;
		if (declared == null) declared = GenericClientActivityContext.preset(GenericClientActivityContext.Activity.GENERAL);
		return intents.context(host.isOperator(this) ? declared.plain() : declared);
	}

	String getScriptState()
	{
		return currentPhase == null ? "starting" : currentPhase;
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
		intents.onGameTick();
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
						wait.ticket.cancel();
						host.cancelTimedOutAction(this, wait.requestId, wait.actionType);
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
		if (finished || wait == null || wait.kind != GenericClientLuaAwait.Kind.ACTION || wait.requestId != requestId)
		{
			return;
		}
		pinnedSnapshot = snapshot;
		resume(valueResponse(receipt));
	}

	Long pendingActionRequestId()
	{
		return finished || wait == null || wait.kind != GenericClientLuaAwait.Kind.ACTION || !wait.dispatched
			? null
			: wait.requestId;
	}

	GenericClientActionBoundary.Ticket actionTicket(long requestId)
	{
		GenericClientLuaAwait current = wait;
		if (finished || current == null || current.requestId != requestId)
		{
			throw new IllegalStateException("Action no longer belongs to this coroutine");
		}
		return current.ticket;
	}

	boolean isCurrentAction(long requestId, GenericClientActionBoundary.Ticket ticket)
	{
		GenericClientLuaAwait current = wait;
		return !finished && current != null && current.kind == GenericClientLuaAwait.Kind.ACTION &&
			current.requestId == requestId && current.ticket == ticket;
	}

	void suspendActionInput(boolean suspended)
	{
		intents.suspendInput(suspended);
		GenericClientLuaAwait current = wait;
		if (current != null) current.ticket.suspendInput(suspended);
	}

	boolean retryAction(long requestId, GenericClientSnapshot snapshot)
	{
		if (finished || wait == null || wait.kind != GenericClientLuaAwait.Kind.ACTION ||
			wait.requestId != requestId || !wait.dispatched)
		{
			return false;
		}
		pinnedSnapshot = snapshot;
		wait.ticket.cancel();
		wait.ticket = intents.newActionTicket();
		wait.dispatched = false;
		dispatchWaitIfReady();
		return true;
	}

	void completePhase(long requestId, Map<String, Object> receipt, GenericClientSnapshot snapshot)
	{
		if (finished || wait == null || wait.kind != GenericClientLuaAwait.Kind.PHASE || wait.requestId != requestId)
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
		currentPhase = null;
		currentActivity = null;
		activated = false;
		startedNanos = 0L;
		inputs = Collections.emptyList();
		actions = Collections.emptyList();
		resolvedInputs = Collections.emptyMap();
		overlayRows = Collections.emptyList();
		pendingActions.clear();

		try
		{
			beginBudget(SOURCE_LOAD_BUDGET_NANOS);
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
		lua.push((JFunction) this::activity);
		lua.setGlobal("__gc_activity");
		lua.push((JFunction) this::scriptState);
		lua.setGlobal("__gc_state");
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
			"local host_activity = __gc_activity\n" +
			"local host_state = __gc_state\n" +
			"local host_yield = coroutine.yield\n" +
			"gc = {}\n" +
			"gc.read = host_read\n" +
			"gc.log = host_log\n" +
			"gc.overlay = host_overlay\n" +
			"gc.next_action = host_next_action\n" +
			"gc.activity = host_activity\n" +
			"gc.state = host_state\n" +
			"gc.await = function(request)\n" +
			"  local response = host_yield({ protocol = 'gc.await.v1', request = request })\n" +
			"  if response and response.host_error then error(response.host_error, 2) end\n" +
			"  return response and response.value or nil\n" +
			"end\n" +
			"gc.intent = function(name, fn)\n" +
			"  if type(name) ~= 'string' or not name:match('%S') then error('gc.intent requires a non-empty name', 2) end\n" +
			"  if type(fn) ~= 'function' then error('gc.intent requires a function', 2) end\n" +
			"  local entered = gc.await { action = { type = 'intent.begin', name = name } }\n" +
			"  if entered.status ~= 'started' then error(entered.reason or 'intent did not start', 2) end\n" +
			"  local results = table.pack(pcall(fn))\n" +
			"  local ended = gc.await { action = { type = 'intent.end', name = name, failed = not results[1] } }\n" +
			"  if not results[1] then error(results[2], 0) end\n" +
			"  if ended.status ~= 'complete' then error(ended.reason or 'intent did not finish', 2) end\n" +
			"  return table.unpack(results, 2, results.n)\n" +
			"end\n" +
			"gc.walk = {}\n" +
			"gc.walk.to = function(options)\n" +
			"  if type(options) ~= 'table' then error('gc.walk.to requires an options table', 2) end\n" +
			"  local action = {}\n" +
			"  for key, value in pairs(options) do\n" +
			"    if key ~= 'activity' and key ~= 'policy' and key ~= 'humanize' and key ~= 'timeout' and key ~= 'ticks' then action[key] = value end\n" +
			"  end\n" +
			"  action.type = 'walk.to'\n" +
			"  return gc.await { action = action, activity = options.activity, policy = options.policy, humanize = options.humanize,\n" +
			"    timeout = options.timeout or (options.ticks and { game_ticks = options.ticks }) }\n" +
			"end\n" +
			"gc.checkpoint = function(key, value)\n" +
			"  local type = value == nil and 'checkpoint.get' or 'checkpoint.set'\n" +
			"  local action = { type = type, key = key, value = value }\n" +
			"  local receipt = gc.await { action = action }\n" +
			"  if not receipt or receipt.status ~= 'complete' then\n" +
			"    error(receipt and receipt.result or 'checkpoint failed', 2)\n" +
			"  end\n" +
			"  if type == 'checkpoint.get' and not receipt.present then return nil end\n" +
			"  return receipt.value\n" +
			"end\n" +
			"gc.clear_checkpoint = function(key)\n" +
			"  local receipt = gc.await {\n" +
			"    action = { type = 'checkpoint.clear', key = key } }\n" +
			"  if not receipt or receipt.status ~= 'complete' then\n" +
			"    error(receipt and receipt.result or 'checkpoint clear failed', 2)\n" +
			"  end\n" +
			"  return receipt.cleared\n" +
			"end\n" +
			"gc.phase = function(name, options)\n" +
			"  local request = { phase = name }\n" +
			"  if options and options.breaks ~= nil then error('Unknown phase option: breaks', 2) end\n" +
			"  if options and options.activity ~= nil then gc.activity(options.activity) end\n" +
			"  if options and options.policy ~= nil then request.policy = options.policy end\n" +
			"  if options and options.humanize ~= nil then request.humanize = options.humanize end\n" +
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
			"__gc_activity = nil\n" +
			"__gc_state = nil\n" +
			"__gc_budget_hook = nil");

	}

	private int activity(Lua state)
	{
		try
		{
			if (state.getTop() >= 1 && !state.isNil(1))
			{
				currentActivity = GenericClientActivityContext.preset(
					GenericClientActivityContext.Activity.fromName(state.toString(1)))
					.withPolicy(state.getTop() >= 2 && !state.isNil(2) ? state.toObject(2) : null);
			}
			state.push(currentActivity == null ? "general" : currentActivity.getActivity().getValue());
			return 1;
		}
		catch (RuntimeException exception)
		{
			state.push(exception.getMessage());
			return -1;
		}
	}

	private int scriptState(Lua state)
	{
		try
		{
			if (state.getTop() >= 1 && !state.isNil(1))
			{
				String name = state.toString(1);
				if (name == null || !name.trim().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"))
				{
					throw new IllegalArgumentException(
						"Script state must be 1-64 letters, numbers, dots, underscores, or hyphens");
				}
				currentPhase = name.trim();
			}
			state.push(getScriptState());
			return 1;
		}
		catch (RuntimeException exception)
		{
			state.push(exception.getMessage());
			return -1;
		}
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
			Object value = host.readSnapshot(this, pinnedSnapshot, subject, query);
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
			Object rawMarkers = state.getTop() >= 2 && !state.isNil(2)
				? normalizeLuaValue(state.toObject(2))
				: null;
			sceneMarkers = GenericClientSceneMarker.parse(rawMarkers);
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
		if (wait != null) wait.ticket.cancel();

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
			GenericClientLuaAwait next = awaitParser.parse(yieldedValue, currentActivity, host.isOperator(this));
			if (!GenericClientLuaIntent.isControl(next.actionType)) next.ticket = intents.newActionTicket();
			wait = next;
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

	private void dispatchWaitIfReady()
	{
		if (!activated || finished || wait == null || wait.dispatched)
		{
			return;
		}
		if (wait.kind != GenericClientLuaAwait.Kind.ACTION && wait.kind != GenericClientLuaAwait.Kind.PHASE)
		{
			return;
		}
		wait.dispatched = true;
		if (wait.kind == GenericClientLuaAwait.Kind.PHASE)
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
			host.actions.submitPhase(this, wait.requestId, wait.phaseName, wait.activityContext);
		}
			else if ("walk.random".equals(wait.actionType))
		{
				host.actions.submitWalkRandom(this, wait.requestId, wait.activityContext);
			}
			else if ("walk.click".equals(wait.actionType))
			{
				host.actions.submitWalkClick(
					this, wait.requestId, wait.destination, wait.activityContext);
			}
		else if ("mouse.offscreen".equals(wait.actionType))
		{
			host.actions.submitMouseOffscreen(this, wait.requestId);
		}
		else if ("npc.interact".equals(wait.actionType))
		{
			host.actions.submitNpcInteract(
				this,
				wait.requestId,
				wait.targetId,
				wait.targetName,
				wait.targetAction,
				wait.within,
				wait.activityContext);
		}
		else if (wait.questAction != null)
		{
			host.actions.submitQuestAction(
				this,
				wait.requestId,
				wait.actionType,
				wait.questAction,
				wait.activityContext);
		}
		else if ("combat.set_style".equals(wait.actionType))
		{
			host.actions.submitCombatSetStyle(this, wait.requestId, wait.within, wait.activityContext);
		}
		else if ("combat.set_auto_retaliate".equals(wait.actionType))
		{
			host.actions.submitCombatSetAutoRetaliate(
				this, wait.requestId, wait.within == 1, wait.activityContext);
		}
		else
		{
			host.actions.submitWalkTo(this, wait.requestId, wait.walkRequest);
		}
	}

	private void beginBudget()
	{
		beginBudget(RESUME_BUDGET_NANOS);
	}

	private void beginBudget(long budgetNanos)
	{
		budgetExceeded = false;
		deadlineNanos = System.nanoTime() + budgetNanos;
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

	private Map<String, Object> valueResponse(Map<String, Object> value)
	{
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("value", intents.decorate(value));
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
		if (wait != null) wait.ticket.cancel();
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
		intents.cancel("coroutine_closed");
		if (coroutineReference != 0)
		{
			lua.unref(coroutineReference);
			coroutineReference = 0;
		}
		coroutine = null;
	}

}
