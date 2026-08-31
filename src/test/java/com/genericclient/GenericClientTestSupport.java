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
		return new GenericClientBehaviorController(
			new GenericClientBehaviorStore(directory),
			new GenericClientBehaviorController.BreakEffects()
			{
				@Override
				public CompletableFuture<String> moveOffscreen(GenericClientBehaviorProfile.Edge edge)
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
			new GenericClientBehaviorController.RandomSource()
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
			message -> { });
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
		private GenericClientLuaHost.WalkRandomAction walkRandom =
			context -> CompletableFuture.completedFuture(interaction("unused"));
		private GenericClientLuaHost.WalkToAction walkTo =
			(destination, within, timeout, context, useRun) ->
				CompletableFuture.completedFuture(Collections.emptyMap());
		private GenericClientLuaHost.NpcInteractAction npcInteract =
			(id, name, action, within, context) -> completedReceipt(
				"rejected", "npc.interact is unavailable in this host");
		private GenericClientLuaHost.CombatModeAction combatMode =
			(style, context) -> completedReceipt(
				"rejected", "combat.set_style is unavailable in this host");
		private GenericClientLuaHost.QuestAction questAction =
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

		LuaHostBuilder walkRandom(GenericClientLuaHost.WalkRandomAction value)
		{
			walkRandom = value;
			return this;
		}

		LuaHostBuilder walkTo(GenericClientLuaHost.WalkToAction value)
		{
			walkTo = value;
			return this;
		}

		LuaHostBuilder npcInteract(GenericClientLuaHost.NpcInteractAction value)
		{
			npcInteract = value;
			return this;
		}

		LuaHostBuilder combatMode(GenericClientLuaHost.CombatModeAction value)
		{
			combatMode = value;
			return this;
		}

		LuaHostBuilder questAction(GenericClientLuaHost.QuestAction value)
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
				walkTo,
				npcInteract,
				combatMode,
				questAction,
				reason -> { },
				behavior,
				message -> { },
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
