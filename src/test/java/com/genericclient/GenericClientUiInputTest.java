package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientUiInputTest
{
	@Rule public TemporaryFolder folders = new TemporaryFolder();

	@Test
	public void rejectsAChildOfAHiddenMenu() throws Exception
	{
		try (GenericClientNativeInputFixture scene = new GenericClientNativeInputFixture(folders.newFolder().toPath()))
		{
			GenericClientNativeInputFixture.Element root = new GenericClientNativeInputFixture.Element(12255235, -1, "");
			root.children(new GenericClientNativeInputFixture.Element(12255235, 7, "Gnome Stronghold"));
			root.hidden = true;
			scene.roots.put(root.id, root.widget);
			Map<String, Object> receipt = scene.inputs.uiInput.click(root.id, 7, null, GenericClientActivityContext.none())
				.get(3, TimeUnit.SECONDS);
			assertEquals("rejected", receipt.get("status"));
			assertEquals(0, scene.clicks.get());
		}
	}

	@Test
	public void rechecksWidgetVisibilityAfterMovingTheMouse() throws Exception
	{
		try (GenericClientNativeInputFixture scene = new GenericClientNativeInputFixture(folders.newFolder().toPath()))
		{
			GenericClientNativeInputFixture.Element root = new GenericClientNativeInputFixture.Element(9043984, -1, "Gandius");
			scene.roots.put(root.id, root.widget);
			scene.onTarget = () -> root.hidden = true;
			Map<String, Object> receipt = scene.inputs.uiInput.click(root.id, null, null, GenericClientActivityContext.none())
				.get(3, TimeUnit.SECONDS);
			assertEquals("rejected", receipt.get("status"));
			assertEquals(0, scene.clicks.get());
		}
	}
}
