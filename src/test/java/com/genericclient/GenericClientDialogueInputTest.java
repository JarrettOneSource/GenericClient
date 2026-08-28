package com.genericclient;

import static org.junit.Assert.assertEquals;

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
}
