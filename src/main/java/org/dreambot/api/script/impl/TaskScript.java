package org.dreambot.api.script.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.dreambot.api.script.AbstractScript;
import org.dreambot.api.script.TaskNode;

public abstract class TaskScript extends AbstractScript
{
	private final List<TaskNode> nodes = new ArrayList<>();
	private int failLimit = -1;
	private int failures;
	private TaskNode last;

	public void addNodes(TaskNode... additions) { nodes.addAll(Arrays.asList(additions)); }
	public void removeNodes(TaskNode... removals) { nodes.removeAll(Arrays.asList(removals)); }
	public TaskNode[] getNodes() { return nodes.toArray(new TaskNode[0]); }
	public TaskNode getLastTaskNode() { return last; }
	public void setFailLimit(int limit) { failLimit = limit; }

	@Override
	public int onLoop()
	{
		TaskNode selected = null;
		for (TaskNode node : nodes)
		{
			if (node.accept() && (selected == null || node.priority() > selected.priority())) selected = node;
		}
		if (selected == null)
		{
			failures++;
			if (failLimit >= 0 && failures > failLimit) stop();
			return 1000;
		}
		failures = 0;
		last = selected;
		return selected.execute();
	}
}
