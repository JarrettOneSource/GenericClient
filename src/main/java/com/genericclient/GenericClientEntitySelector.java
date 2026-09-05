package com.genericclient;

import java.util.Map;

/** Shared identity and attribute filters for observed scene entities. */
final class GenericClientEntitySelector
{
	private final Map<?, ?> attributes;
	private final Integer id;
	private final String name;

	GenericClientEntitySelector(Map<?, ?> query)
	{
		attributes = query != null && query.get("where") instanceof Map ? (Map<?, ?>) query.get("where") : Map.of();
		Object requestedId = query != null && query.get("id") instanceof Number ? query.get("id") : attributes.get("id");
		id = requestedId instanceof Number ? ((Number) requestedId).intValue() : null;
		Object requestedName = attributes.get("name");
		name = requestedName instanceof String && !((String) requestedName).isEmpty() ? (String) requestedName : null;
	}

	boolean matches(int candidateId, String candidateName)
	{
		return (id == null || candidateId == id) && (name == null || candidateName.equalsIgnoreCase(name));
	}

	Boolean flag(String attribute)
	{
		Object value = attributes.get(attribute);
		return value instanceof Boolean ? (Boolean) value : null;
	}
}
