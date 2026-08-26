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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.Scrollable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientDashboardTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void keepsTheDashboardToThreeFocusedTabs() throws Exception
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
			GenericClientDashboard dashboard = new GenericClientDashboard(new FakeActions(), host);
			JTabbedPane tabs = find(dashboard, JTabbedPane.class);
			assertEquals(3, tabs.getTabCount());
			assertEquals("Scripts", tabs.getTitleAt(0));
			assertEquals("Console", tabs.getTitleAt(1));
			assertEquals("Settings", tabs.getTitleAt(2));
			for (int index = 0; index < tabs.getTabCount(); index++)
			{
				JScrollPane scroll = find((Container) tabs.getComponentAt(index), JScrollPane.class);
				Scrollable page = (Scrollable) scroll.getViewport().getView();
				assertTrue(page.getScrollableTracksViewportWidth());
			}

			List<String> labels = labels(dashboard);
			assertTrue(labels.contains("Micro chance %"));
			assertTrue(labels.contains("Mouse move ms"));
			assertTrue(labels.contains("Effect"));
			assertFalse(labels.contains("Game ticks"));
			assertFalse(labels.contains("Long pressure"));
			assertFalse(labels.contains("Nearby NPCs:"));
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

		spinnerInRow(settings, "Micro chance %").setValue(91);
		button(settings, "Save custom").doClick();
		assertEquals(0.91, actions.savedOverrides.getShortReleaseProbability(), 0.0);

		button(settings, "Use seeded").doClick();
		assertEquals(1, actions.resetCount);
	}

	@Test
	public void configValueTypesAreAccessibleToRuneliteProxies() throws Exception
	{
		Class<?> type = GenericClientConfig.class.getMethod("mouseEffect").getReturnType();
		assertTrue(Modifier.isPublic(type.getModifiers()));
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
	}
}
