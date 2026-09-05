package com.genericclient;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
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
	private static final int SCRIPT_TIMEOUT_SECONDS = 420;

	private final int requestedPort;
	private final GenericClientScriptHost scriptHost;
	private final GenericClientAutomationScheduler automationScheduler;
	private final GenericClientRandomEventController randomEventController;
	private final Supplier<java.util.concurrent.CompletableFuture<String>> logoutAction;
	private final Supplier<java.util.concurrent.CompletableFuture<String>> loginAction;
	private final Supplier<Map<String, Object>> statusSupplier;
	private final Supplier<String> noteSupplier;
	private final Function<String, java.util.concurrent.CompletableFuture<String>> noteSetter;
	private final Supplier<java.util.concurrent.CompletableFuture<Map<String, Object>>> screenshotAction;
	private final Supplier<java.util.concurrent.CompletableFuture<Map<String, Object>>> endBreakAction;
	private final GenericClientSceneHighlights sceneHighlights;
	private final Consumer<String> reporter;
	private final Map<String, RpcHandler> handlers;
	private final Gson gson = new Gson();
	private Supplier<Map<String, Object>> healthSupplier = Collections::emptyMap;
	private HttpServer server;
	private ExecutorService executor;

	GenericClientControlServer(
		int port,
		GenericClientScriptHost scriptHost,
		GenericClientAutomationScheduler automationScheduler,
		GenericClientRandomEventController randomEventController,
		Supplier<java.util.concurrent.CompletableFuture<String>> logoutAction,
		Supplier<java.util.concurrent.CompletableFuture<String>> loginAction,
		Supplier<Map<String, Object>> statusSupplier,
		Supplier<String> noteSupplier,
		Function<String, java.util.concurrent.CompletableFuture<String>> noteSetter,
		Supplier<java.util.concurrent.CompletableFuture<Map<String, Object>>> screenshotAction,
		Supplier<java.util.concurrent.CompletableFuture<Map<String, Object>>> endBreakAction,
		GenericClientSceneHighlights sceneHighlights,
		Consumer<String> reporter)
	{
		this.requestedPort = port;
		this.scriptHost = scriptHost;
		this.automationScheduler = automationScheduler;
		this.randomEventController = randomEventController;
		this.logoutAction = logoutAction;
		this.loginAction = loginAction;
		this.statusSupplier = statusSupplier;
		this.noteSupplier = noteSupplier;
		this.noteSetter = noteSetter;
		this.screenshotAction = screenshotAction;
		this.endBreakAction = endBreakAction;
		this.sceneHighlights = sceneHighlights;
		this.reporter = reporter;
		this.handlers = createHandlers();
	}

	void start() throws IOException
	{
		if (server != null)
		{
			throw new IllegalStateException("GenericClient control server is already running");
		}
		try
		{
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
		}
		catch (BindException exception)
		{
			if (requestedPort == 0)
			{
				throw exception;
			}
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			reporter.accept("CONTROL_PORT_BUSY requested=" + requestedPort +
				" fallback=" + server.getAddress().getPort());
		}
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

	void setHealthSupplier(Supplier<Map<String, Object>> healthSupplier)
	{
		if (server != null)
		{
			throw new IllegalStateException("Health supplier must be set before server start");
		}
		this.healthSupplier = healthSupplier == null ? Collections::emptyMap : healthSupplier;
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
		Map<String, Object> supplied = healthSupplier.get();
		Map<String, Object> value = supplied == null
			? new LinkedHashMap<>()
			: new LinkedHashMap<>(supplied);
		value.put("ok", true);
		value.put("name", "GenericClient");
		value.put("protocol", 1);
		value.put("control_url", getUrl());
		write(exchange, 200, value);
	}

	private void handleRpc(HttpExchange exchange) throws IOException
	{
		if (exchange.getRequestHeaders().containsKey("Origin"))
		{
			write(exchange, 403, error("Browser-originated control requests are not accepted"));
			return;
		}
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
			write(exchange, 504, error("Script execution exceeded " + SCRIPT_TIMEOUT_SECONDS + " seconds"));
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
		RpcHandler handler = handlers.get(method);
		if (handler == null)
		{
			throw new IllegalArgumentException("Unknown RPC method: " + method);
		}
		return handler.handle(parameters);
	}

	private Map<String, RpcHandler> createHandlers()
	{
		Map<String, RpcHandler> values = new LinkedHashMap<>();
		values.put("status", parameters -> statusSupplier.get());
		values.put("scene.highlight", parameters -> sceneHighlights.replace(parameters.get("markers")));
		values.put("scene.clear", parameters -> sceneHighlights.clear());
		values.put("screenshot.capture", parameters -> await(screenshotAction.get(), 15));
		values.put("behavior.status", parameters -> behaviorStatus());
		values.put("behavior.profile", parameters -> behaviorProfile());
		values.put("behavior.break.end", parameters -> await(endBreakAction.get(), 30));
		values.put("random_event.status", parameters -> randomEvents().status());
		values.put("random_event.acknowledge", parameters ->
			await(randomEvents().acknowledge(), 10));
		values.put("random_event.complete", parameters -> await(randomEvents().complete(
			optionalStringParameter(parameters, "reason", "completed_via_control"),
			optionalBooleanParameter(parameters, "resume_interrupted", true)), 30));
		values.put("automation.status", parameters -> automation().status());
		values.put("automation.config.get", parameters -> await(automation().getConfig(), 10));
		values.put("automation.config.set", parameters -> await(
			automation().configure(objectParameter(parameters, "config")), 10));
		values.put("automation.enable", parameters -> await(
			automation().setEnabled(booleanParameter(parameters, "enabled")), 10));
		values.put("automation.pause", parameters -> await(
			automation().setPaused(true, "control"), 10));
		values.put("automation.resume", parameters -> await(
			automation().setPaused(false, "control"), 10));
		values.put("automation.reload", parameters -> await(automation().reload(), 10));
		values.put("account.snapshot", parameters -> await(
			scriptHost.readCurrentSnapshot("account"), 10));
		values.put("account.note.get", parameters -> noteSupplier.get());
		values.put("account.note.set", parameters -> await(
			noteSetter.apply(noteParameter(parameters)), 10));
		values.put("session.logout", parameters -> await(logoutAction.get(), 30));
		values.put("session.login", parameters -> await(loginAction.get(), 30));
		values.put("java.eval", parameters -> await(
			scriptHost.evaluate(stringParameter(parameters, "code")), SCRIPT_TIMEOUT_SECONDS));
		values.put("scripts.list", parameters -> scriptHost.listScriptValues());
		values.put("scripts.get", this::scriptDetails);
		values.put("scripts.compile", parameters -> await(scriptHost.compile(
			stringParameter(parameters, "class_name"), stringParameter(parameters, "source")), 30));
		values.put("scripts.run", parameters -> await(scriptHost.start(
			stringParameter(parameters, "id"), inputParameters(parameters)), 10));
		values.put("scripts.action", parameters -> await(
			scriptHost.triggerAction(stringParameter(parameters, "action")), 10));
		values.put("scripts.stop", parameters -> await(scriptHost.stop(), 10));
		values.put("scripts.reload", parameters -> await(scriptHost.reloadCatalog(), 10));
		return Collections.unmodifiableMap(values);
	}

	private Object behaviorProfile()
	{
		Object behavior = behaviorStatus();
		return behavior instanceof Map ? ((Map<?, ?>) behavior).get("profile") : null;
	}

	private Map<String, Object> scriptDetails(Map<String, Object> parameters)
		throws IOException, ExecutionException, InterruptedException, TimeoutException
	{
		String id = stringParameter(parameters, "id");
		Map<String, Object> script = new LinkedHashMap<>(scriptHost.getScriptValue(id));
		List<Map<String, Object>> inputs = new ArrayList<>();
		for (GenericClientScriptInput input : await(scriptHost.describe(id), 10))
		{
			inputs.add(input.toMap());
		}
		script.put("inputs", inputs);
		List<Map<String, Object>> actions = new ArrayList<>();
		for (GenericClientScriptAction action : await(scriptHost.describeActions(id), 10))
		{
			actions.add(action.toMap());
		}
		script.put("actions", actions);
		return script;
	}

	private static <T> T await(java.util.concurrent.CompletableFuture<T> future, int seconds)
		throws ExecutionException, InterruptedException, TimeoutException
	{
		return future.get(seconds, TimeUnit.SECONDS);
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

	@FunctionalInterface
	private interface RpcHandler
	{
		Object handle(Map<String, Object> parameters)
			throws IOException, ExecutionException, InterruptedException, TimeoutException;
	}
}
