package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientControlServerTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	@SuppressWarnings("unchecked")
	public void exposesStructuredLuaAndScriptOperationsOverLoopback() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("control-scripts").toPath(),
			() -> CompletableFuture.completedFuture("unused"),
			(destination, within, timeout) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			message -> { });
		GenericClientControlServer server = new GenericClientControlServer(
			0,
			host,
			() ->
			{
				Map<String, Object> status = new LinkedHashMap<>();
				status.put("game_state", "LOGGED_IN");
				return status;
			},
			message -> { });
		try
		{
			host.publishGameTick(snapshot(9));
			server.start();

			Map<String, Object> evalParameters = new LinkedHashMap<>();
			evalParameters.put("code", "return gc.read('player')");
			Map<String, Object> eval = post(server, "lua.eval", evalParameters);
			Map<String, Object> evalResult = (Map<String, Object>) eval.get("result");
			assertEquals(true, eval.get("ok"));
			assertEquals("completed", evalResult.get("status"));
			assertEquals("Player", ((Map<String, Object>) evalResult.get("value")).get("name"));

			Map<String, Object> saveParameters = new LinkedHashMap<>();
			saveParameters.put("id", "hello-world");
			saveParameters.put("name", "Hello world");
			saveParameters.put("description", "Log one message and finish.");
			saveParameters.put("source", "return function() gc.log('info', 'hello') end\n");
			Map<String, Object> saved = post(server, "scripts.save", saveParameters);
			assertEquals("hello-world", ((Map<String, Object>) saved.get("result")).get("id"));

			Map<String, Object> listed = post(server, "scripts.list", new LinkedHashMap<>());
			assertTrue(((java.util.List<?>) listed.get("result")).size() >= 4);
		}
		finally
		{
			server.close();
			host.close();
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> post(
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
		assertEquals(200, response.statusCode());
		return new Gson().fromJson(response.body(), Map.class);
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
}
