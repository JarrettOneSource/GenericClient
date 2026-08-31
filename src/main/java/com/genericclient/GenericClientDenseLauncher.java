package com.genericclient;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.google.inject.util.Modules;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.hooks.Callbacks;
import net.runelite.client.RuneLite;
import net.runelite.client.RuneLiteModule;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.RuntimeConfigLoader;
import net.runelite.client.rs.ClientLoader;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.OkHttpClient;

@Slf4j
public final class GenericClientDenseLauncher
{
	private GenericClientDenseLauncher()
	{
	}

	public static void main(String[] args)
	{
		try
		{
			launch(args);
		}
		catch (Throwable error)
		{
			log.error("Dense RuneLite startup failed", error);
			System.exit(1);
		}
	}

	private static void launch(String[] args) throws Exception
	{
		System.setProperty(GenericClientRuntimeOptions.DENSE_PROPERTY, "true");
		System.setProperty("java.awt.headless", "false");
		Locale.setDefault(Locale.ENGLISH);
		Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
			log.error("Uncaught exception on {}", thread.getName(), error));

		Arguments parsed = Arguments.parse(args);
		OkHttpClient httpClient = new OkHttpClient.Builder()
			.pingInterval(30L, TimeUnit.SECONDS)
			.build();
		RuneLiteAPI.CLIENT = httpClient;
		RuntimeConfigLoader runtimeConfigLoader = new RuntimeConfigLoader(httpClient);
		ClientLoader clientLoader = new ClientLoader(
			httpClient,
			runtimeConfigLoader,
			parsed.javConfig);
		RuneLiteModule runeLiteModule = new RuneLiteModule(
			httpClient,
			clientLoader,
			runtimeConfigLoader,
			false,
			false,
			true,
			RuneLite.DEFAULT_SESSION_FILE,
			parsed.profile,
			false,
			true);
		Injector injector = Guice.createInjector(
			Modules.override(runeLiteModule).with(new DenseModule()));
		RuneLite.setInjector(injector);
		GenericClientDenseRuntime runtime = injector.getInstance(GenericClientDenseRuntime.class);
		runtime.start();
	}

	private static final class DenseModule extends AbstractModule
	{
		@Override
		protected void configure()
		{
			bind(Callbacks.class).to(GenericClientDenseHooks.class).in(Singleton.class);
			bind(GenericClientDenseSurface.class).in(Singleton.class);
			bind(GenericClientDenseRuntime.class).in(Singleton.class);
		}
	}

	private static final class Arguments
	{
		private final String javConfig;
		private final String profile;

		private Arguments(String javConfig, String profile)
		{
			this.javConfig = javConfig;
			this.profile = profile;
		}

		private static Arguments parse(String[] args)
		{
			String javConfig = RuneLiteProperties.getJavConfig();
			String profile = System.getProperty(GenericClientRuntimeOptions.RUNELITE_PROFILE_PROPERTY);
			for (int index = 0; index < args.length; index++)
			{
				String argument = args[index];
				if (argument.startsWith("--profile="))
				{
					profile = argument.substring("--profile=".length());
				}
				else if ("--profile".equals(argument) && index + 1 < args.length)
				{
					profile = args[++index];
				}
				else if (argument.startsWith("--jav_config="))
				{
					javConfig = argument.substring("--jav_config=".length());
				}
				else if ("--jav_config".equals(argument) && index + 1 < args.length)
				{
					javConfig = args[++index];
				}
			}
			if (profile != null && profile.trim().isEmpty())
			{
				profile = null;
			}
			return new Arguments(javConfig, profile);
		}
	}
}
