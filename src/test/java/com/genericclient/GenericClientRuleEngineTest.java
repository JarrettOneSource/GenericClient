package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class GenericClientRuleEngineTest
{
	@Test
	public void selectsTheHighestPriorityTrueRule()
	{
		GenericClientAutomationConfig config = config(twoRules());
		GenericClientRuleEngine.Evaluation result = evaluate(config, account(true, 4_000_000L, 20));

		assertEquals("restore-cash", result.getSelected().getRule().getId());
		assertEquals(GenericClientRuleEngine.Truth.TRUE, result.get("restore-cash").getTruth());
		assertEquals(GenericClientRuleEngine.Truth.TRUE, result.get("train-strength").getTruth());
	}

	@Test
	public void treatsIncompleteBankCashAsUnknownRatherThanPartialWealth()
	{
		GenericClientAutomationConfig config = config(base(
			"[{\"id\":\"restore-cash\",\"priority\":100,\"when\":{" +
			"\"fact\":\"cash.known_total_value\",\"lt\":5000000}," +
			"\"run\":{\"script\":\"account-auditor\"}}]"));
		GenericClientRuleEngine.Evaluation result = evaluate(config, account(false, 1_000L, 40));

		assertEquals(GenericClientRuleEngine.Truth.UNKNOWN, result.get("restore-cash").getTruth());
		assertFalse(result.get("restore-cash").isEligible());
		assertTrue(result.get("restore-cash").getReason().contains("bank wealth is unknown"));
		assertEquals(null, result.getSelected());
	}

	@Test
	public void propagatesUnknownThroughAllAndAnyButLetsTrueAnyWin()
	{
		String json = base("[{\"id\":\"mixed\",\"priority\":1,\"when\":{\"all\":[" +
			"{\"schedule\":\"work\"},{\"any\":[{\"fact\":\"cash.known_total_value\",\"lt\":100}," +
			"{\"fact\":\"skills.strength.level\",\"lt\":30}]}]}," +
			"\"run\":{\"script\":\"aio-melee\"}}]");
		GenericClientAutomationConfig config = config(json);

		GenericClientRuleEngine.Evaluation trueResult = evaluate(config, account(false, 0L, 20));
		assertEquals(GenericClientRuleEngine.Truth.TRUE, trueResult.get("mixed").getTruth());

		GenericClientRuleEngine.Evaluation unknownResult = evaluate(config, account(false, 0L, 40));
		assertEquals(GenericClientRuleEngine.Truth.UNKNOWN, unknownResult.get("mixed").getTruth());
	}

	@Test
	public void excludesTrueRulesDuringTheirCooldown()
	{
		GenericClientAutomationConfig config = config(twoRules());
		Map<String, Long> cooldowns = Collections.singletonMap("restore-cash", 2_000L);
		GenericClientSchedule.Snapshot schedules = GenericClientSchedule.compile(config)
			.evaluate(Instant.parse("2026-08-31T12:00:00Z"));
		GenericClientRuleEngine.Evaluation result = new GenericClientRuleEngine().evaluate(
			config, schedules, account(true, 4_000_000L, 20), cooldowns, 1_000L);

		assertFalse(result.get("restore-cash").isEligible());
		assertEquals("train-strength", result.getSelected().getRule().getId());
	}

	private static GenericClientRuleEngine.Evaluation evaluate(
		GenericClientAutomationConfig config,
		Map<String, Object> account)
	{
		GenericClientSchedule.Snapshot schedules = GenericClientSchedule.compile(config)
			.evaluate(Instant.parse("2026-08-31T12:00:00Z"));
		return new GenericClientRuleEngine().evaluate(
			config, schedules, account, Collections.emptyMap(), 1_000L);
	}

	private static Map<String, Object> account(boolean bankKnown, long cash, int strength)
	{
		Map<String, Object> strengthValue = new LinkedHashMap<>();
		strengthValue.put("level", (long) strength);
		strengthValue.put("boosted_level", (long) strength);
		strengthValue.put("xp", 1_000L);
		Map<String, Object> skills = new LinkedHashMap<>();
		skills.put("available", true);
		skills.put("total_level", 100L);
		skills.put("strength", strengthValue);
		Map<String, Object> cashValue = new LinkedHashMap<>();
		cashValue.put("available", true);
		cashValue.put("bank_known", bankKnown);
		cashValue.put("known_total_value", cash);
		cashValue.put("complete", bankKnown);
		Map<String, Object> account = new LinkedHashMap<>();
		account.put("skills", skills);
		account.put("cash", cashValue);
		return account;
	}

	private static String twoRules()
	{
		return base("[{\"id\":\"train-strength\",\"priority\":50," +
			"\"when\":{\"all\":[{\"schedule\":\"work\"}," +
			"{\"fact\":\"skills.strength.level\",\"lt\":30}]}," +
			"\"run\":{\"script\":\"aio-melee\"}},{\"id\":\"restore-cash\"," +
			"\"priority\":100,\"when\":{\"all\":[" +
			"{\"schedule\":\"work\"},{\"fact\":\"cash.complete\",\"eq\":true}," +
			"{\"fact\":\"cash.known_total_value\",\"lt\":5000000}]}," +
			"\"run\":{\"script\":\"account-auditor\"}}]");
	}

	private static String base(String rules)
	{
		return "{\"schema\":\"genericclient_automation.v1\",\"zone\":\"UTC\",\"enabled\":true," +
			"\"schedules\":{\"work\":{\"days\":[\"MONDAY\"],\"windows\":[{" +
			"\"from\":\"08:00\",\"until\":\"17:00\"}]}},\"rules\":" + rules + "}";
	}

	@SuppressWarnings("unchecked")
	private static GenericClientAutomationConfig config(String json)
	{
		return GenericClientAutomationConfig.fromMap(new Gson().fromJson(json, Map.class));
	}
}
