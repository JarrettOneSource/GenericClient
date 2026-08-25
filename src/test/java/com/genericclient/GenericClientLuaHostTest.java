package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientLuaHostTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void runsTickReadLogAndRealActionShapeEndToEnd() throws Exception
	{
		AtomicInteger walkRequests = new AtomicInteger();
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("scripts").toPath(),
			() ->
			{
				walkRequests.incrementAndGet();
				return CompletableFuture.completedFuture("WALK_CLICK_EXECUTED test");
			},
			(destination, within, timeout) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			message -> { });
		try
		{
			Files.writeString(
				temporaryFolder.getRoot().toPath().resolve("scripts/test.lua"),
				"return function()\n" +
				"  gc.await { event = 'game.tick' }\n" +
				"  local npcs = gc.read('npcs', { within = 15, limit = 1 })\n" +
				"  gc.log('info', 'npc-count', { count = #npcs })\n" +
				"  local receipt = gc.await { action = { type = 'walk.random' } }\n" +
				"  gc.log('info', 'walk-result', receipt)\n" +
				"end\n",
				StandardCharsets.UTF_8);

			host.start("test.lua").get(2, TimeUnit.SECONDS);
			host.publishGameTick(snapshot(1));
			waitForStatus(host, "COMPLETED");

			assertEquals(1, walkRequests.get());
			assertTrue(host.getRecentLogs().contains("npc-count"));
			assertTrue(host.getRecentLogs().contains("walk-result"));
			assertTrue(host.getRecentLogs().contains("dispatched"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void pinsOneSnapshotForTheEntireCoroutineResume() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("pinned-scripts").toPath(),
			() -> CompletableFuture.completedFuture("unused"),
			(destination, within, timeout) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			message -> { });
		try
		{
			Files.writeString(
				temporaryFolder.getRoot().toPath().resolve("pinned-scripts/pinned.lua"),
				"return function()\n" +
				"  gc.await { event = 'game.tick' }\n" +
				"  local first = gc.read('runtime').game_tick\n" +
				"  local second = gc.read('runtime').game_tick\n" +
				"  gc.log('info', 'pinned-frame', { first = first, second = second })\n" +
				"end\n",
				StandardCharsets.UTF_8);

			host.start("pinned.lua").get(2, TimeUnit.SECONDS);
			host.publishGameTick(snapshot(1));
			host.publishGameTick(snapshot(2));
			waitForStatus(host, "COMPLETED");

			assertTrue(host.getRecentLogs().contains("first=1.0"));
			assertTrue(host.getRecentLogs().contains("second=1.0"));
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void stopsAnInfiniteScriptWithTheInstructionHook() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("fault-scripts").toPath(),
			() -> CompletableFuture.completedFuture("unused"),
			(destination, within, timeout) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			message -> { });
		try
		{
			Files.writeString(
				temporaryFolder.getRoot().toPath().resolve("fault-scripts/infinite.lua"),
				"return function() while true do end end\n",
				StandardCharsets.UTF_8);

			try
			{
				host.start("infinite.lua").get(2, TimeUnit.SECONDS);
				throw new AssertionError("Expected infinite script to fail");
			}
			catch (ExecutionException expected)
			{
				assertTrue(expected.getCause().getMessage().contains("initialization"));
			}
		}
		finally
		{
			host.close();
		}
	}

	private static GenericClientSnapshot snapshot(long tick)
	{
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			231,
			new GenericClientSnapshot.PlayerSnapshot("Player", 3200, 3200, 0, -1),
			Collections.singletonList(new GenericClientSnapshot.NpcSnapshot(
				1,
				100,
				"Banker",
				3201,
				3200,
				0,
				1,
				0,
				-1,
				null,
				Collections.singletonList("Bank"))));
	}

	private static void waitForStatus(GenericClientLuaHost host, String expected) throws InterruptedException
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (!expected.equals(host.getStatus()) && System.nanoTime() < deadline)
		{
			Thread.sleep(10);
		}
		assertEquals(expected, host.getStatus());
	}
}
