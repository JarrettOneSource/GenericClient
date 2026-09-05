package com.genericclient;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

final class GenericClientGrandExchangePolicy
{
	static final long HARD_MINIMUM_CASH_RESERVE = 5_000_000L;
	static final int OFFER_COUNT = 8;

	private final Client client;
	private final Supplier<GenericClientSnapshot> snapshotSupplier;

	GenericClientGrandExchangePolicy(
		Client client,
		Supplier<GenericClientSnapshot> snapshotSupplier)
	{
		this.client = client;
		this.snapshotSupplier = snapshotSupplier;
	}

	Preflight preflight(
		int itemId,
		int quantity,
		int maximumUnitPrice,
		long minimumCashReserve)
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null || offers.length < OFFER_COUNT)
		{
			return Preflight.rejected("grand_exchange_offers_unavailable");
		}
		int empty = -1;
		for (int slot = 0; slot < OFFER_COUNT; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				if (empty < 0)
				{
					empty = slot;
				}
				continue;
			}
			if (offer.getItemId() != itemId)
			{
				continue;
			}
			if (offer.getState() == GrandExchangeOfferState.BUYING ||
				offer.getState() == GrandExchangeOfferState.BOUGHT ||
				offer.getState() == GrandExchangeOfferState.CANCELLED_BUY)
			{
				return Preflight.existing(slot);
			}
			return Preflight.rejected("conflicting_existing_offer_for_item");
		}

		long maximumSpend = (long) quantity * maximumUnitPrice;
		String cashRejection = cashRejection(cashSnapshot(), maximumSpend, minimumCashReserve);
		if (cashRejection != null)
		{
			return Preflight.rejected(cashRejection);
		}
		return empty < 0
			? Preflight.rejected("no_empty_grand_exchange_slot")
			: Preflight.empty(empty);
	}

	private Map<?, ?> cashSnapshot()
	{
		GenericClientSnapshot snapshot = snapshotSupplier.get();
		Object value = snapshot == null ? null : snapshot.read("cash", Collections.emptyMap());
		return value instanceof Map ? (Map<?, ?>) value : null;
	}

	int matchingOfferSlot(int itemId, int quantity, int unitPrice)
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return -1;
		}
		for (int slot = 0; slot < offers.length; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			if (offer != null && offer.getItemId() == itemId &&
				offer.getTotalQuantity() == quantity && offer.getPrice() == unitPrice &&
				(offer.getState() == GrandExchangeOfferState.BUYING ||
					offer.getState() == GrandExchangeOfferState.BOUGHT))
			{
				return slot;
			}
		}
		return -1;
	}

	static boolean isCompletelyUnfilled(GrandExchangeOffer offer)
	{
		return offer != null && offer.getState() == GrandExchangeOfferState.BUYING &&
			offer.getQuantitySold() == 0;
	}

	static int finalUnitPrice(int guideUnitPrice, int maximumUnitPrice)
	{
		if (guideUnitPrice < 1 || maximumUnitPrice < guideUnitPrice)
		{
			throw new IllegalArgumentException(
				"Final GE price requires a positive guide price within the configured ceiling");
		}
		long increased = guideUnitPrice * 125L / 100L;
		return (int) Math.min(maximumUnitPrice, Math.max(guideUnitPrice, increased));
	}

	static boolean matchesRequestedOffer(
		GrandExchangeOffer offer,
		int quantity,
		int maximumUnitPrice)
	{
		return offer != null && offer.getTotalQuantity() == quantity &&
			offer.getPrice() <= maximumUnitPrice &&
			(offer.getState() == GrandExchangeOfferState.BUYING ||
				offer.getState() == GrandExchangeOfferState.BOUGHT);
	}

	static String collectMode(String requested)
	{
		String mode = requested == null
			? "items"
			: requested.trim().toLowerCase(java.util.Locale.ROOT);
		if (!"items".equals(mode) && !"notes".equals(mode) && !"bank".equals(mode))
		{
			throw new IllegalArgumentException(
				"ge.buy collect_mode must be items, notes, or bank");
		}
		return mode;
	}

	static String cashRejection(Map<?, ?> cash, long maximumSpend, long minimumCashReserve)
	{
		if (cash == null || !Boolean.TRUE.equals(cash.get("complete")))
		{
			return "complete_cash_snapshot_required";
		}
		long knownCash = longValue(cash.get("known_total_value"));
		long inventoryCoins = longValue(cash.get("inventory_coins"));
		if (knownCash - maximumSpend < minimumCashReserve)
		{
			return "cash_reserve_would_be_breached";
		}
		return inventoryCoins < maximumSpend
			? "insufficient_inventory_coins_for_offer"
			: null;
	}

	static void validateRequest(
		int itemId,
		String itemName,
		int quantity,
		int maximumUnitPrice,
		long minimumCashReserve)
	{
		if (itemId < 0 || itemName == null || itemName.trim().isEmpty())
		{
			throw new IllegalArgumentException(
				"ge.buy requires a non-negative item id and item name");
		}
		if (quantity < 1 || maximumUnitPrice < 1)
		{
			throw new IllegalArgumentException(
				"ge.buy quantity and maximum unit price must be positive");
		}
		if ((long) quantity * maximumUnitPrice > Integer.MAX_VALUE)
		{
			throw new IllegalArgumentException(
				"ge.buy maximum total cost exceeds the coin stack limit");
		}
		if (minimumCashReserve < HARD_MINIMUM_CASH_RESERVE)
		{
			throw new IllegalArgumentException(
				"ge.buy minimum cash reserve cannot be below 5000000");
		}
	}

	private static long longValue(Object value)
	{
		return value instanceof Number ? ((Number) value).longValue() : 0L;
	}

	static final class Preflight
	{
		final int emptySlot;
		final int existingSlot;
		final String rejection;

		private Preflight(int emptySlot, int existingSlot, String rejection)
		{
			this.emptySlot = emptySlot;
			this.existingSlot = existingSlot;
			this.rejection = rejection;
		}

		private static Preflight empty(int slot)
		{
			return new Preflight(slot, -1, null);
		}

		private static Preflight existing(int slot)
		{
			return new Preflight(-1, slot, null);
		}

		static Preflight rejected(String reason)
		{
			return new Preflight(-1, -1, reason);
		}
	}
}
