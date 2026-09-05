package org.dreambot.api.script;

import org.dreambot.api.methods.MethodProvider;

public abstract class TaskNode extends MethodProvider
{
	public abstract boolean accept();
	public abstract int execute();
	public int priority() { return 0; }
}
