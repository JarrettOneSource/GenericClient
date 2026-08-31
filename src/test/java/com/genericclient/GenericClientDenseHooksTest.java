package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.Canvas;
import java.awt.Image;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.MainBufferProvider;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.PostClientTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;
import org.junit.Test;

public class GenericClientDenseHooksTest
{
	@Test
	public void preservesServerTickDeferredEventAndClientThreadOrdering()
	{
		AtomicInteger tickCount = new AtomicInteger(4);
		Client client = client(tickCount);
		List<Object> events = new ArrayList<>();
		List<Object> deferred = new ArrayList<>();
		AtomicInteger replays = new AtomicInteger();
		AtomicInteger clientWork = new AtomicInteger();
		AtomicInteger tickEndWork = new AtomicInteger();
		ClientThread clientThread = clientThread(client);
		GenericClientDenseHooks hooks = new GenericClientDenseHooks(
			client,
			events::add,
			deferred::add,
			replays::incrementAndGet,
			clientThread,
			new DrawManager(),
			new RecordingInput());

		Object immediate = new Object();
		Object deferredEvent = new Object();
		hooks.post(immediate);
		hooks.postDeferred(deferredEvent);
		clientThread.invokeLater(clientWork::incrementAndGet);
		clientThread.invokeAtTickEnd(tickEndWork::incrementAndGet);
		hooks.serverTick();
		hooks.tick();

		assertSame(immediate, events.get(0));
		assertSame(deferredEvent, deferred.get(0));
		assertTrue(events.get(1) instanceof GameTick);
		assertEquals(1, replays.get());
		assertEquals(5, tickCount.get());
		assertEquals(1, clientWork.get());

		hooks.tickEnd();
		assertEquals(1, tickEndWork.get());
		assertTrue(events.get(2) instanceof PostClientTick);

		hooks.frame();
		assertTrue(events.get(3) instanceof BeforeRender);
	}

	@Test
	public void completesOnDemandFramesAndForwardsInput()
	{
		RecordingInput input = new RecordingInput();
		DrawManager drawManager = new DrawManager();
		Client client = client(new AtomicInteger());
		GenericClientDenseHooks hooks = new GenericClientDenseHooks(
			client,
			event -> { },
			event -> { },
			() -> { },
			clientThread(client),
			drawManager,
			input);
		BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB);
		AtomicReference<Image> received = new AtomicReference<>();
		drawManager.requestNextFrameListener(received::set);

		hooks.draw(new TestBuffer(image), null, 0, 0);
		assertSame(image, received.get());

		Canvas canvas = new Canvas();
		MouseEvent mouse = new MouseEvent(
			canvas, MouseEvent.MOUSE_MOVED, 1L, 0, 1, 2, 0, false);
		MouseWheelEvent wheel = new MouseWheelEvent(
			canvas, MouseEvent.MOUSE_WHEEL, 2L, 0, 1, 2, 0, false,
			MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1);
		KeyEvent key = new KeyEvent(canvas, KeyEvent.KEY_PRESSED, 3L, 0, KeyEvent.VK_A, 'a');

		assertSame(mouse, hooks.mouseMoved(mouse));
		assertSame(wheel, hooks.mouseWheelMoved(wheel));
		hooks.keyPressed(key);
		assertEquals(3, input.calls.get());
	}

	private static Client client(AtomicInteger tickCount)
	{
		return (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			(proxy, method, arguments) ->
			{
				switch (method.getName())
				{
					case "getTickCount":
						return tickCount.get();
					case "setTickCount":
						tickCount.set((Integer) arguments[0]);
						return null;
					case "isClientThread":
						return true;
					case "toString":
						return "DenseHooksTestClient";
					default:
						return defaultValue(method.getReturnType());
				}
			});
	}

	private static ClientThread clientThread(Client client)
	{
		try
		{
			ClientThread clientThread = new ClientThread();
			java.lang.reflect.Field field = ClientThread.class.getDeclaredField("client");
			field.setAccessible(true);
			field.set(clientThread, client);
			return clientThread;
		}
		catch (ReflectiveOperationException exception)
		{
			throw new AssertionError(exception);
		}
	}

	private static Object defaultValue(Class<?> type)
	{
		if (!type.isPrimitive())
		{
			return null;
		}
		if (type == boolean.class)
		{
			return false;
		}
		if (type == byte.class || type == short.class || type == int.class || type == long.class)
		{
			return 0;
		}
		if (type == float.class || type == double.class)
		{
			return 0.0;
		}
		if (type == char.class)
		{
			return '\0';
		}
		return null;
	}

	private static final class TestBuffer implements MainBufferProvider
	{
		private final BufferedImage image;

		private TestBuffer(BufferedImage image)
		{
			this.image = image;
		}

		@Override
		public Image getImage()
		{
			return image;
		}

		@Override
		public int[] getPixels()
		{
			return new int[image.getWidth() * image.getHeight()];
		}

		@Override
		public int getWidth()
		{
			return image.getWidth();
		}

		@Override
		public int getHeight()
		{
			return image.getHeight();
		}
	}

	private static final class RecordingInput
		implements GenericClientDenseHooks.InputForwarder
	{
		private final AtomicInteger calls = new AtomicInteger();

		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			calls.incrementAndGet();
			return event;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent event)
		{
			calls.incrementAndGet();
			return event;
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent event)
		{
			calls.incrementAndGet();
			return event;
		}

		@Override
		public MouseEvent mouseEntered(MouseEvent event)
		{
			calls.incrementAndGet();
			return event;
		}

		@Override
		public MouseEvent mouseExited(MouseEvent event)
		{
			calls.incrementAndGet();
			return event;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent event)
		{
			calls.incrementAndGet();
			return event;
		}

		@Override
		public MouseEvent mouseMoved(MouseEvent event)
		{
			calls.incrementAndGet();
			return event;
		}

		@Override
		public MouseWheelEvent mouseWheelMoved(MouseWheelEvent event)
		{
			calls.incrementAndGet();
			return event;
		}

		@Override
		public void keyPressed(KeyEvent event)
		{
			calls.incrementAndGet();
		}

		@Override
		public void keyReleased(KeyEvent event)
		{
			calls.incrementAndGet();
		}

		@Override
		public void keyTyped(KeyEvent event)
		{
			calls.incrementAndGet();
		}
	}
}
