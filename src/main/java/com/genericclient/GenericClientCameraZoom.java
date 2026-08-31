package com.genericclient;

import java.util.concurrent.CompletableFuture;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.callback.ClientThread;

final class GenericClientCameraZoom
{
	private static final int OUTER_ZOOM = -400;

	private GenericClientCameraZoom()
	{
	}

	static CompletableFuture<State> widen(Client client, ClientThread clientThread)
	{
		CompletableFuture<State> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			State previous = new State(
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL),
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG),
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL_MIN),
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG_MIN));
			client.setVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL_MIN, OUTER_ZOOM);
			client.setVarcIntValue(VarClientID.CAMERA_ZOOM_BIG_MIN, OUTER_ZOOM);
			client.runScript(ScriptID.CAMERA_DO_ZOOM, OUTER_ZOOM, OUTER_ZOOM);
			result.complete(previous);
		});
		return result;
	}

	static void restore(Client client, ClientThread clientThread, State previous)
	{
		clientThread.invoke(() ->
		{
			client.setVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL_MIN, previous.smallMinimum);
			client.setVarcIntValue(VarClientID.CAMERA_ZOOM_BIG_MIN, previous.bigMinimum);
			client.runScript(ScriptID.CAMERA_DO_ZOOM, previous.small, previous.big);
		});
	}

	static final class State
	{
		private final int small;
		private final int big;
		private final int smallMinimum;
		private final int bigMinimum;

		private State(int small, int big, int smallMinimum, int bigMinimum)
		{
			this.small = small;
			this.big = big;
			this.smallMinimum = smallMinimum;
			this.bigMinimum = bigMinimum;
		}
	}
}
