package com.genericclient;

import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.dreambot.api.methods.map.Area;
import org.dreambot.api.methods.map.Tile;
import org.dreambot.api.methods.skills.Skills;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientSpatialCompatibilityTest
{
	@Rule public TemporaryFolder folders = new TemporaryFolder();

	@Test public void rectangularAreasIncludeTheirEdgesOnOnlyTheirDeclaredPlane()
	{
		Area area = new Area(3203,3215,3200,3210,2);
		assertEquals(new Tile(3201,3212,2), area.getCenter());
		for (int x : List.of(3200,3203)) for (int y : List.of(3210,3215)) assertTrue(area.contains(new Tile(x,y,2)));
		for (Tile outside : List.of(new Tile(3199,3212,2), new Tile(3204,3212,2), new Tile(3201,3209,2),
			new Tile(3201,3216,2), new Tile(3201,3212,1))) assertFalse(area.contains(outside));
		assertFalse(area.contains(null));
		Tile origin = new Tile(3201,3212,2);
		assertEquals(new Tile(3204,3208,2), origin.translate(3,-4));
		assertEquals(5.0, origin.distance(origin.translate(3,-4)), 0.0);
		for (Object other : java.util.Arrays.asList(null, "tile", new Tile(3200,3212,2), new Tile(3201,3211,2), new Tile(3201,3212,1)))
			assertNotEquals(origin, other);
		assertEquals(origin.hashCode(), new Tile(3201,3212,2).hashCode());
		assertEquals(2, new java.util.HashSet<>(List.of(origin, new Tile(3201,3212,2), origin.translate(1,0))).size());
	}

	@Test public void missingPlayerAndAccountStateDoNotBecomeInventedCoordinatesOrLevels() throws Exception
	{
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(folders.newFolder().toPath()))
		{
			String query = "return List.of(Double.isInfinite(new org.dreambot.api.methods.map.Tile(3200,3200).distance())," +
				"org.dreambot.api.methods.interactive.NPCs.closest(npc->true)==null," +
				"org.dreambot.api.methods.interactive.GameObjects.closest(object->true)==null);";
			assertEquals(List.of(true,true,true), host.evaluate(query).get(5,TimeUnit.SECONDS).get("value"));
			host.publishGameTick(new GenericClientSnapshot(1,"LOGGED_IN",240,
				new GenericClientPlayerSnapshot(1L,"Player",3200,3200,0,-1),List.of()));
			host.clearSnapshot();
			assertEquals(List.of(true,true,true), host.evaluate(query).get(5,TimeUnit.SECONDS).get("value"));
			for (String expression : List.of("org.dreambot.api.methods.skills.Skills.getRealLevel(org.dreambot.api.methods.skills.Skill.MAGIC)",
				"org.dreambot.api.methods.settings.PlayerSettings.getConfig(65)"))
			{
				ExecutionException failure = assertThrows(ExecutionException.class,
					() -> host.evaluate("return " + expression + ";").get(5,TimeUnit.SECONDS));
				assertTrue(failure.getMessage(), failure.getMessage().contains("unavailable"));
			}
		}
	}

	@Test public void experienceThresholdsIncludeTheFirstAndLastSupportedLevels()
	{
		assertEquals(0, Skills.getExperienceForLevel(1));
		assertEquals(83, Skills.getExperienceForLevel(2));
		assertEquals(13_034_431, Skills.getExperienceForLevel(99));
		assertTrue(Skills.getExperienceForLevel(126) > Skills.getExperienceForLevel(125));
		assertThrows(IllegalArgumentException.class, () -> Skills.getExperienceForLevel(0));
		assertThrows(IllegalArgumentException.class, () -> Skills.getExperienceForLevel(127));
		assertEquals(13, org.dreambot.api.methods.magic.Normal.FIRE_STRIKE.getLevel());
	}
}
