package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.lang.reflect.Proxy;
import net.runelite.api.MenuEntry;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

public class GenericClientGrandExchangeWidgetsTest
{
	@Test
	public void mapsTheStockCreateBuyOfferTextToTheSemanticBuyAction()
	{
		assertTrue(GenericClientGrandExchangeWidgets.matchesActionText(
			"Create <col=ff9040>Buy</col> offer", "Buy"));
		assertFalse(GenericClientGrandExchangeWidgets.matchesActionText("Sell", "Buy"));
	}

	@Test
	public void aimsAtTheBuyControlInsteadOfTheOfferSlotHeading()
	{
		Rectangle slot = new Rectangle(145, 78, 113, 110);

		assertEquals(new Rectangle(165, 139, 20, 22),
			GenericClientGrandExchangeWidgets.buyOfferHitbox(slot));
	}

	@Test
	public void resolvesSentinelSearchRowBoundsThroughItsVisibleParent()
	{
		Widget parent = widget(new Rectangle(9, 367, 485, 104), 0, 0, null);
		Widget row = widget(new Rectangle(-1, -1, 161, 32), 3, 4, parent);

		assertEquals(new Rectangle(12, 371, 161, 32),
			GenericClientGrandExchangeWidgets.resolvedBounds(row));
	}

	@Test
	public void aimsAtTheFirstExactNamedSearchResultCell()
	{
		Widget row = widget(new Rectangle(9, 367, 485, 104), 0, 0, null);

		assertEquals(new Rectangle(9, 367, 161, 32),
			GenericClientGrandExchangeWidgets.searchResultHitbox(row, 0));
		assertEquals(new Rectangle(170, 367, 161, 32),
			GenericClientGrandExchangeWidgets.searchResultHitbox(row, 1));
		assertEquals(new Rectangle(9, 399, 161, 32),
			GenericClientGrandExchangeWidgets.searchResultHitbox(row, 3));
	}

	@Test
	public void resolvesTheVisibleFivePercentControlByExactText()
	{
		Widget fivePercent = widget(
			new Rectangle(411, 198, 37, 25), 0, 0, null, null, "+5%", "");
		Widget setup = widget(
			new Rectangle(24, 59, 472, 258), 0, 0, null,
			null, "", "", fivePercent);

		assertSame(fivePercent,
			GenericClientGrandExchangeWidgets.findByText(setup, "+5%"));
	}

	@Test
	public void mapsClientSlotsToTheVisibleFourByTwoOfferGrid()
	{
		Rectangle index = new Rectangle(24, 59, 472, 258);

		assertEquals(new Rectangle(24, 59, 118, 129),
			GenericClientGrandExchangeWidgets.offerSlotHitbox(index, 0));
		assertEquals(new Rectangle(142, 59, 118, 129),
			GenericClientGrandExchangeWidgets.offerSlotHitbox(index, 1));
		assertEquals(new Rectangle(378, 188, 118, 129),
			GenericClientGrandExchangeWidgets.offerSlotHitbox(index, 7));
	}

	@Test
	public void targetsTheVisibleCancelledOfferRefundIcon()
	{
		assertEquals(new Rectangle(396, 275, 33, 28),
			GenericClientGrandExchangeWidgets.cancelledRefundHitbox(
				new Rectangle(24, 59, 472, 258)));
	}

	@Test
	public void validatesAnOfferActionAgainstItsExactSlotComponent()
	{
		MenuEntry firstSlot = menuEntry(30_474_247);

		assertTrue(GenericClientGrandExchangeWidgets.matchesOfferSlot(firstSlot, 0));
		assertFalse(GenericClientGrandExchangeWidgets.matchesOfferSlot(firstSlot, 1));
	}

