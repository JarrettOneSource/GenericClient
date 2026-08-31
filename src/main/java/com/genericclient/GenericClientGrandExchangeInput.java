package com.genericclient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;

final class GenericClientGrandExchangeInput implements AutoCloseable
{
	static final long HARD_MINIMUM_CASH_RESERVE = 5_000_000L;
	private static final long VERIFY_INTERVAL_MILLIS = 200L;
	private static final long INPUT_SETTLE_MILLIS = 300L;
	private static final int VERIFY_ATTEMPTS = 50;
	private static final int BUY_ATTEMPTS = 300;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientMenuInput menuInput;
	private final GenericClientSyntheticKeyboard keyboard;
	private final GenericClientBehaviorController behavior;
	private final java.util.function.Consumer<String> reporter;
	private final GenericClientGrandExchangeView view;
	private final AtomicBoolean running = new AtomicBoolean();
	private final List<ScheduledFuture<?>> pending = new CopyOnWriteArrayList<>();
	private volatile CompletableFuture<Map<String, Object>> activeResult;
	private volatile boolean closed;

	GenericClientGrandExchangeInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientMenuInput menuInput,
		GenericClientSyntheticKeyboard keyboard,
		GenericClientBehaviorController behavior,
		Supplier<GenericClientSnapshot> snapshotSupplier,
		java.util.function.Consumer<String> reporter)
	{
		this.clientThread = clientThread;
		this.executor = executor;
		this.menuInput = menuInput;
		this.keyboard = keyboard;
		this.behavior = behavior;
		this.reporter = reporter;
		this.view = new GenericClientGrandExchangeView(client, snapshotSupplier, reporter);
	}

	CompletableFuture<Map<String, Object>> buy(
		int itemId,
		String itemName,
		int quantity,
		int maximumUnitPrice,
		long minimumCashReserve,
		String requestedCollectMode,
		GenericClientActivityContext activityContext)
	{
		validateRequest(itemId, itemName, quantity, maximumUnitPrice, minimumCashReserve);
		String collectMode = collectMode(requestedCollectMode);
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		if (closed)
		{
			result.complete(rejected("grand_exchange_input_closed"));
			return result;
		}
		if (!running.compareAndSet(false, true))
		{
			result.complete(rejected("interaction_already_running"));
			return result;
		}
		activeResult = result;
		reporter.accept("GE_BUY_STARTED item=" + itemId + " quantity=" + quantity +
			" maxUnitPrice=" + maximumUnitPrice + " reserve=" + minimumCashReserve +
			" collectMode=" + collectMode);

		clientRead(() -> view.preflight(
			itemId, quantity, maximumUnitPrice, minimumCashReserve)).thenCompose(preflight ->
		{
			if (preflight.rejection() != null)
			{
				return CompletableFuture.completedFuture(rejected(preflight.rejection()));
			}
			if (preflight.existingSlot() >= 0)
			{
				return finishExistingOffer(
					preflight.existingSlot(),
					itemId,
					itemName.trim(),
					quantity,
					maximumUnitPrice,
					minimumCashReserve,
					collectMode,
					activityContext);
			}
			return placeOffer(
				preflight.emptySlot(),
				itemId,
				itemName.trim(),
				quantity,
				maximumUnitPrice,
				minimumCashReserve,
				collectMode,
				activityContext);
		}).whenComplete((receipt, error) ->
		{
			Map<String, Object> completed = receipt;
			if (error != null)
			{
				completed = rejected(rootMessage(error));
			}
			finish(completed);
		});
		return result;
	}

	boolean isRunning()
	{
		return running.get();
	}

	void cancel(String reason)
	{
		if (running.get())
		{
			finish(rejected("cancelled: " + reason));
		}
	}

	private CompletableFuture<Map<String, Object>> placeOffer(
		int slot,
		int itemId,
		String itemName,
		int quantity,
		int maximumUnitPrice,
		long minimumCashReserve,
		String collectMode,
		GenericClientActivityContext activityContext)
	{
		CompletableFuture<List<Map<String, Object>>> flow =
			CompletableFuture.completedFuture(new ArrayList<>());
		flow = append(flow, () -> ensureOfferIndex(activityContext));
		flow = append(flow, () -> clickBuyOffer(slot, activityContext));
		flow = append(flow, () -> waitUntil(
			view::itemSearchVisible, "ge_item_search", VERIFY_ATTEMPTS));
		flow = append(flow, () -> typeWithBehavior(itemName, false, "ge_item_search_text", activityContext));
		flow = append(flow, () -> waitUntil(
			() -> view.searchResultVisible(itemId),
			"ge_exact_search_result",
			VERIFY_ATTEMPTS));
		flow = append(flow, () -> clickSearchResult(itemId, activityContext));
		flow = append(flow, () -> waitUntil(
			() -> view.setupContainsItem(itemId), "ge_offer_item", VERIFY_ATTEMPTS));
		flow = append(flow, () -> clickAndType(
			InterfaceID.GeOffers.SETUP,
			"Enter quantity",
			Integer.toString(quantity),
			"ge_quantity",
			activityContext));
		flow = append(flow, () -> ensurePrice(maximumUnitPrice, activityContext));
		flow = append(flow, () -> clickWidgetAction(
			InterfaceID.GeOffers.SETUP_CONFIRM,
			"Confirm",
			null,
			"confirm_buy_offer",
			activityContext));
		flow = append(flow, () -> confirmPriceWarningIfPresent(
			slot, itemId, quantity, maximumUnitPrice, activityContext));
		flow = append(flow, () -> waitUntil(
			() -> view.offerMatches(slot, itemId, quantity, maximumUnitPrice),
			"ge_offer_placed",
			VERIFY_ATTEMPTS));

		return flow.thenCompose(steps -> waitForPurchase(slot, itemId).thenCompose(purchase ->
		{
			steps.add(purchase);
			if (!"complete".equals(purchase.get("status")))
			{
				return CompletableFuture.completedFuture(offerReceipt(
					purchase, steps, slot, itemId, quantity, maximumUnitPrice, minimumCashReserve));
			}
			return collect(slot, itemId, quantity, collectMode, activityContext).thenApply(collection ->
			{
				steps.add(collection);
				return offerReceipt(
					collection, steps, slot, itemId, quantity, maximumUnitPrice, minimumCashReserve);
			});
		}));
	}

	private CompletableFuture<Map<String, Object>> confirmPriceWarningIfPresent(
		int slot,
		int itemId,
		int quantity,
		int maximumUnitPrice,
		GenericClientActivityContext activityContext)
	{
		return waitUntil(
			() -> view.offerMatches(slot, itemId, quantity, maximumUnitPrice) ||
				view.priceWarningVisible(),
			"ge_offer_confirmation",
			VERIFY_ATTEMPTS).thenCompose(observed ->
		{
			if (!wasAccepted(observed))
			{
				return CompletableFuture.completedFuture(observed);
			}
			return clientRead(() -> view.offerMatches(
				slot, itemId, quantity, maximumUnitPrice)).thenCompose(placed ->
			{
				if (placed)
				{
					Map<String, Object> receipt = new LinkedHashMap<>();
					receipt.put("status", "complete");
					receipt.put("result", "ge_price_warning_not_shown");
					receipt.put("click_count", 0L);
					return CompletableFuture.completedFuture(receipt);
				}
				return clickPriceWarning(activityContext);
			});
		});
	}

	private CompletableFuture<Map<String, Object>> clickPriceWarning(GenericClientActivityContext activityContext)
	{
		return menuInput.interactDirect(view::resolvePriceWarning, activityContext);
	}

	private CompletableFuture<Map<String, Object>> ensureOfferIndex(GenericClientActivityContext activityContext)
	{
		return clientRead(() -> view.isVisible(InterfaceID.GeOffers.INDEX)).thenCompose(visible ->
		{
			if (visible)
			{
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "complete");
				receipt.put("result", "ge_offer_index_visible");
				receipt.put("click_count", 0L);
				return CompletableFuture.completedFuture(receipt);
			}
			return clickWidgetAction(
				InterfaceID.GeOffers.BACK,
				"Back",
				null,
				"return_to_offer_index",
				activityContext).thenCompose(back ->
			{
				if (!wasAccepted(back))
				{
					return CompletableFuture.completedFuture(back);
				}
				return waitUntil(
					() -> view.isVisible(InterfaceID.GeOffers.INDEX),
					"ge_offer_index",
					VERIFY_ATTEMPTS);
			});
		});
	}

	private CompletableFuture<Map<String, Object>> ensurePrice(
		int maximumUnitPrice,
		GenericClientActivityContext activityContext)
	{
		return clientRead(view::visibleSetupUnitPrice).thenCompose(currentPrice ->
		{
			if (currentPrice == maximumUnitPrice)
			{
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "complete");
				receipt.put("result", "ge_maximum_price_already_set");
				receipt.put("unit_price", (long) currentPrice);
				receipt.put("click_count", 0L);
				return CompletableFuture.completedFuture(receipt);
			}
			return clickAndType(
				InterfaceID.GeOffers.SETUP,
				"Enter price",
				Integer.toString(maximumUnitPrice),
				"ge_price",
				activityContext).thenCompose(receipt ->
			{
				if (!wasAccepted(receipt))
				{
					return CompletableFuture.completedFuture(receipt);
				}
				return waitUntil(
					() -> view.visibleSetupUnitPrice() == maximumUnitPrice,
					"ge_price_value",
					VERIFY_ATTEMPTS);
			});
		});
	}

	private CompletableFuture<Map<String, Object>> finishExistingOffer(
		int slot,
		int itemId,
		String itemName,
		int quantity,
		int maximumUnitPrice,
		long minimumCashReserve,
		String collectMode,
		GenericClientActivityContext activityContext)
	{
		return clientRead(() -> view.offerAt(slot)).thenCompose(existing ->
		{
			if (GenericClientGrandExchangeView.shouldReplaceZeroFill(
				existing, quantity, maximumUnitPrice))
			{
				return cancelUnfilledOffer(slot, activityContext).thenCompose(cancelled ->
				{
					if (!wasAccepted(cancelled))
					{
						return CompletableFuture.completedFuture(cancelled);
					}
					return placeOffer(
						slot,
						itemId,
						itemName,
						quantity,
						maximumUnitPrice,
						minimumCashReserve,
						collectMode,
						activityContext);
				});
			}
			if (!GenericClientGrandExchangeView.matchesRequestedOffer(
				existing, quantity, maximumUnitPrice))
			{
				return CompletableFuture.completedFuture(
					rejected("conflicting_existing_offer_for_item"));
			}
			return waitForPurchase(slot, itemId).thenCompose(purchase ->
			{
				List<Map<String, Object>> steps = new ArrayList<>();
				steps.add(purchase);
				if (!"complete".equals(purchase.get("status")))
				{
					return CompletableFuture.completedFuture(offerReceipt(
						purchase, steps, slot, itemId, quantity, maximumUnitPrice,
						minimumCashReserve));
				}
				return collect(slot, itemId, quantity, collectMode, activityContext).thenApply(collection ->
				{
					steps.add(collection);
					return offerReceipt(
						collection, steps, slot, itemId, quantity, maximumUnitPrice,
						minimumCashReserve);
				});
			});
		});
	}

	private CompletableFuture<Map<String, Object>> cancelUnfilledOffer(
		int slot,
		GenericClientActivityContext activityContext)
	{
		return ensureOfferIndex(activityContext).thenCompose(index ->
		{
			if (!wasAccepted(index))
			{
				return CompletableFuture.completedFuture(index);
			}
			return clickWidgetAction(
				view.offerWidgetId(slot),
				"Abort offer",
				null,
				"abort_unfilled_offer",
				activityContext).thenCompose(aborted ->
			{
				if (!wasAccepted(aborted))
				{
					return CompletableFuture.completedFuture(aborted);
				}
				return waitUntil(
					() ->
					{
						GrandExchangeOffer offer = view.offerAt(slot);
						return offer != null && offer.getState() == GrandExchangeOfferState.CANCELLED_BUY;
					},
					"ge_offer_cancelled",
					VERIFY_ATTEMPTS).thenCompose(cancelled ->
				{
					if (!wasAccepted(cancelled))
					{
						return CompletableFuture.completedFuture(cancelled);
					}
					return collectRefund(slot, activityContext);
				});
			});
		});
	}

	private CompletableFuture<Map<String, Object>> collectRefund(
		int slot,
		GenericClientActivityContext activityContext)
	{
		return clickWidgetAction(
			view.offerWidgetId(slot), "View offer", null, "view_cancelled_offer", activityContext)
			.thenCompose(viewReceipt ->
			{
				if (!wasAccepted(viewReceipt))
				{
					return CompletableFuture.completedFuture(viewReceipt);
				}
				return waitUntil(
					() -> view.isVisible(InterfaceID.GeOffers.DETAILS_COLLECT),
					"ge_cancelled_offer_details",
					VERIFY_ATTEMPTS).thenCompose(details ->
				{
					if (!wasAccepted(details))
					{
						return CompletableFuture.completedFuture(details);
					}
					return collectItem(ItemID.COINS, "items", activityContext).thenCompose(collected ->
					{
						if (!wasAccepted(collected))
						{
							return CompletableFuture.completedFuture(collected);
						}
						return waitUntil(
							() -> view.offerEmpty(slot),
							"ge_cancelled_offer_collected",
							VERIFY_ATTEMPTS);
					});
				});
			});
	}

	private CompletableFuture<Map<String, Object>> collect(
		int slot,
		int itemId,
		int quantity,
		String collectMode,
		GenericClientActivityContext activityContext)
	{
		return clientRead(() -> view.inventoryQuantity(itemId)).thenCompose(before ->
			clickWidgetAction(
				view.offerWidgetId(slot), "View offer", null, "view_completed_offer", activityContext)
				.thenCompose(viewReceipt ->
				{
					if (!wasAccepted(viewReceipt))
					{
						return CompletableFuture.completedFuture(viewReceipt);
					}
					return waitUntil(
						() -> view.isVisible(InterfaceID.GeOffers.DETAILS),
						"ge_offer_details",
						VERIFY_ATTEMPTS).thenCompose(details ->
					{
						if (!wasAccepted(details))
						{
							return CompletableFuture.completedFuture(details);
						}
						return collectCompletedOffer(
							slot, itemId, quantity, collectMode, before, activityContext);
					});
				}));
	}

	private CompletableFuture<Map<String, Object>> collectCompletedOffer(
		int slot,
		int itemId,
		int quantity,
		String collectMode,
		int before,
		GenericClientActivityContext activityContext)
	{
		return clientRead(() -> view.collectItemPresent(itemId)).thenCompose(itemPresent ->
		{
			CompletableFuture<Map<String, Object>> itemCollection = itemPresent
				? collectItem(itemId, collectMode, activityContext)
				: CompletableFuture.completedFuture(complete("ge_item_already_collected"));
			return itemCollection.thenCompose(collected ->
			{
				if (!wasAccepted(collected))
				{
					return CompletableFuture.completedFuture(collected);
				}
				return waitUntil(
					() -> view.inventoryQuantity(itemId) >= before + quantity ||
						view.offerEmpty(slot) || !view.collectItemPresent(itemId),
					"ge_collection",
					VERIFY_ATTEMPTS).thenCompose(itemCollected ->
				{
					if (!wasAccepted(itemCollected))
					{
						return CompletableFuture.completedFuture(itemCollected);
					}
					return clientRead(() -> view.offerEmpty(slot)).thenCompose(empty ->
					{
						if (empty)
						{
							return CompletableFuture.completedFuture(itemCollected);
						}
						return collectItem(ItemID.COINS, "items", activityContext).thenCompose(refund ->
						{
							if (!wasAccepted(refund))
							{
								return CompletableFuture.completedFuture(refund);
							}
							return waitUntil(
								() -> view.offerEmpty(slot),
								"ge_refund_collection",
								VERIFY_ATTEMPTS);
						});
					});
				});
			});
		});
	}

	private CompletableFuture<Map<String, Object>> collectItem(
		int itemId,
		String collectMode,
		GenericClientActivityContext activityContext)
	{
		return menuInput.interact(
			() -> view.resolveCollectItem(itemId, collectMode), activityContext);
	}

	private static String collectMode(String requested)
	{
		String mode = requested == null ? "items" : requested.trim().toLowerCase(Locale.ROOT);
		if (!"items".equals(mode) && !"notes".equals(mode) && !"bank".equals(mode))
		{
			throw new IllegalArgumentException(
				"ge.buy collect_mode must be items, notes, or bank");
		}
		return mode;
	}

	private CompletableFuture<Map<String, Object>> clickSearchResult(
		int itemId,
		GenericClientActivityContext activityContext)
	{
		return menuInput.interact(() -> view.resolveSearchResult(itemId), activityContext);
	}

	private CompletableFuture<Map<String, Object>> clickWidgetAction(
		int rootId,
		String action,
		Integer itemId,
		String description,
		GenericClientActivityContext activityContext)
	{
		return menuInput.interact(
			() -> view.resolveWidgetAction(rootId, action, itemId, description),
			activityContext);
	}

	private CompletableFuture<Map<String, Object>> clickBuyOffer(
		int slot,
		GenericClientActivityContext activityContext)
	{
		return menuInput.interact(() -> view.resolveBuyOffer(slot), activityContext);
	}

	private CompletableFuture<Map<String, Object>> clickAndType(
		int rootId,
		String action,
		String text,
		String description,
		GenericClientActivityContext activityContext)
	{
		Map<String, Object> outerBefore = new LinkedHashMap<>();
		return behavior.beforeAction(activityContext).thenCompose(before ->
		{
			outerBefore.putAll(before);
			return menuInput.interact(
				() -> view.resolveWidgetAction(rootId, action, null, description),
				GenericClientActivityContext.none());
		}).thenCompose(click ->
		{
			if (!wasAccepted(click))
			{
				return CompletableFuture.completedFuture(click);
			}
			return waitUntil(
				() -> view.inputModeIs(7),
				description + "_input",
				VERIFY_ATTEMPTS).thenCompose(prompt ->
			{
				if (!wasAccepted(prompt))
				{
					return CompletableFuture.completedFuture(prompt);
				}
				return keyboard.typeAndEnter(text, INPUT_SETTLE_MILLIS)
					.thenCompose(ignored -> waitUntil(
						() -> view.inputModeIs(0),
						description + "_accepted",
						VERIFY_ATTEMPTS))
					.thenCompose(accepted ->
					{
						if (!wasAccepted(accepted))
						{
							return CompletableFuture.completedFuture(accepted);
						}
						return behavior.afterAction(activityContext).thenApply(after ->
						{
							Map<String, Object> receipt = new LinkedHashMap<>();
							receipt.put("status", "complete");
							receipt.put("result", description + "_submitted");
							receipt.put("menu_receipt", click);
							receipt.put("behavior_before", outerBefore);
							receipt.put("behavior_after", after);
							receipt.put("click_count", clickCount(click));
							return receipt;
						});
					});
			});
		});
	}

	private CompletableFuture<Map<String, Object>> typeWithBehavior(
		String text,
		boolean submit,
		String description,
		GenericClientActivityContext activityContext)
	{
		return behavior.beforeAction(activityContext).thenCompose(before ->
			(submit ? keyboard.typeAndEnter(text) : keyboard.type(text)).thenCompose(ignored ->
				behavior.afterAction(activityContext).thenApply(after ->
				{
					Map<String, Object> receipt = new LinkedHashMap<>();
					receipt.put("status", "complete");
					receipt.put("result", description + "_submitted");
					receipt.put("behavior_before", before);
					receipt.put("behavior_after", after);
					receipt.put("click_count", 0L);
					return receipt;
				})));
	}

	private CompletableFuture<Map<String, Object>> waitForPurchase(int slot, int itemId)
	{
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		pollPurchase(slot, itemId, BUY_ATTEMPTS, result);
		return result;
	}

	private void pollPurchase(
		int slot,
		int itemId,
		int attemptsRemaining,
		CompletableFuture<Map<String, Object>> result)
	{
		clientRead(() -> view.offerAt(slot)).whenComplete((offer, error) ->
		{
			if (error != null)
			{
				result.complete(rejected(rootMessage(error)));
				return;
			}
			if (offer == null || offer.getItemId() != itemId)
			{
				result.complete(rejected("matching_buy_offer_disappeared"));
				return;
			}
			if (offer.getState() == GrandExchangeOfferState.BOUGHT)
			{
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "complete");
				receipt.put("result", "ge_purchase_complete");
				receipt.put("quantity_bought", (long) offer.getQuantitySold());
				receipt.put("spent", (long) offer.getSpent());
				receipt.put("click_count", 0L);
				result.complete(receipt);
				return;
			}
			if (offer.getState() != GrandExchangeOfferState.BUYING)
			{
				result.complete(rejected("unexpected_buy_offer_state:" + offer.getState().name()));
				return;
			}
			if (attemptsRemaining <= 1)
			{
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "placed");
				receipt.put("result", "ge_offer_pending");
				receipt.put("quantity_bought", (long) offer.getQuantitySold());
				receipt.put("spent", (long) offer.getSpent());
				receipt.put("click_count", 0L);
				result.complete(receipt);
				return;
			}
			schedule(() -> pollPurchase(slot, itemId, attemptsRemaining - 1, result));
		});
	}

	private CompletableFuture<Map<String, Object>> waitUntil(
		Supplier<Boolean> condition,
		String description,
		int attempts)
	{
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		poll(condition, description, attempts, result);
		return result;
	}

	private void poll(
		Supplier<Boolean> condition,
		String description,
		int attemptsRemaining,
		CompletableFuture<Map<String, Object>> result)
	{
		if (!running.get() || closed)
		{
			result.complete(rejected("cancelled_while_waiting_for_" + description));
			return;
		}
		clientRead(condition).whenComplete((satisfied, error) ->
		{
			if (error != null)
			{
				result.complete(rejected(rootMessage(error)));
			}
			else if (Boolean.TRUE.equals(satisfied))
			{
				Map<String, Object> receipt = new LinkedHashMap<>();
				receipt.put("status", "complete");
				receipt.put("result", description + "_verified");
				receipt.put("click_count", 0L);
				result.complete(receipt);
			}
			else if (attemptsRemaining <= 1)
			{
				result.complete(rejected(description + "_verification_timeout"));
			}
			else
			{
				schedule(() -> poll(condition, description, attemptsRemaining - 1, result));
			}
		});
	}

	private void schedule(Runnable runnable)
	{
		ScheduledFuture<?> future = executor.schedule(runnable, VERIFY_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
		pending.add(future);
	}

	private <T> CompletableFuture<T> clientRead(Supplier<T> reader)
	{
		CompletableFuture<T> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			try
			{
				result.complete(reader.get());
			}
			catch (RuntimeException exception)
			{
				result.completeExceptionally(exception);
			}
		});
		return result;
	}

	private static CompletableFuture<List<Map<String, Object>>> append(
		CompletableFuture<List<Map<String, Object>>> flow,
		Supplier<CompletableFuture<Map<String, Object>>> step)
	{
		return flow.thenCompose(receipts -> step.get().thenApply(receipt ->
		{
			receipts.add(receipt);
			if (!wasAccepted(receipt))
			{
				throw new IllegalStateException(String.valueOf(receipt.get("result")));
			}
			return receipts;
		}));
	}

	private static boolean wasAccepted(Map<String, Object> receipt)
	{
		if (receipt == null)
		{
			return false;
		}
		Object status = receipt.get("status");
		return "dispatched".equals(status) || "complete".equals(status);
	}

	private static long clickCount(Map<String, Object> receipt)
	{
		Object value = receipt.get("click_count");
		return value instanceof Number ? ((Number) value).longValue() : 0L;
	}

	private static Map<String, Object> offerReceipt(
		Map<String, Object> terminal,
		List<Map<String, Object>> steps,
		int slot,
		int itemId,
		int quantity,
		int maximumUnitPrice,
		long minimumCashReserve)
	{
		Map<String, Object> receipt = new LinkedHashMap<>(terminal);
		receipt.put("slot", (long) slot);
		receipt.put("item_id", (long) itemId);
		receipt.put("quantity", (long) quantity);
		receipt.put("maximum_unit_price", (long) maximumUnitPrice);
		receipt.put("minimum_cash_reserve", minimumCashReserve);
		receipt.put("steps", steps);
		long clicks = 0;
		for (Map<String, Object> step : steps)
		{
			clicks += clickCount(step);
		}
		receipt.put("click_count", clicks);
		return receipt;
	}

	private static Map<String, Object> rejected(String reason)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "rejected");
		receipt.put("result", reason);
		receipt.put("click_count", 0L);
		return receipt;
	}

	private static Map<String, Object> complete(String result)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "complete");
		receipt.put("result", result);
		receipt.put("click_count", 0L);
		return receipt;
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
			throw new IllegalArgumentException("ge.buy requires a non-negative item id and item name");
		}
		if (quantity < 1 || maximumUnitPrice < 1)
		{
			throw new IllegalArgumentException("ge.buy quantity and maximum unit price must be positive");
		}
		if ((long) quantity * maximumUnitPrice > Integer.MAX_VALUE)
		{
			throw new IllegalArgumentException("ge.buy maximum total cost exceeds the coin stack limit");
		}
		if (minimumCashReserve < HARD_MINIMUM_CASH_RESERVE)
		{
			throw new IllegalArgumentException("ge.buy minimum cash reserve cannot be below 5000000");
		}
	}

	private void finish(Map<String, Object> receipt)
	{
		if (!running.getAndSet(false))
		{
			return;
		}
		for (ScheduledFuture<?> future : pending)
		{
			future.cancel(false);
		}
		pending.clear();
		reporter.accept("GE_BUY_COMPLETED status=" + receipt.get("status") +
			" result=" + receipt.get("result") + " clicks=" + receipt.get("click_count"));
		CompletableFuture<Map<String, Object>> result = activeResult;
		activeResult = null;
		if (result != null)
		{
			result.complete(receipt);
		}
	}

	private static String rootMessage(Throwable error)
	{
		Throwable current = error;
		while (current.getCause() != null)
		{
			current = current.getCause();
		}
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}

	@Override
	public void close()
	{
		closed = true;
		cancel("input_closed");
	}

}
