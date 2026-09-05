package com.genericclient;

import static com.genericclient.GenericClientWidgets.matchesWidget;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
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
	private final ClientThread clientThread;
	private final GenericClientMenuInput menuInput;
	private final GenericClientSyntheticKeyboard keyboard;
	private final GenericClientBehaviorController behavior;
	private final Supplier<Point> cursorPosition;
	private final Consumer<String> reporter;

	GenericClientDialogueInput(
		Client client,
		ClientThread clientThread,
		GenericClientMenuInput menuInput,
		GenericClientSyntheticKeyboard keyboard,
		GenericClientBehaviorController behavior,
		Supplier<Point> cursorPosition,
		Consumer<String> reporter)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.menuInput = menuInput;
		this.keyboard = keyboard;
		this.behavior = behavior;
		this.cursorPosition = cursorPosition;
		this.reporter = reporter;
	}

	CompletableFuture<Map<String, Object>> continueDialogue(
		GenericClientActivityContext activityContext,
		boolean reading)
	{
		if (behavior.dialogueInputMode() == GenericClientBehaviorProfile.DialogueInputMode.KEYBOARD)
		{
			return keyboardContinue(activityContext, reading);
		}
		if (!reading)
		{
			return menuInput.interactDirect(this::resolveContinue, activityContext);
		}
		return menuInput.interactDirect(
			this::resolveContinue,
			activityContext,
			() -> pacing(visibleContinueText()).toPreInteraction());
	}

	CompletableFuture<Map<String, Object>> respond(GenericClientQuestSnapshot.DialogueSnapshot expected,
		String choice, GenericClientActivityContext context)
	{
		return menuInput.interactDirect(() -> {
			if (client.getGameState() != GameState.LOGGED_IN)
				return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
			if (!expected.samePage(GenericClientQuestSnapshot.captureDialogue(client)))
				return GenericClientMenuInput.Resolution.rejected("dialogue_changed");
			return choice == null ? resolveContinue() : resolveChoice(choice);
		}, context, () -> pacing(choice == null ? expected.text : choice).toPreInteraction());
	}

	CompletableFuture<Map<String, Object>> choose(
		String text,
		GenericClientActivityContext activityContext,
		boolean reading)
	{
		if (text == null || text.trim().isEmpty())
		{
			throw new IllegalArgumentException("Dialogue choice text cannot be empty");
		}
		String exactText = text.trim();
		if (behavior.dialogueInputMode() == GenericClientBehaviorProfile.DialogueInputMode.KEYBOARD)
		{
			return keyboardChoice(exactText, activityContext, reading);
		}
		if (!reading)
		{
			return menuInput.interactDirect(() -> resolveChoice(exactText), activityContext);
		}
		return menuInput.interactDirect(
			() -> resolveChoice(exactText),
			activityContext,
			() -> pacing(visibleChoiceText()).toPreInteraction());
	}

	CompletableFuture<Map<String, Object>> chooseKeyboard(
		String text,
		GenericClientActivityContext activityContext,
		boolean reading)
	{
		if (text == null || text.trim().isEmpty())
		{
			throw new IllegalArgumentException("Dialogue choice text cannot be empty");
		}
		return keyboardChoice(text.trim(), activityContext, reading);
	}

	private CompletableFuture<Map<String, Object>> keyboardContinue(
		GenericClientActivityContext activityContext,
		boolean reading)
	{
		return clientRead(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return KeyboardSelection.rejected("client_not_logged_in");
			}
			if (!visibleOptions().isEmpty())
			{
				return KeyboardSelection.rejected("dialogue_is_choice");
			}
			return visibleContinueWidget(client) == null
				? KeyboardSelection.rejected("dialogue_continue_not_visible")
				: KeyboardSelection.space(visibleContinueText());
		}).thenCompose(selection -> keyboardSelection(selection, activityContext, reading));
	}

	private CompletableFuture<Map<String, Object>> keyboardChoice(
		String exactText,
		GenericClientActivityContext activityContext,
		boolean reading)
	{
		return clientRead(() ->
		{
			List<Widget> options = visibleOptions();
			for (int index = 0; index < options.size() && index < 9; index++)
			{
				Widget option = options.get(index);
				if (exactText.equals(cleanText(option.getText())))
				{
					return KeyboardSelection.digit(
						index + 1,
						visibleChoiceText(),
						option.getId(),
						option.getIndex(),
						option.getItemId());
				}
			}
			return KeyboardSelection.rejected("exact_dialogue_choice_not_visible");
		}).thenCompose(selection -> keyboardSelection(selection, activityContext, reading));
	}

	private CompletableFuture<Map<String, Object>> keyboardSelection(
		KeyboardSelection selection,
		GenericClientActivityContext activityContext,
		boolean reading)
	{
		if (selection.rejection != null)
		{
			return CompletableFuture.completedFuture(
				keyboardReceipt(
					"rejected", selection.rejection, selection, null, null, false));
		}
		Pacing pacing = reading ? pacing(selection.visibleText) : null;
		long delay = pacing == null ? 0L : pacing.delayMillis;
		return (selection.space ? keyboard.pressSpace(delay, activityContext) : keyboard.pressDigit(selection.digit, delay, activityContext))
			.thenCompose(keyboardResult -> dispatchChoiceIfStillVisible(selection, activityContext).thenApply(directFallback ->
				keyboardReceipt("dispatched", "dialogue_key_dispatched", selection, keyboardResult, pacing, directFallback)));
	}


	private CompletableFuture<Boolean> dispatchChoiceIfStillVisible(
		KeyboardSelection selection, GenericClientActivityContext activityContext)
	{
		if (selection.space || selection.widgetId < 0)
		{
			return CompletableFuture.completedFuture(false);
		}
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			if (!activityContext.isInputAllowed())
			{
				result.complete(false);
				return;
			}
			for (Widget option : visibleOptions())
			{
				if (option.getId() == selection.widgetId &&
					option.getIndex() == selection.widgetIndex)
				{
					client.menuAction(
						option.getIndex(),
						option.getId(),
						MenuAction.WIDGET_CONTINUE,
						0,
						selection.itemId,
						"Continue",
						"");
					reporter.accept("DIALOGUE_KEYBOARD_DIRECT_FALLBACK key=" +
						selection.digit + " widget=" + selection.widgetId +
						" index=" + selection.widgetIndex);
					result.complete(true);
					return;
				}
			}
			result.complete(false);
		});
		return result;
	}

	String matchingVisibleChoice(String text)
	{
		if (text == null)
		{
			return null;
		}
		String wanted = withoutTrailingPeriod(text);
		for (Widget option : visibleOptions())
		{
			String visible = cleanText(option.getText());
			if (wanted.equals(withoutTrailingPeriod(visible)))
			{
				return visible;
			}
		}
		return null;
	}

	private static String withoutTrailingPeriod(String text)
	{
		String value = cleanText(text);
		return value.endsWith(".") ? value.substring(0, value.length() - 1).trim() : value;
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
			Point current = cursorPosition.get();
			Point point = widget.getId() == InterfaceID.Objectbox.TEXT
				? objectBoxContinuePoint(
					widget.getBounds(), current, client.getCanvasWidth(), client.getCanvasHeight())
				: dialoguePoint(
					widget.getBounds(), current, client.getCanvasWidth(), client.getCanvasHeight());
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
				entry -> entry.getType() == MenuAction.WIDGET_CONTINUE && matchesWidget(entry, widget),
				widget.getId() == InterfaceID.Objectbox.TEXT ? null : widget.getBounds()));
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
		List<Widget> widgets = GenericClientWidgets.visible(client);
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

	static Point objectBoxContinuePoint(Rectangle textBounds, int canvasWidth, int canvasHeight)
	{
		return objectBoxContinuePoint(textBounds, null, canvasWidth, canvasHeight);
	}

	static Point objectBoxContinuePoint(
		Rectangle textBounds,
		Point current,
		int canvasWidth,
		int canvasHeight)
	{
		if (textBounds == null)
		{
			return null;
		}
		int minimumX = textBounds.x;
		int maximumX = textBounds.x + textBounds.width - 1;
		int x = current == null
			? textBounds.x + textBounds.width / 2
			: clamp(current.x + ThreadLocalRandom.current().nextInt(-5, 6), minimumX, maximumX);
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
			Point point = dialoguePoint(
				option.getBounds(), cursorPosition.get(),
				client.getCanvasWidth(), client.getCanvasHeight());
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
				entry -> entry.getType() == MenuAction.WIDGET_CONTINUE && matchesWidget(entry, option), option.getBounds()));
		}
		return GenericClientMenuInput.Resolution.rejected("exact_dialogue_choice_not_visible");
	}

	static Point dialoguePoint(
		Rectangle bounds,
		Point current,
		int canvasWidth,
		int canvasHeight)
	{
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
		{
			return null;
		}
		Rectangle canvas = new Rectangle(0, 0, canvasWidth, canvasHeight);
		Rectangle visible = bounds.intersection(canvas);
		if (visible.isEmpty())
		{
			return null;
		}
		int minimumX = visible.x + Math.min(3, Math.max(0, visible.width - 1));
		int maximumX = visible.x + visible.width - 1 -
			Math.min(3, Math.max(0, visible.width - 1));
		if (minimumX > maximumX)
		{
			minimumX = visible.x;
			maximumX = visible.x + visible.width - 1;
		}
		int lane = current == null
			? visible.x + visible.width / 2
			: clamp(current.x, minimumX, maximumX);
		int x = clamp(lane + ThreadLocalRandom.current().nextInt(-5, 6), minimumX, maximumX);
		int yInset = Math.min(3, Math.max(0, visible.height - 1));
		int minimumY = visible.y + yInset;
		int maximumY = visible.y + visible.height - 1 - yInset;
		if (minimumY > maximumY)
		{
			minimumY = visible.y;
			maximumY = visible.y + visible.height - 1;
		}
		int y = minimumY == maximumY
			? minimumY
			: ThreadLocalRandom.current().nextInt(minimumY, maximumY + 1);
		return new Point(x, y);
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
				if (GenericClientWidgets.isVisible(child) && child.getIndex() > 0 &&
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
		return GenericClientWidgets.isVisible(widget) &&
			widget.getBounds() != null && widget.getBounds().width > 0 && widget.getBounds().height > 0
			? widget
			: null;
	}

	private static String cleanText(String text)
	{
		return text == null ? "" : Text.removeTags(text).trim();
	}

	private <T> CompletableFuture<T> clientRead(Supplier<T> reader)
	{
		CompletableFuture<T> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			try
			{
				result.complete(reader.get());
			}
			catch (RuntimeException exception)
			{
				result.completeExceptionally(exception);
			}
		});
		return result;
	}

	private static Map<String, Object> keyboardReceipt(
		String status,
		String result,
		KeyboardSelection selection,
		String keyboardResult,
		Pacing pacing,
		boolean directFallback)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", status);
		receipt.put("result", result);
		receipt.put("input_mode", "keyboard");
		receipt.put("key", selection == null ? null : selection.keyLabel());
		receipt.put("keyboard", keyboardResult);
		receipt.put("direct_fallback", directFallback);
		receipt.put("click_count", 0L);
		if (pacing != null)
		{
			receipt.putAll(pacing.toMap());
		}
		return receipt;
	}

	private static int clamp(int value, int minimum, int maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static final class KeyboardSelection
	{
		private final boolean space;
		private final int digit;
		private final String visibleText;
		private final String rejection;
		private final int widgetId;
		private final int widgetIndex;
		private final int itemId;

		private KeyboardSelection(
			boolean space,
			int digit,
			String visibleText,
			String rejection,
			int widgetId,
			int widgetIndex,
			int itemId)
		{
			this.space = space;
			this.digit = digit;
			this.visibleText = visibleText;
			this.rejection = rejection;
			this.widgetId = widgetId;
			this.widgetIndex = widgetIndex;
			this.itemId = itemId;
		}

		private static KeyboardSelection space(String visibleText)
		{
			return new KeyboardSelection(true, 0, visibleText, null, -1, -1, -1);
		}

		private static KeyboardSelection digit(
			int digit,
			String visibleText,
			int widgetId,
			int widgetIndex,
			int itemId)
		{
			return new KeyboardSelection(
				false, digit, visibleText, null, widgetId, widgetIndex, itemId);
		}

		private static KeyboardSelection rejected(String reason)
		{
			return new KeyboardSelection(false, 0, "", reason, -1, -1, -1);
		}

		private String keyLabel()
		{
			return space ? "SPACE" : digit <= 0 ? null : Integer.toString(digit);
		}
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
