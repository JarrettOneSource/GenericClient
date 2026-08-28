package com.genericclient;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GenericClientNpcInputTest
{
	@Test
	public void retriesCameraWhenAProjectedNpcHasNoMatchingHoverAction()
	{
		assertTrue(GenericClientNpcInput.isCameraRetryable("npc_not_visible"));
		assertTrue(GenericClientNpcInput.isCameraRetryable("hover_has_no_matching_action"));
		assertFalse(GenericClientNpcInput.isCameraRetryable("matching_npc_not_found"));
		assertFalse(GenericClientNpcInput.isCameraRetryable("menu_action_executed"));
	}
}
