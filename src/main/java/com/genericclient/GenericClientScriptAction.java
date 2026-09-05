package com.genericclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GenericClientScriptAction
{
	private static final int MAX_ACTIONS = 4;

	private final String id;
	private final String label;

	GenericClientScriptAction(String id, String label)
	{
		this.id = id;
		this.label = label;
	}

	String getId()
	{
		return id;
	}

	String getLabel()
	{
		return label;
	}

	Map<String, Object> toMap()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("id", id);
		value.put("label", label);
		return value;
	}

	static List<GenericClientScriptAction> from(com.genericclient.script.ScriptSettings.Button[] definitions)
	{
		if (definitions.length > MAX_ACTIONS)
			throw new IllegalArgumentException("Scripts may declare at most " + MAX_ACTIONS + " actions");
		List<GenericClientScriptAction> actions = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (com.genericclient.script.ScriptSettings.Button definition : definitions)
		{
			String id = requiredText(definition.id(), "Script action id", 32);
			if (!id.matches("[a-z][a-z0-9_]{0,31}"))
				throw new IllegalArgumentException("Script action id must be 1-32 lowercase letters, numbers, or underscores");
			if (!ids.add(id)) throw new IllegalArgumentException("Duplicate script action id: " + id);
			actions.add(new GenericClientScriptAction(id, requiredText(definition.label(), "Script action label", 24)));
		}
		return Collections.unmodifiableList(actions);
	}

	private static String requiredText(String raw, String label, int maximumLength)
	{
		if (raw.trim().isEmpty())
		{
			throw new IllegalArgumentException(label + " is required");
		}
		String value = raw.trim();
		if (value.length() > maximumLength || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
		{
			throw new IllegalArgumentException(label + " must be at most " + maximumLength + " characters");
		}
		return value;
	}
}
