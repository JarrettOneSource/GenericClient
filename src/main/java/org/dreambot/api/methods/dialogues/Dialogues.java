package org.dreambot.api.methods.dialogues;

import com.genericclient.script.SnapshotData;
import java.util.List;
import java.util.Map;

public final class Dialogues
{
	private Dialogues() {}
	public static boolean inDialogue() { return Boolean.TRUE.equals(SnapshotData.read("dialogue").get("open")); }
	public static boolean canContinue() { return "continue".equals(SnapshotData.read("dialogue").get("type")); }
	public static boolean continueDialogue() { return canContinue() && SnapshotData.action("dialogue.continue", Map.of()); }
	public static String[] getOptions()
	{
		return options().stream().map(option -> (String) ((Map<?, ?>) option).get("text")).toArray(String[]::new);
	}
	public static boolean chooseOption(String text)
	{
		for (Object value : options())
		{
			Map<?, ?> option = (Map<?, ?>) value;
			if (((String) option.get("text")).equalsIgnoreCase(text)) return choose(option);
		}
		return false;
	}
	public static boolean chooseOption(int option)
	{
		List<?> options = options();
		return option > 0 && option <= options.size() && choose((Map<?, ?>) options.get(option - 1));
	}
	public static String getNPCDialogue() { return (String) SnapshotData.read("dialogue").get("raw_text"); }

	private static List<?> options() { return (List<?>) SnapshotData.read("dialogue").get("options"); }
	private static boolean choose(Map<?, ?> option)
	{
		return SnapshotData.action("dialogue.choose", Map.of("index", option.get("index"), "text", option.get("text")));
	}
}
