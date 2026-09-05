package com.genericclient;

import static org.junit.Assert.*;

import com.genericclient.script.ScriptEnvironment;
import com.genericclient.script.ScriptScope;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import org.dreambot.api.methods.interactive.NPCs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientEntityQueryRaceTest
{
	@Rule public TemporaryFolder folders = new TemporaryFolder();

	@Test public void logoutBetweenLocalPresenceAndPositionReturnsNoClosestTarget() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(folders.newFolder().toPath()))
		{
			host.publishGameTick(frame(true));
			AtomicBoolean loggedOut = new AtomicBoolean();
			ScriptScope scope = readScope((subject, query) -> {
				Object captured = host.read(subject, query);
				if (subject.equals("entity") && "player".equals(query.get("kind")) && !loggedOut.getAndSet(true))
					host.clearSnapshot();
				return captured;
			});
			try (scope)
			{
				assertNull(NPCs.closest(npc -> true));
				assertTrue(loggedOut.get());
			}
		}
	}

	@Test public void despawningBetweenFilteringAndRankingSelectsTheNextLiveTarget() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(folders.newFolder().toPath()))
		{
			host.publishGameTick(frame(true));
			AtomicBoolean filtered = new AtomicBoolean();
			AtomicBoolean despawned = new AtomicBoolean();
			ScriptScope scope = readScope((subject, query) -> {
				Object captured = host.read(subject, query);
				if (filtered.get() && subject.equals("entity") && Long.valueOf(123).equals(query.get("identity")) && !despawned.getAndSet(true))
					host.publishGameTick(frame(false));
				return captured;
			});
			try (scope)
			{
				assertEquals(124, NPCs.closest(npc -> {
					if (npc.getId() == 124) filtered.set(true);
					return true;
				}).getId());
				assertTrue(despawned.get());
			}
		}
	}

	private static ScriptScope readScope(BiFunction<String, Map<String, Object>, Object> reader)
	{
		ScriptEnvironment environment = (ScriptEnvironment) Proxy.newProxyInstance(ScriptEnvironment.class.getClassLoader(),
			new Class<?>[]{ScriptEnvironment.class}, (proxy, method, arguments) -> {
				if (!method.getName().equals("read")) throw new AssertionError("A query attempted input: " + method.getName());
				Map<String, Object> query = new LinkedHashMap<>();
				((Map<?, ?>) arguments[1]).forEach((key, value) -> query.put((String) key, value));
				return reader.apply((String) arguments[0], query);
			});
		return new ScriptScope(environment);
	}

	private static GenericClientSnapshot frame(boolean firstPresent)
	{
		List<GenericClientNpcSnapshot> npcs = new ArrayList<>();
		for (int id = firstPresent ? 123 : 124; id <= 124; id++)
			npcs.add(new GenericClientNpcSnapshot(id, id, id, "Target", 3200 + id - 122, 3200, 0, id - 122, 1, -1, null, List.of("Talk-to")));
		return new GenericClientSnapshot(firstPresent ? 1 : 2, "LOGGED_IN", 240,
			new GenericClientPlayerSnapshot(1L, "Player", 3200, 3200, 0, -1), npcs);
	}
}
