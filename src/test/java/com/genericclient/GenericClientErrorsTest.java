package com.genericclient;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.CompletionException;
import org.junit.Test;

public class GenericClientErrorsTest
{
	@Test
	public void showsTheUnderlyingFailureAndNamesExceptionsWithoutMessages()
	{
		assertEquals("Unavailable widget", GenericClientErrors.rootMessage(
			new CompletionException(new IllegalStateException("Unavailable widget"))));
		assertEquals("IllegalStateException", GenericClientErrors.rootMessage(
			new CompletionException(new IllegalStateException())));
		assertEquals("", GenericClientErrors.rootMessage(new IllegalStateException("")));
	}
}
