package com.genericclient;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.ui.overlay.OverlayPanel;

final class GenericClientOverlay extends OverlayPanel
{
	private final GenericClientPlugin plugin;
	private final GenericClientConfig config;

	@Inject
	private GenericClientOverlay(GenericClientPlugin plugin, GenericClientConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showOverlay())
		{
			return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("GenericClient")
			.color(new Color(110, 235, 165))
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Game state")
			.right(plugin.getGameStateName())
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Game ticks")
			.right(Long.toString(plugin.getTickCount()))
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Nearby NPCs")
			.right(Integer.toString(plugin.getNearbyNpcCount()))
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Lua")
			.right(shorten(plugin.getLuaScript() + " " + plugin.getLuaStatus(), 42))
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Last result")
			.right(shorten(plugin.getLastStatus(), 42))
			.build());

		return super.render(graphics);
	}

	private static String shorten(String value, int maxLength)
	{
		if (value == null || value.length() <= maxLength)
		{
			return value;
		}
		return value.substring(0, maxLength - 3) + "...";
	}
}
