package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

public class GenericClientGrandExchangeViewTest
{
	@Test
	public void resumesTheExistingBuyOfferForTheRequestedItem()
	{
		GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
		offers[3] = offer(556, GrandExchangeOfferState.BUYING, 10, 25, 700);

		GenericClientGrandExchangePolicy.Preflight result =
			view(offers, 14_200_000L, 9_200_000L).preflight(
				556, 700, 10, 5_000_000L);

		assertNull(result.rejection);
		assertEquals(3, result.existingSlot);
	}

	@Test
	public void rejectsAnotherStateForTheRequestedItemInsteadOfTakingItsSlot()
	{
		GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
		offers[2] = offer(556, GrandExchangeOfferState.SELLING, 10, 0, 700);

		GenericClientGrandExchangePolicy.Preflight result =
			view(offers, 14_200_000L, 9_200_000L).preflight(
				556, 700, 10, 5_000_000L);

		assertEquals("conflicting_existing_offer_for_item", result.rejection);
	}

	@Test
	public void selectsTheFirstEmptySlotAfterCashChecksPass()
	{
		GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
		offers[0] = offer(555, GrandExchangeOfferState.BUYING, 8, 10, 100);

		GenericClientGrandExchangePolicy.Preflight result =
			view(offers, 14_200_000L, 9_200_000L).preflight(
				556, 700, 10, 5_000_000L);

		assertNull(result.rejection);
		assertEquals(1, result.emptySlot);
	}

	@Test
	public void keepsTheEarliestEmptySlot()
	{
		GrandExchangeOffer[] offers = new GrandExchangeOffer[8];

		GenericClientGrandExchangePolicy.Preflight result =
			view(offers, 14_200_000L, 9_200_000L).preflight(
				556, 700, 10, 5_000_000L);

		assertNull(result.rejection);
		assertEquals(0, result.emptySlot);
	}

	@Test
	public void usesTheFullRequestedCostForTheReserveCheck()
	{
		GrandExchangeOffer[] offers = new GrandExchangeOffer[8];

		GenericClientGrandExchangePolicy.Preflight result =
			view(offers, 5_000_100L, 1_000L).preflight(
				556, 100, 10, 5_000_000L);

		assertEquals("cash_reserve_would_be_breached", result.rejection);
	}

	@Test
	public void rejectsPreflightWhenTheGrandExchangeIsClosed()
	{
		GenericClientGrandExchangePolicy.Preflight result =
			view(GameState.LOGIN_SCREEN, new GrandExchangeOffer[8], 14_200_000L, 9_200_000L)
				.preflight(556, 700, 10, 5_000_000L);

		assertEquals("grand_exchange_not_open", result.rejection);
	}

	@Test
	public void permitsSpendThatLeavesExactlyTheHardReserve()
	{
		Map<String, Object> cash = cash(14_200_000L, 9_200_000L);

		assertNull(GenericClientGrandExchangePolicy.cashRejection(
			cash, 9_200_000L, 5_000_000L));
	}

	@Test
	public void rejectsSpendThatCrossesTheReserve()
	{
		Map<String, Object> cash = cash(14_200_000L, 9_300_000L);

		assertEquals("cash_reserve_would_be_breached",
			GenericClientGrandExchangePolicy.cashRejection(
				cash, 9_200_001L, 5_000_000L));
	}

	@Test
	public void requiresEnoughCoinsInInventoryToPlaceTheOffer()
	{
		Map<String, Object> cash = cash(14_200_000L, 99_999L);

		assertEquals("insufficient_inventory_coins_for_offer",
			GenericClientGrandExchangePolicy.cashRejection(
				cash, 100_000L, 5_000_000L));
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

	private static Map<String, Object> cash(long knownTotal, long inventoryCoins)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("complete", true);
		value.put("known_total_value", knownTotal);
		value.put("inventory_coins", inventoryCoins);
		return value;
	}

	private static GenericClientGrandExchangeView view(
		GrandExchangeOffer[] offers,
		long knownTotal,
		long inventoryCoins)
	{
		return view(GameState.LOGGED_IN, offers, knownTotal, inventoryCoins);
	}

	private static GenericClientGrandExchangeView view(
		GameState gameState,
		GrandExchangeOffer[] offers,
		long knownTotal,
		long inventoryCoins)
	{
		Widget root = widget(new Rectangle(0, 0, 500, 400), 0, 0, null);
		Client client = (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			(proxy, method, arguments) ->
			{
				switch (method.getName())
				{
					case "getGameState":
						return gameState;
					case "getWidget":
						return root;
					case "getGrandExchangeOffers":
						return offers;
					default:
						return method.getReturnType() == boolean.class
							? false
							: method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
		GenericClientAccountSnapshot account = new GenericClientAccountSnapshot(
			true,
			0,
			Collections.emptyList(),
			GenericClientAccountSnapshot.ContainerSnapshot.unavailable(),
			GenericClientAccountSnapshot.ContainerSnapshot.unavailable(),
			GenericClientAccountSnapshot.BankSnapshot.unknown(),
			GenericClientAccountSnapshot.QuestListSnapshot.unavailable(),
			GenericClientAccountSnapshot.GrandExchangeSnapshot.unavailable(),
			new GenericClientAccountSnapshot.CashSnapshot(
				true, true, inventoryCoins, 0, knownTotal - inventoryCoins, 0));
		GenericClientSnapshot snapshot = new GenericClientSnapshot(
			0,
			"LOGGED_IN",
			0,
			null,
			Collections.emptyList(),
			account);
		return new GenericClientGrandExchangeView(client, () -> snapshot);
	}

	private static Widget widget(
		Rectangle bounds,
		int relativeX,
		int relativeY,
		Widget parent)
	{
		return widget(bounds, relativeX, relativeY, parent, null);
	}

	private static Widget widget(
		Rectangle bounds,
		int relativeX,
		int relativeY,
		Widget parent,
		String[] actions)
	{
		return widget(bounds, relativeX, relativeY, parent, actions, null, null);
	}

	private static Widget widget(
		Rectangle bounds,
		int relativeX,
		int relativeY,
		Widget parent,
		String[] actions,
		String text,
		String name,
		Widget... children)
	{
		return (Widget) Proxy.newProxyInstance(
			Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class},
			(proxy, method, arguments) ->
			{
				switch (method.getName())
				{
					case "getBounds":
						return bounds;
					case "getRelativeX":
						return relativeX;
					case "getRelativeY":
						return relativeY;
					case "getParent":
						return parent;
					case "getActions":
						return actions;
					case "getText":
						return text;
					case "getName":
						return name;
					case "getChildren":
						return children;
					default:
						return method.getReturnType() == boolean.class
							? false
							: method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
	}

	private static GrandExchangeOffer offer(
		GrandExchangeOfferState state,
		int price,
		int quantitySold,
		int totalQuantity)
	{
		return offer(0, state, price, quantitySold, totalQuantity);
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
