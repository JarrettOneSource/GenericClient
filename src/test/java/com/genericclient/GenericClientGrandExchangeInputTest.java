package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

public class GenericClientGrandExchangeInputTest
{
	@Test
	public void permitsSpendThatLeavesExactlyTheHardReserve()
	{
		Map<String, Object> cash = cash(14_200_000L, 9_200_000L);

		assertNull(GenericClientGrandExchangeInput.cashRejection(
			cash, 9_200_000L, 5_000_000L));
	}

	@Test
	public void rejectsSpendThatCrossesTheReserve()
	{
		Map<String, Object> cash = cash(14_200_000L, 9_300_000L);

		assertEquals("cash_reserve_would_be_breached",
			GenericClientGrandExchangeInput.cashRejection(
				cash, 9_200_001L, 5_000_000L));
	}

	@Test
	public void requiresEnoughCoinsInInventoryToPlaceTheOffer()
	{
		Map<String, Object> cash = cash(14_200_000L, 99_999L);

		assertEquals("insufficient_inventory_coins_for_offer",
			GenericClientGrandExchangeInput.cashRejection(
				cash, 100_000L, 5_000_000L));
	}

	@Test(expected = IllegalArgumentException.class)
	public void cannotLowerTheHardReserve()
	{
		GenericClientGrandExchangeInput.validateRequest(
			556, "Air rune", 300, 10, 4_999_999L);
	}

	@Test
	public void mapsTheStockCreateBuyOfferTextToTheSemanticBuyAction()
	{
		assertTrue(GenericClientGrandExchangeInput.matchesActionText(
			"Create <col=ff9040>Buy</col> offer", "Buy"));
	}

	@Test
	public void aimsAtTheBuyControlInsteadOfTheOfferSlotHeading()
	{
		Rectangle slot = new Rectangle(145, 78, 113, 110);

		assertEquals(new Rectangle(165, 139, 20, 22),
			GenericClientGrandExchangeInput.buyOfferHitbox(slot));
	}

	@Test
	public void recognizesTheVisiblePriceWarningChoiceWithoutADeclaredAction()
	{
		Widget yes = widget(new Rectangle(307, 201, 40, 32), 0, 0, null, null, "Yes", null);

		assertTrue(GenericClientGrandExchangeInput.matchesWidgetText(yes, "Yes"));
	}

	@Test
	public void resolvesSentinelSearchRowBoundsThroughItsVisibleParent()
	{
		Widget parent = widget(new Rectangle(9, 367, 485, 104), 0, 0, null);
		Widget row = widget(new Rectangle(-1, -1, 161, 32), 0, 0, parent);

		assertEquals(new Rectangle(9, 367, 161, 32),
			GenericClientGrandExchangeInput.resolvedWidgetBounds(row));
	}

	@Test
	public void replacesOnlyAZeroFillBuyWithStaleQuantityOrPrice()
	{
		assertTrue(GenericClientGrandExchangeInput.shouldReplaceZeroFill(
			offer(GrandExchangeOfferState.BUYING, 5, 0, 675), 700, 10));
		assertTrue(GenericClientGrandExchangeInput.shouldReplaceZeroFill(
			offer(GrandExchangeOfferState.BUYING, 5, 0, 700), 700, 10));
		assertEquals(false, GenericClientGrandExchangeInput.shouldReplaceZeroFill(
			offer(GrandExchangeOfferState.BUYING, 5, 1, 700), 700, 10));
		assertEquals(false, GenericClientGrandExchangeInput.shouldReplaceZeroFill(
			offer(GrandExchangeOfferState.BOUGHT, 5, 0, 700), 700, 10));
		assertEquals(false, GenericClientGrandExchangeInput.shouldReplaceZeroFill(
			offer(GrandExchangeOfferState.BUYING, 10, 0, 700), 700, 10));
	}

	@Test
	public void acceptsOnlyMatchingActiveOrCompletedOffers()
	{
		assertTrue(GenericClientGrandExchangeInput.matchesRequestedOffer(
			offer(GrandExchangeOfferState.BUYING, 10, 0, 700), 700, 10));
		assertTrue(GenericClientGrandExchangeInput.matchesRequestedOffer(
			offer(GrandExchangeOfferState.BOUGHT, 5, 700, 700), 700, 10));
		assertEquals(false, GenericClientGrandExchangeInput.matchesRequestedOffer(
			offer(GrandExchangeOfferState.BOUGHT, 5, 675, 675), 700, 10));
	}

	@Test
	public void prefersUnnotedItemCollectionAction()
	{
		Widget collect = widget(
			new Rectangle(100, 100, 36, 32),
			0,
			0,
			null,
			new String[]{"Collect-notes", "Collect-items", "Bank"});

		assertEquals("Collect-items", GenericClientGrandExchangeInput.collectAction(collect));
	}

	private static Map<String, Object> cash(long knownTotal, long inventoryCoins)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("complete", true);
		value.put("known_total_value", knownTotal);
		value.put("inventory_coins", inventoryCoins);
		return value;
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
		String name)
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
					default:
						return method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
	}

	private static GrandExchangeOffer offer(
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
