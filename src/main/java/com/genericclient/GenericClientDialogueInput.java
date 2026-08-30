package com.genericclient;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

final class GenericClientDialogueInput
{
	private static final int[] CONTINUE_WIDGETS =
	{
		InterfaceID.ChatLeft.CONTINUE,
		InterfaceID.ChatRight.CONTINUE,
		InterfaceID.ChatBoth.CONTINUE,
		InterfaceID.Objectbox.TEXT,
		InterfaceID.ObjectboxDouble.PAUSEBUTTON
	};

	private final Client client;
	private final GenericClientMenuInput menuInput;
	private final GenericClientBehaviorController behavior;
	private final Consumer<String> reporter;

	GenericClientDialogueInput(
		Client client,
		GenericClientMenuInput menuInput,
		GenericClientBehaviorController behavior,
		Consumer<String> reporter)
	{
		this.client = client;
		this.menuInput = menuInput;
		this.behavior = behavior;
		this.reporter = reporter;
	}

	CompletableFuture<Map<String, Object>> continueDialogue(GenericClientActivityContext activityContext)
	{
		return menuInput.interactDirect(
			this::resolveContinue,
			activityContext,
			() -> pacing(visibleContinueText()).toPreInteraction());
	}

	CompletableFuture<Map<String, Object>> choose(String text, GenericClientActivityContext activityContext)
	{
		if (text == null || text.trim().isEmpty())
		{
			throw new IllegalArgumentException("Dialogue choice text cannot be empty");
		}
		String exactText = text.trim();
		return menuInput.interactDirect(
			() -> resolveChoice(exactText),
			activityContext,
			() -> pacing(visibleChoiceText()).toPreInteraction());
	}

	private Pacing pacing(String text)
	{
		int readingPercent = behavior.dialogueReadingPercent();
		int words = countWords(text);
		long delayMillis = readingDelayMillis(readingPercent, words);
		Pacing pacing = new Pacing(
			readingPercent,
			GenericClientBehaviorProfile.dialogueReadingStyle(readingPercent),
			GenericClientBehaviorProfile.dialogueWordsPerMinute(readingPercent),
			words,
			delayMillis);
		reporter.accept("DIALOGUE_READING_DELAY style=" + pacing.style.replace(' ', '_') +
			" percent=" + readingPercent + " words=" + words + " millis=" + delayMillis);
		return pacing;
	}

	private String visibleContinueText()
	{
		for (int id : new int[]{
			InterfaceID.ChatLeft.TEXT,
			InterfaceID.ChatRight.TEXT,
			InterfaceID.ChatBoth.TEXT,
			InterfaceID.Objectbox.TEXT,
			InterfaceID.ObjectboxDouble.TEXT})
		{
			Widget widget = visibleWidget(id);
			if (widget != null)
			{
				String text = cleanText(widget.getText());
				if (!text.isEmpty())
				{
					return text;
				}
			}
		}
		return "";
	}

	private String visibleChoiceText()
	{
		StringBuilder text = new StringBuilder();
		for (Widget option : visibleOptions())
		{
			if (text.length() > 0)
			{
				text.append(' ');
			}
			text.append(cleanText(option.getText()));
		}
		return text.toString();
	}

	static long readingDelayMillis(int readingPercent, int words)
	{
		if (readingPercent <= 20 || words <= 0)
		{
			return 0L;
		}
		int wordsPerMinute = GenericClientBehaviorProfile.dialogueWordsPerMinute(readingPercent);
		double reactionMillis = 150.0 + (readingPercent - 20.0) / 80.0 * 250.0;
		long calculated = Math.round(reactionMillis + words * 60_000.0 / wordsPerMinute);
		return Math.min(9_000L, Math.max(0L, calculated));
	}

	static int countWords(String text)
	{
		String cleaned = cleanText(text).trim();
		return cleaned.isEmpty() ? 0 : cleaned.split("\\s+").length;
	}

