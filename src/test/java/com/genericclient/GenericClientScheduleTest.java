package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.Map;
import org.junit.Test;

public class GenericClientScheduleTest
{
	@Test
	public void evaluatesWeekdayWindowWithInclusiveStartAndExclusiveEnd()
	{
		GenericClientSchedule schedule = schedule(
			"America/New_York",
			"[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\"]",
			"08:00", "17:00");

		GenericClientSchedule.State before = schedule.evaluate(Instant.parse("2026-08-31T11:59:00Z")).get("window");
		assertFalse(before.isActive());
		assertEquals(Instant.parse("2026-08-31T12:00:00Z"), before.getNextTransition());

		GenericClientSchedule.State start = schedule.evaluate(Instant.parse("2026-08-31T12:00:00Z")).get("window");
		assertTrue(start.isActive());
		assertEquals(Instant.parse("2026-08-31T21:00:00Z"), start.getNextTransition());

		GenericClientSchedule.State end = schedule.evaluate(Instant.parse("2026-08-31T21:00:00Z")).get("window");
		assertFalse(end.isActive());
	}

	@Test
	public void carriesAnOvernightWindowIntoTheFollowingDay()
	{
		GenericClientSchedule schedule = schedule("UTC", "[\"FRIDAY\"]", "22:00", "02:00");

		GenericClientSchedule.State saturday =
			schedule.evaluate(Instant.parse("2026-09-05T01:00:00Z")).get("window");
		assertTrue(saturday.isActive());
		assertEquals(Instant.parse("2026-09-05T02:00:00Z"), saturday.getNextTransition());
	}

	@Test
	public void usesZoneRulesAcrossTheSpringDstTransition()
	{
		GenericClientSchedule schedule = schedule(
			"America/New_York", "[\"SUNDAY\"]", "01:00", "04:00");

		GenericClientSchedule.State state =
			schedule.evaluate(Instant.parse("2026-03-08T06:30:00Z")).get("window");
		assertTrue(state.isActive());
		assertEquals(Instant.parse("2026-03-08T08:00:00Z"), state.getNextTransition());
	}

	@Test
	public void advancesFromFridayAfterCloseToMondayOpen()
	{
		GenericClientSchedule schedule = schedule(
			"UTC",
			"[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\"]",
			"08:00", "17:00");

		GenericClientSchedule.State state =
			schedule.evaluate(Instant.parse("2026-09-04T18:00:00Z")).get("window");
		assertFalse(state.isActive());
		assertEquals(Instant.parse("2026-09-07T08:00:00Z"), state.getNextTransition());
	}

	@SuppressWarnings("unchecked")
	private static GenericClientSchedule schedule(String zone, String days, String from, String until)
	{
		String json = "{\"schema\":\"genericclient_automation.v1\",\"zone\":\"" + zone +
			"\",\"schedules\":{\"window\":{\"days\":" + days + ",\"windows\":[{" +
			"\"from\":\"" + from + "\",\"until\":\"" + until + "\"}]}},\"rules\":[]}";
		GenericClientAutomationConfig config = GenericClientAutomationConfig.fromMap(
			new Gson().fromJson(json, Map.class));
		return GenericClientSchedule.compile(config);
	}
}
