package com.genericclient;

import org.junit.Test;

public class GenericClientGrandExchangeInputTest
{
	@Test
	public void acceptsEveryRequestBoundary()
	{
		assertValid(() -> GenericClientGrandExchangePolicy.validateRequest(
			0, "Air rune", 1, Integer.MAX_VALUE, 5_000_000L));
		assertValid(() -> GenericClientGrandExchangePolicy.validateRequest(
			0, "Air rune", 1, 1, 5_000_000L));
	}

	@Test
	public void rejectsMissingItemIdentity()
	{
		assertInvalid(() -> GenericClientGrandExchangePolicy.validateRequest(
			-1, "Air rune", 1, 1, 5_000_000L));
		assertInvalid(() -> GenericClientGrandExchangePolicy.validateRequest(
			556, null, 1, 1, 5_000_000L));
		assertInvalid(() -> GenericClientGrandExchangePolicy.validateRequest(
			556, "   ", 1, 1, 5_000_000L));
	}

	@Test
	public void rejectsNonPositiveQuantityOrPrice()
	{
		assertInvalid(() -> GenericClientGrandExchangePolicy.validateRequest(
			556, "Air rune", 0, 1, 5_000_000L));
		assertInvalid(() -> GenericClientGrandExchangePolicy.validateRequest(
			556, "Air rune", 1, 0, 5_000_000L));
	}

	@Test
	public void rejectsCoinStackOverflow()
	{
		assertInvalid(() -> GenericClientGrandExchangePolicy.validateRequest(
			556, "Air rune", 2, 1_073_741_824, 5_000_000L));
	}

	@Test(expected = IllegalArgumentException.class)
	public void cannotLowerTheHardReserve()
	{
		GenericClientGrandExchangePolicy.validateRequest(
			556, "Air rune", 300, 10, 4_999_999L);
	}

	private static void assertInvalid(Request request)
	{
		try
		{
			request.validate();
			throw new AssertionError("Expected request validation to fail");
		}
		catch (IllegalArgumentException expected)
		{
			// Expected.
		}
	}

	private static void assertValid(Request request)
	{
		request.validate();
	}

	private interface Request
	{
		void validate();
	}
}
