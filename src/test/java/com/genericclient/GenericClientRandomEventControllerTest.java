package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

public class GenericClientRandomEventControllerTest
{
	private static final Clock CLOCK = Clock.fixed(
		Instant.parse("2026-08-29T15:30:00Z"), ZoneOffset.UTC);

	@Test
	@SuppressWarnings("unchecked")
	public void detectsOwnedEventAndKeepsItLatchedAfterDespawn() throws Exception
	{
		RuntimeStub runtime = new RuntimeStub();
		List<String> alerts = new ArrayList<>();
		List<String> reports = new ArrayList<>();
		GenericClientRandomEventController controller = new GenericClientRandomEventController(
			id -> id == NpcID.MACRO_MILES ? "miles-solver" : null,
			runtime,
			alerts::add,
			reports::add,
			CLOCK);
		Player player = player(null);
		NPC miles = npc(
			NpcID.MACRO_MILES,
			"Miles",
			73,
			new WorldPoint(3162, 3487, 0),
			new String[]{"Talk-to", "Dismiss", null, null, null});

		controller.onInteractingChanged(player, new InteractingChanged(miles, player), 5936L);
		Map<String, Object> status = controller.status();

		assertTrue((Boolean) status.get("active"));
		assertTrue((Boolean) status.get("blocks_automation"));
		assertFalse((Boolean) status.get("attention_required"));
		assertEquals("solver_running", status.get("state"));
		assertEquals((long) NpcID.MACRO_MILES, status.get("npc_id"));
		assertEquals("Miles", status.get("npc_name"));
		assertEquals(73L, status.get("npc_index"));
		assertEquals(5936L, status.get("detected_tick"));
		assertEquals("2026-08-29T15:30:00Z", status.get("detected_at"));
		assertEquals("miles-solver", status.get("solver_script"));
		assertEquals(List.of("Talk-to", "Dismiss"), status.get("actions"));
		assertEquals(3162L, ((Map<String, Object>) status.get("world")).get("x"));
		assertEquals("miles-solver", runtime.solverScript);
		assertTrue(runtime.interrupted);
		assertEquals(1, alerts.size());
		assertTrue(alerts.get(0).contains("Miles"));
		assertTrue(reports.get(0).contains("RANDOM_EVENT_DETECTED"));

		controller.onNpcDespawned(new NpcDespawned(miles), 5940L);
		status = controller.status();
		assertTrue((Boolean) status.get("active"));
		assertFalse((Boolean) status.get("present"));
		assertEquals(5940L, status.get("despawned_tick"));
	}

	@Test
	public void rejectsEventsThatAreNotOwnedByTheLocalPlayer()
	{
		RuntimeStub runtime = new RuntimeStub();
		GenericClientRandomEventController controller = controller(runtime, id -> null);
		NPC miles = npc(
			NpcID.MACRO_MILES, "Miles", 1, new WorldPoint(3200, 3200, 0), new String[]{"Talk-to"});
		Player local = player(miles);
		Player other = player(null);

		controller.onInteractingChanged(local, new InteractingChanged(miles, local), 1L);
		controller.onInteractingChanged(local, new InteractingChanged(miles, other), 2L);
		controller.onInteractingChanged(local, new InteractingChanged(other, local), 3L);

		assertFalse((Boolean) controller.status().get("active"));
		assertFalse(runtime.interrupted);
	}

	@Test
	public void unknownEventRequiresAttentionUntilExplicitCompletion() throws Exception
	{
		RuntimeStub runtime = new RuntimeStub();
		GenericClientRandomEventController controller = controller(runtime, id -> null);
		Player player = player(null);
		NPC genie = npc(
			NpcID.MACRO_GENI, "Genie", 4, new WorldPoint(3200, 3200, 0), new String[]{"Talk-to"});

		controller.onInteractingChanged(player, new InteractingChanged(genie, player), 10L);
		assertTrue((Boolean) controller.status().get("attention_required"));
		assertEquals("unregistered", controller.status().get("solver_status"));

		Map<String, Object> acknowledged = controller.acknowledge().get(2, TimeUnit.SECONDS);
		assertTrue((Boolean) acknowledged.get("acknowledged"));
		assertTrue((Boolean) acknowledged.get("attention_required"));
		assertFalse(runtime.released);

		Map<String, Object> completed = controller.complete("solved_from_repl", true)
			.get(2, TimeUnit.SECONDS);
		assertFalse((Boolean) completed.get("active"));
		assertFalse((Boolean) completed.get("blocks_automation"));
		assertEquals("completed", completed.get("state"));
		assertEquals("solved_from_repl", completed.get("resolution"));
		assertTrue(runtime.released);
		assertTrue(runtime.resumeInterrupted);
	}

	@Test
	public void solverFaultKeepsTheEventBlocked()
	{
		RuntimeStub runtime = new RuntimeStub();
		GenericClientRandomEventController controller = controller(runtime, id -> "genie-solver");
		Player player = player(null);
		NPC genie = npc(
			NpcID.MACRO_GENI, "Genie", 4, new WorldPoint(3200, 3200, 0), new String[]{"Talk-to"});

		controller.onInteractingChanged(player, new InteractingChanged(genie, player), 10L);
		String eventKey = String.valueOf(controller.status().get("event_key"));
		controller.solverFinished(eventKey, "FAULTED", "wrong widget");

		Map<String, Object> status = controller.status();
		assertTrue((Boolean) status.get("active"));
		assertTrue((Boolean) status.get("attention_required"));
		assertEquals("attention_required", status.get("state"));
		assertEquals("faulted", status.get("solver_status"));
		assertEquals("wrong widget", status.get("last_error"));
		assertFalse(runtime.released);
	}

