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
		return begin(GenericClientActivityContext.none());
	}

	synchronized Operation begin(GenericClientActivityContext context)
	{
		active = new Operation(this, context);
		return active;
	}

	synchronized void cancel(GenericClientActivityContext context)
	{
		if (active != null && active.context.ownsSameInput(context)) active = null;
	}

	synchronized void cancel()
	{
		active = null;
	}

	private synchronized boolean isActive(Operation operation)
	{
		return active == operation && operation.context.isInputAllowed();
	}

	private synchronized boolean face(Operation operation, int yaw, int pitch)
	{
		if (!isActive(operation))
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
		private final GenericClientActivityContext context;

		private Operation(GenericClientCameraOwner owner, GenericClientActivityContext context)
		{
			this.owner = owner;
			this.context = context;
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
