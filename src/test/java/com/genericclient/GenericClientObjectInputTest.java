package com.genericclient;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class GenericClientObjectInputTest
{
	@Test
	public void facesAndRetriesWhenAProjectedClickboxHasNoMenuAction()
	{
		assertTrue(GenericClientObjectInput.shouldFaceAndRetry(
			receipt("hover_has_no_matching_action")));
		assertTrue(GenericClientObjectInput.shouldFaceAndRetry(
			receipt("object_not_visible")));
		assertFalse(GenericClientObjectInput.shouldFaceAndRetry(
			receipt("matching_object_not_found")));
	}

	private static Map<String, Object> receipt(String result)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("result", result);
		return receipt;
	}
}
