package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Canvas;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientScriptBehaviorOwnershipTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void aCancelledPrayerCompletionCannotClaimPrayerOwnership() throws Exception
	{
		Map<String, Object> release = exercisePrayer(GenericClientActionBoundary.Ticket::cancel);
		assertEquals("no_script_owned_prayer", release.get("result"));
		assertEquals(0L, release.get("click_count"));
	}

	@Test
	public void aCurrentPrayerCompletionOwnsAndReleasesItsPrayer() throws Exception
	{
		Map<String, Object> release = exercisePrayer(ticket -> { });
		assertEquals("prayer_state_verified", release.get("result"));
		assertEquals(false, release.get("enabled"));
		assertEquals(1L, release.get("click_count"));
	}

	@Test
	public void anEmergencyPausePreservesVerifiedPrayerOwnership() throws Exception
	{
		Map<String, Object> release = exercisePrayer(ticket -> ticket.suspendInput(true));
		assertEquals("prayer_state_verified", release.get("result"));
		assertEquals(false, release.get("enabled"));
	}

	private Map<String, Object> exercisePrayer(Consumer<GenericClientActionBoundary.Ticket> onCompletion) throws Exception
	{
		GenericClientActionBoundary.Ticket ticket = new GenericClientActionBoundary.Ticket();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		Canvas canvas = new Canvas();
		canvas.setSize(800, 600);
		AtomicBoolean active = new AtomicBoolean();
		AtomicReference<Point> cursor = new AtomicReference<>(new Point(20, 20));
		canvas.addMouseListener(new MouseAdapter()
		{
			@Override public void mousePressed(MouseEvent event) { active.set(!active.get()); }
		});
		canvas.addMouseMotionListener(new MouseAdapter()
		{
			@Override public void mouseMoved(MouseEvent event) { cursor.set(event.getPoint()); }
		});
		Widget prayerWidget = prayerWidget(active);
		Menu menu = (Menu) Proxy.newProxyInstance(Menu.class.getClassLoader(), new Class<?>[]{Menu.class},
			(proxy, method, arguments) -> {
				if (method.getName().equals("getMenuEntries")) return new MenuEntry[0];
				throw new AssertionError("Unexpected menu read: " + method.getName());
			});
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, arguments) -> {
				switch (method.getName())
				{
					case "getGameState": return GameState.LOGGED_IN;
					case "getRealSkillLevel":
					case "getBoostedSkillLevel": return 77;
					case "getVarbitValue": return active.get() ? 1 : 0;
					case "getWidget": return prayerWidget;
					case "getCanvas": return canvas;
					case "getCanvasWidth": return canvas.getWidth();
					case "getCanvasHeight": return canvas.getHeight();
					case "getMouseCanvasPosition": return new net.runelite.api.Point(cursor.get().x, cursor.get().y);
					case "isMenuOpen": return false;
					case "getMenu": return menu;
					default: throw new AssertionError("Unexpected client read: " + method.getName());
				}
			});
		ClientThread clientThread = new ClientThread()
		{
			@Override public void invoke(Runnable action) { action.run(); }
			@Override public void invoke(java.util.function.BooleanSupplier action) { action.getAsBoolean(); }
		};
		GenericClientMouseProfile profile = GenericClientMouseProfile.load(
			GenericClientMouseProfile.installDefault(temporary.newFolder().toPath()));
		Frame frame = new Frame();
		SwingUtilities.invokeAndWait(() -> {
			frame.add(canvas);
			frame.setSize(800, 600);
			frame.setVisible(true);
		});
		try (GenericClientSyntheticMouse mouse = new GenericClientSyntheticMouse(canvas, executor, () -> profile,
			() -> 50, cursor.get(), new GenericClientMouseEffectOverlay(() -> GenericClientMouseEffect.OFF,
				canvas::getWidth, canvas::getHeight, System::currentTimeMillis), message -> { }, point -> { });
			GenericClientMenuInput input = new GenericClientMenuInput(client, clientThread, executor, mouse, message -> { }))
		{
			GenericClientPrayerInput prayer = new GenericClientPrayerInput(client, clientThread, executor, input, message -> {
				if (message.startsWith("PRAYER_COMPLETED")) onCompletion.accept(ticket);
			});
			GenericClientQuestActions actions = new GenericClientQuestActions(null, null, null, null, null,
				null, null, null, null, null, prayer, null, null, null, null, null, null);
			actions.execute("prayer.set", Map.of("prayer", "protect_from_melee"),
				GenericClientActivityContext.none().withTicket(ticket)).get(3, TimeUnit.SECONDS);
			assertTrue("The physical prayer action must be verified before cancellation", active.get());
			return actions.releaseScriptPrayer(GenericClientActivityContext.none()).get(3, TimeUnit.SECONDS);
		}
		finally
		{
			SwingUtilities.invokeAndWait(frame::dispose);
			executor.shutdownNow();
		}
	}

	private static Widget prayerWidget(AtomicBoolean active)
	{
		return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
			(proxy, method, arguments) -> {
				switch (method.getName())
				{
					case "getId": return InterfaceID.Prayerbook.CONTAINER;
					case "getIndex": return 0;
					case "getSpriteId": return SpriteID.Prayeron.PROTECT_FROM_MELEE;
					case "getName":
					case "getText": return "";
					case "getActions": return new String[]{active.get() ? "Deactivate" : "Activate"};
					case "getBounds": return new Rectangle(200, 100, 30, 30);
					case "isHidden":
					case "isSelfHidden": return false;
					case "getChildren":
					case "getStaticChildren":
					case "getDynamicChildren":
					case "getNestedChildren":
					case "getParent": return null;
					default: throw new AssertionError("Unexpected prayer widget read: " + method.getName());
				}
			});
	}

	@Test
	public void aCancelledConfigurationCannotDisableReplacementRunProtections() throws Exception
	{
		assertEquals("action_cancelled", configure(true).get("result"));
	}

	@Test
	public void aCurrentConfigurationAppliesAllRequestedBehaviorSettings() throws Exception
	{
		assertEquals("client_behaviors_configured", configure(false).get("result"));
	}

	private Map<String, Object> configure(boolean cancelDuringVerification) throws Exception
	{
		GenericClientActionBoundary.Ticket ticket = new GenericClientActionBoundary.Ticket();
		GenericClientEmergencyController emergency = new GenericClientEmergencyController(
			(id, action) -> { throw new AssertionError("Configuration must not consume food"); },
			escape -> { throw new AssertionError("Configuration must not escape"); },
			() -> CompletableFuture.completedFuture(Map.of()),
			reason -> CompletableFuture.completedFuture(null), message -> { });
		GenericClientCombatGuard.Runtime runtime = (GenericClientCombatGuard.Runtime) Proxy.newProxyInstance(
			getClass().getClassLoader(), new Class<?>[]{GenericClientCombatGuard.Runtime.class},
			(proxy, method, arguments) -> {
				if (method.getName().equals("cancelInput"))
				{
					assertFalse(((GenericClientActivityContext) arguments[0]).isInputAllowed());
					return null;
				}
				throw new AssertionError("Configuration dispatched guard input: " + method.getName());
			});
		GenericClientCombatGuard guard = new GenericClientCombatGuard(runtime, message -> { });
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, arguments) -> {
				if (method.getName().equals("getGameState")) return GameState.LOGGED_IN;
				if (method.getName().equals("getVarpValue"))
				{
					assertEquals(VarPlayerID.OPTION_NODEF, arguments[0]);
					// A stop and replacement can occur after the read starts but before its completion callback.
					if (cancelDuringVerification)
					{
						ticket.cancel();
						emergency.resetScriptBehavior();
						guard.resetScriptBehavior();
					}
					return 1;
				}
				throw new AssertionError("Unexpected client read: " + method.getName());
			});
		ClientThread clientThread = new ClientThread()
		{
			@Override public void invoke(Runnable action) { action.run(); }
		};
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		try (GenericClientCombatInput combat = new GenericClientCombatInput(client, clientThread, executor, null, message -> { }))
		{
			GenericClientQuestActions actions = new GenericClientQuestActions(null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, combat, emergency, guard);
			Map<String, Object> receipt = actions.execute("client.behaviors.configure", Map.of("auto_retaliate", false,
				"emergency_consumables", false, "emergency_escape", false, "combat_prayer", false),
				GenericClientActivityContext.none().withTicket(ticket)).get(2, TimeUnit.SECONDS);
			assertEquals(cancelDuringVerification, emergency.status().get("automatic_consumables_enabled"));
			assertEquals(cancelDuringVerification, emergency.status().get("automatic_escape_enabled"));
			assertEquals(cancelDuringVerification, guard.status().get("automatic_prayer_enabled"));
			return receipt;
		}
		finally { executor.shutdownNow(); }
	}
}
