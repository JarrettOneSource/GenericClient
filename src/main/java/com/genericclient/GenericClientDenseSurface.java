package com.genericclient;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import javax.inject.Singleton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.api.Constants;

@Singleton
final class GenericClientDenseSurface implements AutoCloseable
{
	private JFrame frame;

	synchronized void show(Component client) throws InvocationTargetException, InterruptedException
	{
		if (frame != null)
		{
			throw new IllegalStateException("Dense surface is already visible");
		}
		if (GraphicsEnvironment.isHeadless())
		{
			throw new IllegalStateException(
				"Dense RuneLite requires an X11 display; start it with Xvfb");
		}
		Runnable create = () ->
		{
			Dimension size = Constants.GAME_FIXED_SIZE;
			client.setMinimumSize(size);
			client.setPreferredSize(size);
			client.setSize(size);

			JPanel panel = new JPanel(new BorderLayout());
			panel.setBackground(Color.BLACK);
			panel.setMinimumSize(size);
			panel.setPreferredSize(size);
			panel.add(client, BorderLayout.CENTER);

			JFrame window = new JFrame("GenericClient Dense");
			window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
			window.setUndecorated(true);
			window.setContentPane(panel);
			window.pack();
			window.setResizable(false);
			window.setLocation(0, 0);
			window.setVisible(true);
			frame = window;
		};
		if (SwingUtilities.isEventDispatchThread())
		{
			create.run();
		}
		else
		{
			SwingUtilities.invokeAndWait(create);
		}
	}

	synchronized boolean isVisible()
	{
		return frame != null && frame.isDisplayable();
	}

	@Override
	public synchronized void close()
	{
		JFrame window = frame;
		frame = null;
		if (window == null)
		{
			return;
		}
		Runnable dispose = window::dispose;
		if (SwingUtilities.isEventDispatchThread())
		{
			dispose.run();
		}
		else
		{
			try
			{
				SwingUtilities.invokeAndWait(dispose);
			}
			catch (InterruptedException exception)
			{
				Thread.currentThread().interrupt();
			}
			catch (InvocationTargetException exception)
			{
				throw new IllegalStateException("Unable to dispose dense surface", exception.getCause());
			}
		}
	}
}
