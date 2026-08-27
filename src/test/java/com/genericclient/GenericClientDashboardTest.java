package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientDashboardTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void keepsThePopoutToThreeFocusedDestinations() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("behavior").toPath()),
			message -> { });
		try
		{
			GenericClientDashboard dashboard = new GenericClientDashboard(null, new FakeActions(), host);
			JPanel content = dashboard.getContent();
			assertTrue(findOrNull(content, JTabbedPane.class) == null);
			assertTrue(buttonOrNull(content, "Automations") != null);
			assertTrue(buttonOrNull(content, "Console") != null);
			assertTrue(buttonOrNull(content, "Settings") != null);

			List<String> labels = labels(content);
			assertTrue(labels.contains("Chance"));
			assertTrue(labels.contains("Move duration"));
			assertTrue(labels.contains("Effect"));
			assertFalse(labels.contains("Game ticks"));
			assertFalse(labels.contains("Long pressure"));
			assertFalse(labels.contains("Nearby NPCs:"));
			dashboard.close();
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void settingsSaveAndResetTheVisibleBehaviorValues()
	{
		FakeActions actions = new FakeActions();
		GenericClientSettingsPanel settings = new GenericClientSettingsPanel(actions);
		GenericClientBehaviorProfile profile = GenericClientBehaviorProfile.fromAccountHash(1234L);
		Map<String, Object> behavior = new LinkedHashMap<>();
		behavior.put("available", true);
		behavior.put("profile", profile.toMap());
		behavior.put("state", "ready");
		behavior.put("break_remaining_millis", 0L);
		settings.updateBehavior(behavior);

		spinnerInRow(settings, "Chance").setValue(91);
		button(settings, "Save custom").doClick();
		assertEquals(0.91, actions.savedOverrides.getShortReleaseProbability(), 0.0);

		button(settings, "Use seeded").doClick();
		assertEquals(1, actions.resetCount);
	}

	@Test
	public void longBreakBannerCanEndTheBreak() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("break-banner-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("break-banner-behavior").toPath()),
			message -> { });
		try
		{
			FakeActions actions = new FakeActions();
			GenericClientDashboard dashboard = new GenericClientDashboard(null, actions, host);
			Map<String, Object> behavior = new LinkedHashMap<>();
			behavior.put("available", true);
			behavior.put("profile", GenericClientBehaviorProfile.fromAccountHash(1234L).toMap());
			behavior.put("state", "long_break");
			behavior.put("long_break_mode", "afk");
			behavior.put("break_remaining_millis", 305_000L);

			dashboard.updateBehaviorState(behavior);
			SwingUtilities.invokeAndWait(() -> { });
			JButton end = button(dashboard.getContent(), "×");
			assertTrue(end.getParent().isVisible());
			assertTrue(labels(dashboard.getContent()).contains("Break"));

			SwingUtilities.invokeAndWait(end::doClick);
			assertEquals(1, actions.endedLongBreaks);
			assertFalse(end.getParent().isVisible());
			dashboard.close();
		}
		finally
		{
			host.close();
		}
	}

	@Test
	public void configValueTypesAreAccessibleToRuneliteProxies() throws Exception
	{
		Class<?> type = GenericClientConfig.class.getMethod("mouseEffect").getReturnType();
		assertTrue(Modifier.isPublic(type.getModifiers()));
	}

	@Test
	public void rendersWalkerDestinationsFromTheLuaDescriptor() throws Exception
	{
		GenericClientLuaHost host = new GenericClientLuaHost(
			temporaryFolder.newFolder("walker-ui-scripts").toPath(),
			breaks -> CompletableFuture.completedFuture(GenericClientTestSupport.interaction("unused")),
			(destination, within, timeout, breaks) -> CompletableFuture.completedFuture(Collections.emptyMap()),
			reason -> { },
			GenericClientTestSupport.behavior(temporaryFolder.newFolder("walker-ui-behavior").toPath()),
			message -> { });
		try
		{
			GenericClientScriptsPanel panel = new GenericClientScriptsPanel(host);
			JComboBox<?> selector = scriptSelector(panel);
			SwingUtilities.invokeAndWait(() -> selectScript(selector, "walker"));
			waitForLabel(panel, "Destination");

			JComboBox<?> destinations = comboInRow(panel, "Destination");
			assertEquals(6, destinations.getItemCount());
			assertEquals("Grand Exchange", destinations.getSelectedItem().toString());
			assertEquals("Varrock Center", destinations.getItemAt(1).toString());
		}
		finally
		{
			host.close();
		}
	}

	private static List<String> labels(Container root)
	{
		List<String> values = new ArrayList<>();
		for (Component component : root.getComponents())
		{
			if (component instanceof JLabel)
			{
				values.add(((JLabel) component).getText());
			}
			if (component instanceof Container)
			{
				values.addAll(labels((Container) component));
			}
		}
		return values;
	}

	private static <T> T find(Container root, Class<T> type)
	{
		for (Component component : root.getComponents())
		{
			if (type.isInstance(component))
			{
				return type.cast(component);
			}
			if (component instanceof Container)
			{
				T nested = findOrNull((Container) component, type);
				if (nested != null)
				{
					return nested;
				}
			}
		}
		throw new AssertionError("Missing component " + type.getSimpleName());
	}

	private static <T> T findOrNull(Container root, Class<T> type)
	{
		for (Component component : root.getComponents())
		{
			if (type.isInstance(component))
			{
				return type.cast(component);
			}
			if (component instanceof Container)
			{
				T nested = findOrNull((Container) component, type);
				if (nested != null)
				{
					return nested;
				}
			}
		}
		return null;
	}

	private static JSpinner spinnerInRow(Container root, String label)
	{
		JSpinner spinner = spinnerInRowOrNull(root, label);
		if (spinner != null)
		{
			return spinner;
		}
		throw new AssertionError("Missing spinner row " + label);
	}

	private static JComboBox<?> comboInRow(Container root, String label)
	{
		JComboBox<?> combo = comboInRowOrNull(root, label);
		if (combo != null)
		{
			return combo;
		}
		throw new AssertionError("Missing combo row " + label);
	}

	private static JComboBox<?> comboInRowOrNull(Container root, String label)
	{
		for (Component component : root.getComponents())
		{
			if (component instanceof JPanel && hasDirectLabel((JPanel) component, label))
			{
				JComboBox<?> combo = findOrNull((JPanel) component, JComboBox.class);
				if (combo != null)
				{
					return combo;
				}
			}
			if (component instanceof Container)
			{
				JComboBox<?> nested = comboInRowOrNull((Container) component, label);
				if (nested != null)
				{
					return nested;
				}
			}
		}
		return null;
	}

	private static JComboBox<?> scriptSelector(Container root)
	{
		JComboBox<?> selector = scriptSelectorOrNull(root);
		if (selector != null)
		{
			return selector;
		}
		throw new AssertionError("Missing script selector");
	}

	private static JComboBox<?> scriptSelectorOrNull(Container root)
	{
		for (Component component : root.getComponents())
		{
			if (component instanceof JComboBox)
			{
				JComboBox<?> combo = (JComboBox<?>) component;
				if (combo.getItemCount() > 0 &&
					combo.getItemAt(0) instanceof GenericClientScriptRegistry.Script)
				{
					return combo;
				}
			}
			if (component instanceof Container)
			{
				JComboBox<?> nested = scriptSelectorOrNull((Container) component);
				if (nested != null)
				{
					return nested;
				}
			}
		}
		return null;
	}

	private static void selectScript(JComboBox<?> selector, String id)
	{
		for (int index = 0; index < selector.getItemCount(); index++)
		{
			GenericClientScriptRegistry.Script script =
				(GenericClientScriptRegistry.Script) selector.getItemAt(index);
			if (id.equals(script.getId()))
			{
				selector.setSelectedIndex(index);
				return;
			}
		}
		throw new AssertionError("Missing script " + id);
	}

	private static void waitForLabel(Container root, String label) throws Exception
	{
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
		while (System.nanoTime() < deadline)
		{
			final boolean[] found = new boolean[1];
			SwingUtilities.invokeAndWait(() -> found[0] = labels(root).contains(label));
			if (found[0])
			{
				return;
			}
			Thread.sleep(10);
		}
		throw new AssertionError("Missing label " + label);
	}

	private static JSpinner spinnerInRowOrNull(Container root, String label)
	{
		for (Component component : root.getComponents())
		{
			if (component instanceof JPanel && hasDirectLabel((JPanel) component, label))
			{
				JSpinner spinner = findOrNull((JPanel) component, JSpinner.class);
				if (spinner != null)
				{
					return spinner;
				}
			}
			if (component instanceof Container)
			{
				JSpinner nested = spinnerInRowOrNull((Container) component, label);
				if (nested != null)
				{
					return nested;
				}
			}
		}
		return null;
	}

	private static boolean hasDirectLabel(Container root, String text)
	{
		for (Component component : root.getComponents())
		{
			if (component instanceof JLabel && text.equals(((JLabel) component).getText()))
			{
				return true;
			}
		}
		return false;
	}

	private static JButton button(Container root, String text)
	{
		for (Component component : root.getComponents())
		{
			if (component instanceof JButton && text.equals(((JButton) component).getText()))
			{
				return (JButton) component;
			}
			if (component instanceof Container)
			{
				JButton nested = buttonOrNull((Container) component, text);
				if (nested != null)
				{
					return nested;
				}
			}
		}
		throw new AssertionError("Missing button " + text);
	}

	private static JButton buttonOrNull(Container root, String text)
	{
		for (Component component : root.getComponents())
		{
			if (component instanceof JButton && text.equals(((JButton) component).getText()))
			{
				return (JButton) component;
			}
			if (component instanceof Container)
			{
				JButton nested = buttonOrNull((Container) component, text);
				if (nested != null)
				{
					return nested;
				}
			}
		}
		return null;
	}

	private static final class FakeActions implements GenericClientDashboardActions
	{
		private GenericClientBehaviorOverrides savedOverrides;
		private int resetCount;
		private int endedLongBreaks;

		@Override public void printDiagnostics() { }
		@Override public void logNearbyNpcs() { }
		@Override public void walkToRandomTile() { }
		@Override public void setMouseProfile(String file) { }
		@Override public void setMouseEffect(GenericClientMouseEffect effect) { }
		@Override public void reloadMouseProfile() { }
		@Override public void startMouseRecording() { }
		@Override public void stopMouseRecording() { }
		@Override public String saveBehaviorOverrides(GenericClientBehaviorOverrides overrides)
		{
			savedOverrides = overrides;
			return "Saved";
		}
		@Override public String resetBehaviorOverrides()
		{
			resetCount++;
			return "Seeded";
		}
		@Override public CompletableFuture<String> endLongBreak()
		{
			endedLongBreaks++;
			return CompletableFuture.completedFuture("Long break ended");
		}
	}
}
