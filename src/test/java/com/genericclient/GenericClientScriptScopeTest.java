package com.genericclient;

import static org.junit.Assert.*;

import com.genericclient.script.ScriptEnvironment;
import com.genericclient.script.ScriptScope;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class GenericClientScriptScopeTest
{
	@Test public void closingAnOldScopeCannotClearANewBindingOfTheSameEnvironment()
	{
		ScriptEnvironment environment = environment();
		ScriptScope old = new ScriptScope(environment);
		old.close();
		ScriptScope current = new ScriptScope(environment);
		try (current)
		{
			assertThrows(IllegalStateException.class, old::close);
			assertSame(environment, ScriptScope.current());
		}
		assertThrows(IllegalStateException.class, ScriptScope::current);
	}

	@Test public void nestedAndForeignWorkersCannotReplaceOrCloseTheBinding() throws Exception
	{
		ScriptEnvironment environment = environment();
		ScriptScope scope = new ScriptScope(environment);
		try (scope)
		{
			assertThrows(IllegalStateException.class, () -> new ScriptScope(environment()));
			ExecutionException failure = assertThrows(ExecutionException.class,
				() -> CompletableFuture.runAsync(scope::close).get(5, TimeUnit.SECONDS));
			assertEquals("Script scope is not bound to this worker", failure.getCause().getMessage());
			assertSame(environment, ScriptScope.current());
		}
		assertThrows(IllegalStateException.class, ScriptScope::current);
	}

	@Test public void aSatisfiedWaitStillRequiresAScriptWorker()
	{
		assertThrows(NullPointerException.class, () -> new ScriptScope(null));
		assertThrows(IllegalStateException.class, () -> org.dreambot.api.utilities.Sleep.sleepUntil(() -> true, 0));
		assertThrows(IllegalStateException.class, () -> org.dreambot.api.utilities.Sleep.sleepWhile(() -> false, 0));
	}

	@Test public void aScriptLoopChecksOwnershipBeforeUserCodeAndStopsWhenTheEnvironmentEnds()
	{
		java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
		java.util.concurrent.atomic.AtomicBoolean revoked = new java.util.concurrent.atomic.AtomicBoolean();
		java.util.concurrent.atomic.AtomicInteger loops = new java.util.concurrent.atomic.AtomicInteger();
		ScriptEnvironment environment = (ScriptEnvironment) Proxy.newProxyInstance(ScriptEnvironment.class.getClassLoader(),
			new Class<?>[]{ScriptEnvironment.class}, (proxy, method, arguments) -> {
				switch (method.getName())
				{
					case "isRunning": return running.get();
					case "checkpoint":
						if (revoked.get()) throw new java.util.concurrent.CancellationException();
						return null;
					case "sleep": running.set(false); return null;
					default: throw new AssertionError("Unexpected script effect: " + method.getName());
				}
			});
		org.dreambot.api.script.AbstractScript script = new org.dreambot.api.script.AbstractScript()
		{
			@Override public int onLoop() { loops.incrementAndGet(); return 0; }
		};
		ScriptScope scope = new ScriptScope(environment);
		try (scope)
		{
			script.run();
			assertEquals(1, loops.get());
			script.run();
			assertEquals(1, loops.get());
			running.set(true);
			revoked.set(true);
			assertThrows(java.util.concurrent.CancellationException.class, script::run);
			assertEquals(1, loops.get());
		}
	}

	private static ScriptEnvironment environment()
	{
		return (ScriptEnvironment) Proxy.newProxyInstance(ScriptEnvironment.class.getClassLoader(),
			new Class<?>[]{ScriptEnvironment.class}, (proxy, method, arguments) -> {
				throw new AssertionError("Binding a script must not invoke it: " + method.getName());
			});
	}
}
