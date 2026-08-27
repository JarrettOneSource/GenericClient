package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

public class GenericClientBankCacheTest
{
	@Test
	public void keepsTheLastObservedBankAfterTheWidgetCloses()
	{
		AtomicBoolean open = new AtomicBoolean(true);
		Widget widget = proxy(Widget.class, (method, arguments) ->
			"isHidden".equals(method.getName()) ? !open.get() : defaultValue(method.getReturnType()));
		ItemContainer container = proxy(ItemContainer.class, (method, arguments) ->
			"getItems".equals(method.getName())
				? new Item[]{new Item(net.runelite.api.gameval.ItemID.COINS, 6_000_000)}
				: defaultValue(method.getReturnType()));
		ItemComposition coins = proxy(ItemComposition.class, (method, arguments) ->
		{
			switch (method.getName())
			{
				case "getName":
					return "Coins";
				case "isStackable":
				case "isTradeable":
				case "isGeTradeable":
					return true;
				default:
					return defaultValue(method.getReturnType());
			}
		});
		Client client = proxy(Client.class, (method, arguments) ->
		{
			switch (method.getName())
			{
				case "getWidget":
					return widget;
				case "getItemContainer":
					return open.get() && ((Number) arguments[0]).intValue() == InventoryID.BANK
						? container
						: null;
				case "getItemDefinition":
					return coins;
				default:
					return defaultValue(method.getReturnType());
			}
		});

		GenericClientBankCache cache = new GenericClientBankCache();
		Map<String, Object> live = cache.capture(client, 11).toMap();
		open.set(false);
		Map<String, Object> cached = cache.capture(client, 12).toMap();
		cache.clear();
		Map<String, Object> cleared = cache.capture(client, 13).toMap();

		assertEquals("open", live.get("state"));
		assertEquals(11L, live.get("captured_game_tick"));
		assertEquals(1L, live.get("occupied_slots"));
		assertEquals("cached", cached.get("state"));
		assertEquals(11L, cached.get("captured_game_tick"));
		assertEquals("unknown", cleared.get("state"));
		assertEquals(false, cleared.get("available"));
	}

	private interface Invocation
	{
		Object invoke(Method method, Object[] arguments);
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Invocation invocation)
	{
		return (T) Proxy.newProxyInstance(
			type.getClassLoader(),
			new Class<?>[]{type},
			(proxy, method, arguments) -> invocation.invoke(
				method,
				arguments == null ? new Object[0] : arguments));
	}

	private static Object defaultValue(Class<?> type)
	{
		if (!type.isPrimitive())
		{
			return null;
		}
		if (type == boolean.class)
		{
			return false;
		}
		if (type == char.class)
		{
			return '\0';
		}
		if (type == long.class)
		{
			return 0L;
		}
		if (type == float.class)
		{
			return 0F;
		}
		if (type == double.class)
		{
			return 0D;
		}
		return 0;
	}
}