	private GenericClientMenuInput.Resolution resolveContinue()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		if (!visibleOptions().isEmpty())
		{
			return GenericClientMenuInput.Resolution.rejected("dialogue_is_choice");
		}
		Widget widget = visibleContinueWidget(client);
		if (widget != null)
		{
			Point point = widget.getId() == InterfaceID.Objectbox.TEXT
				? objectBoxContinuePoint(
					widget.getBounds(), client.getCanvasWidth(), client.getCanvasHeight())
				: GenericClientMenuInput.randomPointInside(
					widget.getBounds(), client.getCanvasWidth(), client.getCanvasHeight());
			if (point == null)
			{
				return GenericClientMenuInput.Resolution.rejected(
					"dialogue_continue_not_clickable");
			}
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("kind", "dialogue");
			value.put("type", "continue");
			value.put("widget_id", (long) widget.getId());
			return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
				point,
				"Continue",
				"dialogue_continue:" + widget.getId(),
				value,
				entry -> entry.getType() == MenuAction.WIDGET_CONTINUE && matchesWidget(entry, widget)));
		}
		return GenericClientMenuInput.Resolution.rejected("dialogue_continue_not_visible");
	}

	static Widget visibleContinueWidget(Client client)
	{
		for (int id : CONTINUE_WIDGETS)
		{
			Widget widget = visibleWidget(client, id);
			if (widget != null)
			{
				return widget;
			}
		}
		List<Widget> widgets = visibleWidgets(client);
		for (Widget widget : widgets)
		{
			String[] actions = widget.getActions();
			if (actions == null)
			{
				continue;
			}
			for (String action : actions)
			{
				if ("Continue".equalsIgnoreCase(cleanText(action)))
				{
					return widget;
				}
			}
		}
		for (Widget widget : widgets)
		{
			if (cleanText(widget.getText()).toLowerCase(java.util.Locale.ROOT)
				.contains("click here to continue"))
			{
				return widget;
			}
		}
		return null;
	}

	private static List<Widget> visibleWidgets(Client client)
	{
		Widget[] roots = client.getWidgetRoots();
		if (roots == null)
		{
			return Collections.emptyList();
		}
		List<Widget> result = new ArrayList<>();
		Set<Widget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Widget> queue = new ArrayDeque<>();
		for (Widget root : roots)
		{
			if (root != null)
			{
				queue.addLast(root);
			}
		}
		while (!queue.isEmpty() && result.size() < 1_024)
		{
			Widget widget = queue.removeFirst();
			if (!seen.add(widget))
			{
				continue;
			}
			if (isVisible(widget) && widget.getBounds() != null &&
				widget.getBounds().width > 0 && widget.getBounds().height > 0)
			{
				result.add(widget);
			}
			enqueue(queue, widget.getChildren());
			enqueue(queue, widget.getDynamicChildren());
			enqueue(queue, widget.getStaticChildren());
			enqueue(queue, widget.getNestedChildren());
		}
		return result;
	}

	private static void enqueue(ArrayDeque<Widget> queue, Widget[] children)
	{
		if (children == null)
		{
			return;
		}
		for (Widget child : children)
		{
			if (child != null)
			{
				queue.addLast(child);
			}
		}
	}

	static Point objectBoxContinuePoint(Rectangle textBounds, int canvasWidth, int canvasHeight)
	{
		if (textBounds == null)
		{
			return null;
		}
		int x = textBounds.x + textBounds.width / 2;
		int y = textBounds.y + textBounds.height + 8;
		return x >= 0 && y >= 0 && x < canvasWidth && y < canvasHeight
			? new Point(x, y)
			: null;
	}

	private GenericClientMenuInput.Resolution resolveChoice(String exactText)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		for (Widget option : visibleOptions())
		{
			String optionText = cleanText(option.getText());
			if (!exactText.equals(optionText))
			{
				continue;
			}
			Point point = GenericClientMenuInput.randomPointInside(
				option.getBounds(), client.getCanvasWidth(), client.getCanvasHeight());
			if (point == null)
			{
				return GenericClientMenuInput.Resolution.rejected("dialogue_choice_not_clickable");
			}
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("kind", "dialogue");
			value.put("type", "choice");
			value.put("index", (long) option.getIndex());
			value.put("text", optionText);
			return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
				point,
				"Choose",
				"dialogue_choice:" + option.getIndex(),
				value,
				entry -> entry.getType() == MenuAction.WIDGET_CONTINUE && matchesWidget(entry, option)));
		}
		return GenericClientMenuInput.Resolution.rejected("exact_dialogue_choice_not_visible");
	}

	private List<Widget> visibleOptions()
	{
		Widget parent = visibleWidget(InterfaceID.Chatmenu.OPTIONS);
		if (parent == null)
		{
			return new ArrayList<>();
		}
		Widget[] children = parent.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			children = parent.getChildren();
		}
		List<Widget> result = new ArrayList<>();
		if (children != null)
		{
			for (Widget child : children)
			{
				if (child != null && child.getIndex() > 0 && !child.isHidden() &&
					!cleanText(child.getText()).isEmpty() && child.getBounds() != null &&
					child.getBounds().width > 0 && child.getBounds().height > 0)
				{
					result.add(child);
				}
			}
		}
		result.sort(Comparator.comparingInt(Widget::getIndex));
		return result;
	}

	private Widget visibleWidget(int id)
	{
		return visibleWidget(client, id);
	}

	private static Widget visibleWidget(Client client, int id)
	{
		Widget widget = client.getWidget(id);
		return isVisible(widget) &&
			widget.getBounds() != null && widget.getBounds().width > 0 && widget.getBounds().height > 0
			? widget
			: null;
	}

	private static boolean isVisible(Widget widget)
	{
		for (Widget current = widget; current != null; current = current.getParent())
		{
			if (current.isHidden() || current.isSelfHidden())
			{
				return false;
			}
		}
		return widget != null;
	}

	private static boolean matchesWidget(MenuEntry entry, Widget target)
	{
		Widget widget = entry.getWidget();
		if (widget != null)
		{
			return widget.getId() == target.getId() && widget.getIndex() == target.getIndex();
		}
		return entry.getParam1() == target.getId() &&
			(target.getIndex() < 0 || entry.getParam0() == target.getIndex());
	}

	private static String cleanText(String text)
	{
		return text == null ? "" : Text.removeTags(text).trim();
	}

	private static final class Pacing
	{
		private final int percent;
		private final String style;
		private final int wordsPerMinute;
		private final int wordCount;
		private final long delayMillis;

		private Pacing(
			int percent,
			String style,
			int wordsPerMinute,
			int wordCount,
			long delayMillis)
		{
			this.percent = percent;
			this.style = style;
			this.wordsPerMinute = wordsPerMinute;
			this.wordCount = wordCount;
			this.delayMillis = delayMillis;
		}

		private Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("dialogue_reading_percent", (long) percent);
			value.put("dialogue_reading_style", style);
			value.put("dialogue_words_per_minute", (long) wordsPerMinute);
			value.put("dialogue_word_count", (long) wordCount);
			value.put("dialogue_reading_delay_millis", delayMillis);
			return value;
		}

		private GenericClientMenuInput.PreInteraction toPreInteraction()
		{
			return GenericClientMenuInput.PreInteraction.delayed(delayMillis, toMap());
		}
	}
}
