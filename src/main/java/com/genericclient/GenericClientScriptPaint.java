package com.genericclient;

import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

final class GenericClientScriptPaint extends Overlay
{
	private final GenericClientScriptHost host;
	GenericClientScriptPaint(GenericClientScriptHost host)
	{
		this.host = host;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}
	@Override public Dimension render(Graphics2D graphics)
	{
		Graphics2D copy = (Graphics2D) graphics.create();
		try { host.paint(copy); }
		finally { copy.dispose(); }
		return null;
	}
}
