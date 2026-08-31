package com.genericclient;

import net.runelite.api.Client;

final class GenericClientCameraOwner
{
	private final Client client;
	private Operation active;

	GenericClientCameraOwner(Client client)
	{
		this.client = client;
	}

	synchronized Operation begin()
	{
		active = new Operation(this);
		return active;
	}

	synchronized void cancel()
	{
		active = null;
	}

	private synchronized boolean isActive(Operation operation)
	{
		return active == operation;
	}

	private synchronized boolean face(Operation operation, int yaw, int pitch)
	{
		if (active != operation)
		{
			return false;
		}
		client.setCameraYawTarget(yaw);
		client.setCameraPitchTarget(pitch);
		return true;
	}

	static final class Operation
	{
		private final GenericClientCameraOwner owner;

		private Operation(GenericClientCameraOwner owner)
		{
			this.owner = owner;
		}

		boolean isActive()
		{
			return owner.isActive(this);
		}

		boolean face(int yaw, int pitch)
		{
			return owner.face(this, yaw, pitch);
		}
	}
}
