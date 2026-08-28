package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.Map;
import org.junit.Test;

public class GenericClientAutomationConfigTest
{
	@Test
	public void validatesAndRoundTripsTheVersionOneSchema()
	{
		GenericClientAutomationConfig config = config(
			"{\"schema\":\"genericclient_automation.v1\",\"zone\":\"America/New_York\"," +
			"\"enabled\":true,\"schedules\":{\"work-hours\":{\"days\":[\"MONDAY\"]," +
			"\"windows\":[{\"from\":\"08:00\",\"until\":\"17:00\"}]}},\"rules\":[{" +
			"\"id\":\"train-strength\",\"priority\":10,\"when\":{\"all\":[" +
			"{\"schedule\":\"work-hours\"},{\"fact\":\"skills.strength.level\",\"lt\":30}]}," +
			"\"run\":{\"script\":\"aio-melee\",\"inputs\":{\"skill\":\"strength\"}}," +
			"\"retryAfter\":\"PT5M\"}]}"
		);

		assertTrue(config.isEnabled());
		assertEquals("America/New_York", config.getZone());
		assertEquals("train-strength", config.getRules().get(0).getId());
		assertEquals(300_000L, config.getRules().get(0).getRetryAfterMillis());
		assertFalse(config.withEnabled(false).isEnabled());
		assertEquals("work-hours", ((Map<?, ?>) config.toMap().get("schedules")).keySet().iterator().next());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsUnknownFacts()
	{
		config("{\"schema\":\"genericclient_automation.v1\",\"zone\":\"UTC\"," +
			"\"schedules\":{},\"rules\":[{\"id\":\"bad\",\"when\":{" +
			"\"fact\":\"skills.magic.fake\",\"lt\":10},\"run\":{\"script\":\"walker\"}}]}");
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsAmbiguousConditionNodes()
	{
		config("{\"schema\":\"genericclient_automation.v1\",\"zone\":\"UTC\"," +
			"\"schedules\":{\"always\":{\"days\":[\"MONDAY\"],\"windows\":[{" +
			"\"from\":\"00:00\",\"until\":\"23:59\"}]}},\"rules\":[{\"id\":\"bad\"," +
			"\"when\":{\"schedule\":\"always\",\"fact\":\"cash.complete\",\"eq\":true}," +
			"\"run\":{\"script\":\"walker\"}}]}");
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNumericOperatorsForBooleanFacts()
	{
		config("{\"schema\":\"genericclient_automation.v1\",\"zone\":\"UTC\"," +
			"\"schedules\":{},\"rules\":[{\"id\":\"bad\",\"when\":{" +
			"\"fact\":\"cash.complete\",\"lt\":1},\"run\":{\"script\":\"walker\"}}]}");
	}

	@SuppressWarnings("unchecked")
	private static GenericClientAutomationConfig config(String json)
	{
		return GenericClientAutomationConfig.fromMap(new Gson().fromJson(json, Map.class));
	}
}
