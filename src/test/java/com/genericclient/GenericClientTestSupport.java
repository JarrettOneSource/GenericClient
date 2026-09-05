package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.rules.TemporaryFolder;

final class GenericClientTestSupport
{
	private GenericClientTestSupport()
	{
	}

	static GenericClientBehaviorController behavior(Path directory) throws Exception
	{
		return behavior(directory, edge -> { });
	}

	static GenericClientBehaviorController behavior(
		Path directory,
		Consumer<GenericClientBehaviorProfile.Edge> offscreen) throws Exception
	{
		GenericClientBehaviorController controller = new GenericClientBehaviorController(
			new GenericClientBehaviorStore(directory),
			new GenericClientBehaviorController.BreakEffects()
			{
				@Override
				public CompletableFuture<String> moveOffscreen(GenericClientBehaviorProfile.Edge edge, GenericClientActivityContext context)
				{
					offscreen.accept(edge);
					return CompletableFuture.completedFuture("offscreen");
				}

				@Override
				public CompletableFuture<String> logout()
				{
					return CompletableFuture.completedFuture("logout");
				}

				@Override
				public CompletableFuture<String> ensureLoggedIn()
				{
					return CompletableFuture.completedFuture("login");
				}
			},
			(task, delayMillis) -> () -> { },
			GenericClientBehaviorController.systemClock(),
			new java.util.Random()
			{
				@Override
				public double nextDouble()
				{
					return 0.999;
				}

				@Override
				public double nextGaussian()
				{
					return 0.0;
				}
			},
			() -> GenericClientPolicyResolver.Signals.CLEAR,
			message -> { });
		controller.activateAccount(1L);
		controller.setLoggedIn(true);
		return controller;
	}

	static GenericClientInteractionResult interaction(String detail)
	{
		return new GenericClientInteractionResult(
			null,
			detail,
			true,
			Collections.singletonMap("status", "bypassed"),
			Collections.singletonMap("status", "bypassed"));
	}

