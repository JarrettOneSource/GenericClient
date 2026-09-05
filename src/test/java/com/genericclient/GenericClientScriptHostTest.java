package com.genericclient;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientScriptHostTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test public void compilesAndRunsDreamBotLifecycleWithSnapshotReadsInTheConstructor() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
		{
			host.publishGameTick(snapshot(1));
			host.compile("Lifecycle", GenericClientTestSupport.javaScript("Lifecycle", "",
				"private final boolean loggedIn = org.dreambot.api.Client.isLoggedIn();\n" +
				"private int loops;\n" +
				"public void onStart() { log(\"start:\" + loggedIn); }\n" +
				"public int onLoop() { log(\"loop:\" + ++loops); return loops == 2 ? -1 : 1; }\n" +
				"public void onExit() { log(\"exit\"); }" )).get(5, TimeUnit.SECONDS);
			host.start("Lifecycle").get(5, TimeUnit.SECONDS);
			await(() -> host.getStatus().equals("COMPLETED"));
			assertEquals("Lifecycle: start:true\nLifecycle: loop:1\nLifecycle: loop:2\nLifecycle: exit", host.getRecentLogs());
			assertFalse(host.getRunState().isRunning());
		}
	}

	@Test public void runsTheUnchangedOfficialDreamBotExample() throws Exception
	{
		String source;
		try (java.io.InputStream input = getClass().getResourceAsStream("/dreambot-first-script/Main.java"))
		{
			source = new String(java.util.Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
		}
		java.util.List<Long> loops = new java.util.concurrent.CopyOnWriteArrayList<>();
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"official-example")
			.report(message -> { if(message.equals("Main: My first script!")) loops.add(System.nanoTime()); }).build())
		{
			host.compile("Main",source).get();
			assertEquals("Script Name",host.listScripts().get(0).getName());
			host.start("Main").get();
			await(() -> loops.size() >= 2);
			host.stop().get();
			assertTrue("The example's loop delay must be honored",loops.get(1)-loops.get(0) >= TimeUnit.MILLISECONDS.toNanos(1000));
			assertEquals("STOPPED",host.getStatus());
		}
	}

	@Test public void failedCompilationLeavesThePreviouslyCompiledScriptExecutable() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
		{
			host.compile("Working", GenericClientTestSupport.javaScript("Working", "",
				"public int onLoop() { log(\"working\"); return -1; }")).get();
			try { host.compile("Working", "not Java").get(); fail("Invalid Java compiled"); }
			catch (java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("line")); }
			host.start("Working").get();
			await(() -> host.getStatus().equals("COMPLETED"));
			assertEquals("Working: working", host.getRecentLogs());
		}
	}

	@Test public void rejectedMetadataLeavesTheInstalledScriptUsableAfterRestart() throws Exception
	{
		java.nio.file.Path directory = temporary.newFolder().toPath();
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(directory))
		{
			host.compile("Installed", GenericClientTestSupport.javaScript("Installed", "",
				"public int onLoop() { log(\"original\"); return -1; }")).get();
			try
			{
				host.compile("Installed", GenericClientTestSupport.javaScript("Installed",
					"@ScriptSettings(id=\"Installed\",inputs=@ScriptSettings.Input(id=\"mode\",label=\"Mode\",choices={\"one\"},defaultValue=\"missing\"))",
					"public int onLoop() { return -1; }")).get();
				fail("Invalid catalog metadata was accepted");
			}
			catch (java.util.concurrent.ExecutionException expected)
			{
				assertTrue(expected.getCause().getMessage().contains("Invalid choices"));
			}
		}
		try (GenericClientScriptHost restarted = GenericClientTestSupport.scriptHost(directory))
		{
			restarted.start("Installed").get();
			await(() -> restarted.getStatus().equals("COMPLETED"));
			assertEquals("Installed: original", restarted.getRecentLogs());
		}
	}

	@Test public void scriptAssertionFailureIsReportedAsFaulted() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
		{
			host.compile("Asserting", GenericClientTestSupport.javaScript("Asserting", "",
				"public int onLoop() { throw new AssertionError(\"broken invariant\"); }" +
				"public void onExit() { org.dreambot.api.utilities.Logger.error(\"cleanup\"); }")).get();
			host.start("Asserting").get();
			await(() -> host.getStatus().equals("FAULTED"));
			assertFalse(host.getRunState().isRunning());
			assertTrue(host.getActiveScriptView().toMap().toString().contains("broken invariant"));
			assertEquals("Asserting: cleanup",host.getRecentLogs());
		}
	}

	@Test public void paintingWaitsForStartAndEndsBeforeCleanup() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
		{
			host.compile("Painted", GenericClientTestSupport.javaScript("Painted", "",
				"private String label; public void onStart(){log(\"starting\"); sleepUntil(org.dreambot.api.Client::isLoggedIn,60000);" +
				"Automation.activity(\"manual\");if(!Automation.phase(\"ready\").containsKey(\"status\"))throw new AssertionError(\"Phase receipt missing\");" +
				"Automation.overlay(Map.of(\"State\",\"Ready\"));Automation.markers(List.of(Map.of(\"tile\",Map.of(\"x\",3200,\"y\",3200,\"plane\",0),\"label\",\"Here\")));" +
				"label=\"ready\";log(\"ready:\"+getManifest().name()+\":\"+getVersion());}" +
				"public int onLoop(){return 50;} public void onPaint(java.awt.Graphics g){super.onPaint(g);log(\"paint:\"+label.toUpperCase()+\":\"+isPaused());}" +
				"public void onPause(){org.dreambot.api.utilities.Logger.warn(\"paused\");}" +
				"public void onResume(){org.dreambot.api.utilities.Logger.info(\"resumed\");}" +
				"public void onExit(){label=null;org.dreambot.api.utilities.Logger.debug(\"exit\");}")).get();
			host.publishGameTick(new GenericClientSnapshot(0,"LOGIN_SCREEN",240,null,Collections.emptyList()));
			host.startScheduled("paint-schedule","Painted",Map.of()).get();
			await(() -> host.getRecentLogs().contains("starting"));
			java.awt.Graphics2D graphics = new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics();
			try
			{
				host.paint(graphics);
				assertFalse(host.getRecentLogs().contains("paint"));
				host.publishGameTick(snapshot(1));
				await(() -> host.getRecentLogs().contains("ready:Painted:1.0"));
				assertEquals(List.of(Map.of("label","State","value","Ready")),host.getActiveScriptView().toMap().get("overlay"));
				assertEquals(1,host.getSceneMarkers().size());
				assertEquals("Here",host.getSceneMarkers().get(0).getLabel());
				assertEquals(new net.runelite.api.coords.WorldPoint(3200,3200,0),host.getSceneMarkers().get(0).getTile());
				await(() -> { host.paint(graphics); return host.getRecentLogs().contains("paint:READY:false"); });
				host.pauseForManualInput("mouse").get();
				await(() -> host.getRecentLogs().contains("paused"));
				host.paint(graphics);
				assertTrue(host.getRecentLogs().contains("paint:READY:true"));
				host.resumeAfterManualInput("idle").get();
				await(() -> host.getRecentLogs().contains("resumed"));
				host.stopScheduled("paint-schedule","schedule ended").get(3,TimeUnit.SECONDS);
				await(() -> host.getRecentLogs().contains("exit"));
				assertTrue(host.getSceneMarkers().isEmpty());
				String finished = host.getRecentLogs();
				host.paint(graphics);
				assertEquals(finished,host.getRecentLogs());
			}
			finally { graphics.dispose(); }
		}
	}

	@Test public void paintingMayReadButCannotDispatchWaitOrChangeTheWorkerScope() throws Exception
	{
		AtomicInteger inputs = new AtomicInteger();
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"paint-authority")
			.questAction((type,arguments,context) -> {
				inputs.incrementAndGet();
				return java.util.concurrent.CompletableFuture.completedFuture(Map.of("status","complete"));
			}).build())
		{
			host.publishGameTick(snapshot(1));
			host.compile("PaintAuthority",GenericClientTestSupport.javaScript("PaintAuthority","",
				"public int onLoop(){Automation.activity(\"skilling\");Automation.intent(\"working\",()->{sleep(60000);return null;});return -1;}" +
				"public void onPaint(java.awt.Graphics2D graphics){" +
				"List<Runnable> attempts=List.of(()->ScriptScope.current().checkpoint()," +
				"()->ScriptScope.current().execute(\"test.forbidden\",Map.of(),5000)," +
				"()->Automation.activity(\"manual\"),()->Automation.phase(\"paint\")," +
				"()->Automation.intent(\"paint\",()->null),()->sleep(1),()->org.dreambot.api.utilities.Sleep.sleepTicks(1)," +
				"()->sleepUntil(()->true,0),()->org.dreambot.api.methods.MethodProvider.sleepWhile(()->false,0));" +
				"for(Runnable action:attempts){try{action.run();throw new AssertionError(\"Paint changed worker state\");}" +
				"catch(IllegalStateException expected){log(\"blocked\");}}log(\"read:\"+org.dreambot.api.Client.isLoggedIn());}"))
				.get(5,TimeUnit.SECONDS);
			host.start("PaintAuthority").get(5,TimeUnit.SECONDS);
			await(() -> host.quietMillis(null,0)>0);
			java.awt.Graphics2D graphics = new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics();
			try { host.paint(graphics); }
			finally { graphics.dispose(); }
			assertEquals(0,inputs.get());
			assertEquals(9,host.getRecentLogs().lines().filter(line -> line.endsWith(": blocked")).count());
			assertTrue(host.getRecentLogs().contains("read:true"));
			assertEquals("starting",host.getScriptState());
			assertEquals("skilling",host.getActivity());
			Map<?,?> behavior = (Map<?,?>)host.read("behavior",Map.of());
			assertEquals("working",behavior.get("intent"));
			assertEquals(1,behavior.get("intent_depth"));
		}
	}

	@Test public void scriptOverlayIsolatesGraphicsStateWhenPaintingThrows() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
		{
			host.publishGameTick(snapshot(1));
			host.compile("PaintIsolation",GenericClientTestSupport.javaScript("PaintIsolation","",
				"public int onLoop(){log(\"painting-ready\");return 60000;}" +
				"public void onPaint(java.awt.Graphics2D graphics){" +
				"graphics.translate(4,4);graphics.setClip(0,0,2,2);graphics.setColor(java.awt.Color.RED);" +
				"graphics.fillRect(0,0,2,2);throw new AssertionError(\"paint callback failed\");}"))
				.get(5,TimeUnit.SECONDS);
			host.start("PaintIsolation").get(5,TimeUnit.SECONDS);
			await(() -> host.getRecentLogs().contains("painting-ready"));
			java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(12,12,java.awt.image.BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D graphics = canvas.createGraphics();
			try
			{
				graphics.setColor(java.awt.Color.BLUE);
				graphics.setClip(0,0,12,12);
				assertNull(new GenericClientScriptPaint(host).render(graphics));
				assertEquals(java.awt.Color.RED.getRGB(),canvas.getRGB(4,4));
				assertEquals(java.awt.Color.BLUE,graphics.getColor());
				assertEquals(new java.awt.geom.AffineTransform(),graphics.getTransform());
				assertEquals(new java.awt.Rectangle(0,0,12,12),graphics.getClipBounds());
				graphics.fillRect(0,0,2,2);
				assertEquals(java.awt.Color.BLUE.getRGB(),canvas.getRGB(0,0));
				assertTrue(host.getRecentLogs().contains("paint callback failed"));
				assertEquals("RUNNING",host.getStatus());
			}
			finally { graphics.dispose(); }
		}
	}

	@Test public void stopInterruptsPredicateWaitAndReleasesOwnershipOnce() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
		{
			AtomicInteger ended = new AtomicInteger();
			host.setScriptEndListener((id, owner) -> ended.incrementAndGet());
			host.compile("Waiting", GenericClientTestSupport.javaScript("Waiting", "",
				"public int onLoop() { log(\"waiting\"); sleepUntil(() -> false, 60000); log(\"too late\"); return -1; }\n" +
				"public void onExit() { log(\"exit\"); }")).get();
			host.start("Waiting").get();
			await(() -> host.getRecentLogs().contains("waiting"));
			host.stopForManualEscape().get(1, TimeUnit.SECONDS);
			assertFalse(host.getRunState().isRunning());
			await(() -> host.getRecentLogs().contains("exit"));
			assertFalse(host.getRecentLogs().contains("too late"));
			assertEquals(1, ended.get());
		}
	}

	@Test public void clearingTheClientStateInvalidatesDiagnosticReads() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
		{
			host.publishGameTick(snapshot(7));
			assertNotNull(host.readCurrentSnapshot("player").get());
			host.clearSnapshot();
			assertNull(host.readCurrentSnapshot("player").get());
			Map<String, Object> result = host.evaluate("return org.dreambot.api.Client.isLoggedIn();").get(5, TimeUnit.SECONDS);
			assertEquals(false, result.get("value"));
		}
	}

	@Test public void predicateTimeoutDoesNotConsumeManualPauseTime() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
		{
			host.compile("TimedWait", GenericClientTestSupport.javaScript("TimedWait", "",
				"public int onLoop(){log(\"waiting\");boolean ready=sleepUntil(org.dreambot.api.Client::isLoggedIn,200,20);log(\"ready:\"+ready);return -1;}" +
				"public void onPause(){log(\"paused\");}")).get();
			host.start("TimedWait").get();
			await(() -> host.getRecentLogs().contains("waiting"));
			host.pauseForManualInput("mouse").get();
			await(() -> host.getRecentLogs().contains("paused"));
			Thread.sleep(300);
			host.resumeAfterManualInput("idle").get();
			Thread.sleep(50);
			host.publishGameTick(snapshot(1));
			await(() -> host.getStatus().equals("COMPLETED"));
			assertTrue(host.getRecentLogs(),host.getRecentLogs().contains("ready:true"));
		}
	}

	@Test public void scheduledStopWaitsForTheScriptToAcceptButManualStopRemainsImmediate() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
		{
			host.compile("Scheduled", GenericClientTestSupport.javaScript("Scheduled", "",
				"public int onLoop(){return 10;} public boolean onScheduledStop(){log(\"schedule check\");return org.dreambot.api.Client.isLoggedIn();}" +
				"public void onExit(){log(\"exit\");}")).get();
			host.startScheduled("rule","Scheduled",Map.of()).get();
			java.util.concurrent.CompletableFuture<String> stopped = host.stopScheduled("rule","schedule ended");
			await(() -> host.getRecentLogs().contains("schedule check"));
			assertFalse(stopped.isDone());
			assertTrue(host.getRunState().isRunning());
			host.publishGameTick(snapshot(1));
			stopped.get(3,TimeUnit.SECONDS);
			assertFalse(host.getRunState().isRunning());
			await(() -> host.getRecentLogs().contains("exit"));
			host.clearSnapshot();
			host.startScheduled("rule","Scheduled",Map.of()).get();
			java.util.concurrent.CompletableFuture<String> deferred = host.stopScheduled("rule","schedule ended");
			host.stopForManualEscape().get(1,TimeUnit.SECONDS);
			assertFalse(host.getRunState().isRunning());
			deferred.get(1,TimeUnit.SECONDS);
		}
	}

	@Test public void finishedOwnerIsReleasedBeforeAReplacementStartsDuringCleanup() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
		{
			java.util.List<String> owners = new java.util.concurrent.CopyOnWriteArrayList<>();
			host.setScriptStartListener((id,owner,context) -> owners.add("start:"+id));
			host.setScriptEndListener((id,owner) -> owners.add("end:"+id));
			host.compile("SlowCleanup", GenericClientTestSupport.javaScript("SlowCleanup", "",
				"public int onLoop(){return -1;} public void onExit(){log(\"cleanup started\");try{Thread.sleep(500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}log(\"cleanup ended\");}")).get();
			host.compile("Replacement", GenericClientTestSupport.javaScript("Replacement", "", "public int onLoop(){return -1;}")).get();
			host.start("SlowCleanup").get();
			await(() -> host.getRecentLogs().contains("cleanup started"));
			host.start("Replacement").get();
			await(() -> host.getStatus().equals("COMPLETED"));
			await(() -> host.getRecentLogs().contains("cleanup ended"));
			assertEquals(java.util.List.of("start:SlowCleanup","end:SlowCleanup","start:Replacement","end:Replacement"),owners);
		}
	}

	@Test public void diagnosticsDoNotTriggerStandaloneScriptBehaviorResets() throws Exception
	{
		java.util.concurrent.atomic.AtomicInteger cancelled = new java.util.concurrent.atomic.AtomicInteger();
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"diagnostic-policy")
			.cancel(reason -> cancelled.incrementAndGet()).build())
		{
			java.util.List<String> boundaries = new java.util.ArrayList<>();
			host.setScriptStartListener((id,owner,context) -> boundaries.add("start"));
			host.setScriptEndListener((id,owner) -> boundaries.add("end"));
			assertEquals(7,host.evaluate("return 7;").get().get("value"));
			assertTrue("Diagnostics must not reset account behavior",boundaries.isEmpty());
			assertEquals("Read-only diagnostics must not cancel unrelated input",0,cancelled.get());
		}
	}

	@Test public void lateCleanupCannotCancelNewDashboardInput() throws Exception
	{
		java.util.concurrent.atomic.AtomicBoolean dashboardInput = new java.util.concurrent.atomic.AtomicBoolean();
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"dashboard-input")
			.cancel(reason -> dashboardInput.set(false)).build())
		{
			host.compile("Cleanup",GenericClientTestSupport.javaScript("Cleanup","",
				"public int onLoop(){return -1;} public void onExit(){log(\"cleanup\");try{Thread.sleep(300);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}")).get();
			host.start("Cleanup").get();
			await(() -> host.getRecentLogs().contains("cleanup"));
			dashboardInput.set(true);
			await(() -> host.getStatus().equals("COMPLETED"));
			assertTrue("The previous run must not cancel a newer operator action",dashboardInput.get());
		}
	}

	@Test public void diagnosticCompilationFilesAreRemovedAfterTheResultIsDelivered() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
		{
			String jar = (String)host.evaluate("return getClass().getProtectionDomain().getCodeSource().getLocation().toExternalForm();").get().get("value");
			java.nio.file.Path directory = java.nio.file.Path.of(java.net.URI.create(jar)).getParent();
			assertFalse("Completed diagnostics must release their temporary source and JAR",java.nio.file.Files.exists(directory));
		}
	}

	@Test public void stopDoesNotWaitForStartupWorkToFinish() throws Exception
	{
		java.util.concurrent.CompletableFuture<Void> release = new java.util.concurrent.CompletableFuture<>();
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
		{
			host.compile("Starting",GenericClientTestSupport.javaScript("Starting","","public int onLoop(){log(\"loop\");return -1;}")).get();
			java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
			java.util.concurrent.atomic.AtomicReference<GenericClientActivityContext> authority = new java.util.concurrent.atomic.AtomicReference<>();
			host.setScriptStartListener((id,owner,context) -> { authority.set(context); entered.countDown(); release.join(); });
			host.start("Starting");
			assertTrue(entered.await(3,TimeUnit.SECONDS));
			try
			{
				java.util.concurrent.CompletableFuture.supplyAsync(() -> host.stopForManualEscape().join()).get(1,TimeUnit.SECONDS);
				assertFalse(host.getRunState().isRunning());
				assertFalse(authority.get().isInputAllowed());
			}
			finally { release.complete(null); }
			assertFalse(host.getRecentLogs().contains("loop"));
		}
		finally { release.complete(null); }
	}

	@Test public void startupFailureRevokesItsInputAndAllowsANewRun() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
		{
			AtomicInteger ended = new AtomicInteger();
			java.util.concurrent.atomic.AtomicReference<GenericClientActivityContext> authority = new java.util.concurrent.atomic.AtomicReference<>();
			host.setScriptEndListener((id,owner) -> ended.incrementAndGet());
			host.setScriptStartListener((id,owner,context) ->
			{
				authority.set(context);
				throw new IllegalStateException("Startup input failed");
			});
			host.compile("StartupFailure",GenericClientTestSupport.javaScript("StartupFailure","",
				"public StartupFailure(){log(\"constructed\");} public int onLoop(){log(\"loop\");return -1;}")).get();
			host.start("StartupFailure").get();
			await(() -> host.getStatus().equals("FAULTED"));
			assertFalse(authority.get().isInputAllowed());
			assertEquals(1,ended.get());
			assertTrue(host.getRecentLogs().isEmpty());
			assertTrue(host.getActiveScriptView().toMap().toString().contains("Startup input failed"));
			host.setScriptStartListener((id,owner,context) -> {});
			host.start("StartupFailure").get();
			await(() -> host.getStatus().equals("COMPLETED"));
			assertEquals("StartupFailure: constructed\nStartupFailure: loop",host.getRecentLogs());
			assertEquals(2,ended.get());
		}
	}

	@Test public void manualAndEmergencyPausesHaveIndependentOwnership() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
		{
			host.compile("Paused", GenericClientTestSupport.javaScript("Paused", "",
				"private boolean ready; public int onLoop() { if(!ready){log(\"ready\");ready=true;} return 10; }\n" +
				"public void onPause() { log(\"pause\"); }\n" +
				"public void onResume() { log(\"resume\"); }")).get();
			host.start("Paused").get();
			await(() -> host.getRecentLogs().contains("ready"));
			host.pauseForManualInput("mouse").get();
			await(() -> host.getRecentLogs().contains("pause"));
			host.pauseForEmergency("health").get();
			host.resumeAfterManualInput("mouse").get();
			assertEquals(true, host.controlState().get("paused"));
			host.resumeAfterEmergency("health").get();
			await(() -> host.getRecentLogs().contains("resume"));
			assertEquals("Paused: ready\nPaused: pause\nPaused: resume", host.getRecentLogs());
		}
	}

	static GenericClientSnapshot snapshot(long tick)
	{
		return new GenericClientSnapshot(tick, "LOGGED_IN", 240,
			new GenericClientPlayerSnapshot(1L, "Player", 3200, 3200, 0, -1), Collections.emptyList());
	}

	static void await(java.util.function.BooleanSupplier condition) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
		assertTrue("Expected runtime transition did not occur", condition.getAsBoolean());
	}
}
