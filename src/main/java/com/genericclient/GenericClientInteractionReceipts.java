package com.genericclient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GenericClientInteractionReceipts
{
	private GenericClientInteractionReceipts()
	{
	}

	static boolean wasDispatched(Map<String, Object> receipt)
	{
		return receipt != null && "dispatched".equals(receipt.get("status"));
	}

	static Map<String, Object> composite(
		String result,
		Map<String, Object> first,
		Map<String, Object> second)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", second.get("status"));
		receipt.put("result", result);
		List<Map<String, Object>> steps = new ArrayList<>();
		steps.add(first);
		steps.add(second);
		receipt.put("steps", steps);
		receipt.put("click_count", clickCount(first) + clickCount(second));
		return receipt;
	}

	static long clickCount(Map<String, Object> receipt)
	{
		Object value = receipt.get("click_count");
		return value instanceof Number ? ((Number) value).longValue() : 0L;
	}

	static Map<String, Object> rejected(String result)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "rejected");
		receipt.put("result", result);
		receipt.put("click_count", 0L);
		return receipt;
	}
}
