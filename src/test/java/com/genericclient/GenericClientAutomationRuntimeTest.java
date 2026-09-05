package com.genericclient;

import static org.junit.Assert.*;

import java.awt.Canvas;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyManager;

public class GenericClientAutomationRuntimeTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void runtimeEndsTheBreakOnlyWhenTheResolvedDamageIsUnexpected() throws Exception
	{
		for (String activity : new String[]{"skilling", "combat"})
		{
			Path directory = temporary.newFolder().toPath();
			GenericClientBehaviorState state = new GenericClientBehaviorState(
				GenericClientBehaviorProfile.fromAccountHash(42).getId(), 1.0, 1.0);
			long now = System.currentTimeMillis();
			state.startBreak("micro", "none", now, now + 120_000);
			new GenericClientBehaviorStore(directory.resolve("behavior")).save(state, now);
			try (Fixture fixture = new Fixture(directory); GenericClientAutomationRuntime runtime = fixture.start())
			{
				fixture.gameState.set(GameState.LOGGED_IN);
				fixture.player = player();
				GenericClientSnapshot baseline = GenericClientDamageTrackerTest.snapshot(10, 80, 6);
				runtime.scriptHost.publishGameTick(baseline);
				runtime.scriptHost.compile("Damage", GenericClientTestSupport.javaScript("Damage", "",
					"public int onLoop(){Automation.activity(\"" + activity + "\", Map.of(\"breaks\",true));log(\"waiting\");org.dreambot.api.utilities.Sleep.sleepTicks(100);return -1;}"))
					.get(2, TimeUnit.SECONDS);
				runtime.scriptHost.start("Damage").get(2, TimeUnit.SECONDS);
				GenericClientScriptHostTest.await(() -> runtime.scriptHost.getRecentLogs().contains("waiting"));
				runtime.behaviorController.activateAccount(42);
				runtime.publishGameTick(baseline);
				runtime.recordHitsplat(hit(fixture.player, new int[]{HitsplatID.POISON, 2}));
				runtime.publishGameTick(GenericClientDamageTrackerTest.snapshot(11, 78, 5));
				assertTrue(runtime.behaviorController.isPaused());
				assertEquals("expected_poison", runtime.combatGuard.status().get("damage_type"));
				runtime.recordHitsplat(hit(fixture.player, new int[]{HitsplatID.DAMAGE_ME, 3}));
				runtime.recordHitsplat(hit(fixture.player, new int[]{HitsplatID.HEAL, 3}));
				runtime.publishGameTick(GenericClientDamageTrackerTest.snapshot(12, 78, 5));
				assertEquals(activity, runtime.scriptHost.getActivity());
				if ("combat".equals(activity))
				{
					assertTrue(runtime.behaviorController.isPaused());
					assertEquals(1L, fixture.breakEnded.getCount());
				}
				else
				{
					assertTrue("Unexpected damage did not end the active break", fixture.breakEnded.await(5, TimeUnit.SECONDS));
					assertFalse(runtime.behaviorController.isPaused());
					assertTrue(((java.util.List<?>) runtime.behaviorController.status().get("policy_reasons")).contains("damage_grace"));
				}
			}
		}
	}

	@Test
	public void hitsplatCaptureFiltersTheRecipientAndCopiesMutableValues() throws Exception
	{
		try (Fixture fixture = new Fixture(temporary.newFolder().toPath());
			GenericClientAutomationRuntime runtime = fixture.start())
		{
			Player player = player();
			fixture.player = player;
			int[] value = {HitsplatID.DAMAGE_ME, 3};
			HitsplatApplied event = hit(player(), value);
			runtime.recordHitsplat(event);
			runtime.combatGuard.publishGameTick(GenericClientDamageTrackerTest.snapshot(10, 80, 0),
				GenericClientBehaviorPolicy.SKILLING, true);
			assertEquals("none", runtime.combatGuard.status().get("damage_type"));
			event.setActor(player);
			runtime.recordHitsplat(event);
			value[0] = HitsplatID.HEAL;
			value[1] = 10;
			runtime.combatGuard.publishGameTick(GenericClientDamageTrackerTest.snapshot(11, 87, 0),
				GenericClientBehaviorPolicy.SKILLING, true);
			assertEquals("ordinary_hit", runtime.combatGuard.status().get("damage_type"));
			assertEquals(111L, runtime.combatGuard.observation().damageGraceUntilTick);
			fixture.player = null;
			value[0] = HitsplatID.DAMAGE_ME;
			runtime.recordHitsplat(event);
			runtime.combatGuard.publishGameTick(GenericClientDamageTrackerTest.snapshot(12, 87, 0),
				GenericClientBehaviorPolicy.SKILLING, true);
			assertEquals("none", runtime.combatGuard.status().get("damage_type"));
		}
	}

	private static Player player()
	{
		return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
			(proxy, method, arguments) -> { throw new AssertionError("Unexpected player read: " + method.getName()); });
	}

	private static HitsplatApplied hit(Player actor, int[] value)
	{
		HitsplatApplied event = new HitsplatApplied();
		event.setActor(actor);
		event.setHitsplat((Hitsplat) Proxy.newProxyInstance(Hitsplat.class.getClassLoader(), new Class<?>[]{Hitsplat.class},
			(proxy, method, arguments) -> {
				switch (method.getName())
				{
					case "getHitsplatType": return value[0];
					case "getAmount": return value[1];
					default: throw new AssertionError("Unexpected hitsplat read: " + method.getName());
				}
			}));
		return event;
	}

	@Test
	public void failedStartupReleasesCanvasListenersBeforeRetry() throws Exception
	{
		for (String failedPath : new String[]{"behavior", "navigation", "scripts", "automation"})
		{
			Path directory = temporary.newFolder().toPath();
			Path broken = directory.resolve(failedPath);
			Files.createDirectories(broken.getParent());
			Files.writeString(broken, "invalid");
			try (Fixture fixture = new Fixture(directory))
			{
				assertThrows(IOException.class, fixture::start);
				assertEquals("Mouse listener leaked from " + failedPath, 0, fixture.canvas.getMouseListeners().length);
				assertEquals("Motion listener leaked from " + failedPath, 0, fixture.canvas.getMouseMotionListeners().length);
				Files.delete(broken);
				try (GenericClientAutomationRuntime runtime = fixture.start())
				{
					assertEquals("idle", runtime.scriptHost.getActivity());
					assertFalse(runtime.automationInputOwned());
				}
				assertEquals(0, fixture.canvas.getMouseListeners().length);
				assertEquals(0, fixture.canvas.getMouseMotionListeners().length);
			}
		}
	}

	@Test
	public void logoutInvalidatesTheHostAndWalkerAndFreshFramesRecover() throws Exception
	{
		try (Fixture fixture = new Fixture(temporary.newFolder().toPath());
			GenericClientAutomationRuntime runtime = fixture.start())
		{
			GenericClientSnapshot snapshot = new GenericClientSnapshot(1, "LOGGED_IN", 225,
				new GenericClientPlayerSnapshot(1L, "runtime-test", 3200, 3200, 0, -1), Collections.emptyList());
			runtime.latestSnapshot = snapshot;
			runtime.scriptHost.publishGameTick(snapshot);
			runtime.walker.publishGameTick(snapshot);
			assertNotNull(runtime.scriptHost.readCurrentSnapshot("player").get(2, TimeUnit.SECONDS));
			runtime.onGameStateChanged(GameState.LOGIN_SCREEN);
			assertNull(runtime.latestSnapshot);
			assertNull(runtime.scriptHost.readCurrentSnapshot("player").get(2, TimeUnit.SECONDS));
			Map<String, Object> stale = runtime.walker.walkTo(new GenericClientWalkRequest(
				new net.runelite.api.coords.WorldPoint(3200, 3200, 0), 0, 100, GenericClientActivityContext.none(),
				false, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null))
				.get(2, TimeUnit.SECONDS);
			assertEquals("unavailable", stale.get("status"));
			fixture.gameState.set(GameState.LOGGED_IN);
			fixture.accountHash.set(42);
			runtime.onGameStateChanged(GameState.LOGGED_IN);
			runtime.scriptHost.publishGameTick(snapshot);
			runtime.walker.publishGameTick(snapshot);
			assertNotNull(runtime.scriptHost.readCurrentSnapshot("player").get(2, TimeUnit.SECONDS));
			java.util.concurrent.CompletableFuture<Map<String, Object>> fresh = runtime.walker.walkTo(new GenericClientWalkRequest(
				new net.runelite.api.coords.WorldPoint(3200, 3200, 0), 0, 100, GenericClientActivityContext.none(),
				false, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null));
			runtime.walker.publishGameTick(snapshot);
			assertEquals("arrived", fresh.get(2, TimeUnit.SECONDS).get("status"));
		}
	}

	@Test
	public void corruptNavigationMemoryCannotRetainThePreviousBehaviorAccount() throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		try (Fixture fixture = new Fixture(directory); GenericClientAutomationRuntime runtime = fixture.start())
		{
			fixture.gameState.set(GameState.LOGGED_IN);
			fixture.accountHash.set(42);
			runtime.activateBehaviorProfile();
			String nextId = GenericClientBehaviorProfile.fromAccountHash(99).getId();
			Files.writeString(directory.resolve("navigation/edges-" + nextId + ".json"), "{");
			fixture.accountHash.set(99);
			runtime.onAccountHashChanged();
			assertEquals(nextId, runtime.behaviorController.currentProfile().getId());
			Map<String, Object> receipt = runtime.walker.walkTo(new GenericClientWalkRequest(
				new net.runelite.api.coords.WorldPoint(3200, 3200, 0), 0, 100, GenericClientActivityContext.none(),
				false, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null)).get(2, TimeUnit.SECONDS);
			assertEquals("navigation_account_unavailable", receipt.get("reason"));
		}
	}

	@Test
	public void accountChangeCancelsOldTravelAndRequiresAFreshAccountFrame() throws Exception
	{
		try (Fixture fixture = new Fixture(temporary.newFolder().toPath());
			GenericClientAutomationRuntime runtime = fixture.start())
		{
			fixture.gameState.set(GameState.LOGGED_IN);
			fixture.accountHash.set(42);
			runtime.activateBehaviorProfile();
			GenericClientSnapshot snapshot = new GenericClientSnapshot(1, "LOGGED_IN", 240,
				new GenericClientPlayerSnapshot(1L,"first-account", 3200, 3200, 0, -1), Collections.emptyList());
			runtime.latestSnapshot = snapshot;
			runtime.scriptHost.publishGameTick(snapshot);
			runtime.walker.publishGameTick(snapshot);
			assertNotNull(runtime.scriptHost.read("player", Map.of()));
			GenericClientWalkRequest request = new GenericClientWalkRequest(
				new net.runelite.api.coords.WorldPoint(3201, 3200, 0), 0, 100, GenericClientActivityContext.none(),
				false, Collections.emptyList(), GenericClientWalkInterrupts.NONE, Collections.emptyList(), null);
			java.util.concurrent.CompletableFuture<Map<String, Object>> old = runtime.walker.walkTo(request);
			fixture.accountHash.set(99);
			runtime.onAccountHashChanged();
			assertEquals("account_changed", old.get(2, TimeUnit.SECONDS).get("reason"));
			assertNull(runtime.latestSnapshot);
			assertNull(runtime.scriptHost.read("player", Map.of()));
			assertEquals("unavailable", runtime.walker.walkTo(request).get(2, TimeUnit.SECONDS).get("status"));
		}
	}

	@Test
	public void plainInputUsesProfileTimingWhileACombatScriptIsWaiting() throws Exception
	{
		try (Fixture fixture = new Fixture(temporary.newFolder().toPath());
			GenericClientAutomationRuntime runtime = fixture.start())
		{
			fixture.gameState.set(GameState.LOGGED_IN);
			runtime.scriptHost.publishGameTick(new GenericClientSnapshot(1, "LOGGED_IN", 225,
				new GenericClientPlayerSnapshot(1L, "runtime-test", 3200, 3200, 0, -1), Collections.emptyList()));
			runtime.scriptHost.compile("Combat", GenericClientTestSupport.javaScript("Combat", "",
				"public int onLoop(){Automation.activity(\"combat\");log(\"waiting\");org.dreambot.api.utilities.Sleep.sleepTicks(10);return -1;}"))
				.get(2, TimeUnit.SECONDS);
			runtime.scriptHost.start("Combat").get(2, TimeUnit.SECONDS);
			GenericClientScriptHostTest.await(() -> runtime.scriptHost.getRecentLogs().contains("waiting"));
			assertEquals("combat", runtime.scriptHost.getActivity());
			assertEquals(runtime.behaviorController.mouseMoveDurationMillis(),
				GenericClientActivityContext.none().mouseMoveDurationMillis(runtime.mouseMoveDurationMillis()));
			assertEquals(180, GenericClientActivityContext.preset(GenericClientActivityContext.Activity.COMBAT)
				.mouseMoveDurationMillis(runtime.mouseMoveDurationMillis()));
		}
	}

	private static final class Fixture implements AutoCloseable
	{
		private final Path directory;
		private final Canvas canvas = new Canvas();
		private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		private final AtomicReference<GameState> gameState = new AtomicReference<>(GameState.LOGIN_SCREEN);
		private final java.util.concurrent.atomic.AtomicLong accountHash = new java.util.concurrent.atomic.AtomicLong(-1);
		private Player player;
		private final java.util.concurrent.CountDownLatch breakEnded = new java.util.concurrent.CountDownLatch(1);

		private Fixture(Path directory)
		{
			this.directory = directory;
			canvas.setSize(800, 600);
		}

		private GenericClientAutomationRuntime start() throws IOException
		{
			Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
				(proxy, method, arguments) -> {
					switch (method.getName())
					{
						case "getCanvas": return canvas;
						case "getMouseCanvasPosition": return new Point(20, 20);
						case "getGameState": return gameState.get();
						case "getLocalPlayer": return player;
						case "isMenuOpen": return false;
						case "getAccountHash": return accountHash.get();
						case "getVarpValue":
							assertEquals(VarPlayerID.OPTION_NODEF, arguments[0]);
							return 0;
						default: throw new AssertionError("Unexpected client read: " + method.getName());
					}
				});
			ClientThread clientThread = new ClientThread()
			{
				@Override public void invoke(Runnable action) { action.run(); }
				@Override public void invoke(java.util.function.BooleanSupplier action) { action.getAsBoolean(); }
			};
			GenericClientMouseProfile profile = GenericClientMouseProfile.load(
				Path.of("src/main/resources/com/genericclient/mouse/default.json"));
			return new GenericClientAutomationRuntime(client, clientThread, executor, com.google.inject.Guice.createInjector(binder -> binder.bind(Client.class).toInstance(client))
					.getInstance(KeyManager.class), directory,
				GenericClientCollisionMap.loadBundled(), () -> profile,
				new GenericClientMouseEffectOverlay(() -> GenericClientMouseEffect.OFF,
					canvas::getWidth, canvas::getHeight, System::currentTimeMillis), message -> { },
				message -> { if (message.startsWith("BEHAVIOR_BREAK_COMPLETED")) breakEnded.countDown(); }, new GenericClientEntityIds());
		}

		@Override public void close() { executor.shutdownNow(); }
	}
}
