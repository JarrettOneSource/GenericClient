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
}
