package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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

		GenericClientGrandExchangeView.Preflight result =
			view(offers, 14_200_000L, 9_200_000L).preflight(
				556, 700, 10, 5_000_000L);

		assertNull(result.rejection());
		assertEquals(3, result.existingSlot());
	}

	@Test
	public void rejectsAnotherStateForTheRequestedItemInsteadOfTakingItsSlot()
	{
		GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
		offers[2] = offer(556, GrandExchangeOfferState.CANCELLED_BUY, 10, 0, 700);

		GenericClientGrandExchangeView.Preflight result =
			view(offers, 14_200_000L, 9_200_000L).preflight(
				556, 700, 10, 5_000_000L);

		assertEquals("conflicting_existing_offer_for_item", result.rejection());
	}

	@Test
	public void selectsTheFirstEmptySlotAfterCashChecksPass()
	{
		GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
		offers[0] = offer(555, GrandExchangeOfferState.BUYING, 8, 10, 100);

		GenericClientGrandExchangeView.Preflight result =
			view(offers, 14_200_000L, 9_200_000L).preflight(
				556, 700, 10, 5_000_000L);

		assertNull(result.rejection());
		assertEquals(1, result.emptySlot());
	}

	@Test
	public void keepsTheEarliestEmptySlot()
	{
		GrandExchangeOffer[] offers = new GrandExchangeOffer[8];

		GenericClientGrandExchangeView.Preflight result =
			view(offers, 14_200_000L, 9_200_000L).preflight(
				556, 700, 10, 5_000_000L);

		assertNull(result.rejection());
		assertEquals(0, result.emptySlot());
	}

	@Test
	public void usesTheFullRequestedCostForTheReserveCheck()
	{
		GrandExchangeOffer[] offers = new GrandExchangeOffer[8];

		GenericClientGrandExchangeView.Preflight result =
			view(offers, 5_000_100L, 1_000L).preflight(
				556, 100, 10, 5_000_000L);

		assertEquals("cash_reserve_would_be_breached", result.rejection());
	}

	@Test
	public void rejectsPreflightWhenTheGrandExchangeIsClosed()
	{
		GenericClientGrandExchangeView.Preflight result =
			view(GameState.LOGIN_SCREEN, new GrandExchangeOffer[8], 14_200_000L, 9_200_000L)
				.preflight(556, 700, 10, 5_000_000L);

		assertEquals("grand_exchange_not_open", result.rejection());
	}

	@Test
	public void permitsSpendThatLeavesExactlyTheHardReserve()
	{
		Map<String, Object> cash = cash(14_200_000L, 9_200_000L);

		assertNull(GenericClientGrandExchangeView.cashRejection(
			cash, 9_200_000L, 5_000_000L));
	}

	@Test
	public void rejectsSpendThatCrossesTheReserve()
	{
		Map<String, Object> cash = cash(14_200_000L, 9_300_000L);

		assertEquals("cash_reserve_would_be_breached",
			GenericClientGrandExchangeView.cashRejection(
				cash, 9_200_001L, 5_000_000L));
	}

	@Test
	public void requiresEnoughCoinsInInventoryToPlaceTheOffer()
	{
		Map<String, Object> cash = cash(14_200_000L, 99_999L);

		assertEquals("insufficient_inventory_coins_for_offer",
			GenericClientGrandExchangeView.cashRejection(
				cash, 100_000L, 5_000_000L));
	}

	@Test
	public void mapsTheStockCreateBuyOfferTextToTheSemanticBuyAction()
	{
		assertTrue(GenericClientGrandExchangeView.matchesActionText(
			"Create <col=ff9040>Buy</col> offer", "Buy"));
		assertFalse(GenericClientGrandExchangeView.matchesActionText("Sell", "Buy"));
	}

	@Test
	public void aimsAtTheBuyControlInsteadOfTheOfferSlotHeading()
	{
		Rectangle slot = new Rectangle(145, 78, 113, 110);

		assertEquals(new Rectangle(165, 139, 20, 22),
			GenericClientGrandExchangeView.buyOfferHitbox(slot));
	}

	@Test
	public void preservesTheMinimumOnePixelBuySlot()
	{
		assertEquals(new Rectangle(10, 21, 1, 1),
			GenericClientGrandExchangeView.buyOfferHitbox(new Rectangle(9, 20, 1, 2)));
		assertEquals(new Rectangle(10, 21, 1, 1),
			GenericClientGrandExchangeView.buyOfferHitbox(new Rectangle(9, 20, 2, 1)));
	}

	@Test
	public void recognizesTheVisiblePriceWarningChoiceWithoutADeclaredAction()
	{
		Widget yes = widget(new Rectangle(307, 201, 40, 32), 0, 0, null, null, "Yes", null);

		assertTrue(GenericClientGrandExchangeView.matchesWidgetText(yes, "Yes"));
	}

	@Test
	public void findsPriceWarningChoiceInsideAttachedPopupRoot()
	{
		Rectangle scope = new Rectangle(20, 20, 480, 300);
		Widget yes = widget(new Rectangle(307, 201, 40, 32), 0, 0, null, null, "Yes", null);
		Widget popup = widget(new Rectangle(35, 125, 440, 120), 0, 0, null, null, null, null, yes);

		assertSame(yes, GenericClientGrandExchangeView.findByTextWithin(popup, "Yes", scope));
	}

	@Test
	public void usesTheWidgetCenterWhenScopingPriceWarningChoices()
	{
		Widget centered = widget(new Rectangle(100, 100, 40, 20), 0, 0, null, null, "Yes", null);
		Rectangle aroundCenter = new Rectangle(118, 108, 5, 5);

		assertSame(centered,
			GenericClientGrandExchangeView.findByTextWithin(centered, "Yes", aroundCenter));
		assertNull(GenericClientGrandExchangeView.findByTextWithin(
			centered, "Yes", new Rectangle(0, 0, 20, 20)));
	}

	@Test
	public void traversesEveryRuneLiteWidgetChildCollection()
	{
		Rectangle bounds = new Rectangle(0, 0, 200, 200);
		Widget child = widget(bounds, 0, 0, null, null, "child", null);
		Widget dynamic = widget(bounds, 0, 0, null, null, "dynamic", null);
		Widget statik = widget(bounds, 0, 0, null, null, "static", null);
		Widget nested = widget(bounds, 0, 0, null, null, "nested", null);
		Widget root = widgetWithBranches(bounds,
			new Widget[]{child}, new Widget[]{dynamic}, new Widget[]{statik}, new Widget[]{nested});

		assertSame(child, GenericClientGrandExchangeView.findByTextWithin(root, "child", bounds));
		assertSame(dynamic, GenericClientGrandExchangeView.findByTextWithin(root, "dynamic", bounds));
		assertSame(statik, GenericClientGrandExchangeView.findByTextWithin(root, "static", bounds));
		assertSame(nested, GenericClientGrandExchangeView.findByTextWithin(root, "nested", bounds));
	}

	@Test
	public void skipsInvisibleAndRunawayWidgetDescendants()
	{
		Rectangle scope = new Rectangle(0, 0, 200, 200);
		Widget zeroWidth = widget(new Rectangle(10, 10, 0, 20), 0, 0, null, null, "Yes", null);
		Widget zeroHeight = widget(new Rectangle(10, 10, 20, 0), 0, 0, null, null, "Yes", null);
		Widget[] children = new Widget[512];
		for (int index = 0; index < children.length; index++)
		{
			children[index] = widget(scope, 0, 0, null, null,
				index == children.length - 1 ? "limit" : "other", null);
		}
		Widget root = widget(scope, 0, 0, null, null, null, null, children);

		assertNull(GenericClientGrandExchangeView.findByTextWithin(zeroWidth, "Yes", scope));
		assertNull(GenericClientGrandExchangeView.findByTextWithin(zeroHeight, "Yes", scope));
		assertNull(GenericClientGrandExchangeView.findByTextWithin(root, "limit", scope));
	}

	@Test
	public void scopesPriceWarningToTheOfferIndexAfterSetupCloses()
	{
		Rectangle index = new Rectangle(20, 20, 480, 300);

		assertEquals(index, GenericClientGrandExchangeView.priceWarningScope(null, index));
	}

	@Test
	public void resolvesSentinelSearchRowBoundsThroughItsVisibleParent()
	{
		Widget parent = widget(new Rectangle(9, 367, 485, 104), 0, 0, null);
		Widget row = widget(new Rectangle(-1, -1, 161, 32), 3, 4, parent);

		assertEquals(new Rectangle(12, 371, 161, 32),
			GenericClientGrandExchangeView.resolvedWidgetBounds(row));
	}

	@Test
	public void keepsVisibleZeroCoordinatesAndResolvesOnePixelSentinels()
	{
		Widget parent = widget(new Rectangle(20, 30, 100, 100), 0, 0, null);
		Widget zeroX = widget(new Rectangle(0, 5, 10, 10), 7, 8, parent);
		Widget zeroY = widget(new Rectangle(5, 0, 10, 10), 7, 8, parent);
		Widget oneWide = widget(new Rectangle(-1, -1, 1, 2), 7, 8, parent);
		Widget oneHigh = widget(new Rectangle(-1, -1, 2, 1), 7, 8, parent);

		assertEquals(new Rectangle(0, 5, 10, 10),
			GenericClientGrandExchangeView.resolvedWidgetBounds(zeroX));
		assertEquals(new Rectangle(5, 0, 10, 10),
			GenericClientGrandExchangeView.resolvedWidgetBounds(zeroY));
		assertEquals(new Rectangle(27, 38, 1, 2),
			GenericClientGrandExchangeView.resolvedWidgetBounds(oneWide));
		assertEquals(new Rectangle(27, 38, 2, 1),
			GenericClientGrandExchangeView.resolvedWidgetBounds(oneHigh));
	}

	@Test
	public void resolvesAgainstParentsOnTheCanvasEdges()
	{
		Widget xEdge = widget(new Rectangle(0, 30, 100, 100), 0, 0, null);
		Widget yEdge = widget(new Rectangle(20, 0, 100, 100), 0, 0, null);
		Widget fromXEdge = widget(new Rectangle(-1, -1, 10, 10), 2, 3, xEdge);
		Widget fromYEdge = widget(new Rectangle(-1, -1, 10, 10), 2, 3, yEdge);

		assertEquals(new Rectangle(2, 33, 10, 10),
			GenericClientGrandExchangeView.resolvedWidgetBounds(fromXEdge));
		assertEquals(new Rectangle(22, 3, 10, 10),
			GenericClientGrandExchangeView.resolvedWidgetBounds(fromYEdge));
	}

	@Test
	public void aimsAtTheExactSearchResultIconInsteadOfBlankRowSpace()
	{
		Widget row = widget(new Rectangle(9, 367, 485, 104), 0, 0, null);
		Widget icon = widget(new Rectangle(17, 374, 36, 32), 0, 0, row);

		assertEquals(new Rectangle(17, 374, 36, 32),
			GenericClientGrandExchangeView.searchResultHitbox(row, icon));
	}

	@Test
	public void ignoresZeroSizedSearchResultIcons()
	{
		Widget row = widget(new Rectangle(9, 367, 485, 104), 0, 0, null);
		Widget zeroWidth = widget(new Rectangle(17, 374, 0, 32), 0, 0, row);
		Widget zeroHeight = widget(new Rectangle(17, 374, 36, 0), 0, 0, row);

		assertEquals(row.getBounds(), GenericClientGrandExchangeView.searchResultHitbox(row, zeroWidth));
		assertEquals(row.getBounds(), GenericClientGrandExchangeView.searchResultHitbox(row, zeroHeight));
	}

	@Test
	public void replacesOnlyAZeroFillBuyWithStaleQuantityOrPrice()
	{
		assertTrue(GenericClientGrandExchangeView.shouldReplaceZeroFill(
			offer(GrandExchangeOfferState.BUYING, 5, 0, 675), 700, 10));
		assertTrue(GenericClientGrandExchangeView.shouldReplaceZeroFill(
			offer(GrandExchangeOfferState.BUYING, 5, 0, 700), 700, 10));
		assertFalse(GenericClientGrandExchangeView.shouldReplaceZeroFill(
			offer(GrandExchangeOfferState.BUYING, 5, 1, 700), 700, 10));
		assertFalse(GenericClientGrandExchangeView.shouldReplaceZeroFill(
			offer(GrandExchangeOfferState.BOUGHT, 5, 0, 700), 700, 10));
		assertFalse(GenericClientGrandExchangeView.shouldReplaceZeroFill(
			offer(GrandExchangeOfferState.BUYING, 10, 0, 700), 700, 10));
	}

	@Test
	public void acceptsOnlyMatchingActiveOrCompletedOffers()
	{
		assertTrue(GenericClientGrandExchangeView.matchesRequestedOffer(
			offer(GrandExchangeOfferState.BUYING, 10, 0, 700), 700, 10));
		assertTrue(GenericClientGrandExchangeView.matchesRequestedOffer(
			offer(GrandExchangeOfferState.BOUGHT, 5, 700, 700), 700, 10));
		assertFalse(GenericClientGrandExchangeView.matchesRequestedOffer(
			offer(GrandExchangeOfferState.BOUGHT, 5, 675, 675), 700, 10));
	}

	@Test
	public void selectsTheRequestedCollectionMode()
	{
		Widget collect = widget(
			new Rectangle(100, 100, 36, 32),
			0,
			0,
			null,
			new String[]{"Collect-notes", "Collect-items", "Bank"});

		assertEquals("Collect-items", GenericClientGrandExchangeView.collectAction(collect, "items"));
		assertEquals("Collect-notes", GenericClientGrandExchangeView.collectAction(collect, "notes"));
		assertEquals("Bank", GenericClientGrandExchangeView.collectAction(collect, "bank"));
		assertNull(GenericClientGrandExchangeView.collectAction(
			widget(new Rectangle(0, 0, 10, 10), 0, 0, null, new String[]{"Examine"}),
			"items"));
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
		return new GenericClientGrandExchangeView(client, () -> snapshot, message -> { });
	}

	private static Widget widgetWithBranches(
		Rectangle bounds,
		Widget[] children,
		Widget[] dynamicChildren,
		Widget[] staticChildren,
		Widget[] nestedChildren)
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
					case "getChildren":
						return children;
					case "getDynamicChildren":
						return dynamicChildren;
					case "getStaticChildren":
						return staticChildren;
					case "getNestedChildren":
						return nestedChildren;
					default:
						return method.getReturnType() == boolean.class
							? false
							: method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
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
