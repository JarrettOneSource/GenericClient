package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.gameval.NpcID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientRandomEventLuaHostTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	@SuppressWarnings("unchecked")
	public void exposesTheLatchedRandomEventThroughLua() throws Exception
	{
		HostFixture fixture = fixture("read");
		try
		{
			Map<String, Object> randomEvent = new LinkedHashMap<>();
			randomEvent.put("active", true);
			randomEvent.put("npc_id", (long) NpcID.MACRO_MILES);
			fixture.host.setRandomEventHooks(() -> randomEvent, (key, status, error) -> { });
			fixture.host.publishGameTick(snapshot(1L));

			Map<String, Object> result = fixture.host.evaluate(
				"return gc.read('random_event')").get(2, TimeUnit.SECONDS);
			Map<String, Object> value = (Map<String, Object>) result.get("value");

			assertEquals(true, value.get("active"));
			assertEquals(NpcID.MACRO_MILES, ((Number) value.get("npc_id")).intValue());
		}
		finally
		{
			fixture.close();
		}
	}

	@Test
	public void preemptsManualScriptRunsSolverAndResumesAfterExplicitRelease() throws Exception
	{
		HostFixture fixture = fixture("interrupt");
		AtomicReference<String> terminal = new AtomicReference<>();
		try
		{
			fixture.host.setRandomEventHooks(
				Collections::emptyMap,
				(key, status, error) -> terminal.set(key + ":" + status));
			fixture.host.saveScript(
				"worker",
				"Worker",
				"Wait until stopped.",
				"return { run = function() while true do gc.await { event = 'game.tick' } end end }\n")
				.get(2, TimeUnit.SECONDS);
			fixture.host.saveScript(
				"miles-solver",
				"Miles Solver",
				"Return only after the observed event is solved.",
				"return { run = function() return 'solved' end }\n",
				List.of(NpcID.MACRO_MILES))
				.get(2, TimeUnit.SECONDS);
			fixture.host.start("worker").get(2, TimeUnit.SECONDS);
			assertEquals("worker", fixture.host.getActiveScript());

			String interrupted = fixture.host.interruptForRandomEvent("10:5437:4")
				.get(2, TimeUnit.SECONDS);
			String solverStarted = fixture.host.startRandomEventSolver(
				"10:5437:4", "miles-solver").get(2, TimeUnit.SECONDS);

			assertTrue(interrupted.contains("RANDOM_EVENT_BLOCKED"));
			assertTrue(solverStarted.contains("RANDOM_EVENT_SOLVER_STARTED"));
			assertEquals("10:5437:4:COMPLETED", terminal.get());
			assertEquals("miles-solver", fixture.host.getActiveScript());
			assertEquals("COMPLETED", fixture.host.getStatus());
			try
			{
				fixture.host.start("walker").get(2, TimeUnit.SECONDS);
				throw new AssertionError("Expected normal scripts to remain blocked");
			}
			catch (ExecutionException exception)
			{
				assertTrue(exception.getCause().getMessage().contains("random event"));
			}

			String released = fixture.host.releaseRandomEvent("10:5437:4", true)
				.get(2, TimeUnit.SECONDS);
			assertTrue(released.contains("RANDOM_EVENT_RELEASED"));
			assertEquals("worker", fixture.host.getActiveScript());
			assertTrue(fixture.host.getRunState().isRunning());
			assertTrue(fixture.host.getRunState().isManual());
		}
		finally
		{
			fixture.close();
		}
	}

	@Test
	public void unknownEventBlocksNewStandaloneScriptsUntilReleased() throws Exception
	{
		HostFixture fixture = fixture("unknown");
		try
		{
			fixture.host.interruptForRandomEvent("20:326:2").get(2, TimeUnit.SECONDS);
			assertTrue(fixture.host.isRandomEventBlocked());

			try
			{
				fixture.host.start("walker").get(2, TimeUnit.SECONDS);
				throw new AssertionError("Expected random-event block");
			}
			catch (ExecutionException exception)
			{
				assertTrue(exception.getCause().getMessage().contains("random event"));
			}

			fixture.host.releaseRandomEvent("20:326:2", false).get(2, TimeUnit.SECONDS);
			assertFalse(fixture.host.isRandomEventBlocked());
			assertTrue(fixture.host.start("walker").get(2, TimeUnit.SECONDS).contains("LUA_STARTED"));
		}
		finally
		{
			fixture.close();
		}
	}

	@Test
	public void lateActionCompletionCannotResumeTheInterruptedCoroutine() throws Exception
	{
		CompletableFuture<GenericClientInteractionResult> pendingWalk = new CompletableFuture<>();
		GenericClientBehaviorController behavior = GenericClientTestSupport.behavior(
			temporaryFolder.newFolder("late-action-behavior").toPath());
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("late-action-scripts").toPath(),
			breaks -> pendingWalk,
			(destination, within, timeout, breaks, run) ->
				CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			behavior,
			message -> { });
		try
		{
			host.saveScript(
				"worker",
				"Worker",
				"Wait on one client action.",
				"return { run = function() gc.await { action = { type = 'walk.random' } }; " +
					"gc.log('info', 'resumed_after_action') end }\n")
				.get(2, TimeUnit.SECONDS);
			host.start("worker").get(2, TimeUnit.SECONDS);

			host.interruptForRandomEvent("30:326:7").get(2, TimeUnit.SECONDS);
			pendingWalk.complete(GenericClientTestSupport.interaction("late"));
			Thread.sleep(50L);

			assertEquals("ATTENTION_REQUIRED", host.getStatus());
			assertFalse(host.getRecentLogs().contains("resumed_after_action"));
		}
		finally
		{
			host.close();
			behavior.close();
		}
	}

	@Test
	public void interruptsBusyReplThenAllowsDiagnosticReplDuringTheLatch() throws Exception
	{
		HostFixture fixture = fixture("repl-interrupt");
		try
		{
			Map<String, Object> randomEvent = new LinkedHashMap<>();
			randomEvent.put("active", true);
			randomEvent.put("npc_name", "Genie");
			fixture.host.setRandomEventHooks(() -> randomEvent, (key, status, error) -> { });
			CompletableFuture<Map<String, Object>> busy = fixture.host.evaluate(
				"gc.await { ticks = 100 }; return 'late'");
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (!Boolean.TRUE.equals(fixture.host.controlState().get("repl_busy")) &&
				System.nanoTime() < deadline)
			{
				Thread.sleep(10L);
			}
			assertEquals(true, fixture.host.controlState().get("repl_busy"));

			fixture.host.interruptForRandomEvent("40:326:8").get(2, TimeUnit.SECONDS);

			assertTrue(busy.isCompletedExceptionally());
			Map<String, Object> diagnostic = fixture.host.evaluate(
				"return gc.read('random_event')").get(2, TimeUnit.SECONDS);
			assertEquals("Genie",
				((Map<?, ?>) diagnostic.get("value")).get("npc_name"));
		}
		finally
		{
			fixture.close();
		}
	}

	@Test
	public void failedManualResumeDoesNotRelatchTheCompletedEvent() throws Exception
	{
		HostFixture fixture = fixture("resume-failure");
		try
		{
			fixture.host.saveScript(
				"worker",
				"Worker",
				"Wait until interrupted.",
				"return { run = function() while true do gc.await { event = 'game.tick' } end end }\n")
				.get(2, TimeUnit.SECONDS);
			fixture.host.start("worker").get(2, TimeUnit.SECONDS);
			fixture.host.interruptForRandomEvent("50:326:9").get(2, TimeUnit.SECONDS);
			fixture.host.saveScript(
				"worker",
				"Worker",
				"Broken after interruption.",
				"this is not lua\n")
				.get(2, TimeUnit.SECONDS);

			String released = fixture.host.releaseRandomEvent("50:326:9", true)
				.get(2, TimeUnit.SECONDS);

			assertTrue(released.contains("resume_error="));
			assertFalse(fixture.host.isRandomEventBlocked());
		}
		finally
		{
			fixture.close();
		}
	}

	private HostFixture fixture(String name) throws Exception
	{
		Path scripts = temporaryFolder.newFolder(name + "-scripts").toPath();
		GenericClientBehaviorController behavior = GenericClientTestSupport.behavior(
			temporaryFolder.newFolder(name + "-behavior").toPath());
		GenericClientLuaHost host = new GenericClientLuaHost(
			scripts,
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks, run) ->
				CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			behavior,
			message -> { });
		return new HostFixture(host, behavior);
	}

	private static GenericClientSnapshot snapshot(long tick)
	{
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientSnapshot.PlayerSnapshot("Player", 3200, 3200, 0, -1),
			Collections.emptyList());
	}

	private static final class HostFixture implements AutoCloseable
	{
		private final GenericClientLuaHost host;
		private final GenericClientBehaviorController behavior;

		private HostFixture(
			GenericClientLuaHost host,
			GenericClientBehaviorController behavior)
		{
			this.host = host;
			this.behavior = behavior;
		}

		@Override
		public void close()
		{
			host.close();
			behavior.close();
		}
	}
}
