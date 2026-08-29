package com.genericclient;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class GenericClientControlServer implements AutoCloseable
{
	private static final int MAX_REQUEST_BYTES = 1_048_576;
	private static final int LUA_TIMEOUT_SECONDS = 420;

	private final int requestedPort;
	private final GenericClientLuaHost luaHost;
	private final GenericClientAutomationScheduler automationScheduler;
	private final GenericClientRandomEventController randomEventController;
	private final Supplier<java.util.concurrent.CompletableFuture<String>> logoutAction;
	private final Supplier<java.util.concurrent.CompletableFuture<String>> loginAction;
	private final Supplier<Map<String, Object>> statusSupplier;
	private final Supplier<String> noteSupplier;
	private final Function<String, java.util.concurrent.CompletableFuture<String>> noteSetter;
	private final Supplier<java.util.concurrent.CompletableFuture<Map<String, Object>>> screenshotAction;
	private final Supplier<java.util.concurrent.CompletableFuture<Map<String, Object>>> endBreakAction;
	private final Consumer<String> reporter;
	private final Gson gson = new Gson();
	private HttpServer server;
	private ExecutorService executor;

	GenericClientControlServer(
		int port,
		GenericClientLuaHost luaHost,
		Supplier<java.util.concurrent.CompletableFuture<String>> logoutAction,
		Supplier<java.util.concurrent.CompletableFuture<String>> loginAction,
		Supplier<Map<String, Object>> statusSupplier,
		Supplier<String> noteSupplier,
		Function<String, java.util.concurrent.CompletableFuture<String>> noteSetter,
		Supplier<java.util.concurrent.CompletableFuture<Map<String, Object>>> screenshotAction,
		Supplier<java.util.concurrent.CompletableFuture<Map<String, Object>>> endBreakAction,
		Consumer<String> reporter)
	{
		this(
			port,
			luaHost,
			null,
			null,
			logoutAction,
			loginAction,
			statusSupplier,
			noteSupplier,
			noteSetter,
			screenshotAction,
			endBreakAction,
			reporter);
	}

	GenericClientControlServer(
		int port,
		GenericClientLuaHost luaHost,
		GenericClientAutomationScheduler automationScheduler,
		Supplier<java.util.concurrent.CompletableFuture<String>> logoutAction,
		Supplier<java.util.concurrent.CompletableFuture<String>> loginAction,
		Supplier<Map<String, Object>> statusSupplier,
		Supplier<String> noteSupplier,
		Function<String, java.util.concurrent.CompletableFuture<String>> noteSetter,
		Supplier<java.util.concurrent.CompletableFuture<Map<String, Object>>> screenshotAction,
		Supplier<java.util.concurrent.CompletableFuture<Map<String, Object>>> endBreakAction,
		Consumer<String> reporter)
	{
		this(
			port,
			luaHost,
			automationScheduler,
			null,
			logoutAction,
			loginAction,
			statusSupplier,
			noteSupplier,
			noteSetter,
			screenshotAction,
			endBreakAction,
			reporter);
	}

	GenericClientControlServer(
		int port,
		GenericClientLuaHost luaHost,
		GenericClientAutomationScheduler automationScheduler,
		GenericClientRandomEventController randomEventController,
		Supplier<java.util.concurrent.CompletableFuture<String>> logoutAction,
		Supplier<java.util.concurrent.CompletableFuture<String>> loginAction,
		Supplier<Map<String, Object>> statusSupplier,
		Supplier<String> noteSupplier,
		Function<String, java.util.concurrent.CompletableFuture<String>> noteSetter,
		Supplier<java.util.concurrent.CompletableFuture<Map<String, Object>>> screenshotAction,
		Supplier<java.util.concurrent.CompletableFuture<Map<String, Object>>> endBreakAction,
		Consumer<String> reporter)
	{
		this.requestedPort = port;
		this.luaHost = luaHost;
		this.automationScheduler = automationScheduler;
		this.randomEventController = randomEventController;
		this.logoutAction = logoutAction;
		this.loginAction = loginAction;
		this.statusSupplier = statusSupplier;
		this.noteSupplier = noteSupplier;
		this.noteSetter = noteSetter;
		this.screenshotAction = screenshotAction;
		this.endBreakAction = endBreakAction;
		this.reporter = reporter;
	}

	void start() throws IOException
	{
		if (server != null)
		{
			throw new IllegalStateException("GenericClient control server is already running");
		}
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
		executor = Executors.newCachedThreadPool(runnable ->
		{
			Thread thread = new Thread(runnable, "GenericClient-Control");
			thread.setDaemon(true);
			return thread;
		});
		server.setExecutor(executor);
		server.createContext("/health", this::handleHealth);
		server.createContext("/rpc", this::handleRpc);
		server.start();
		reporter.accept("CONTROL_SERVER_STARTED url=" + getUrl());
	}

	String getUrl()
	{
		if (server == null)
		{
			return "http://127.0.0.1:" + requestedPort;
		}
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private void handleHealth(HttpExchange exchange) throws IOException
	{
		if (!"GET".equals(exchange.getRequestMethod()))
		{
			write(exchange, 405, error("Health requires GET"));
			return;
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("ok", true);
		value.put("name", "GenericClient");
		value.put("protocol", 1);
		write(exchange, 200, value);
	}

	private void handleRpc(HttpExchange exchange) throws IOException
	{
		if (!"POST".equals(exchange.getRequestMethod()))
		{
			write(exchange, 405, error("RPC requires POST"));
			return;
		}

		byte[] requestBytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
		if (requestBytes.length > MAX_REQUEST_BYTES)
		{
			write(exchange, 413, error("RPC request exceeds 1 MiB"));
			return;
		}

		try
		{
			RpcRequest request = gson.fromJson(
				new String(requestBytes, StandardCharsets.UTF_8),
				RpcRequest.class);
			if (request == null || request.method == null || request.method.trim().isEmpty())
			{
				throw new IllegalArgumentException("RPC request requires a method");
			}
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("ok", true);
			response.put("result", dispatch(request.method, request.params == null
				? Collections.emptyMap()
				: request.params));
			write(exchange, 200, response);
		}
		catch (JsonParseException | IllegalArgumentException exception)
		{
			write(exchange, 400, error(exception.getMessage()));
		}
		catch (ExecutionException exception)
		{
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			write(exchange, 409, error(cause.getMessage()));
		}
		catch (IllegalStateException exception)
		{
			write(exchange, 409, error(exception.getMessage()));
		}
		catch (TimeoutException exception)
		{
			write(exchange, 504, error("Lua execution exceeded " + LUA_TIMEOUT_SECONDS + " seconds"));
		}
		catch (InterruptedException exception)
		{
			Thread.currentThread().interrupt();
			write(exchange, 503, error("Control request was interrupted"));
		}
		catch (RuntimeException exception)
		{
			reporter.accept("CONTROL_REQUEST_FAILED method=" + exchange.getRequestMethod() +
				" message=" + exception.getMessage());
			write(exchange, 500, error(exception.getMessage()));
		}
		catch (IOException exception)
		{
			write(exchange, 500, error(exception.getMessage()));
		}
	}

	private Object dispatch(String method, Map<String, Object> parameters)
		throws IOException, ExecutionException, InterruptedException, TimeoutException
	{
		switch (method)
		{
			case "status":
				return statusSupplier.get();
			case "screenshot.capture":
				return screenshotAction.get().get(15, TimeUnit.SECONDS);
			case "behavior.status":
				return behaviorStatus();
			case "behavior.profile":
				Object behavior = behaviorStatus();
				return behavior instanceof Map ? ((Map<?, ?>) behavior).get("profile") : null;
			case "behavior.break.end":
				return endBreakAction.get().get(30, TimeUnit.SECONDS);
			case "random_event.status":
				return randomEvents().status();
			case "random_event.acknowledge":
				return randomEvents().acknowledge().get(10, TimeUnit.SECONDS);
			case "random_event.complete":
				return randomEvents().complete(
					optionalStringParameter(parameters, "reason", "completed_via_control"),
					optionalBooleanParameter(parameters, "resume_interrupted", true))
					.get(30, TimeUnit.SECONDS);
			case "automation.status":
				return automation().status();
			case "automation.config.get":
				return automation().getConfig().get(10, TimeUnit.SECONDS);
			case "automation.config.set":
				return automation().configure(objectParameter(parameters, "config"))
					.get(10, TimeUnit.SECONDS);
			case "automation.enable":
				return automation().setEnabled(booleanParameter(parameters, "enabled"))
					.get(10, TimeUnit.SECONDS);
			case "automation.pause":
				return automation().setPaused(true, "control").get(10, TimeUnit.SECONDS);
			case "automation.resume":
				return automation().setPaused(false, "control").get(10, TimeUnit.SECONDS);
			case "automation.reload":
				return automation().reload().get(10, TimeUnit.SECONDS);
			case "account.snapshot":
				return luaHost.readCurrentSnapshot("account").get(10, TimeUnit.SECONDS);
			case "account.note.get":
				return noteSupplier.get();
			case "account.note.set":
				return noteSetter.apply(noteParameter(parameters)).get(10, TimeUnit.SECONDS);
			case "session.logout":
				return logoutAction.get().get(30, TimeUnit.SECONDS);
			case "session.login":
				return loginAction.get().get(30, TimeUnit.SECONDS);
			case "lua.eval":
				return luaHost.evaluate(stringParameter(parameters, "code"))
					.get(LUA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			case "lua.reset":
				return luaHost.resetRepl().get(10, TimeUnit.SECONDS);
			case "scripts.list":
				return luaHost.listScriptValues();
			case "scripts.get":
			{
				String id = stringParameter(parameters, "id");
				Map<String, Object> script = new LinkedHashMap<>(luaHost.getScriptValue(id));
				List<Map<String, Object>> inputs = new ArrayList<>();
				for (GenericClientScriptInput input : luaHost.describe(id).get(10, TimeUnit.SECONDS))
				{
					inputs.add(input.toMap());
				}
				script.put("inputs", inputs);
				List<Map<String, Object>> actions = new ArrayList<>();
				for (GenericClientScriptAction action : luaHost.describeActions(id).get(10, TimeUnit.SECONDS))
				{
					actions.add(action.toMap());
				}
				script.put("actions", actions);
				return script;
			}
			case "scripts.save":
				return luaHost.saveScript(
					stringParameter(parameters, "id"),
					stringParameter(parameters, "name"),
					stringParameter(parameters, "description"),
					stringParameter(parameters, "source"),
					integerListParameter(parameters, "random_events"))
					.get(10, TimeUnit.SECONDS);
			case "scripts.run":
				return luaHost.start(
					stringParameter(parameters, "id"),
					inputParameters(parameters)).get(10, TimeUnit.SECONDS);
			case "scripts.action":
				return luaHost.triggerAction(stringParameter(parameters, "action"))
					.get(10, TimeUnit.SECONDS);
			case "scripts.stop":
				return luaHost.stop().get(10, TimeUnit.SECONDS);
			case "scripts.reload":
				return luaHost.reloadManifest().get(10, TimeUnit.SECONDS);
			default:
				throw new IllegalArgumentException("Unknown RPC method: " + method);
		}
	}

	private Object behaviorStatus()
	{
		return statusSupplier.get().get("behavior");
	}

	private GenericClientAutomationScheduler automation()
	{
		if (automationScheduler == null)
		{
			throw new IllegalStateException("Automation scheduler is unavailable");
		}
		return automationScheduler;
	}

	private GenericClientRandomEventController randomEvents()
	{
		if (randomEventController == null)
		{
			throw new IllegalStateException("Random-event controller is unavailable");
		}
		return randomEventController;
	}

	private static String stringParameter(Map<String, Object> parameters, String name)
	{
		Object value = parameters.get(name);
		if (!(value instanceof String) || ((String) value).trim().isEmpty())
		{
			throw new IllegalArgumentException("RPC parameter " + name + " must be a non-empty string");
		}
		return (String) value;
	}

	private static String noteParameter(Map<String, Object> parameters)
	{
		Object value = parameters.get("text");
		if (!(value instanceof String) || ((String) value).trim().isEmpty())
		{
			throw new IllegalArgumentException("RPC parameter text must be a non-empty string");
		}
		if (((String) value).length() > 20_000)
		{
			throw new IllegalArgumentException("RPC account note cannot exceed 20,000 characters");
		}
		return (String) value;
	}

	private static boolean booleanParameter(Map<String, Object> parameters, String name)
	{
		Object value = parameters.get(name);
		if (!(value instanceof Boolean))
		{
			throw new IllegalArgumentException("RPC parameter " + name + " must be a boolean");
		}
		return (Boolean) value;
	}

	private static boolean optionalBooleanParameter(
		Map<String, Object> parameters,
		String name,
		boolean defaultValue)
	{
		Object value = parameters.get(name);
		if (value == null)
		{
			return defaultValue;
		}
		if (!(value instanceof Boolean))
		{
			throw new IllegalArgumentException("RPC parameter " + name + " must be a boolean");
		}
		return (Boolean) value;
	}

	private static String optionalStringParameter(
		Map<String, Object> parameters,
		String name,
		String defaultValue)
	{
		Object value = parameters.get(name);
		if (value == null)
		{
			return defaultValue;
		}
		if (!(value instanceof String) || ((String) value).trim().isEmpty())
		{
			throw new IllegalArgumentException("RPC parameter " + name + " must be a non-empty string");
		}
		return ((String) value).trim();
	}

	private static List<Integer> integerListParameter(Map<String, Object> parameters, String name)
	{
		Object value = parameters.get(name);
		if (value == null)
		{
			return Collections.emptyList();
		}
		if (!(value instanceof List))
		{
			throw new IllegalArgumentException("RPC parameter " + name + " must be an array of integers");
		}
		List<Integer> result = new ArrayList<>();
		for (Object item : (List<?>) value)
		{
			if (!(item instanceof Number))
			{
				throw new IllegalArgumentException(
					"RPC parameter " + name + " must contain only integers");
			}
			double numeric = ((Number) item).doubleValue();
			int integer = ((Number) item).intValue();
			if (numeric != integer || integer <= 0)
			{
				throw new IllegalArgumentException(
					"RPC parameter " + name + " must contain only positive integers");
			}
			result.add(integer);
		}
		return result;
	}

	private static Map<String, Object> objectParameter(Map<String, Object> parameters, String name)
	{
		Object value = parameters.get(name);
		if (!(value instanceof Map))
		{
			throw new IllegalArgumentException("RPC parameter " + name + " must be an object");
		}
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
		{
			if (!(entry.getKey() instanceof String))
			{
				throw new IllegalArgumentException("RPC object keys must be strings");
			}
			result.put((String) entry.getKey(), entry.getValue());
		}
		return result;
	}

	private static Map<String, Object> inputParameters(Map<String, Object> parameters)
	{
		Object rawInputs = parameters.get("inputs");
		if (rawInputs == null)
		{
			return Collections.emptyMap();
		}
		if (!(rawInputs instanceof Map))
		{
			throw new IllegalArgumentException("RPC parameter inputs must be an object");
		}
		Map<String, Object> inputs = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawInputs).entrySet())
		{
			if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String))
			{
				throw new IllegalArgumentException("RPC script input values must be strings");
			}
			inputs.put((String) entry.getKey(), entry.getValue());
		}
		return inputs;
	}

	private static Map<String, Object> error(String message)
	{
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("ok", false);
		response.put("error", message == null ? "Unknown control error" : message);
		return response;
	}

	private void write(HttpExchange exchange, int status, Object value) throws IOException
	{
		byte[] body = gson.toJson(value).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream output = exchange.getResponseBody())
		{
			output.write(body);
		}
	}

	@Override
	public void close()
	{
		HttpServer current = server;
		server = null;
		if (current != null)
		{
			current.stop(0);
		}
		ExecutorService currentExecutor = executor;
		executor = null;
		if (currentExecutor != null)
		{
			currentExecutor.shutdownNow();
		}
	}

	private static final class RpcRequest
	{
		private String method;
		private Map<String, Object> params;
	}
}