	static GenericClientSnapshot luaSnapshot(long tick)
	{
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			231,
			new GenericClientWorldSnapshot.PlayerSnapshot("Player", 3200, 3200, 0, -1),
			Collections.singletonList(new GenericClientWorldSnapshot.NpcSnapshot(
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


	static String script(String body)
	{
		return "return { run = function(input)\n" + body + "\nend }\n";
	}

	static Map<String, Object> receipt(String status)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", status);
		receipt.put("click_count", 0L);
		return receipt;
	}

	static void waitForStatus(GenericClientLuaHost host, String expected) throws InterruptedException
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (!expected.equals(host.getStatus()) && System.nanoTime() < deadline)
		{
			Thread.sleep(10);
		}
		assertEquals(expected, host.getStatus());
	}

	static GenericClientWalker walker(GenericClientWalker.WalkInput input, GenericClientWalker.ObstacleInput obstacles,
		GenericClientCollisionMap collisionMap, java.util.function.Consumer<String> reporter)
	{
		return walker(input, obstacles,
			(enabled, context) -> CompletableFuture.completedFuture(Collections.singletonMap("status", "unchanged")),
			(reason, owner) -> { }, collisionMap, reporter);
	}

	static GenericClientWalker walker(GenericClientWalker.WalkInput input, GenericClientWalker.ObstacleInput obstacles,
		java.util.function.BiFunction<Boolean, GenericClientActivityContext, CompletableFuture<Map<String, Object>>> setRun,
		java.util.function.BiConsumer<String, GenericClientActivityContext> cancelRun,
		GenericClientCollisionMap collisionMap, java.util.function.Consumer<String> reporter)
	{
		return new GenericClientWalker(input, obstacles, new GenericClientWalker.RunInput()
		{
			@Override public CompletableFuture<Map<String, Object>> setEnabled(boolean enabled, GenericClientActivityContext context)
			{ return setRun.apply(enabled, context); }
			@Override public void cancel(String reason, GenericClientActivityContext context) { cancelRun.accept(reason, context); }
		}, (step, frame, context) -> {
			GenericClientTransport.ObjectStep object = (GenericClientTransport.ObjectStep) step;
			return obstacles.interact(object.id, object.action, object.find(frame).getWorldPoint(), 8, context);
		}, collisionMap, edgeMemory(), reporter);
	}

	static GenericClientTransport transport(String id, net.runelite.api.coords.WorldPoint origin,
		net.runelite.api.coords.WorldPoint destination, int cost)
	{
		return new GenericClientTransport(id, origin, destination, cost,
			new net.runelite.api.coords.WorldArea(destination, 1, 1),
			java.util.List.of(new GenericClientTransport.ObjectStep(1, "Climb-up", new net.runelite.api.coords.WorldArea(origin, 1, 1))),
			java.util.List.of());
	}

	static GenericClientWalker walkerWithTransitions(GenericClientWalker.WalkInput input,
		GenericClientWalkTransitions.Input transitions) throws java.io.IOException
	{
		return new GenericClientWalker(input, new GenericClientWalkTestFixtures.FakeObstacleInput(),
			new GenericClientWalker.RunInput()
			{
				@Override public CompletableFuture<Map<String, Object>> setEnabled(boolean enabled, GenericClientActivityContext context)
				{ return CompletableFuture.completedFuture(Map.of("status", "unchanged")); }
				@Override public void cancel(String reason, GenericClientActivityContext context) { }
			}, transitions, GenericClientCollisionMap.loadBundled(), edgeMemory(), message -> { });
	}

	static GenericClientEdgeMemory edgeMemory()
	{
		try
		{
			Path directory = java.nio.file.Files.createTempDirectory("genericclient-test-navigation-");
			directory.toFile().deleteOnExit();
			String id = GenericClientBehaviorProfile.fromAccountHash(42).getId();
			directory.resolve("edges-" + id + ".json").toFile().deleteOnExit();
			GenericClientEdgeMemory memory = new GenericClientEdgeMemory(directory, System::currentTimeMillis, message -> { });
			memory.activateAccount(42);
			return memory;
		}
		catch (java.io.IOException exception)
		{
			throw new java.io.UncheckedIOException(exception);
		}
	}

	static LuaHostBuilder luaHost(TemporaryFolder folders, String name) throws Exception
	{
		Path root = folders.newFolder(name).toPath();
		return new LuaHostBuilder(root.resolve("scripts"), behavior(root.resolve("behavior")));
	}

	static LuaHostBuilder luaHost(
		Path scriptsDirectory,
		GenericClientBehaviorController behavior)
	{
		return new LuaHostBuilder(scriptsDirectory, behavior);
	}

	static final class LuaHostBuilder
	{
		private final Path scriptsDirectory;
		private GenericClientLuaActions.WalkRandomAction walkRandom =
			context -> CompletableFuture.completedFuture(interaction("unused"));
		private GenericClientLuaActions.WalkClickAction walkClick = (destination, context) ->
			CompletableFuture.completedFuture(new GenericClientInteractionResult(destination,
				"WALK_TILE_CLICK_UNAVAILABLE", false, Collections.emptyMap(), Collections.emptyMap()));
		private java.util.function.Consumer<String> cancel = reason -> { };
		private java.util.function.Consumer<String> report = message -> { };
		private GenericClientLuaActions.WalkToAction walkTo =
			(request, clickBoundary) ->
				CompletableFuture.completedFuture(Collections.emptyMap());
		private GenericClientLuaActions.NpcInteractAction npcInteract =
			(id, name, action, within, context) -> completedReceipt(
				"rejected", "npc.interact is unavailable in this host");
		private GenericClientLuaActions.CombatModeAction combatMode =
			(style, context) -> completedReceipt(
				"rejected", "combat.set_style is unavailable in this host");
		private GenericClientLuaActions.QuestAction questAction =
			(type, action, context) -> completedReceipt(
				"rejected", type + " is unavailable in this host");
		private final GenericClientBehaviorController behavior;
		private LongSupplier clock = System::nanoTime;

		private LuaHostBuilder(
			Path scriptsDirectory,
			GenericClientBehaviorController behavior)
		{
			this.scriptsDirectory = scriptsDirectory;
			this.behavior = behavior;
		}

		LuaHostBuilder walkRandom(GenericClientLuaActions.WalkRandomAction value)
		{
			walkRandom = value;
			return this;
		}

		LuaHostBuilder walkClick(GenericClientLuaActions.WalkClickAction value) { walkClick = value; return this; }
		LuaHostBuilder cancel(java.util.function.Consumer<String> value) { cancel = value; return this; }
		LuaHostBuilder report(java.util.function.Consumer<String> value) { report = value; return this; }

		LuaHostBuilder walkTo(GenericClientLuaActions.WalkToAction value)
		{
			walkTo = value;
			return this;
		}

		LuaHostBuilder npcInteract(GenericClientLuaActions.NpcInteractAction value)
		{
			npcInteract = value;
			return this;
		}

		LuaHostBuilder combatMode(GenericClientLuaActions.CombatModeAction value)
		{
			combatMode = value;
			return this;
		}

		LuaHostBuilder questAction(GenericClientLuaActions.QuestAction value)
		{
			questAction = value;
			return this;
		}

		LuaHostBuilder clock(LongSupplier value)
		{
			clock = value;
			return this;
		}

		GenericClientLuaHost build() throws Exception
		{
			return new GenericClientLuaHost(
				scriptsDirectory,
				walkRandom,
				walkClick,
				walkTo,
				npcInteract,
				combatMode,
				questAction,
				cancel,
				behavior,
				report,
				clock);
		}

		private static CompletableFuture<Map<String, Object>> completedReceipt(
			String status,
			String result)
		{
			Map<String, Object> receipt = new LinkedHashMap<>();
			receipt.put("status", status);
			receipt.put("result", result);
			return CompletableFuture.completedFuture(receipt);
		}
	}
}
