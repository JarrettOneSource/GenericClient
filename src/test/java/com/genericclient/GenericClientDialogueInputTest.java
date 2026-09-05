package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Point;
import java.awt.Rectangle;
import org.junit.Test;

public class GenericClientDialogueInputTest
{
	@Test
	public void objectBoxContinueIsBelowTheTextWidget()
	{
		assertEquals(
			new Point(284, 447),
			GenericClientDialogueInput.objectBoxContinuePoint(
				new Rectangle(53, 370, 463, 69), 800, 600));
	}

	@Test
	public void dialogueReadingDelayUsesTheProfileAndWordCount()
	{
		assertEquals(0L, GenericClientDialogueInput.readingDelayMillis(20, 40));
		long shortPage = GenericClientDialogueInput.readingDelayMillis(70, 5);
		long longPage = GenericClientDialogueInput.readingDelayMillis(70, 20);
		assertTrue(shortPage > 0L);
		assertTrue(longPage > shortPage);
		assertEquals(9_000L, GenericClientDialogueInput.readingDelayMillis(100, 100));
	}

	@Test
	public void dialogueWordCountIgnoresFormattingTags()
	{
		assertEquals(6, GenericClientDialogueInput.countWords(
			"<col=ffffff>Read this line</col> and this one"));
	}

	@Test
	public void dialogueChoiceKeepsTheCurrentHorizontalLane()
	{
		Rectangle option = new Rectangle(100, 220, 320, 24);
		for (int attempt = 0; attempt < 50; attempt++)
		{
			Point point = GenericClientDialogueInput.dialoguePoint(
				option, new Point(260, 180), 800, 600);
			assertTrue(Math.abs(point.x - 260) <= 5);
			assertTrue(point.y >= option.y && point.y < option.y + option.height);
		}
	}
}
