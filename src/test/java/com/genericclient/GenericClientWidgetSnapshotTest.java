package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.awt.Rectangle;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.HashTable;
import net.runelite.api.WidgetNode;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

public class GenericClientWidgetSnapshotTest
{
	@Test
	public void capturesAndFiltersVisibleWidgetFacts()
	{
		Widget label = widget(
			1703958, 0, "<col=ffffff>COINS</col>", null, new Rectangle(100, 100, 50, 20));
		Widget arrow = widget(
			1703941, 0, "", new String[]{"Select"}, new Rectangle(90, 80, 20, 20));
		Widget root = widget(
			1703936, 0, "", null, new Rectangle(50, 50, 400, 300), label, arrow);
		Client client = proxy(Client.class, (method, arguments) ->
			"getWidgetRoots".equals(method) ? new Widget[]{root} : null);

		GenericClientWidgetSnapshot snapshot = GenericClientWidgetSnapshot.capture(client);
		Map<String, Object> query = new LinkedHashMap<>();
		query.put("ids", Arrays.asList(1703958L, 1703941L));
		List<Map<String, Object>> values = snapshot.read(query);

		assertEquals(2, values.size());
		assertEquals(1703941L, values.get(0).get("id"));
		assertEquals(Arrays.asList("Select"), values.get(0).get("actions"));
		assertEquals("COINS", values.get(1).get("text"));
		assertEquals(26L, values.get(1).get("group_id"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void capturesAWidgetFromAnAttachedInterface()
	{
		Widget yes = widget(
			18939912, 0, "Yes", null, new Rectangle(307, 201, 40, 32));
		Widget popup = widget(
			18939904, 0, "", null, new Rectangle(35, 125, 440, 120), yes);
		WidgetNode node = proxy(WidgetNode.class, (method, arguments) ->
			"getId".equals(method) ? 289 : null);
		HashTable<WidgetNode> components = proxy(HashTable.class, (method, arguments) ->
			"iterator".equals(method)
				? Collections.singletonList(node).iterator()
				: null);
		Client client = proxy(Client.class, (method, arguments) ->
		{
			if ("getWidgetRoots".equals(method))
			{
				return new Widget[0];
			}
			if ("getComponentTable".equals(method))
			{
				return components;
			}
			if ("getWidget".equals(method) && arguments != null && arguments.length == 2)
			{
				return popup;
			}
			return null;
		});

		GenericClientWidgetSnapshot snapshot = GenericClientWidgetSnapshot.capture(client);
		Map<String, Object> query = new LinkedHashMap<>();
		query.put("id", 18939912L);
		List<Map<String, Object>> values = snapshot.read(query);

		assertEquals(1, values.size());
		assertEquals("Yes", values.get(0).get("text"));
	}

	private static Widget widget(
		int id,
		int index,
		String text,
		String[] actions,
		Rectangle bounds,
		Widget... children)
	{
		return proxy(Widget.class, (method, arguments) ->
		{
			switch (method)
			{
				case "getId":
					return id;
				case "getIndex":
					return index;
				case "getText":
					return text;
				case "getActions":
					return actions;
				case "getBounds":
					return bounds;
				case "getChildren":
					return children;
				case "getItemId":
				case "getModelId":
					return -1;
				default:
					return null;
			}
		});
	}

	private static <T> T proxy(Class<T> type, Invocation invocation)
	{
		return type.cast(Proxy.newProxyInstance(
			type.getClassLoader(),
			new Class<?>[]{type},
			(proxy, method, arguments) ->
			{
				Object value = invocation.invoke(method.getName(), arguments);
				if (value != null || !method.getReturnType().isPrimitive())
				{
					return value;
				}
				return method.getReturnType() == boolean.class ? false : 0;
			}));
	}

	@FunctionalInterface
	private interface Invocation
	{
		Object invoke(String method, Object[] arguments);
	}
}
