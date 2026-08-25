package com.genericclient;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public final class GenericClientLauncher
{
	private GenericClientLauncher()
	{
	}

	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GenericClientPlugin.class);
		RuneLite.main(args);
	}
}
