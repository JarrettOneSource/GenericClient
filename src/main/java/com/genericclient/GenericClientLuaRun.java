package com.genericclient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** One standalone run's identity, terminal result and presentation snapshot. */
final class GenericClientLuaRun
{
	final long id;
	final String owner;
	final GenericClientScriptRegistry.Script definition;
	final GenericClientLuaScript script;
	final Map<String, Object> values;
	final long startedNanos;
	private volatile String status;
	private volatile long finishedNanos = -1L;
	private volatile List<GenericClientOverlayRow> terminalOverlay = Collections.emptyList();
	private volatile Object terminalValue;
	private volatile String terminalError;

	GenericClientLuaRun(
		long id,
		String owner,
		GenericClientScriptRegistry.Script definition,
		GenericClientLuaScript script,
		Map<String, Object> values,
		String status,
		long startedNanos)
	{
		this.id = id;
		this.owner = owner;
		this.definition = definition;
		this.script = script;
		this.values = values;
		this.status = status;
		this.startedNanos = startedNanos;
	}

	void finish(
		String terminalStatus,
		List<GenericClientOverlayRow> overlay,
		Object value,
		String error,
		long nowNanos)
	{
		status = terminalStatus;
		terminalOverlay = overlay;
		terminalValue = value;
		terminalError = error;
		finishedNanos = nowNanos;
	}

	GenericClientActiveScript snapshot(long nowNanos)
	{
		long end = finishedNanos < 0L ? nowNanos : finishedNanos;
		long runtimeMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, end - startedNanos));
		List<GenericClientOverlayRow> overlay = finishedNanos < 0L
			? script.getOverlayRows()
			: terminalOverlay;
		return new GenericClientActiveScript(
			definition.getId(),
			definition.getName(),
			definition.getDescription(),
			status,
			runtimeMillis,
			script.getInputs(),
			values,
			script.getActions(),
			overlay,
			terminalValue,
			terminalError);
	}
	static final class State
	{
		private static final State NONE = new State(-1L, null, null, "IDLE", false);
		private final long runId;
		private final String owner;
		private final String scriptId;
		private final String status;
		private final boolean running;

		State(long runId, String owner, String scriptId, String status, boolean running)
		{
			this.runId = runId;
			this.owner = owner;
			this.scriptId = scriptId;
			this.status = status;
			this.running = running;
		}

		static State none()
		{
			return NONE;
		}

		long getRunId()
		{
			return runId;
		}

		String getOwner()
		{
			return owner;
		}

		String getScriptId()
		{
			return scriptId;
		}

		String getStatus()
		{
			return status;
		}

		boolean isRunning()
		{
			return running;
		}

		boolean isManual()
		{
			return "manual".equals(owner);
		}

		String getRuleId()
		{
			return owner != null && owner.startsWith("rule:") ? owner.substring(5) : null;
		}
	}



}
