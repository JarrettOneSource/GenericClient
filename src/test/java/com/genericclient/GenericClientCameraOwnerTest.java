package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.VarClientID;
import org.junit.Test;

public class GenericClientCameraOwnerTest
{
	@Test
	public void newerOperationsAndCancellationInvalidateStaleCameraWork()
	{
		CameraState camera = new CameraState();
		GenericClientCameraOwner owner = new GenericClientCameraOwner(camera.client());

		GenericClientCameraOwner.Operation first = owner.begin();
		assertTrue(first.isActive());

		GenericClientCameraOwner.Operation second = owner.begin();
		assertFalse(first.isActive());
		assertTrue(second.isActive());

		owner.cancel();
		assertFalse(second.isActive());

		assertFalse(second.face(1_024, 2_048));
		assertEquals(0, camera.yaw.get());
		assertEquals(0, camera.pitch.get());
	}

	@Test
	public void activeOperationFacesTheTargetWithoutChangingZoom()
	{
		CameraState camera = new CameraState();
		GenericClientCameraOwner.Operation operation =
			new GenericClientCameraOwner(camera.client()).begin();

		assertTrue(operation.face(4_096, 3_064));

		assertEquals(4_096, camera.yaw.get());
		assertEquals(3_064, camera.pitch.get());
		camera.assertZoom(120, 180, 20, 30);
	}

	private static final class CameraState
	{
		private final Map<Integer, Integer> varcs = new HashMap<>();
		private final AtomicInteger yaw = new AtomicInteger();
		private final AtomicInteger pitch = new AtomicInteger();

		private CameraState()
		{
			varcs.put(VarClientID.CAMERA_ZOOM_SMALL, 120);
			varcs.put(VarClientID.CAMERA_ZOOM_BIG, 180);
			varcs.put(VarClientID.CAMERA_ZOOM_SMALL_MIN, 20);
			varcs.put(VarClientID.CAMERA_ZOOM_BIG_MIN, 30);
		}

		private Client client()
		{
			return (Client) Proxy.newProxyInstance(
				Client.class.getClassLoader(),
				new Class<?>[]{Client.class},
				(proxy, method, arguments) ->
				{
					switch (method.getName())
					{
						case "getVarcIntValue":
							return varcs.getOrDefault((Integer) arguments[0], 0);
						case "setVarcIntValue":
							varcs.put((Integer) arguments[0], (Integer) arguments[1]);
							return null;
						case "runScript":
							applyScript((Object[]) arguments[0]);
							return null;
						case "setCameraYawTarget":
							yaw.set((Integer) arguments[0]);
							return null;
						case "setCameraPitchTarget":
							pitch.set((Integer) arguments[0]);
							return null;
						default:
							return method.getReturnType() == boolean.class
								? false
								: method.getReturnType().isPrimitive() ? 0 : null;
					}
				});
		}

		private void applyScript(Object[] arguments)
		{
			if (((Number) arguments[0]).intValue() != ScriptID.CAMERA_DO_ZOOM)
			{
				return;
			}
			varcs.put(VarClientID.CAMERA_ZOOM_SMALL, ((Number) arguments[1]).intValue());
			varcs.put(VarClientID.CAMERA_ZOOM_BIG, ((Number) arguments[2]).intValue());
		}

		private void assertZoom(int small, int big, int smallMinimum, int bigMinimum)
		{
			assertEquals(small, varcs.get(VarClientID.CAMERA_ZOOM_SMALL).intValue());
			assertEquals(big, varcs.get(VarClientID.CAMERA_ZOOM_BIG).intValue());
			assertEquals(smallMinimum,
				varcs.get(VarClientID.CAMERA_ZOOM_SMALL_MIN).intValue());
			assertEquals(bigMinimum,
				varcs.get(VarClientID.CAMERA_ZOOM_BIG_MIN).intValue());
		}
	}
}
