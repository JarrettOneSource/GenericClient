package com.genericclient;

import java.awt.Graphics;
import java.awt.Image;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MainBufferProvider;
import net.runelite.api.Renderable;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.hooks.Callbacks;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.DeferredEventBus;

@Slf4j
@Singleton
public final class GenericClientDenseHooks implements Callbacks
{
	private static final GameTick GAME_TICK = new GameTick();
	private static final BeforeRender BEFORE_RENDER = new BeforeRender();
	private static final Method CLIENT_THREAD_INVOKE = clientThreadMethod("invoke");
	private static final Method CLIENT_THREAD_TICK_END = clientThreadMethod("invokeTickEnd");

	private final Client client;
	private final Consumer<Object> eventPoster;
	private final Consumer<Object> deferredPoster;
	private final Runnable deferredReplay;
	private final ClientThread clientThread;
	private final DrawManager drawManager;
	private final InputForwarder input;
	private boolean shouldProcessGameTick;

	@Inject
	GenericClientDenseHooks(
		Client client,
		EventBus eventBus,
		DeferredEventBus deferredEventBus,
		ClientThread clientThread,
		DrawManager drawManager,
		MouseManager mouseManager,
		KeyManager keyManager)
	{
		this(
			client,
			eventBus::post,
			deferredEventBus::post,
			deferredEventBus::replay,
			clientThread,
			drawManager,
			new RuneLiteInputForwarder(mouseManager, keyManager));
	}

	GenericClientDenseHooks(
		Client client,
		Consumer<Object> eventPoster,
		Consumer<Object> deferredPoster,
		Runnable deferredReplay,
		ClientThread clientThread,
		DrawManager drawManager,
		InputForwarder input)
	{
		this.client = client;
		this.eventPoster = eventPoster;
		this.deferredPoster = deferredPoster;
		this.deferredReplay = deferredReplay;
		this.clientThread = clientThread;
		this.drawManager = drawManager;
		this.input = input;
	}

	@Override
	public void post(Object event)
	{
		eventPoster.accept(event);
	}

	@Override
	public void postDeferred(Object event)
	{
		deferredPoster.accept(event);
	}

	@Override
	public void tick()
	{
		if (shouldProcessGameTick)
		{
			shouldProcessGameTick = false;
			deferredReplay.run();
			eventPoster.accept(GAME_TICK);
			client.setTickCount(client.getTickCount() + 1);
		}
		invokeClientThread(CLIENT_THREAD_INVOKE);
	}

	@Override
	public void tickEnd()
	{
		invokeClientThread(CLIENT_THREAD_TICK_END);
		eventPoster.accept(new PostClientTick());
	}

	@Override
	public void frame()
	{
		eventPoster.accept(BEFORE_RENDER);
	}

	@Override
	public void serverTick()
	{
		shouldProcessGameTick = true;
	}

	@Override
	public void drawScene()
	{
	}

	@Override
	public void drawAboveOverheads()
	{
	}

	@Override
	public void draw(MainBufferProvider bufferProvider, Graphics graphics, int x, int y)
	{
		drawManager.processDrawComplete(bufferProvider::getImage);
		if (Boolean.getBoolean("genericclient.dense.present") && graphics != null)
		{
			Image image = bufferProvider.getImage();
			graphics.drawImage(image, x, y, null);
		}
	}

	@Override
	public void drawInterface(int interfaceId, List<WidgetItem> widgetItems)
	{
	}

	@Override
	public void drawLayer(Widget layer, List<WidgetItem> widgetItems)
	{
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		return input.mousePressed(event);
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		return input.mouseReleased(event);
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		return input.mouseClicked(event);
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent event)
	{
		return input.mouseEntered(event);
	}

	@Override
	public MouseEvent mouseExited(MouseEvent event)
	{
		return input.mouseExited(event);
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent event)
	{
		return input.mouseDragged(event);
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent event)
	{
		return input.mouseMoved(event);
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent event)
	{
		return input.mouseWheelMoved(event);
	}

