package com.genericclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GenericClientActiveScript
{
	private static final GenericClientActiveScript NONE = new GenericClientActiveScript(
		null, null, null, "IDLE", 0L, Collections.emptyList(), Collections.emptyMap(),
		Collections.emptyList(), Collections.emptyList());

	private final String id;
	private final String name;
	private final String description;
	private final String status;
	private final long runtimeMillis;
	private final List<GenericClientScriptInput> inputs;
	private final Map<String, Object> values;
	private final List<GenericClientScriptAction> actions;
	private final List<GenericClientOverlayRow> overlayRows;
	private final Object result;
	private final String error;

	GenericClientActiveScript(
		String id,
		String name,
		String description,
		String status,
		long runtimeMillis,
		List<GenericClientScriptInput> inputs,
		Map<String, Object> values,
		List<GenericClientScriptAction> actions,
		List<GenericClientOverlayRow> overlayRows)
	{
		this(id, name, description, status, runtimeMillis, inputs, values, actions,
			overlayRows, null, null);
	}

	GenericClientActiveScript(
		String id,
		String name,
		String description,
		String status,
		long runtimeMillis,
		List<GenericClientScriptInput> inputs,
		Map<String, Object> values,
		List<GenericClientScriptAction> actions,
		List<GenericClientOverlayRow> overlayRows,
		Object result,
		String error)
	{
		this.id = id;
		this.name = name;
		this.description = description;
		this.status = status;
		this.runtimeMillis = Math.max(0L, runtimeMillis);
		this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
		this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
		this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
		this.overlayRows = Collections.unmodifiableList(new ArrayList<>(overlayRows));
		this.result = result;
		this.error = error;
	}

	static GenericClientActiveScript none()
	{
		return NONE;
	}

	boolean isPresent()
	{
		return id != null;
	}

	boolean isRunning()
	{
		return "WAITING".equals(status) || "RUNNING".equals(status);
	}

	String getId()
	{
		return id;
	}

	String getName()
	{
		return name;
	}

	String getDescription()
	{
		return description;
	}

	String getStatus()
	{
		return status;
	}

	long getRuntimeMillis()
	{
		return runtimeMillis;
	}

	List<GenericClientScriptInput> getInputs()
	{
		return inputs;
	}

	Map<String, Object> getValues()
	{
		return values;
	}

	List<GenericClientScriptAction> getActions()
	{
		return actions;
	}

	List<GenericClientOverlayRow> getOverlayRows()
	{
		return overlayRows;
	}

	Map<String, Object> toMap()
	{
		if (!isPresent())
		{
			return new LinkedHashMap<>();
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		result.put("name", name);
		result.put("description", description);
		result.put("status", status);
		result.put("running", isRunning());
		result.put("runtime_millis", runtimeMillis);
		result.put("values", new LinkedHashMap<>(values));
		List<Map<String, Object>> inputValues = new ArrayList<>();
		for (GenericClientScriptInput input : inputs)
		{
			inputValues.add(input.toMap());
		}
		result.put("inputs", inputValues);
		List<Map<String, Object>> actionValues = new ArrayList<>();
		for (GenericClientScriptAction action : actions)
		{
			actionValues.add(action.toMap());
		}
		result.put("actions", actionValues);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (GenericClientOverlayRow row : overlayRows)
		{
			rows.add(row.toMap());
		}
		result.put("overlay", rows);
		if (!isRunning())
		{
			result.put("result", this.result);
			if (error != null)
			{
				result.put("error", error);
			}
		}
		return result;
	}
}
