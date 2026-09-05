package com.genericclient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

final class GenericClientInteractionResult
{
	private final WorldPoint target;
	private final String detail;
	private final boolean clickDispatched;
	private final Map<String, Object> behaviorBefore;
	private final Map<String, Object> behaviorAfter;

	GenericClientInteractionResult(
		WorldPoint target,
		String detail,
		boolean clickDispatched,
		Map<String, Object> behaviorBefore,
		Map<String, Object> behaviorAfter)
	{
		this.target = target;
		this.detail = detail;
		this.clickDispatched = clickDispatched;
		this.behaviorBefore = immutableCopy(behaviorBefore);
		this.behaviorAfter = immutableCopy(behaviorAfter);
	}

	WorldPoint getTarget()
	{
		return target;
	}

	String getDetail()
	{
		return detail;
	}

	boolean isClickDispatched()
	{
		return clickDispatched;
	}

	boolean isWalkExecuted()
	{
		return detail != null &&
			(detail.startsWith("WALK_CLICK_EXECUTED") || detail.startsWith("WALK_TILE_CLICK_EXECUTED"));
	}

	Map<String, Object> getBehaviorBefore()
	{
		return behaviorBefore;
	}

	Map<String, Object> getBehaviorAfter()
	{
		return behaviorAfter;
	}

	Map<String, Object> toReceipt()
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", isWalkExecuted() ? "dispatched" : "rejected");
		receipt.put("result", detail);
		receipt.put("target", worldMap(target));
		receipt.put("behavior_before", behaviorBefore);
		if (!behaviorAfter.isEmpty())
		{
			receipt.put("behavior_after", behaviorAfter);
		}
		return receipt;
	}

	@SuppressWarnings("unchecked")
	GenericClientInteractionResult withBehavior(Map<String, Object> receipt)
	{
		return new GenericClientInteractionResult(target, detail, clickDispatched,
			(Map<String, Object>) receipt.get("behavior_before"),
			(Map<String, Object>) receipt.get("behavior_after"));
	}

	private static Map<String, Object> immutableCopy(Map<String, Object> value)
	{
		return value == null
			? Collections.emptyMap()
			: Collections.unmodifiableMap(new LinkedHashMap<>(value));
	}

	private static Map<String, Object> worldMap(WorldPoint point)
	{
		if (point == null)
		{
			return null;
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("x", (long) point.getX());
		value.put("y", (long) point.getY());
		value.put("plane", (long) point.getPlane());
		return value;
	}
}
