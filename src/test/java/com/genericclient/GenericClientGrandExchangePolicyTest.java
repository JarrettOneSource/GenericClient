package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

public class GenericClientGrandExchangePolicyTest
{
	@Test
	public void permitsSpendThatLeavesExactlyTheHardReserve()
	{
		assertNull(GenericClientGrandExchangePolicy.cashRejection(
			cash(14_200_000L, 9_200_000L), 9_200_000L, 5_000_000L));
	}

	@Test
	public void rejectsSpendThatCrossesTheReserve()
	{
		assertEquals("cash_reserve_would_be_breached",
			GenericClientGrandExchangePolicy.cashRejection(
				cash(14_200_000L, 9_300_000L), 9_200_001L, 5_000_000L));
	}

	@Test
	public void requiresEnoughCoinsInInventoryToPlaceTheOffer()
	{
		assertEquals("insufficient_inventory_coins_for_offer",
			GenericClientGrandExchangePolicy.cashRejection(
				cash(14_200_000L, 99_999L), 100_000L, 5_000_000L));
	}

	@Test(expected = IllegalArgumentException.class)
	public void cannotLowerTheHardReserve()
	{
		GenericClientGrandExchangePolicy.validateRequest(
			556, "Air rune", 300, 10, 4_999_999L);
	}

	@Test
	public void increasesPriceOnlyForAnImmediatelyUnfilledOffer()
	{
		assertTrue(GenericClientGrandExchangePolicy.isCompletelyUnfilled(
			offer(GrandExchangeOfferState.BUYING, 500, 0, 10)));
		assertFalse(GenericClientGrandExchangePolicy.isCompletelyUnfilled(
			offer(GrandExchangeOfferState.BUYING, 500, 1, 10)));
		assertFalse(GenericClientGrandExchangePolicy.isCompletelyUnfilled(
			offer(GrandExchangeOfferState.BOUGHT, 500, 10, 10)));
	}

	@Test
	public void purchaseCompletionAlsoSettlesAnInFlightCancellation()
	{
		assertTrue(GenericClientGrandExchangeInput.isCancellationSettled(
			offer(GrandExchangeOfferState.CANCELLED_BUY, 98, 0, 6)));
		assertTrue(GenericClientGrandExchangeInput.isCancellationSettled(
			offer(GrandExchangeOfferState.BOUGHT, 98, 6, 6)));
		assertFalse(GenericClientGrandExchangeInput.isCancellationSettled(
			offer(GrandExchangeOfferState.BUYING, 98, 0, 6)));
	}

	@Test
	public void finalPriceIsAtMostTwentyFivePercentAboveGuidePerItem()
	{
		assertEquals(125, GenericClientGrandExchangePolicy.finalUnitPrice(100, 10_000));
		assertEquals(123, GenericClientGrandExchangePolicy.finalUnitPrice(99, 10_000));
		assertEquals(110, GenericClientGrandExchangePolicy.finalUnitPrice(100, 110));
	}

	@Test
	public void acceptsOnlyMatchingActiveOrCompletedOffers()
	{
		assertTrue(GenericClientGrandExchangePolicy.matchesRequestedOffer(
			offer(GrandExchangeOfferState.BUYING, 10, 0, 700), 700, 10));
		assertTrue(GenericClientGrandExchangePolicy.matchesRequestedOffer(
			offer(GrandExchangeOfferState.BOUGHT, 5, 700, 700), 700, 10));
		assertFalse(GenericClientGrandExchangePolicy.matchesRequestedOffer(
			offer(GrandExchangeOfferState.BOUGHT, 5, 675, 675), 700, 10));
	}

	@Test
	public void resumesACancelledBuyOfferSoItsRefundCanBeCollected()
	{
		GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
		offers[1] = offer(2448, GrandExchangeOfferState.CANCELLED_BUY, 1365, 0, 1);
		Client client = (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			(proxy, method, arguments) ->
			{
				if ("getGrandExchangeOffers".equals(method.getName()))
				{
					return offers;
				}
				return method.getReturnType().isPrimitive() ? 0 : null;
			});

		GenericClientGrandExchangePolicy.Preflight preflight =
			new GenericClientGrandExchangePolicy(client, () -> null)
				.preflight(2448, 1, 10_000, 5_000_000L);

		assertEquals(1, preflight.existingSlot);
		assertNull(preflight.rejection);
	}

	@Test
	public void resolvesTheActualSlotUsedByTheClientAfterPlacement()
	{
		GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
		offers[5] = offer(379, GrandExchangeOfferState.BOUGHT, 98, 4, 4);
		Client client = (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			(proxy, method, arguments) ->
			{
				if ("getGrandExchangeOffers".equals(method.getName()))
				{
					return offers;
				}
				return method.getReturnType().isPrimitive() ? 0 : null;
			});

		assertEquals(5, new GenericClientGrandExchangePolicy(client, () -> null)
			.matchingOfferSlot(379, 4, 98));
	}

	private static Map<String, Object> cash(long knownTotal, long inventoryCoins)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("complete", true);
		value.put("known_total_value", knownTotal);
		value.put("inventory_coins", inventoryCoins);
		return value;
	}

	private static GrandExchangeOffer offer(
		GrandExchangeOfferState state,
		int price,
		int quantitySold,
		int totalQuantity)
	{
		return offer(2448, state, price, quantitySold, totalQuantity);
	}

	private static GrandExchangeOffer offer(
		int itemId,
		GrandExchangeOfferState state,
		int price,
		int quantitySold,
		int totalQuantity)
	{
		return (GrandExchangeOffer) Proxy.newProxyInstance(
			GrandExchangeOffer.class.getClassLoader(),
			new Class<?>[]{GrandExchangeOffer.class},
			(proxy, method, arguments) ->
			{
				switch (method.getName())
				{
					case "getState":
						return state;
					case "getItemId":
						return itemId;
					case "getPrice":
						return price;
					case "getQuantitySold":
						return quantitySold;
					case "getTotalQuantity":
						return totalQuantity;
					default:
						return method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
	}
}