	@Override
	public void keyPressed(KeyEvent event)
	{
		input.keyPressed(event);
	}

	@Override
	public void keyReleased(KeyEvent event)
	{
		input.keyReleased(event);
	}

	@Override
	public void keyTyped(KeyEvent event)
	{
		input.keyTyped(event);
	}

	@Override
	public boolean draw(Renderable renderable, boolean drawingUi)
	{
		return true;
	}

	@Override
	public void error(String message, Throwable throwable)
	{
		if (throwable == null)
		{
			log.error(message);
		}
		else
		{
			log.error(message, throwable);
		}
	}

	@Override
	public void openUrl(String url)
	{
		log.info("Dense client suppressed external URL open: {}", url);
	}

	@Override
	public boolean isRuneLiteClientOutdated()
	{
		return false;
	}

	private void invokeClientThread(Method method)
	{
		try
		{
			method.invoke(clientThread);
		}
		catch (IllegalAccessException exception)
		{
			throw new IllegalStateException("Cannot drain RuneLite client-thread work", exception);
		}
		catch (InvocationTargetException exception)
		{
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException)
			{
				throw (RuntimeException) cause;
			}
			if (cause instanceof Error)
			{
				throw (Error) cause;
			}
			throw new IllegalStateException("RuneLite client-thread work failed", cause);
		}
	}

	private static Method clientThreadMethod(String name)
	{
		try
		{
			Method method = ClientThread.class.getDeclaredMethod(name);
			method.setAccessible(true);
			return method;
		}
		catch (ReflectiveOperationException exception)
		{
			throw new ExceptionInInitializerError(exception);
		}
	}

	interface InputForwarder
	{
		MouseEvent mousePressed(MouseEvent event);

		MouseEvent mouseReleased(MouseEvent event);

		MouseEvent mouseClicked(MouseEvent event);

		MouseEvent mouseEntered(MouseEvent event);

		MouseEvent mouseExited(MouseEvent event);

		MouseEvent mouseDragged(MouseEvent event);

		MouseEvent mouseMoved(MouseEvent event);

		MouseWheelEvent mouseWheelMoved(MouseWheelEvent event);

		void keyPressed(KeyEvent event);

		void keyReleased(KeyEvent event);

		void keyTyped(KeyEvent event);
	}

	private static final class RuneLiteInputForwarder implements InputForwarder
	{
		private final MouseManager mouseManager;
		private final KeyManager keyManager;

		private RuneLiteInputForwarder(MouseManager mouseManager, KeyManager keyManager)
		{
			this.mouseManager = mouseManager;
			this.keyManager = keyManager;
		}

		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			return mouseManager.processMousePressed(event);
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent event)
		{
			return mouseManager.processMouseReleased(event);
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent event)
		{
			return mouseManager.processMouseClicked(event);
		}

		@Override
		public MouseEvent mouseEntered(MouseEvent event)
		{
			return mouseManager.processMouseEntered(event);
		}

		@Override
		public MouseEvent mouseExited(MouseEvent event)
		{
			return mouseManager.processMouseExited(event);
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent event)
		{
			return mouseManager.processMouseDragged(event);
		}

		@Override
		public MouseEvent mouseMoved(MouseEvent event)
		{
			return mouseManager.processMouseMoved(event);
		}

		@Override
		public MouseWheelEvent mouseWheelMoved(MouseWheelEvent event)
		{
			return mouseManager.processMouseWheelMoved(event);
		}

		@Override
		public void keyPressed(KeyEvent event)
		{
			keyManager.processKeyPressed(event);
		}

		@Override
		public void keyReleased(KeyEvent event)
		{
			keyManager.processKeyReleased(event);
		}

		@Override
		public void keyTyped(KeyEvent event)
		{
			keyManager.processKeyTyped(event);
		}
	}
}
