package com.genericclient;

import java.util.LinkedHashMap;
import java.util.Map;

final class GenericClientOverlayRow
{

	private final String label;
	private final String value;

	GenericClientOverlayRow(String label, String value)
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

}
