package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientControlServerTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void fallsBackToAnEphemeralPortWhenAnotherNormalClientOwnsTheConfiguredPort()
		throws Exception
	{
		ServerSocket occupied = new ServerSocket();
		occupied.bind(new InetSocketAddress("127.0.0.1", 0));
		int requestedPort = occupied.getLocalPort();
		List<String> messages = new java.util.ArrayList<>();
		GenericClientControlServer server = new GenericClientControlServer(
			requestedPort,
			null,
			null,
			null,
			() -> CompletableFuture.completedFuture("SESSION_LOGGED_OUT"),
			() -> CompletableFuture.completedFuture("SESSION_LOGGED_IN"),
			Collections::emptyMap,
			() -> "",
			text -> CompletableFuture.completedFuture("ACCOUNT_NOTE_UPDATED"),
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			() -> CompletableFuture.completedFuture(Collections.emptyMap()),
			new GenericClientSceneHighlights(Collections::emptyList),
			messages::add);
		try
		{
			server.start();
			assertNotEquals(requestedPort, URI.create(server.getUrl()).getPort());
			assertTrue(messages.stream().anyMatch(message -> message.startsWith("CONTROL_PORT_BUSY")));
		}
		finally
		{
			server.close();
			occupied.close();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void exposesStructuredLuaAndScriptOperationsOverLoopback() throws Exception
	{
		GenericClientLuaHost host =
			GenericClientTestSupport.luaHost(temporaryFolder, "control").build();
		AtomicReference<String> note = new AtomicReference<>("Account Goal");
		Map<String, Object> screenshot = new LinkedHashMap<>();
		screenshot.put("mime_type", "image/png");
		screenshot.put("image_base64", "iVBORw0KGgo=");
		screenshot.put("width", 1L);
		screenshot.put("height", 1L);
		Map<String, Object> endedBreak = new LinkedHashMap<>();
		endedBreak.put("status", "ended");
		endedBreak.put("type", "long");
		GenericClientAutomationScheduler automation = new GenericClientAutomationScheduler(
			temporaryFolder.newFolder("control-automation").toPath(), host, message -> { });
		automation.activateProfile("0123456789abcdef").get();
		GenericClientRandomEventController randomEvents = new GenericClientRandomEventController(
			id -> null,
			new GenericClientRandomEventController.Runtime()
			{
				@Override
				public CompletableFuture<String> interrupt(String eventKey, String solverScript)
				{
					return CompletableFuture.completedFuture("interrupted");
				}

				@Override
				public CompletableFuture<String> release(
					String eventKey,
					boolean resumeInterrupted)
				{
					return CompletableFuture.completedFuture("released");
				}
			},
			message -> { },
			message -> { });
		GenericClientSceneHighlights sceneHighlights = new GenericClientSceneHighlights(
			Collections::emptyList);
		GenericClientControlServer server = new GenericClientControlServer(
			0,
			host,
			automation,
			randomEvents,
			() -> CompletableFuture.completedFuture("SESSION_LOGGED_OUT"),
			() -> CompletableFuture.completedFuture("SESSION_LOGGED_IN"),
			() ->
			{
				Map<String, Object> status = new LinkedHashMap<>();
				status.put("game_state", "LOGGED_IN");
				Map<String, Object> behavior = new LinkedHashMap<>();
				Map<String, Object> profile = new LinkedHashMap<>();
				profile.put("title", "Frequent multitasking; regular long breaks");
				behavior.put("state", "ready");
				behavior.put("profile", profile);
				status.put("behavior", behavior);
				status.put("lua", host.controlState());
				return status;
			},
			note::get,
			text ->
			{
				note.set(text);
				return CompletableFuture.completedFuture("ACCOUNT_NOTE_UPDATED");
			},
			() -> CompletableFuture.completedFuture(screenshot),
			() -> CompletableFuture.completedFuture(endedBreak),
			sceneHighlights,
			message -> { });
		try
		{
			host.publishGameTick(snapshot(9));
			server.setHealthSupplier(() ->
			{
				Map<String, Object> health = new LinkedHashMap<>();
				health.put("schema", GenericClientInstanceRegistration.SCHEMA);
				health.put("instance_id", "control-test");
				health.put("pid", 1234L);
				return health;
			});
			server.start();
			assertEquals(400, send(server, "missing.method", new LinkedHashMap<>()).statusCode());
			Map<String, Object> health = get(server);
			assertEquals(true, health.get("ok"));
			assertEquals("control-test", health.get("instance_id"));
			assertEquals(server.getUrl(), health.get("control_url"));

			Map<String, Object> evalParameters = new LinkedHashMap<>();
			evalParameters.put("code", "return gc.read('player')");
			Map<String, Object> eval = post(server, "lua.eval", evalParameters);
			Map<String, Object> evalResult = (Map<String, Object>) eval.get("result");
			assertEquals(true, eval.get("ok"));
			assertEquals("completed", evalResult.get("status"));
			assertEquals("Player", ((Map<String, Object>) evalResult.get("value")).get("name"));

			Map<String, Object> statusResponse = post(server, "status", new LinkedHashMap<>());
			Map<String, Object> statusResult = (Map<String, Object>) statusResponse.get("result");
			assertTrue(((Map<String, Object>) statusResult.get("lua")).get("active_inputs") instanceof Map);
			Map<String, Object> automationStatus = post(
				server, "automation.status", new LinkedHashMap<>());
			assertEquals("0123456789abcdef",
				((Map<String, Object>) automationStatus.get("result")).get("profile"));
			Map<String, Object> randomEventStatus = post(
				server, "random_event.status", new LinkedHashMap<>());
			assertEquals("idle",
				((Map<String, Object>) randomEventStatus.get("result")).get("state"));
			assertEquals(409,
				send(server, "random_event.acknowledge", new LinkedHashMap<>()).statusCode());
			Map<String, Object> config = new LinkedHashMap<>();
			config.put("schema", "genericclient_automation.v1");
			config.put("zone", "UTC");
			config.put("enabled", false);
			config.put("schedules", new LinkedHashMap<>());
			config.put("rules", new java.util.ArrayList<>());
			Map<String, Object> configParameters = new LinkedHashMap<>();
			configParameters.put("config", config);
			post(server, "automation.config.set", configParameters);
			Map<String, Object> enableParameters = new LinkedHashMap<>();
			enableParameters.put("enabled", true);
			assertEquals(true, ((Map<String, Object>) post(
				server, "automation.enable", enableParameters).get("result")).get("enabled"));
			assertEquals(true, ((Map<String, Object>) post(
				server, "automation.pause", new LinkedHashMap<>()).get("result")).get("paused"));
			assertEquals(false, ((Map<String, Object>) post(
				server, "automation.resume", new LinkedHashMap<>()).get("result")).get("paused"));
			assertEquals("UTC", ((Map<String, Object>) post(
				server, "automation.config.get", new LinkedHashMap<>()).get("result")).get("zone"));
			post(server, "automation.reload", new LinkedHashMap<>());
			Map<String, Object> invalidEnable = new LinkedHashMap<>();
			invalidEnable.put("enabled", "yes");
			assertEquals(400, send(server, "automation.enable", invalidEnable).statusCode());
			Map<String, Object> screenshotResponse = post(
				server, "screenshot.capture", new LinkedHashMap<>());
			assertEquals("image/png",
				((Map<String, Object>) screenshotResponse.get("result")).get("mime_type"));
			Map<String, Object> tile = new LinkedHashMap<>();
			tile.put("x", 2746);
			tile.put("y", 2799);
			tile.put("plane", 0);
			Map<String, Object> marker = new LinkedHashMap<>();
			marker.put("tile", tile);
			Map<String, Object> highlightParameters = new LinkedHashMap<>();
			highlightParameters.put("markers", Collections.singletonList(marker));
			Map<String, Object> highlighted = post(
				server, "scene.highlight", highlightParameters);
			assertEquals(1.0,
				((Map<String, Object>) highlighted.get("result")).get("marker_count"));
			assertEquals(1, sceneHighlights.visibleMarkers().size());
			post(server, "scene.clear", new LinkedHashMap<>());
			assertTrue(sceneHighlights.visibleMarkers().isEmpty());
			Map<String, Object> accountResponse = post(server, "account.snapshot", new LinkedHashMap<>());
			Map<String, Object> account = (Map<String, Object>) accountResponse.get("result");
			assertEquals("Player", ((Map<String, Object>) account.get("player")).get("name"));
			assertTrue(account.get("skills") instanceof Map);
			assertEquals("Account Goal", post(server, "account.note.get", new LinkedHashMap<>()).get("result"));
			Map<String, Object> noteParameters = new LinkedHashMap<>();
			noteParameters.put("text", "Account Goal\n\nVerified audit");
			assertEquals("ACCOUNT_NOTE_UPDATED",
				post(server, "account.note.set", noteParameters).get("result"));
			assertEquals("Account Goal\n\nVerified audit", note.get());

			Map<String, Object> saveParameters = new LinkedHashMap<>();
			saveParameters.put("id", "hello-world");
			saveParameters.put("name", "Hello world");
			saveParameters.put("description", "Log one message and finish.");
			saveParameters.put("source",
				"return { inputs = {{ id = 'greeting', label = 'Greeting', type = 'choice', " +
				"choices = {{ value = 'hello', label = 'Hello' }, " +
				"{ value = 'goodbye', label = 'Goodbye' }} }}, " +
				"actions = {{ id = 'refresh', label = 'Refresh' }}, " +
				"run = function(input) gc.log('info', input.greeting); while true do " +
				"gc.await { event = 'game.tick' }; local action = gc.next_action(); " +
				"if action then gc.log('info', action); return action end end end }\n");
			saveParameters.put("random_events", List.of(net.runelite.api.gameval.NpcID.MACRO_MILES));
			Map<String, Object> saved = post(server, "scripts.save", saveParameters);
			assertEquals("hello-world", ((Map<String, Object>) saved.get("result")).get("id"));
			assertEquals(1,
				((List<?>) ((Map<String, Object>) saved.get("result")).get("random_events")).size());

			Map<String, Object> getParameters = new LinkedHashMap<>();
			getParameters.put("id", "hello-world");
			Map<String, Object> fetched = post(server, "scripts.get", getParameters);
			java.util.List<Map<String, Object>> inputs =
				(java.util.List<Map<String, Object>>) ((Map<String, Object>) fetched.get("result")).get("inputs");
			assertEquals("greeting", inputs.get(0).get("id"));
			assertEquals(2, ((java.util.List<?>) inputs.get(0).get("choices")).size());
			java.util.List<Map<String, Object>> actions =
				(java.util.List<Map<String, Object>>) ((Map<String, Object>) fetched.get("result")).get("actions");
			assertEquals("refresh", actions.get(0).get("id"));

			Map<String, Object> runParameters = new LinkedHashMap<>();
			runParameters.put("id", "hello-world");
			runParameters.put("inputs", Collections.singletonMap("greeting", "goodbye"));
			assertTrue(((String) post(server, "scripts.run", runParameters).get("result"))
				.contains("LUA_STARTED"));
			assertTrue(host.getRecentLogs().contains("INFO goodbye"));
			Map<String, Object> actionParameters = new LinkedHashMap<>();
			actionParameters.put("action", "refresh");
			assertTrue(((String) post(server, "scripts.action", actionParameters).get("result"))
				.contains("SCRIPT_ACTION_QUEUED"));
			host.publishGameTick(snapshot(10));
			long actionDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
			while (!host.getRecentLogs().contains("INFO refresh") && System.nanoTime() < actionDeadline)
			{
				Thread.sleep(10);
			}
			assertTrue(host.getRecentLogs().contains("INFO refresh"));

			Map<String, Object> listed = post(server, "scripts.list", new LinkedHashMap<>());
			assertEquals(1, ((java.util.List<?>) listed.get("result")).size());

			Map<String, Object> behaviorStatus = post(server, "behavior.status", new LinkedHashMap<>());
			assertEquals("ready", ((Map<String, Object>) behaviorStatus.get("result")).get("state"));
			Map<String, Object> behaviorProfile = post(server, "behavior.profile", new LinkedHashMap<>());
			assertEquals("Frequent multitasking; regular long breaks",
				((Map<String, Object>) behaviorProfile.get("result")).get("title"));
			Map<String, Object> breakEnd = post(
				server, "behavior.break.end", new LinkedHashMap<>());
			assertEquals("ended", ((Map<String, Object>) breakEnd.get("result")).get("status"));
			assertEquals("SESSION_LOGGED_OUT",
				post(server, "session.logout", new LinkedHashMap<>()).get("result"));
			assertEquals("SESSION_LOGGED_IN",
				post(server, "session.login", new LinkedHashMap<>()).get("result"));
		}
		finally
		{
			server.close();
			automation.close();
			host.close();
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> get(GenericClientControlServer server) throws Exception
	{
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(server.getUrl() + "/health"))
			.GET()
			.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(
			request,
			HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		return new Gson().fromJson(response.body(), Map.class);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> post(
		GenericClientControlServer server,
		String method,
		Map<String, Object> parameters) throws Exception
	{
		HttpResponse<String> response = send(server, method, parameters);
		assertEquals(200, response.statusCode());
		return new Gson().fromJson(response.body(), Map.class);
	}

	private static HttpResponse<String> send(
		GenericClientControlServer server,
		String method,
		Map<String, Object> parameters) throws Exception
	{
		Map<String, Object> requestValue = new LinkedHashMap<>();
		requestValue.put("method", method);
		requestValue.put("params", parameters);
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(server.getUrl() + "/rpc"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(requestValue)))
			.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(
			request,
			HttpResponse.BodyHandlers.ofString());
		return response;
	}

	private static GenericClientSnapshot snapshot(long tick)
	{
		return new GenericClientSnapshot(
			tick,
			"LOGGED_IN",
			240,
			new GenericClientWorldSnapshot.PlayerSnapshot("Player", 3200, 3200, 0, -1),
			Collections.emptyList());
	}
}
