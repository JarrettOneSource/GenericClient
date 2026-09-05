package org.dreambot.api.methods.skills;

import com.genericclient.script.SnapshotData;
import java.util.Locale;
import java.util.Map;

public final class Skills
{
	private Skills() {}
	private static Map<?, ?> skill(Skill skill)
	{
		Map<?, ?> skills = SnapshotData.read("skills");
		if (!Boolean.TRUE.equals(skills.get("available"))) throw new IllegalStateException("Skill state is unavailable");
		return SnapshotData.map(skills.get(skill.name().toLowerCase(Locale.ROOT)));
	}
	public static int getRealLevel(Skill skill) { return SnapshotData.integer(skill(skill), "level"); }
	public static int getBoostedLevel(Skill skill) { return SnapshotData.integer(skill(skill), "boosted_level"); }
	public static int getExperience(Skill skill) { return SnapshotData.integer(skill(skill), "xp"); }
	public static int getExperienceForLevel(int level)
	{
		if (level < 1 || level > 126) throw new IllegalArgumentException("Level must be between 1 and 126");
		int points = 0;
		for (int current = 1; current < level; current++) points += (int) Math.floor(current + 300 * Math.pow(2, current / 7.0));
		return points / 4;
	}
}
