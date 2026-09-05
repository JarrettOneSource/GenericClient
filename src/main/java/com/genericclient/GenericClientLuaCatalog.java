package com.genericclient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/** Registered script definitions, source persistence and isolated descriptor inspection. */
final class GenericClientLuaCatalog
{
	private final GenericClientScriptRegistry registry;
	private final GenericClientLuaHost host;
	private final Executor scheduler;
	private final Consumer<String> reporter;

	GenericClientLuaCatalog(Path directory, GenericClientLuaHost host, Executor scheduler, Consumer<String> reporter) throws IOException
	{
		registry = new GenericClientScriptRegistry(directory);
		this.host = host;
		this.scheduler = scheduler;
		this.reporter = reporter;
	}

	GenericClientScriptRegistry.Script definition(String id) { return registry.get(id); }
	GenericClientLuaScript open(String id) throws IOException
	{
		return new GenericClientLuaScript(host, id, registry.readExecutableSource(id));
	}

	List<GenericClientScriptRegistry.Script> listScripts()
	{
		return registry.list();
	}


	CompletableFuture<List<GenericClientScriptInput>> describe(String scriptId)
	{
		return inspect(scriptId, GenericClientLuaScript::getInputs);
	}


	CompletableFuture<List<GenericClientScriptAction>> describeActions(String scriptId)
	{
		return inspect(scriptId, GenericClientLuaScript::getActions);
	}


	private <T> CompletableFuture<T> inspect(
		String scriptId,
		Function<GenericClientLuaScript, T> reader)
	{
		CompletableFuture<T> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			try (GenericClientLuaScript descriptor = new GenericClientLuaScript(
				host,
				scriptId,
				registry.readExecutableSource(scriptId)))
			{
				completion.complete(reader.apply(descriptor));
			}
			catch (IOException | RuntimeException exception)
			{
				completion.completeExceptionally(exception);
			}
		});
		return completion;
	}


	long getManifestRevision()
	{
		return registry.getRevision();
	}


	String findRandomEventSolver(int npcId)
	{
		GenericClientScriptRegistry.Script solver = registry.findRandomEventSolver(npcId);
		return solver == null ? null : solver.getId();
	}


	CompletableFuture<String> reloadManifest()
	{
		CompletableFuture<String> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			try
			{
				registry.reload();
				String result = "SCRIPT_MANIFEST_LOADED scripts=" + registry.list().size();
				reporter.accept(result);
				completion.complete(result);
			}
			catch (IOException | RuntimeException exception)
			{
				reporter.accept("SCRIPT_MANIFEST_FAILED message=" + exception.getMessage());
				completion.completeExceptionally(exception);
			}
		});
		return completion;
	}


	CompletableFuture<Map<String, Object>> saveScript(
		String id,
		String name,
		String description,
		String source)
	{
		return saveScript(id, name, description, source, Collections.emptyList());
	}


	CompletableFuture<Map<String, Object>> saveScript(
		String id,
		String name,
		String description,
		String source,
		List<Integer> randomEvents)
	{
		CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
		scheduler.execute(() ->
		{
			try
			{
				completion.complete(registry.save(
					id, name, description, source, randomEvents).toMap());
			}
			catch (IOException | RuntimeException exception)
			{
				completion.completeExceptionally(exception);
			}
		});
		return completion;
	}


	List<Map<String, Object>> listScriptValues()
	{
		List<Map<String, Object>> result = new ArrayList<>();
		for (GenericClientScriptRegistry.Script script : registry.list())
		{
			result.add(script.toMap());
		}
		return Collections.unmodifiableList(result);
	}


	Map<String, Object> getScriptValue(String id) throws IOException
	{
		Map<String, Object> result = new LinkedHashMap<>(registry.get(id).toMap());
		result.put("source", registry.readSource(id));
		result.put("module_sources", registry.readModuleSources(id));
		return result;
	}

}
