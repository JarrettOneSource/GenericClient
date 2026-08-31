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
	private static final long SOURCE_LOAD_BUDGET_NANOS = 100_000_000L;
	private static final int HOOK_INSTRUCTION_INTERVAL = 1_000;
	private static final int DEFAULT_RANDOM_ACTION_TIMEOUT_TICKS = 8;
	private static final int DEFAULT_NPC_ACTION_TIMEOUT_TICKS = 20;
	private static final int DEFAULT_BANK_ACTION_TIMEOUT_TICKS = 200;
	private static final int DEFAULT_GE_ACTION_TIMEOUT_TICKS = 300;
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
	private long startedNanos;
	private boolean finished = true;
	private String terminalStatus = "COMPLETED";
	private String faultMessage;
	private Object returnValue;
	private GenericClientSnapshot pinnedSnapshot;
	private Wait wait;
	private long nextRequestId;
	private String currentPhase;
	private GenericClientActivityContext.Activity currentActivity =
		GenericClientActivityContext.Activity.GENERAL;
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

	String getActivity()
	{
		Wait current = wait;
		if (current != null &&
			(current.kind == WaitKind.ACTION || current.kind == WaitKind.PHASE) &&
			current.activityContext != null)
		{
			return current.activityContext.getActivity().getValue();
		}
		return currentActivity.getValue();
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
		currentActivity = GenericClientActivityContext.Activity.GENERAL;
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
			"gc.phase = function(name, options)\n" +
			"  local request = { phase = name }\n" +
			"  if options and options.activity ~= nil then gc.activity(options.activity) end\n" +
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
				String name = state.toString(1);
				currentActivity = GenericClientActivityContext.Activity.fromName(name);
			}
			state.push(currentActivity.getValue());
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
		Map<?, ?> request = awaitRequest(yieldedValue);
		boolean breaksEnabled = breaksEnabled(request);
		GenericClientActivityContext.Activity requestedActivity = requestedActivity(request);

		Object ticks = request.get("ticks");
		if (ticks instanceof Number)
		{
			return parseTickWait((Number) ticks);
		}

		Object event = request.get("event");
		if (event instanceof String)
		{
			return parseEventWait((String) event);
		}

		Object action = request.get("action");
		if (action instanceof Map)
		{
			return parseActionWait(
				request, (Map<?, ?>) action, breaksEnabled, requestedActivity);
		}

		Object phase = request.get("phase");
		if (phase instanceof String)
		{
			return parsePhaseWait((String) phase, breaksEnabled, requestedActivity);
		}

		throw new IllegalArgumentException("Await request must contain ticks, event, action, or phase");
	}

	private static Map<?, ?> awaitRequest(Object yieldedValue)
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
		return (Map<?, ?>) envelope.get("request");
	}

	private static boolean breaksEnabled(Map<?, ?> request)
	{
		if (request.containsKey("breaks") && !(request.get("breaks") instanceof Boolean))
		{
			throw new IllegalArgumentException("breaks must be true or false");
		}
		return !(request.get("breaks") instanceof Boolean) ||
			(Boolean) request.get("breaks");
	}

	private GenericClientActivityContext.Activity requestedActivity(Map<?, ?> request)
	{
		GenericClientActivityContext.Activity requestedActivity = currentActivity;
		if (request.containsKey("activity"))
		{
			if (!(request.get("activity") instanceof String))
			{
				throw new IllegalArgumentException("activity must be a string");
			}
			requestedActivity = GenericClientActivityContext.Activity.fromName(
				(String) request.get("activity"));
		}
		return requestedActivity;
	}

	private static Wait parseTickWait(Number value)
	{
		int ticks = value.intValue();
		if (ticks < 1)
		{
			throw new IllegalArgumentException("Tick wait must be positive");
		}
		return Wait.ticks(ticks);
	}

	private static Wait parseEventWait(String event)
	{
		if (!"game.tick".equals(event))
		{
			throw new IllegalArgumentException("Unsupported event: " + event);
		}
		return Wait.gameTick();
	}

	private Wait parseActionWait(
		Map<?, ?> request,
		Map<?, ?> action,
		boolean breaksEnabled,
		GenericClientActivityContext.Activity requestedActivity)
	{
		Object typeValue = action.get("type");
		if (!(typeValue instanceof String))
		{
			throw new IllegalArgumentException("Action requires a type string");
		}
		String type = (String) typeValue;
		int timeout = actionTimeout(request, type);

		switch (type)
		{
			case "walk.random":
				return Wait.randomAction(
					++nextRequestId,
					timeout,
					activityContext(type, action, breaksEnabled, requestedActivity));
			case "mouse.offscreen":
				return Wait.mouseOffscreen(++nextRequestId, timeout);
			case "walk.to":
				return parseWalkAction(action, timeout, breaksEnabled, requestedActivity);
			case "npc.interact":
				return parseNpcAction(action, timeout, breaksEnabled, requestedActivity);
			case "combat.set_style":
				return parseCombatStyle(action, timeout, breaksEnabled, requestedActivity);
			case "combat.set_auto_retaliate":
				return parseAutoRetaliate(action, timeout, breaksEnabled, requestedActivity);
			default:
				if (isQuestAction(type))
				{
					return Wait.questAction(
						++nextRequestId,
						timeout,
						type,
						copyAction(action),
						activityContext(type, action, breaksEnabled, requestedActivity));
				}
				throw new IllegalArgumentException("Unsupported action: " + type);
		}
	}

	private static int actionTimeout(Map<?, ?> request, String type)
	{
		int timeout = defaultActionTimeout(type);
		Object timeoutValue = request.get("timeout");
		if (timeoutValue instanceof Map)
		{
			Object gameTicks = ((Map<?, ?>) timeoutValue).get("game_ticks");
			if (gameTicks instanceof Number)
			{
				timeout = ((Number) gameTicks).intValue();
			}
		}
		if (timeout < 1)
		{
			throw new IllegalArgumentException("Action timeout must be positive");
		}
		return timeout;
	}

	private static int defaultActionTimeout(String type)
	{
		switch (type)
		{
			case "walk.to":
				return DEFAULT_WALK_ACTION_TIMEOUT_TICKS;
			case "bank.loadout":
				return DEFAULT_BANK_ACTION_TIMEOUT_TICKS;
			case "ge.buy":
				return DEFAULT_GE_ACTION_TIMEOUT_TICKS;
			case "npc.interact":
			case "combat.set_style":
			case "combat.set_auto_retaliate":
				return DEFAULT_NPC_ACTION_TIMEOUT_TICKS;
			default:
				return isQuestAction(type)
					? DEFAULT_NPC_ACTION_TIMEOUT_TICKS
					: DEFAULT_RANDOM_ACTION_TIMEOUT_TICKS;
		}
	}

	private Wait parseWalkAction(
		Map<?, ?> action,
		int timeout,
		boolean breaksEnabled,
		GenericClientActivityContext.Activity requestedActivity)
	{
		Object destinationValue = action.get("destination");
		if (!(destinationValue instanceof Map))
		{
			throw new IllegalArgumentException("walk.to requires a destination table");
		}
		Map<?, ?> destination = (Map<?, ?>) destinationValue;
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
		if (action.get("run") != null && !(action.get("run") instanceof Boolean))
		{
			throw new IllegalArgumentException("walk.to run must be true or false");
		}
		return Wait.walkAction(
			++nextRequestId,
			timeout,
			new WorldPoint(x, y, plane),
			within,
			activityContext("walk.to", action, breaksEnabled, requestedActivity),
			!Boolean.FALSE.equals(action.get("run")));
	}

	private Wait parseNpcAction(
		Map<?, ?> action,
		int timeout,
		boolean breaksEnabled,
		GenericClientActivityContext.Activity requestedActivity)
	{
		Integer id = optionalNonNegativeInt(action, "id", "npc.interact");
		String name = optionalText(action.get("name"));
		if (id == null && name == null)
		{
			throw new IllegalArgumentException("npc.interact requires id or name");
		}
		String option = requiredText(action, "action", "npc.interact");
		int within = action.get("within") instanceof Number
			? ((Number) action.get("within")).intValue()
			: 15;
		if (within < 1 || within > 32)
		{
			throw new IllegalArgumentException("npc.interact within must be between 1 and 32");
		}
		return Wait.npcAction(
			++nextRequestId,
			timeout,
			id,
			name,
			option,
			within,
			activityContext("npc.interact", action, breaksEnabled, requestedActivity));
	}

	private Wait parseCombatStyle(
		Map<?, ?> action,
		int timeout,
		boolean breaksEnabled,
		GenericClientActivityContext.Activity requestedActivity)
	{
		Object styleValue = action.get("style");
		if (!(styleValue instanceof Number))
		{
			throw new IllegalArgumentException("combat.set_style requires a numeric style");
		}
		int style = ((Number) styleValue).intValue();
		if (style < 0 || style > 3)
		{
			throw new IllegalArgumentException("combat.set_style style must be between 0 and 3");
		}
		return Wait.combatStyle(
			++nextRequestId,
			timeout,
			style,
			activityContext("combat.set_style", action, breaksEnabled, requestedActivity));
	}

	private Wait parseAutoRetaliate(
		Map<?, ?> action,
		int timeout,
		boolean breaksEnabled,
		GenericClientActivityContext.Activity requestedActivity)
	{
		Object enabledValue = action.get("enabled");
		if (!(enabledValue instanceof Boolean))
		{
			throw new IllegalArgumentException(
				"combat.set_auto_retaliate requires enabled=true or enabled=false");
		}
		return Wait.combatAutoRetaliate(
			++nextRequestId,
			timeout,
			(Boolean) enabledValue,
			activityContext("combat.set_auto_retaliate", action, breaksEnabled, requestedActivity));
	}

	private Wait parsePhaseWait(
		String value,
		boolean breaksEnabled,
		GenericClientActivityContext.Activity requestedActivity)
	{
		String phase = value.trim();
		if (!phase.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"))
		{
			throw new IllegalArgumentException(
				"Phase name must be 1-64 letters, numbers, dots, underscores, or hyphens");
		}
		return Wait.phase(
			++nextRequestId,
			phase,
			GenericClientActivityContext.of(requestedActivity, breaksEnabled));
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
			host.submitPhase(this, wait.requestId, wait.phaseName, wait.activityContext);
		}
		else if ("walk.random".equals(wait.actionType))
		{
			host.submitWalkRandom(this, wait.requestId, wait.activityContext);
		}
		else if ("mouse.offscreen".equals(wait.actionType))
		{
			host.submitMouseOffscreen(this, wait.requestId);
		}
		else if ("npc.interact".equals(wait.actionType))
		{
			host.submitNpcInteract(
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
			host.submitQuestAction(
				this,
				wait.requestId,
				wait.actionType,
				wait.questAction,
				wait.activityContext);
		}
		else if ("combat.set_style".equals(wait.actionType))
		{
			host.submitCombatSetStyle(this, wait.requestId, wait.within, wait.activityContext);
		}
		else if ("combat.set_auto_retaliate".equals(wait.actionType))
		{
			host.submitCombatSetAutoRetaliate(
				this, wait.requestId, wait.within == 1, wait.activityContext);
		}
		else
		{
				host.submitWalkTo(
				this,
				wait.requestId,
					wait.destination,
					wait.within,
					wait.remainingTicks,
					wait.activityContext,
					wait.useRun);
		}
	}

	private GenericClientActivityContext activityContext(
		String actionType,
		Map<?, ?> action,
		boolean discretionaryBehaviorEnabled,
		GenericClientActivityContext.Activity requestedActivity)
	{
		GenericClientActivityContext.Activity activity = requestedActivity;
		if ("bank.loadout".equals(actionType))
		{
			activity = GenericClientActivityContext.Activity.BANKING;
		}
		else if ("ge.buy".equals(actionType))
		{
			activity = GenericClientActivityContext.Activity.TRADING;
		}
		else if ("npc.interact".equals(actionType) &&
			"Bank".equalsIgnoreCase(String.valueOf(action.get("action"))))
		{
			activity = GenericClientActivityContext.Activity.BANKING;
		}
		else if ("npc.interact".equals(actionType) &&
			"Exchange".equalsIgnoreCase(String.valueOf(action.get("action"))))
		{
			activity = GenericClientActivityContext.Activity.TRADING;
		}
		else if (actionType.startsWith("combat.") ||
			("npc.interact".equals(actionType) &&
				"Attack".equalsIgnoreCase(String.valueOf(action.get("action")))))
		{
			activity = GenericClientActivityContext.Activity.COMBAT;
		}
		else if (actionType.startsWith("dialogue.") ||
			("npc.interact".equals(actionType) &&
				"Talk-to".equalsIgnoreCase(String.valueOf(action.get("action")))))
		{
			activity = GenericClientActivityContext.Activity.DIALOGUE;
		}
		else if (actionType.startsWith("walk.") &&
			activity != GenericClientActivityContext.Activity.COMBAT &&
			activity != GenericClientActivityContext.Activity.BANKING &&
			activity != GenericClientActivityContext.Activity.TRADING &&
			activity != GenericClientActivityContext.Activity.DIALOGUE)
		{
			activity = GenericClientActivityContext.Activity.TRAVEL;
		}
		return GenericClientActivityContext.of(activity, discretionaryBehaviorEnabled);
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

	private static boolean isQuestAction(String type)
	{
		return "object.interact".equals(type) ||
			"item.interact".equals(type) ||
			"equipment.interact".equals(type) ||
			"item.use_on_object".equals(type) ||
			"item.use_on_npc".equals(type) ||
			"ground_item.take".equals(type) ||
			"dialogue.continue".equals(type) ||
			"dialogue.choose".equals(type) ||
			"bank.loadout".equals(type) ||
			"ge.buy".equals(type) ||
			"combat.cast".equals(type) ||
			"combat.set_autocast".equals(type) ||
			"prayer.set".equals(type) ||
			"ui.close".equals(type) ||
			"ui.click".equals(type) ||
			"ui.key".equals(type) ||
			"safety.configure".equals(type) ||
			"safety.clear".equals(type);
	}

	private static Map<String, Object> copyAction(Map<?, ?> value)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : value.entrySet())
		{
			if (!(entry.getKey() instanceof String))
			{
				throw new IllegalArgumentException("Action keys must be strings");
			}
			result.put((String) entry.getKey(), normalizeLuaValue(entry.getValue()));
		}
		return Collections.unmodifiableMap(result);
	}

	private static String requiredText(Map<?, ?> value, String key, String actionType)
	{
		Object raw = value.get(key);
		if (!(raw instanceof String) || ((String) raw).trim().isEmpty())
		{
			throw new IllegalArgumentException(actionType + " requires a non-empty " + key);
		}
		return ((String) raw).trim();
	}

	private static Integer optionalNonNegativeInt(Map<?, ?> value, String key, String actionType)
	{
		Object raw = value.get(key);
		if (raw == null)
		{
			return null;
		}
		if (!(raw instanceof Number))
		{
			throw new IllegalArgumentException(actionType + " " + key + " must be numeric");
		}
		int result = ((Number) raw).intValue();
		if (result < 0)
		{
			throw new IllegalArgumentException(actionType + " " + key + " cannot be negative");
		}
		return result;
	}

	private static String optionalText(Object raw)
	{
		if (raw == null)
		{
			return null;
		}
		if (!(raw instanceof String) || ((String) raw).trim().isEmpty())
		{
			throw new IllegalArgumentException("Optional text values must be non-empty strings");
		}
		return ((String) raw).trim();
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
		private final Integer targetId;
		private final String targetName;
		private final String targetAction;
		private final GenericClientActivityContext activityContext;
		private final boolean useRun;
		private final String phaseName;
		private final Map<String, Object> questAction;
		private int remainingTicks;
		private boolean dispatched;

		private Wait(
			WaitKind kind,
			long requestId,
			int remainingTicks,
			String actionType,
			WorldPoint destination,
			int within,
			Integer targetId,
			String targetName,
			String targetAction,
			GenericClientActivityContext activityContext,
			boolean useRun,
			String phaseName,
			Map<String, Object> questAction)
		{
			this.kind = kind;
			this.requestId = requestId;
			this.remainingTicks = remainingTicks;
			this.actionType = actionType;
			this.destination = destination;
			this.within = within;
			this.targetId = targetId;
			this.targetName = targetName;
			this.targetAction = targetAction;
			this.activityContext = activityContext;
			this.useRun = useRun;
			this.phaseName = phaseName;
			this.questAction = questAction;
		}

		private static Wait gameTick()
		{
			return new Wait(WaitKind.GAME_TICK, 0, 0, null, null, 0, null, null, null,
				GenericClientActivityContext.general(false), true, null, null);
		}

		private static Wait ticks(int ticks)
		{
			return new Wait(WaitKind.TICKS, 0, ticks, null, null, 0, null, null, null,
				GenericClientActivityContext.general(false), true, null, null);
		}

		private static Wait randomAction(
			long requestId,
			int timeoutTicks,
			GenericClientActivityContext activityContext)
		{
			return new Wait(
				WaitKind.ACTION, requestId, timeoutTicks, "walk.random", null, 0,
				null, null, null, activityContext, true, null, null);
		}

		private static Wait walkAction(
			long requestId,
			int timeoutTicks,
			WorldPoint destination,
			int within,
			GenericClientActivityContext activityContext,
			boolean useRun)
		{
			return new Wait(
				WaitKind.ACTION, requestId, timeoutTicks, "walk.to", destination, within,
				null, null, null, activityContext, useRun, null, null);
		}

		private static Wait npcAction(
			long requestId,
			int timeoutTicks,
			Integer id,
			String name,
			String action,
			int within,
			GenericClientActivityContext activityContext)
		{
			return new Wait(
				WaitKind.ACTION, requestId, timeoutTicks, "npc.interact", null, within,
				id, name, action, activityContext, true, null, null);
		}

		private static Wait questAction(
			long requestId,
			int timeoutTicks,
			String type,
			Map<String, Object> action,
			GenericClientActivityContext activityContext)
		{
			return new Wait(
				WaitKind.ACTION, requestId, timeoutTicks, type, null, 0,
				null, null, null, activityContext, true, null, action);
		}

		private static Wait combatStyle(
			long requestId,
			int timeoutTicks,
			int style,
			GenericClientActivityContext activityContext)
		{
			return new Wait(
				WaitKind.ACTION, requestId, timeoutTicks, "combat.set_style", null, style,
				null, null, null, activityContext, true, null, null);
		}

		private static Wait combatAutoRetaliate(
			long requestId,
			int timeoutTicks,
			boolean enabled,
			GenericClientActivityContext activityContext)
		{
			return new Wait(
				WaitKind.ACTION, requestId, timeoutTicks, "combat.set_auto_retaliate", null,
					enabled ? 1 : 0, null, null, null, activityContext, true, null, null);
		}

		private static Wait mouseOffscreen(long requestId, int timeoutTicks)
		{
			return new Wait(
				WaitKind.ACTION, requestId, timeoutTicks, "mouse.offscreen", null, 0,
					null, null, null, GenericClientActivityContext.general(false), true, null, null);
		}

		private static Wait phase(
			long requestId,
			String name,
			GenericClientActivityContext activityContext)
		{
			return new Wait(WaitKind.PHASE, requestId, 0, null, null, 0,
				null, null, null, activityContext, true, name, null);
		}
	}
}
