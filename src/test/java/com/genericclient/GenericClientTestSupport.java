package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.rules.TemporaryFolder;

final class GenericClientTestSupport
{
	private GenericClientTestSupport()
	{
	}

	static GenericClientScriptHost scriptHost(Path directory) throws Exception
	{
		return new GenericClientScriptHost(directory,
			context -> { throw new AssertionError("Unexpected random walk"); },
			(destination, context) -> { throw new AssertionError("Unexpected click"); },
			(request, boundary) ->
				{ throw new AssertionError("Unexpected route"); },
			(id, index, identity, name, action, within, context) -> { throw new AssertionError("Unexpected NPC input"); },
			(mode, context) -> { throw new AssertionError("Unexpected combat input"); },
			(type, arguments, context) -> { throw new AssertionError("Unexpected action " + type); },
			reason -> {}, behavior(directory.resolve("behavior")), System::nanoTime, message -> {});
	}

	static String javaScript(String className, String settings, String members)
	{
		return "import java.util.*;\n" +
			"import org.dreambot.api.script.*;\n" +
			"import com.genericclient.script.*;\n" +
			"@ScriptManifest(name=\"" + className + "\",author=\"Test\",category=Category.MISC,version=1)\n" +
			settings + "\npublic class " + className + " extends AbstractScript {\n" + members + "\n}\n";
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

	static Map<String, Object> receipt(String status)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", status);
		receipt.put("click_count", 0L);
		return receipt;
	}

	static void waitForStatus(GenericClientScriptHost host, String expected) throws InterruptedException
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

	static ScriptHostBuilder scriptHost(TemporaryFolder folders, String name) throws Exception
	{
		Path root = folders.newFolder(name).toPath();
		return new ScriptHostBuilder(root.resolve("scripts"), behavior(root.resolve("behavior")));
	}

	static ScriptHostBuilder scriptHost(
		Path scriptsDirectory,
		GenericClientBehaviorController behavior)
	{
		return new ScriptHostBuilder(scriptsDirectory, behavior);
	}

	static final class ScriptHostBuilder
	{
		private final Path scriptsDirectory;
		private GenericClientScriptActions.WalkRandomAction walkRandom =
			context -> CompletableFuture.completedFuture(interaction("unused"));
		private GenericClientScriptActions.WalkClickAction walkClick = (destination, context) ->
			CompletableFuture.completedFuture(new GenericClientInteractionResult(destination,
				"WALK_TILE_CLICK_UNAVAILABLE", false, Collections.emptyMap(), Collections.emptyMap()));
		private java.util.function.Consumer<String> cancel = reason -> { };
		private java.util.function.Consumer<String> report = message -> { };
		private java.util.function.LongSupplier nanoClock = System::nanoTime;
		private GenericClientScriptActions.WalkToAction walkTo =
			(request, clickBoundary) ->
				CompletableFuture.completedFuture(Collections.emptyMap());
		private GenericClientScriptActions.NpcInteractAction npcInteract =
			(id, index, identity, name, action, within, context) -> completedReceipt(
				"rejected", "npc.interact is unavailable in this host");
		private GenericClientScriptActions.CombatModeAction combatMode =
			(style, context) -> completedReceipt(
				"rejected", "combat.set_style is unavailable in this host");
		private GenericClientScriptActions.QuestAction questAction =
			(type, action, context) -> completedReceipt(
				"rejected", type + " is unavailable in this host");
		private final GenericClientBehaviorController behavior;

		private ScriptHostBuilder(
			Path scriptsDirectory,
			GenericClientBehaviorController behavior)
		{
			this.scriptsDirectory = scriptsDirectory;
			this.behavior = behavior;
		}

		ScriptHostBuilder walkRandom(GenericClientScriptActions.WalkRandomAction value)
		{
			walkRandom = value;
			return this;
		}

		ScriptHostBuilder walkClick(GenericClientScriptActions.WalkClickAction value) { walkClick = value; return this; }
		ScriptHostBuilder cancel(java.util.function.Consumer<String> value) { cancel = value; return this; }
		ScriptHostBuilder report(java.util.function.Consumer<String> value) { report = value; return this; }
		ScriptHostBuilder nanoClock(java.util.function.LongSupplier value) { nanoClock = value; return this; }

		ScriptHostBuilder walkTo(GenericClientScriptActions.WalkToAction value)
		{
			walkTo = value;
			return this;
		}

		ScriptHostBuilder npcInteract(GenericClientScriptActions.NpcInteractAction value)
		{
			npcInteract = value;
			return this;
		}

		ScriptHostBuilder combatMode(GenericClientScriptActions.CombatModeAction value)
		{
			combatMode = value;
			return this;
		}

		ScriptHostBuilder questAction(GenericClientScriptActions.QuestAction value)
		{
			questAction = value;
			return this;
		}

		GenericClientScriptHost build() throws Exception
		{
			return new GenericClientScriptHost(
				scriptsDirectory,
				walkRandom,
				walkClick,
				walkTo,
				npcInteract,
				combatMode,
				questAction,
				cancel,
				behavior,
				nanoClock,
				report);
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
