package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientTransportWidgetTest
{
	@Rule public TemporaryFolder folders = new TemporaryFolder();

	@Test
	public void selectsTheCapturedDestinationWithinItsOwnMenuIncludingNestedChildren() throws Exception
	{
		try (GenericClientNativeInputFixture scene = new GenericClientNativeInputFixture(folders.newFolder().toPath()))
		{
			GenericClientTransport.WidgetStep step = new GenericClientTransport.WidgetStep(12255235, "Gnome Stronghold");
			GenericClientNativeInputFixture.Element foreign = new GenericClientNativeInputFixture.Element(9043984, -1, "Gnome Stronghold");
			scene.roots.put(foreign.id, foreign.widget);
			assertFalse(step.available(snapshot(scene)));
			GenericClientNativeInputFixture.Element root = new GenericClientNativeInputFixture.Element(12255235, -1, "");
			GenericClientNativeInputFixture.Element branch = new GenericClientNativeInputFixture.Element(12255236, -1, "");
			GenericClientNativeInputFixture.Element destination = new GenericClientNativeInputFixture.Element(12255236, 9,
				"<col=ffff00>2: Gnome Stronghold</col>");
			destination.bounds.setBounds(400, 200, 160, 30);
			root.children(branch);
			branch.nested = new net.runelite.api.widgets.Widget[]{destination.widget};
			destination.parent = branch;
			scene.roots.put(root.id, root.widget);
			assertTrue(step.available(snapshot(scene)));
			Map<String, Object> receipt = step.execute(scene.inputs, snapshot(scene), GenericClientActivityContext.none())
				.get(3, TimeUnit.SECONDS);
			assertEquals("dispatched", receipt.get("status"));
			Map<?, ?> target = (Map<?, ?>) receipt.get("target");
			assertEquals(12255236L, target.get("widget_id"));
			assertEquals(9L, target.get("widget_index"));
			assertEquals(1, scene.clicks.get());
			assertTrue(destination.bounds.contains(scene.pressed.get(0)));
			root.hidden = true;
			assertFalse(step.available(snapshot(scene)));
		}
	}

	@Test
	public void refusesAChangedDestinationLabelBeforeDispatch() throws Exception
	{
		try (GenericClientNativeInputFixture scene = new GenericClientNativeInputFixture(folders.newFolder().toPath()))
		{
			GenericClientNativeInputFixture.Element root = new GenericClientNativeInputFixture.Element(12255235, -1, "");
			GenericClientNativeInputFixture.Element destination = new GenericClientNativeInputFixture.Element(12255235, 7, "Gnome Stronghold");
			root.children(destination);
			scene.roots.put(root.id, root.widget);
			GenericClientTransport.WidgetStep step = new GenericClientTransport.WidgetStep(root.id, "Gnome Stronghold");
			GenericClientSnapshot captured = snapshot(scene);
			assertTrue(step.available(captured));
			scene.onTarget = () -> destination.text = "Tree Gnome Village";
			Map<String, Object> receipt = step.execute(scene.inputs, captured, GenericClientActivityContext.none())
				.get(3, TimeUnit.SECONDS);
			assertEquals("rejected", receipt.get("status"));
			assertEquals("destination_not_visible:Gnome Stronghold", receipt.get("result"));
			assertEquals(0, scene.clicks.get());
			assertFalse(step.available(snapshot(scene)));
			assertTrue("The original captured frame stays immutable", step.available(captured));
		}
	}

	@Test
	public void aGliderButtonUsesItsVerifiedWidgetAndRespectsCancellation() throws Exception
	{
		try (GenericClientNativeInputFixture scene = new GenericClientNativeInputFixture(folders.newFolder().toPath()))
		{
			GenericClientTransport.WidgetStep step = new GenericClientTransport.WidgetStep(9043984);
			assertFalse(step.available(snapshot(scene)));
			GenericClientNativeInputFixture.Element button = new GenericClientNativeInputFixture.Element(9043984, -1, "Gandius");
			scene.roots.put(button.id, button.widget);
			assertTrue(step.available(snapshot(scene)));
			Map<String, Object> receipt = step.execute(scene.inputs, snapshot(scene), GenericClientActivityContext.none())
				.get(3, TimeUnit.SECONDS);
			assertEquals("dispatched", receipt.get("status"));
			assertEquals(1, scene.clicks.get());
			GenericClientActivityContext context = GenericClientActivityContext.none().openInputScope();
			scene.onTarget = context::cancelInput;
			Map<String, Object> cancelled = step.execute(scene.inputs, snapshot(scene), context).get(3, TimeUnit.SECONDS);
			assertEquals("rejected", cancelled.get("status"));
			assertEquals(1, scene.clicks.get());
		}
	}

	private static GenericClientSnapshot snapshot(GenericClientNativeInputFixture scene)
	{
		return new GenericClientSnapshot(1, "LOGGED_IN", 240,
			new GenericClientPlayerSnapshot(1L,"transport-test", 3184, 3508, 0, 0), List.of(),
			GenericClientAccountSnapshot.empty(), GenericClientQuestSnapshot.empty(), List.of(),
			GenericClientSceneCollision.empty(), GenericClientWidgetSnapshot.capture(scene.client));
	}
}
