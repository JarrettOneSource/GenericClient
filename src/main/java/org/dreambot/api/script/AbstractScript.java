package org.dreambot.api.script;

import com.genericclient.script.ScriptEnvironment;
import com.genericclient.script.ScriptScope;
import java.awt.Graphics;
import java.awt.Graphics2D;
import org.dreambot.api.methods.MethodProvider;

public abstract class AbstractScript extends MethodProvider implements Runnable
{
	public abstract int onLoop();
	public void onStart() {}
	public void onStart(String... parameters) { onStart(); }
	public void onExit() {}
	public void onPause() {}
	public void onResume() {}
	public boolean onScheduledStop() { return true; }
	public void onPaint(Graphics graphics) {}
	public void onPaint(Graphics2D graphics) { onPaint((Graphics) graphics); }

	public final ScriptManifest getManifest()
	{
		return getClass().getAnnotation(ScriptManifest.class);
	}

	public void stop() { ScriptScope.current().stop(); }
	public boolean isPaused() { return ScriptScope.current().isPaused(); }
	public double getVersion() { return getManifest().version(); }

	@Override
	public final void run()
	{
		ScriptEnvironment environment = ScriptScope.current();
		while (environment.isRunning())
		{
			environment.checkpoint();
			int delay = onLoop();
			if (delay < 0) return;
			environment.sleep(delay);
		}
	}
}