	@Test
	public void completedSolverReleasesAndResumesTheInterruptedScript() throws Exception
	{
		RuntimeStub runtime = new RuntimeStub();
		GenericClientRandomEventController controller = controller(runtime, id -> "genie-solver");
		Player player = player(null);
		NPC genie = npc(
			NpcID.MACRO_GENI, "Genie", 4, new WorldPoint(3200, 3200, 0), new String[]{"Talk-to"});

		controller.onInteractingChanged(player, new InteractingChanged(genie, player), 10L);
		String eventKey = String.valueOf(controller.status().get("event_key"));
		controller.solverFinished(eventKey, "COMPLETED", null);
		Map<String, Object> status = controller.status();

		assertFalse((Boolean) status.get("active"));
		assertEquals("completed", status.get("state"));
		assertEquals("solver_completed", status.get("resolution"));
		assertTrue(runtime.released);
		assertTrue(runtime.resumeInterrupted);
	}

	@Test
	public void synchronousRuntimeFailureLeavesTheEventLatchedForAttention() throws Exception
	{
		GenericClientRandomEventController controller = new GenericClientRandomEventController(
			id -> "genie-solver",
			new GenericClientRandomEventController.Runtime()
			{
				@Override
				public CompletableFuture<String> interrupt(String eventKey, String solverScript)
				{
					throw new IllegalStateException("runtime unavailable");
				}

				@Override
				public CompletableFuture<String> release(
					String eventKey,
					boolean resumeInterrupted)
				{
					throw new IllegalStateException("release unavailable");
				}
			},
			message -> { },
			message -> { },
			CLOCK);
		Player player = player(null);
		NPC genie = npc(
			NpcID.MACRO_GENI, "Genie", 4, new WorldPoint(3200, 3200, 0), new String[]{"Talk-to"});

		controller.onInteractingChanged(player, new InteractingChanged(genie, player), 10L);
		assertEquals("start_failed", controller.status().get("solver_status"));
		assertTrue((Boolean) controller.status().get("attention_required"));

		Map<String, Object> result = controller.complete("manual_attempt", false)
			.get(2, TimeUnit.SECONDS);
		assertTrue((Boolean) result.get("active"));
		assertEquals("attention_required", result.get("state"));
		assertEquals("release unavailable", result.get("last_error"));
	}

	private static GenericClientRandomEventController controller(
		RuntimeStub runtime,
		GenericClientRandomEventController.SolverLookup lookup)
	{
		return new GenericClientRandomEventController(
			lookup, runtime, message -> { }, message -> { }, CLOCK);
	}

	private static Player player(Actor interacting)
	{
		return proxy(Player.class, (method, arguments) ->
		{
			if ("getInteracting".equals(method.getName()))
			{
				return interacting;
			}
			if ("getName".equals(method.getName()))
			{
				return "Player";
			}
			return defaultValue(method.getReturnType());
		});
	}

	private static NPC npc(
		int id,
		String name,
		int index,
		WorldPoint world,
		String[] actions)
	{
		NPCComposition composition = proxy(NPCComposition.class, (method, arguments) ->
			"getActions".equals(method.getName()) ? actions : defaultValue(method.getReturnType()));
		return proxy(NPC.class, (method, arguments) ->
		{
			switch (method.getName())
			{
				case "getId":
					return id;
				case "getName":
					return name;
				case "getIndex":
					return index;
				case "getWorldLocation":
					return world;
				case "getComposition":
				case "getTransformedComposition":
					return composition;
				default:
					return defaultValue(method.getReturnType());
			}
		});
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Invocation invocation)
	{
		return (T) Proxy.newProxyInstance(
			type.getClassLoader(),
			new Class<?>[]{type},
			(instance, method, arguments) ->
			{
				if ("equals".equals(method.getName()))
				{
					return instance == arguments[0];
				}
				if ("hashCode".equals(method.getName()))
				{
					return System.identityHashCode(instance);
				}
				if ("toString".equals(method.getName()))
				{
					return type.getSimpleName();
				}
				return invocation.invoke(method, arguments);
			});
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
		if (type == byte.class)
		{
			return (byte) 0;
		}
		if (type == short.class)
		{
			return (short) 0;
		}
		if (type == int.class)
		{
			return 0;
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
		if (type == char.class)
		{
			return '\0';
		}
		return null;
	}

	@FunctionalInterface
	private interface Invocation
	{
		Object invoke(Method method, Object[] arguments);
	}

	private static final class RuntimeStub implements GenericClientRandomEventController.Runtime
	{
		private boolean interrupted;
		private boolean released;
		private boolean resumeInterrupted;
		private String solverScript;

		@Override
		public CompletableFuture<String> interrupt(String eventKey, String solverScript)
		{
			interrupted = true;
			this.solverScript = solverScript;
			return CompletableFuture.completedFuture(
				solverScript == null ? "RANDOM_EVENT_BLOCKED" : "RANDOM_EVENT_SOLVER_STARTED");
		}

		@Override
		public CompletableFuture<String> release(String eventKey, boolean resumeInterrupted)
		{
			released = true;
			this.resumeInterrupted = resumeInterrupted;
			return CompletableFuture.completedFuture("RANDOM_EVENT_RELEASED");
		}
	}
}
