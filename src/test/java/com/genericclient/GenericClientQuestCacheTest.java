package com.genericclient;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import org.junit.Test;

public class GenericClientQuestCacheTest
{
	@Test
	public void copiesNumericMidquestProgressBeforeTheNativeStackIsReused()
	{
		AtomicInteger progress = new AtomicInteger(8);
		int[] stack = {0};
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				if ("getIntStack".equals(method.getName())) return stack;
				if ("runScript".equals(method.getName()))
				{
					Object[] script = (Object[]) args[0];
					int scriptId = (Integer) script[0];
					assertTrue(scriptId == 4029 || scriptId == 4024);
					int questId = (Integer) script[1];
					stack[0] = scriptId == 4029 ? 0 : questId == Quest.values()[0].getId() ? progress.get() : -1;
					return null;
				}
				throw new AssertionError("Unexpected client call: " + method.getName());
			});
		GenericClientQuestCache cache = new GenericClientQuestCache();
		GenericClientAccountSnapshot.QuestListSnapshot first = cache.capture(client, 100);
		assertEquals(8, quest(first, 0).get("progress"));
		assertEquals("in_progress", quest(first, 0).get("state"));
		assertNull(quest(first, 1).get("progress"));
		progress.set(9);
		assertEquals(8, quest(cache.capture(client, 109), 0).get("progress"));
		GenericClientAccountSnapshot.QuestListSnapshot refreshed = cache.capture(client, 110);
		assertEquals(9, quest(refreshed, 0).get("progress"));
		assertEquals("in_progress", quest(refreshed, 0).get("state"));
		assertEquals(8, quest(first, 0).get("progress"));
		progress.set(10);
		cache.clear();
		assertEquals(10, quest(cache.capture(client, 1), 0).get("progress"));
	}

	private static Map<?, ?> quest(GenericClientAccountSnapshot.QuestListSnapshot snapshot, int index)
	{
		return (Map<?, ?>) snapshot.toMap().get(Quest.values()[index].name().toLowerCase(Locale.ROOT));
	}
}
