package com.genericclient;

import static org.junit.Assert.*;

import java.awt.Canvas;
import java.awt.Frame;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.client.callback.ClientThread;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientActorInputTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void npcInputCannotSelectAPlayerMenuEntryWithTheSameIndex() throws Exception
    {
        try (Input input = new Input())
        {
            input.scene.addNpc();
            input.scene.add(7,"Other player",3202,3200,0,88,-1,false);
            input.entryIndex = 7;
            input.entryAction = "Attack";
            long identity = input.scene.identities.identify(input.scene.npcs.get(7));
            Map<String,Object> wrongActor = input.npcs.interact(123,7,identity,null,"Attack",32,GenericClientActivityContext.none())
                .get(5,TimeUnit.SECONDS);
            assertEquals("rejected",wrongActor.get("status"));
            assertEquals(0,input.clicks.get());
            input.entryType = MenuAction.NPC_FIRST_OPTION;
            Map<String,Object> correctActor = input.npcs.interact(123,7,identity,null,"Attack",32,GenericClientActivityContext.none())
                .get(5,TimeUnit.SECONDS);
            assertEquals("dispatched",correctActor.get("status"));
            assertEquals(1,input.clicks.get());
        }
    }

    @Test public void compiledPlayerInteractionDispatchesTheObservedMenuAction() throws Exception
    {
        try (Input input = new Input(); GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"player-input")
            .questAction((type,arguments,context) -> {
                assertEquals("player.interact",type);
                return input.players.interact(arguments,context);
            }).build())
        {
            host.publishGameTick(input.scene.snapshot(1));
            Map<String,Object> result = host.evaluate(
                "org.dreambot.api.wrappers.interactive.Entity friend=org.dreambot.api.methods.interactive.Players.closest(\"Friend\");" +
                "return friend.interact(\"Follow\");").get(5,TimeUnit.SECONDS);
            assertEquals(true,result.get("value"));
            assertEquals(1,input.clicks.get());
        }
    }

    @Test public void aReusedPlayerIndexCannotTakeOverPendingInput() throws Exception
    {
        try (Input input = new Input())
        {
            Map<String,Object> original = input.request("Follow");
            AtomicBoolean replace = new AtomicBoolean(true);
            input.onMove = () -> {
                if (!replace.getAndSet(false)) return;
                input.scene.identities.onPlayerDespawned(new PlayerDespawned(input.scene.players.get(28)));
                input.scene.add(28,"Replacement",3201,3200,0,88,-1,false);
            };
            Map<String,Object> stale = input.players.interact(original,GenericClientActivityContext.none())
                .get(5,TimeUnit.SECONDS);
            assertEquals("rejected",stale.get("status"));
            assertEquals(0,input.clicks.get());
            Map<String,Object> fresh = input.players.interact(input.request("Follow"),GenericClientActivityContext.none())
                .get(5,TimeUnit.SECONDS);
            assertEquals("dispatched",fresh.get("status"));
            assertEquals(1,input.clicks.get());
        }
    }

    @Test public void unavailablePlayerActionsAndInvisibleTargetsDoNotClick() throws Exception
    {
        try (Input input = new Input())
        {
            Map<String,Object> unavailable = input.players.interact(input.request("Attack"),GenericClientActivityContext.none())
                .get(5,TimeUnit.SECONDS);
            assertEquals("player_action_unavailable",unavailable.get("result"));
            input.scene.shapes.remove(28);
            Map<String,Object> invisible = input.players.interact(input.request("Follow"),GenericClientActivityContext.none())
                .get(5,TimeUnit.SECONDS);
            assertEquals("player_not_visible",invisible.get("result"));
            assertEquals(0,input.clicks.get());
        }
    }

    private final class Input implements AutoCloseable
    {
        final GenericClientPlayerCompatibilityTest.Scene scene = new GenericClientPlayerCompatibilityTest.Scene();
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        final Frame frame = new Frame();
        final Canvas canvas = new Canvas();
        final AtomicReference<Point> cursor = new AtomicReference<>(new Point(20,20));
        final AtomicInteger clicks = new AtomicInteger();
        final GenericClientSyntheticMouse mouse;
        final GenericClientMenuInput menu;
        final GenericClientPlayerInput players;
        final GenericClientNpcInput npcs;
        final AtomicInteger cameraYaw = new AtomicInteger();
        final AtomicInteger cameraPitch = new AtomicInteger();
        int entryIndex = 28;
        String entryAction = "Follow";
        MenuAction entryType = MenuAction.PLAYER_FIRST_OPTION;
        Runnable onMove = () -> {};

        Input() throws Exception
        {
            scene.add(17,"Local",3200,3200,0,42,-1,false);
            scene.add(28,"Friend",3201,3200,0,88,-1,false);
            canvas.setSize(800,600);
            MenuEntry entry = entry();
            Menu currentMenu = (Menu) Proxy.newProxyInstance(Menu.class.getClassLoader(),new Class<?>[]{Menu.class},
                (proxy,method,args) -> {
                    if (!method.getName().equals("getMenuEntries")) throw new AssertionError(method.getName());
                    java.awt.Shape shape = scene.shapes.get(entryIndex);
                    return shape != null && shape.contains(cursor.get()) ? new MenuEntry[]{entry} : new MenuEntry[0];
                });
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),new Class<?>[]{Client.class},
                (proxy,method,args) -> {
                    switch (method.getName())
                    {
                        case "getGameState": return GameState.LOGGED_IN;
                        case "getCanvas": return canvas;
                        case "getMouseCanvasPosition": return new net.runelite.api.Point(cursor.get().x,cursor.get().y);
                        case "isMenuOpen": case "isWidgetSelected": return false;
                        case "getMenu": return currentMenu;
                        case "getCameraYaw": return cameraYaw.get();
                        case "getCameraPitch": return cameraPitch.get();
                        case "setCameraYawTarget": cameraYaw.set((Integer)args[0]); return null;
                        case "setCameraPitchTarget": cameraPitch.set((Integer)args[0]); return null;
                        default: return method.invoke(scene.client,args);
                    }
                });
            ClientThread clientThread = new ClientThread()
            {
                @Override public void invoke(Runnable action) { action.run(); }
                @Override public void invoke(java.util.function.BooleanSupplier action) { action.getAsBoolean(); }
            };
            GenericClientMouseProfile profile = GenericClientMouseProfile.load(GenericClientMouseProfile.installDefault(temporary.newFolder().toPath()));
            mouse = new GenericClientSyntheticMouse(canvas,executor,() -> profile,() -> 50,cursor.get(),
                new GenericClientMouseEffectOverlay(() -> GenericClientMouseEffect.OFF,canvas::getWidth,canvas::getHeight,System::currentTimeMillis),
                message -> {},point -> {});
            menu = new GenericClientMenuInput(client,clientThread,executor,mouse,message -> {});
            players = new GenericClientPlayerInput(client,menu,scene.identities);
            npcs = new GenericClientNpcInput(client,clientThread,executor,menu,new GenericClientCameraOwner(client),message -> {},scene.identities);
            canvas.addMouseMotionListener(new MouseAdapter()
            {
                @Override public void mouseMoved(MouseEvent event) { cursor.set(event.getPoint()); onMove.run(); }
            });
            canvas.addMouseListener(new MouseAdapter()
            {
                @Override public void mousePressed(MouseEvent event)
                {
                    clicks.incrementAndGet();
                    menu.onMenuOptionClicked(new MenuOptionClicked(entry));
                }
            });
            SwingUtilities.invokeAndWait(() -> {
                frame.add(canvas);
                frame.setSize(800,600);
                frame.setVisible(true);
            });
        }

        private MenuEntry entry()
        {
            return (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),new Class<?>[]{MenuEntry.class},
                (proxy,method,args) -> {
                    switch (method.getName())
                    {
                        case "getType": return entryType;
                        case "getWorldViewId": return -1;
                        case "getOption": return entryAction;
                        case "getTarget": return entryType == MenuAction.NPC_FIRST_OPTION ? scene.npcs.get(entryIndex).getName() : scene.players.get(entryIndex).getName();
                        case "getPlayer": return entryType == MenuAction.PLAYER_FIRST_OPTION ? scene.players.get(entryIndex) : null;
                        case "getNpc": return entryType == MenuAction.NPC_FIRST_OPTION ? scene.npcs.get(entryIndex) : null;
                        case "getIdentifier": return entryIndex;
                        case "getParam0": case "getParam1": return 0;
                        case "getItemId": return -1;
                        case "getWidget": return null;
                        default: throw new AssertionError("Unexpected menu entry read: " + method.getName());
                    }
                });
        }

        Map<String,Object> request(String action)
        {
            return Map.of("index",28,"world_view",-1,"identity",scene.identities.identify(scene.players.get(28)),"action",action,"within",32);
        }

        @Override public void close() throws Exception
        {
            menu.close();
            mouse.close();
            executor.shutdownNow();
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }
}
