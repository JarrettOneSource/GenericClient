package com.genericclient.script;

/** Binds static DreamBot calls to the worker that owns their input authority. */
public final class ScriptScope implements AutoCloseable
{
	private static final ThreadLocal<ScriptScope> CURRENT = new ThreadLocal<>();
	private final ScriptEnvironment environment;

	public ScriptScope(ScriptEnvironment environment)
	{
		if (CURRENT.get() != null)
		{
			throw new IllegalStateException("A script already owns this worker");
		}
		this.environment = java.util.Objects.requireNonNull(environment);
		CURRENT.set(this);
	}

	public static ScriptEnvironment current()
	{
		ScriptScope scope = CURRENT.get();
		if (scope == null)
		{
			throw new IllegalStateException("Script API called outside a script worker");
		}
		return scope.environment;
	}

	@Override
	public void close()
	{
		if (CURRENT.get() != this)
		{
			throw new IllegalStateException("Script scope is not bound to this worker");
		}
		CURRENT.remove();
	}
}
