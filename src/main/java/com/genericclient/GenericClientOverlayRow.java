package com.genericclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GenericClientOverlayRow
{
	private static final int MAX_ROWS = 3;

	private final String label;
	private final String value;

	private GenericClientOverlayRow(String label, String value)
	{
		this.label = label;
		this.value = value;
	}

	String getLabel()
	{
		return label;
	}

	String getValue()
	{
		return value;
	}

	Map<String, Object> toMap()
	{
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("label", label);
		result.put("value", value);
		return result;
	}

	static List<GenericClientOverlayRow> parse(Object rawRows)
	{
		if (rawRows == null || rawRows instanceof Map && ((Map<?, ?>) rawRows).isEmpty())
		{
			return Collections.emptyList();
		}
		if (!(rawRows instanceof List))
		{
			throw new IllegalArgumentException("gc.overlay requires an array of rows");
		}
		List<?> raw = (List<?>) rawRows;
		if (raw.size() > MAX_ROWS)
		{
			throw new IllegalArgumentException("Script overlays may contain at most " + MAX_ROWS + " rows");
		}
		List<GenericClientOverlayRow> rows = new ArrayList<>();
		for (Object item : raw)
		{
			if (!(item instanceof Map))
			{
				throw new IllegalArgumentException("Each script overlay row must be a table");
			}
			Map<?, ?> row = (Map<?, ?>) item;
			rows.add(new GenericClientOverlayRow(
				label(row.get("label")),
				value(row.get("value"))));
		}
		return Collections.unmodifiableList(rows);
	}

	private static String label(Object raw)
	{
		if (!(raw instanceof String))
		{
			throw new IllegalArgumentException("Overlay label must be text");
		}
		return bounded(String.valueOf(raw), "Overlay label", 16);
	}

	private static String value(Object raw)
	{
		if (!(raw instanceof String || raw instanceof Number || raw instanceof Boolean))
		{
			throw new IllegalArgumentException("Overlay value must be text or a primitive value");
		}
		String text;
		if (raw instanceof Number && Double.isFinite(((Number) raw).doubleValue()) &&
			((Number) raw).doubleValue() == ((Number) raw).longValue())
		{
			text = Long.toString(((Number) raw).longValue());
		}
		else
		{
			text = String.valueOf(raw);
		}
		return bounded(text, "Overlay value", 24);
	}

	private static String bounded(String raw, String label, int maximumLength)
	{
		String value = raw.trim();
		if (value.isEmpty() || value.length() > maximumLength ||
			value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
		{
			throw new IllegalArgumentException(label + " must be 1-" + maximumLength + " characters");
		}
		return value;
	}
}
