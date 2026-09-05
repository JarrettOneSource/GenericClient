package com.genericclient;

import java.awt.Canvas;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

/** Actual mouse/menu dispatch against controlled native scene objects and widgets. */
final class GenericClientNativeInputFixture implements AutoCloseable
{
	final GenericClientEntityIds identities = new GenericClientEntityIds();
	final Map<Integer, Widget> roots = new LinkedHashMap<>();
	final AtomicInteger clicks = new AtomicInteger();
	final List<Point> pressed = new ArrayList<>();
	final List<Integer> continuedWidgetIndices = new ArrayList<>();
	final Client client;
	final GenericClientNativeInputs inputs;
	final GenericClientBehaviorController behavior;
	final Map<Integer, ObjectComposition> objects = new LinkedHashMap<>();
	final Map<Integer, ItemComposition> items = new LinkedHashMap<>();
	Widget selectedWidget;
	Player player;
	volatile Runnable onTarget = () -> { };
	private volatile Consumer<MouseEvent> onPress = event -> { };
	private volatile Supplier<MenuEntry[]> menuEntries = () -> new MenuEntry[0];
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private final Canvas canvas = new Canvas();
	private final Frame frame = new Frame();
	private final AtomicReference<Point> cursor = new AtomicReference<>(new Point(20, 20));
	private final GenericClientSyntheticMouse mouse;
	private final GenericClientSyntheticKeyboard keyboard;

	GenericClientNativeInputFixture(Path directory) throws Exception
	{
		canvas.setSize(800, 600);
		canvas.addMouseListener(new MouseAdapter()
		{
			@Override public void mousePressed(MouseEvent event)
			{
				pressed.add(event.getPoint());
				clicks.incrementAndGet();
				onPress.accept(event);
			}
		});
		canvas.addMouseMotionListener(new MouseAdapter()
		{
			@Override public void mouseMoved(MouseEvent event) { cursor.set(event.getPoint()); }
		});
		Menu menu = (Menu) Proxy.newProxyInstance(Menu.class.getClassLoader(), new Class<?>[]{Menu.class},
			(proxy, method, arguments) -> {
				if (method.getName().equals("getMenuEntries")) return menuEntries.get();
				throw new AssertionError("Unexpected menu read: " + method.getName());
			});
		client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, arguments) -> {
				switch (method.getName())
				{
					case "getGameState": return GameState.LOGGED_IN;
					case "menuAction":
						continuedWidgetIndices.add((Integer) arguments[0]);
						return null;
					case "getLocalPlayer": return player;
					case "getObjectDefinition": return objects.get((Integer) arguments[0]);
					case "getItemDefinition": return items.get((Integer) arguments[0]);
					case "getSelectedWidget": return selectedWidget;
					case "isWidgetSelected": return selectedWidget != null;
					case "getWidget": return roots.get(arguments.length == 1 ? (Integer) arguments[0]
						: (Integer) arguments[0] << 16 | (Integer) arguments[1]);
					case "getWidgetRoots": return roots.values().toArray(new Widget[0]);
					case "getComponentTable": return null;
					case "getCanvas": return canvas;
					case "getCanvasWidth": return canvas.getWidth();
					case "getCanvasHeight": return canvas.getHeight();
					case "getViewportWidth": return canvas.getWidth();
					case "getViewportHeight": return canvas.getHeight();
					case "getViewportXOffset":
					case "getViewportYOffset": return 0;
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
		GenericClientMouseProfile profile = GenericClientMouseProfile.load(GenericClientMouseProfile.installDefault(directory));
		behavior = GenericClientTestSupport.behavior(directory, edge -> { });
		SwingUtilities.invokeAndWait(() -> {
			frame.add(canvas);
			frame.setSize(800, 600);
			frame.setVisible(true);
		});
		mouse = new GenericClientSyntheticMouse(canvas, executor, () -> profile, () -> 50, cursor.get(),
			new GenericClientMouseEffectOverlay(() -> GenericClientMouseEffect.OFF, canvas::getWidth,
				canvas::getHeight, System::currentTimeMillis), message -> { }, point -> { });
		keyboard = new GenericClientSyntheticKeyboard(canvas, executor, message -> { });
		inputs = new GenericClientNativeInputs(client, clientThread, executor, mouse, keyboard,
			behavior, () -> null, message -> {
				if (message.startsWith("MENU_INTERACTION_TARGET")) onTarget.run();
			}, identities);
	}

	void offerMenu(Rectangle bounds, MenuEntry entry)
	{
		menuEntries = () -> bounds.contains(cursor.get()) ? new MenuEntry[]{entry} : new MenuEntry[0];
		onPress = event -> {
			if (bounds.contains(event.getPoint())) inputs.onMenuOptionClicked(new MenuOptionClicked(entry));
		};
	}

	@Override public void close() throws Exception
	{
		inputs.close();
		mouse.close();
		keyboard.close();
		executor.shutdownNow();
		SwingUtilities.invokeAndWait(frame::dispose);
	}

	static final class Element
	{
		private static final Map<String, Object> METADATA = Map.of(
			"getName", "", "getActions", new String[0],
			"getStaticChildren", new Widget[0], "getModelId", -1,
			"getSpriteId", -1, "getType", 4);
		final int id;
		final int index;
		final Widget widget;
		final Rectangle bounds = new Rectangle(200, 100, 150, 30);
		volatile String text;
		volatile boolean hidden;
		volatile boolean selfHidden;
		int itemId = -1;
		int itemQuantity;
		Element parent;
		Widget[] children = new Widget[0];
		Widget[] nested = new Widget[0];

		Element(int id, int index, String text)
		{
			this.id = id;
			this.index = index;
			this.text = text;
			widget = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
				(proxy, method, arguments) -> read(method.getName()));
		}

		void children(Element... elements)
		{
			children = new Widget[elements.length];
			for (int index = 0; index < elements.length; index++)
			{
				elements[index].parent = this;
				children[index] = elements[index].widget;
			}
		}

		private Object read(String method)
		{
			switch (method)
			{
				case "getId": return id;
				case "getIndex": return index;
				case "getText": return text;
				case "getItemId": return itemId;
				case "getItemQuantity": return itemQuantity;
				case "getBounds": return bounds;
				case "isHidden": return hidden;
				case "isSelfHidden": return selfHidden;
				case "getParent": return parent == null ? null : parent.widget;
				case "getParentId": return parent == null ? -1 : parent.id;
				case "getChildren":
				case "getDynamicChildren": return children;
				case "getNestedChildren": return nested;
				default:
					if (METADATA.containsKey(method)) return METADATA.get(method);
					throw new AssertionError("Unexpected widget read: " + method);
			}
		}
	}
}