	@Test
	public void resolvesTheActionBearingChildOfAnOccupiedOfferSlot()
	{
		Widget abort = widget(
			new Rectangle(45, 115, 36, 32), 0, 0, null,
			new String[]{"Abort offer"});
		Widget slot = widget(
			new Rectangle(24, 59, 118, 129), 0, 0, null,
			null, null, null, abort);

		assertSame(abort,
			GenericClientGrandExchangeWidgets.findByAction(
				slot, "Abort offer", null));
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

		assertEquals("Collect-items", GenericClientGrandExchangeWidgets.collectAction(collect, "items"));
		assertEquals("Collect-notes", GenericClientGrandExchangeWidgets.collectAction(collect, "notes"));
		assertEquals("Bank", GenericClientGrandExchangeWidgets.collectAction(collect, "bank"));
		assertNull(GenericClientGrandExchangeWidgets.collectAction(
			widget(new Rectangle(0, 0, 10, 10), 0, 0, null, new String[]{"Examine"}), "items"));
	}

	@Test
	public void preservesTheMinimumOnePixelBuySlot()
	{
		assertEquals(new Rectangle(10, 21, 1, 1),
			GenericClientGrandExchangeWidgets.buyOfferHitbox(new Rectangle(9, 20, 1, 2)));
		assertEquals(new Rectangle(10, 21, 1, 1),
			GenericClientGrandExchangeWidgets.buyOfferHitbox(new Rectangle(9, 20, 2, 1)));
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

		assertSame(child, GenericClientGrandExchangeWidgets.findByText(root, "child"));
		assertSame(dynamic, GenericClientGrandExchangeWidgets.findByText(root, "dynamic"));
		assertSame(statik, GenericClientGrandExchangeWidgets.findByText(root, "static"));
		assertSame(nested, GenericClientGrandExchangeWidgets.findByText(root, "nested"));
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

		assertNull(GenericClientGrandExchangeWidgets.findByText(zeroWidth, "Yes"));
		assertNull(GenericClientGrandExchangeWidgets.findByText(zeroHeight, "Yes"));
		assertNull(GenericClientGrandExchangeWidgets.findByText(root, "limit"));
	}

	@Test
	public void controlsUnderHiddenParentsAreNotSelectable()
	{
		Rectangle bounds = new Rectangle(0, 0, 200, 200);
		Widget parent = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
			(proxy, method, args) -> "isSelfHidden".equals(method.getName()) ? true :
				method.getReturnType() == boolean.class ? false : method.getReturnType().isPrimitive() ? 0 : null);
		Widget yes = widget(bounds, 0, 0, parent, null, "Yes", null);
		assertNull(GenericClientGrandExchangeWidgets.findByText(yes, "Yes"));
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
			GenericClientGrandExchangeWidgets.resolvedBounds(zeroX));
		assertEquals(new Rectangle(5, 0, 10, 10),
			GenericClientGrandExchangeWidgets.resolvedBounds(zeroY));
		assertEquals(new Rectangle(27, 38, 1, 2),
			GenericClientGrandExchangeWidgets.resolvedBounds(oneWide));
		assertEquals(new Rectangle(27, 38, 2, 1),
			GenericClientGrandExchangeWidgets.resolvedBounds(oneHigh));
	}

	@Test
	public void resolvesAgainstParentsOnTheCanvasEdges()
	{
		Widget xEdge = widget(new Rectangle(0, 30, 100, 100), 0, 0, null);
		Widget yEdge = widget(new Rectangle(20, 0, 100, 100), 0, 0, null);
		Widget fromXEdge = widget(new Rectangle(-1, -1, 10, 10), 2, 3, xEdge);
		Widget fromYEdge = widget(new Rectangle(-1, -1, 10, 10), 2, 3, yEdge);

		assertEquals(new Rectangle(2, 33, 10, 10),
			GenericClientGrandExchangeWidgets.resolvedBounds(fromXEdge));
		assertEquals(new Rectangle(22, 3, 10, 10),
			GenericClientGrandExchangeWidgets.resolvedBounds(fromYEdge));
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

	private static MenuEntry menuEntry(int widgetId)
	{
		return (MenuEntry) Proxy.newProxyInstance(
			MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class},
			(proxy, method, arguments) ->
			{
				if ("getParam1".equals(method.getName()))
				{
					return widgetId;
				}
				return method.getReturnType() == boolean.class
					? false
					: method.getReturnType().isPrimitive() ? 0 : null;
			});
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
					case "getDynamicChildren":
						return children;
					default:
						return method.getReturnType() == boolean.class
							? false
							: method.getReturnType().isPrimitive() ? 0 : null;
				}
			});
	}
}
