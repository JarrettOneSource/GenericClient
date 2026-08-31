package com.genericclient;

import com.google.inject.Injector;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;
import net.runelite.client.RuntimeConfig;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;

@Slf4j
@Singleton
public final class GenericClientDenseRuntime implements AutoCloseable
{
	private final Injector injector;
	private final Client client;
	private final RuntimeConfig runtimeConfig;
	private final ConfigManager configManager;
	private final EventBus eventBus;
	private final PluginManager pluginManager;
	private final GenericClientDenseSurface surface;
	private final AtomicBoolean closed = new AtomicBoolean();
	private Plugin genericClientPlugin;
	private Thread shutdownHook;

	@Inject
	GenericClientDenseRuntime(
		Injector injector,
		Client client,
		RuntimeConfig runtimeConfig,
		ConfigManager configManager,
		EventBus eventBus,
		PluginManager pluginManager,
		GenericClientDenseSurface surface)
	{
		this.injector = injector;
		this.client = client;
		this.runtimeConfig = runtimeConfig;
		this.configManager = configManager;
		this.eventBus = eventBus;
		this.pluginManager = pluginManager;
		this.surface = surface;
	}

	public void start() throws Exception
	{
		System.setProperty(GenericClientRuntimeOptions.DENSE_PROPERTY, "true");
		applyRuntimeSystemProperties();
		System.setProperty("jagex.disableBouncyCastle", "true");
		System.setProperty("jagex.userhome", RuneLite.RUNELITE_DIR.getAbsolutePath());

		injector.injectMembers(client);
		client.initialize();
		configManager.load();
		eventBus.register(configManager);
		surface.show((Component) client);

		List<Plugin> loaded = pluginManager.loadPlugins(
			Collections.<Class<?>>singletonList(GenericClientPlugin.class),
			null);
		if (loaded.size() != 1)
		{
			throw new IllegalStateException("Dense runtime could not instantiate GenericClient");
		}
		genericClientPlugin = loaded.get(0);
		pluginManager.loadDefaultPluginConfiguration(loaded);
		startPlugin(genericClientPlugin);
		client.unblockStartup();

		shutdownHook = new Thread(this::close, "GenericClient-Dense-Shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);
		log.info("Dense RuneLite runtime started with only GenericClient");
	}

	@Override
	public void close()
	{
		if (!closed.compareAndSet(false, true))
		{
			return;
		}
		ClientShutdown shutdown = new ClientShutdown();
		eventBus.post(shutdown);
		shutdown.waitForAllConsumers(Duration.ofSeconds(10));
		Plugin plugin = genericClientPlugin;
		genericClientPlugin = null;
		if (plugin != null)
		{
			try
			{
				runOnEdt(() ->
				{
					try
					{
						pluginManager.stopPlugin(plugin);
					}
					catch (PluginInstantiationException exception)
					{
						throw new IllegalStateException(exception);
					}
				});
			}
			catch (RuntimeException exception)
			{
				log.warn("Unable to stop GenericClient cleanly", exception);
			}
		}
		try
		{
			client.stopNow();
		}
		catch (RuntimeException exception)
		{
			log.debug("Injected client was already stopped", exception);
		}
		surface.close();
		Thread hook = shutdownHook;
		shutdownHook = null;
		if (hook != null && hook != Thread.currentThread())
		{
			try
			{
				Runtime.getRuntime().removeShutdownHook(hook);
			}
			catch (IllegalStateException ignored)
			{
				// JVM shutdown is already in progress.
			}
		}
	}

	private void applyRuntimeSystemProperties()
	{
		if (runtimeConfig == null || runtimeConfig.getSysProps() == null)
		{
			return;
		}
		for (Map.Entry<String, String> entry : runtimeConfig.getSysProps().entrySet())
		{
			if (entry.getKey() != null && entry.getValue() != null)
			{
				System.setProperty(entry.getKey(), entry.getValue());
			}
		}
	}

	private void startPlugin(Plugin plugin)
	{
		runOnEdt(() ->
		{
			pluginManager.setPluginEnabled(plugin, true);
			try
			{
				if (!pluginManager.startPlugin(plugin))
				{
					throw new IllegalStateException("GenericClient was not started");
				}
			}
			catch (PluginInstantiationException exception)
			{
				throw new IllegalStateException("Unable to start GenericClient", exception);
			}
		});
	}

	private static void runOnEdt(Runnable action)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			action.run();
			return;
		}
		try
		{
			SwingUtilities.invokeAndWait(action);
		}
		catch (InterruptedException exception)
		{
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for AWT", exception);
		}
		catch (InvocationTargetException exception)
		{
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException)
			{
				throw (RuntimeException) cause;
			}
			throw new IllegalStateException("AWT action failed", cause);
		}
	}
}
