package com.genericclient;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.Text;

final class GenericClientGrandExchangeInput implements AutoCloseable
{
	static final long HARD_MINIMUM_CASH_RESERVE = 5_000_000L;
	private static final long VERIFY_INTERVAL_MILLIS = 200L;
	private static final long INPUT_SETTLE_MILLIS = 300L;
	private static final int VERIFY_ATTEMPTS = 50;
	private static final int BUY_ATTEMPTS = 300;
	private static final int[] OFFER_WIDGETS =
	{
		InterfaceID.GeOffers.INDEX_0,
		InterfaceID.GeOffers.INDEX_1,
		InterfaceID.GeOffers.INDEX_2,
		InterfaceID.GeOffers.INDEX_3,
		InterfaceID.GeOffers.INDEX_4,
		InterfaceID.GeOffers.INDEX_5,
		InterfaceID.GeOffers.INDEX_6,
		InterfaceID.GeOffers.INDEX_7
	};

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientMenuInput menuInput;
	private final GenericClientSyntheticKeyboard keyboard;
	private final GenericClientBehaviorController behavior;
	private final Supplier<GenericClientSnapshot> snapshotSupplier;
	private final java.util.function.Consumer<String> reporter;
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
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.menuInput = menuInput;
		this.keyboard = keyboard;
		this.behavior = behavior;
		this.snapshotSupplier = snapshotSupplier;
		this.reporter = reporter;
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

		clientRead(() -> preflight(
			itemId, quantity, maximumUnitPrice, minimumCashReserve)).thenCompose(preflight ->
		{
			if (preflight.rejection != null)
			{
				return CompletableFuture.completedFuture(rejected(preflight.rejection));
			}
			if (preflight.existingSlot >= 0)
			{
				return finishExistingOffer(
					preflight.existingSlot,
					itemId,
					itemName.trim(),
					quantity,
					maximumUnitPrice,
					minimumCashReserve,
					collectMode,
					activityContext);
			}
			return placeOffer(
				preflight.emptySlot,
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
			this::itemSearchVisible, "ge_item_search", VERIFY_ATTEMPTS));
		flow = append(flow, () -> typeWithBehavior(itemName, false, "ge_item_search_text", activityContext));
		flow = append(flow, () -> waitUntil(
			() -> findItemSearchResult(itemId) != null, "ge_exact_search_result", VERIFY_ATTEMPTS));
		flow = append(flow, () -> clickSearchResult(itemId, activityContext));
		flow = append(flow, () -> waitUntil(
			() -> setupContainsItem(itemId), "ge_offer_item", VERIFY_ATTEMPTS));
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
			() -> offerMatches(slot, itemId, quantity, maximumUnitPrice),
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
			() -> offerMatches(slot, itemId, quantity, maximumUnitPrice) ||
				priceWarningTarget() != null,
			"ge_offer_confirmation",
			VERIFY_ATTEMPTS).thenCompose(observed ->
		{
			if (!wasAccepted(observed))
			{
				return CompletableFuture.completedFuture(observed);
			}
			return clientRead(() -> offerMatches(
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
		return menuInput.interactDirect(() ->
		{
			Widget target = priceWarningTarget();
			return targetForWidget(target, "Yes", null, "confirm_price_warning");
		}, activityContext);
	}

	private Widget priceWarningTarget()
	{
		Widget setup = visibleWidget(InterfaceID.GeOffers.SETUP);
		Widget index = visibleWidget(InterfaceID.GeOffers.INDEX);
		Rectangle scope = priceWarningScope(
			resolvedWidgetBounds(setup), resolvedWidgetBounds(index));
		if (scope == null)
		{
			return null;
		}
		Widget popup = visibleWidget(InterfaceID.Popupoverlay.UNIVERSE);
		Widget target = findByTextWithin(popup, "Yes", scope);
		if (target == null)
		{
			target = findByAction(popup, "Yes", null);
		}
		if (target != null && inside(target, scope))
		{
			return target;
		}
		target = findByTextAcrossVisibleRoots("Yes", scope);
		return target == null
			? findByActionAcrossVisibleRoots("Yes", scope)
			: target;
	}

	static Rectangle priceWarningScope(Rectangle setupBounds, Rectangle indexBounds)
	{
		return setupBounds == null ? indexBounds : setupBounds;
	}

	private Widget findByTextAcrossVisibleRoots(String text, Rectangle scope)
	{
		Widget[] roots = client.getWidgetRoots();
		if (roots == null)
		{
			return null;
		}
		for (Widget root : roots)
		{
			Widget candidate = findByTextWithin(root, text, scope);
			if (candidate != null)
			{
				return candidate;
			}
		}
		return null;
	}

	static Widget findByTextWithin(Widget root, String text, Rectangle scope)
	{
		for (Widget candidate : descendants(root))
		{
			if (inside(candidate, scope) && matchesWidgetText(candidate, text))
			{
				return candidate;
			}
		}
		return null;
	}

	static boolean matchesWidgetText(Widget widget, String expected)
	{
		return widget != null && (expected.equalsIgnoreCase(clean(widget.getText())) ||
			expected.equalsIgnoreCase(clean(widget.getName())));
	}

	private Widget findByActionAcrossVisibleRoots(String action, Rectangle scope)
	{
		Widget[] roots = client.getWidgetRoots();
		if (roots == null)
		{
			return null;
		}
		List<Widget> candidates = new ArrayList<>();
		for (Widget root : roots)
		{
			candidates.addAll(descendants(root));
		}
		for (Widget candidate : candidates)
		{
			if (inside(candidate, scope) && hasDeclaredAction(candidate, action))
			{
				return candidate;
			}
		}
		return null;
	}

	private static boolean inside(Widget widget, Rectangle scope)
	{
		Rectangle bounds = widget.getBounds();
		return bounds != null && scope != null && scope.contains(
			bounds.x + bounds.width / 2,
			bounds.y + bounds.height / 2);
	}

	private CompletableFuture<Map<String, Object>> ensureOfferIndex(GenericClientActivityContext activityContext)
	{
		return clientRead(() -> visibleWidget(InterfaceID.GeOffers.INDEX) != null).thenCompose(visible ->
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
					() -> visibleWidget(InterfaceID.GeOffers.INDEX) != null,
					"ge_offer_index",
					VERIFY_ATTEMPTS);
			});
		});
	}

	private CompletableFuture<Map<String, Object>> ensurePrice(
		int maximumUnitPrice,
		GenericClientActivityContext activityContext)
	{
		return clientRead(this::visibleSetupUnitPrice).thenCompose(currentPrice ->
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
					() -> visibleSetupUnitPrice() == maximumUnitPrice,
					"ge_price_value",
					VERIFY_ATTEMPTS);
			});
		});
	}

	private int visibleSetupUnitPrice()
	{
		for (Widget widget : descendants(visibleWidget(InterfaceID.GeOffers.SETUP)))
		{
			String text = clean(widget.getText());
			if (!text.endsWith(" coins"))
			{
				continue;
			}
			String number = text.substring(0, text.length() - " coins".length()).replace(",", "");
			try
			{
				return Integer.parseInt(number);
			}
			catch (NumberFormatException ignored)
			{
				// Continue to the next visible value.
			}
		}
		return -1;
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
		return clientRead(() -> offerAt(slot)).thenCompose(existing ->
		{
			if (shouldReplaceZeroFill(existing, quantity, maximumUnitPrice))
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
			if (!matchesRequestedOffer(existing, quantity, maximumUnitPrice))
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

	static boolean shouldReplaceZeroFill(
		GrandExchangeOffer offer,
		int quantity,
		int maximumUnitPrice)
	{
		return offer != null && offer.getState() == GrandExchangeOfferState.BUYING &&
			offer.getQuantitySold() == 0 &&
			(offer.getTotalQuantity() != quantity || offer.getPrice() != maximumUnitPrice);
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
				OFFER_WIDGETS[slot],
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
						GrandExchangeOffer offer = offerAt(slot);
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
			OFFER_WIDGETS[slot], "View offer", null, "view_cancelled_offer", activityContext)
			.thenCompose(view ->
			{
				if (!wasAccepted(view))
				{
					return CompletableFuture.completedFuture(view);
				}
				return waitUntil(
					() -> visibleWidget(InterfaceID.GeOffers.DETAILS_COLLECT) != null,
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
							() -> offerEmpty(slot),
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
		return clientRead(() -> inventoryQuantity(itemId)).thenCompose(before ->
			clickWidgetAction(
				OFFER_WIDGETS[slot], "View offer", null, "view_completed_offer", activityContext)
				.thenCompose(view ->
				{
					if (!wasAccepted(view))
					{
						return CompletableFuture.completedFuture(view);
					}
					return waitUntil(
						() -> visibleWidget(InterfaceID.GeOffers.DETAILS) != null,
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
		return clientRead(() -> collectItemPresent(itemId)).thenCompose(itemPresent ->
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
					() -> inventoryQuantity(itemId) >= before + quantity ||
						offerEmpty(slot) || !collectItemPresent(itemId),
					"ge_collection",
					VERIFY_ATTEMPTS).thenCompose(itemCollected ->
				{
					if (!wasAccepted(itemCollected))
					{
						return CompletableFuture.completedFuture(itemCollected);
					}
					return clientRead(() -> offerEmpty(slot)).thenCompose(empty ->
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
								() -> offerEmpty(slot),
								"ge_refund_collection",
								VERIFY_ATTEMPTS);
						});
					});
				});
			});
		});
	}

	private boolean collectItemPresent(int itemId)
	{
		Widget root = visibleWidget(InterfaceID.GeOffers.DETAILS_COLLECT);
		if (root == null)
		{
			return false;
		}
		for (Widget widget : descendants(root))
		{
			if (widget.getItemId() == itemId)
			{
				return true;
			}
		}
		return false;
	}

	private CompletableFuture<Map<String, Object>> collectItem(
		int itemId,
		String collectMode,
		GenericClientActivityContext activityContext)
	{
		return menuInput.interact(() ->
		{
			Widget root = visibleWidget(InterfaceID.GeOffers.DETAILS_COLLECT);
			Widget item = null;
			for (Widget widget : descendants(root))
			{
				if (widget.getItemId() == itemId)
				{
					item = widget;
					break;
				}
			}
			if (item == null)
			{
				return GenericClientMenuInput.Resolution.rejected("collect_offer_not_visible");
			}
			Widget target = item;
			String action = collectAction(target, collectMode);
			for (Widget parent = item.getParent(); action == null && parent != null;
				parent = parent.getParent())
			{
				target = parent;
				action = collectAction(target, collectMode);
				if (parent == root)
				{
					break;
				}
			}
			if (action == null)
			{
				Rectangle itemBounds = item.getBounds();
				for (Widget candidate : descendants(root))
				{
					String candidateAction = collectAction(candidate, collectMode);
					Rectangle candidateBounds = candidate.getBounds();
					if (candidateAction != null && candidateBounds != null && itemBounds != null &&
						candidateBounds.contains(
							itemBounds.x + itemBounds.width / 2,
							itemBounds.y + itemBounds.height / 2))
					{
						target = candidate;
						action = candidateAction;
						break;
					}
				}
			}
			if (action == null)
			{
				reportWidgetTree("collect_offer", root);
			}
			return action == null
				? GenericClientMenuInput.Resolution.rejected("collect_offer_has_no_inventory_action")
				: targetForWidget(target, action, itemId, "collect_offer");
		}, activityContext);
	}

	static String collectAction(Widget widget, String collectMode)
	{
		if ("bank".equals(collectMode))
		{
			return hasAction(widget, "Bank") ? "Bank" : null;
		}
		if ("notes".equals(collectMode))
		{
			return hasAction(widget, "Collect-notes") ? "Collect-notes" : null;
		}
		if (hasAction(widget, "Collect-items"))
		{
			return "Collect-items";
		}
		if (hasAction(widget, "Collect-item"))
		{
			return "Collect-item";
		}
		return hasAction(widget, "Collect") ? "Collect" : null;
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
		return menuInput.interact(() ->
		{
			Widget widget = findItemSearchResult(itemId);
			Widget item = findItemSearchResultItem(itemId);
			return targetForSearchResult(widget, item, itemId);
		}, activityContext);
	}

	private GenericClientMenuInput.Resolution targetForSearchResult(
		Widget widget,
		Widget item,
		int itemId)
	{
		if (!geOpen())
		{
			return GenericClientMenuInput.Resolution.rejected("grand_exchange_not_open");
		}
		if (widget == null)
		{
			return GenericClientMenuInput.Resolution.rejected("ge_search_result_not_visible");
		}
		Point point = GenericClientMenuInput.randomPointInside(
			searchResultHitbox(widget, item), client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected("ge_search_result_not_clickable");
		}
		String itemName = clean(widget.getName());
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "ge_search_result");
		value.put("item_id", (long) itemId);
		value.put("item_name", itemName);
		value.put("widget_id", (long) widget.getId());
		value.put("widget_index", (long) widget.getIndex());
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			"Select",
			"ge_search_result:" + itemId,
			value,
			entry -> menuMatchesAction(entry, "Select") &&
				itemName.equalsIgnoreCase(clean(entry.getTarget()))));
	}

	private CompletableFuture<Map<String, Object>> clickWidgetAction(
		int rootId,
		String action,
		Integer itemId,
		String description,
		GenericClientActivityContext activityContext)
	{
		return menuInput.interact(() ->
		{
			Widget root = visibleWidget(rootId);
			Widget target = findByAction(root, action, itemId);
			if (target == null)
			{
				reportWidgetTree(description, root);
			}
			if (target == null && root != null && itemId == null)
			{
				target = root;
			}
			return targetForWidget(target, action, itemId, description);
		}, activityContext);
	}

	private CompletableFuture<Map<String, Object>> clickBuyOffer(
		int slot,
		GenericClientActivityContext activityContext)
	{
		return menuInput.interact(() ->
		{
			Widget slotWidget = visibleWidget(OFFER_WIDGETS[slot]);
			if (!geOpen())
			{
				return GenericClientMenuInput.Resolution.rejected("grand_exchange_not_open");
			}
			if (slotWidget == null)
			{
				return GenericClientMenuInput.Resolution.rejected("open_buy_offer_not_visible");
			}
			Rectangle hitbox = buyOfferHitbox(resolvedWidgetBounds(slotWidget));
			Point point = GenericClientMenuInput.randomPointInside(
				hitbox, client.getCanvasWidth(), client.getCanvasHeight());
			if (point == null)
			{
				return GenericClientMenuInput.Resolution.rejected("open_buy_offer_not_clickable");
			}

			Map<String, Object> value = new LinkedHashMap<>();
			value.put("kind", "ge_offer_slot");
			value.put("slot", (long) slot);
			value.put("widget_id", (long) slotWidget.getId());
			value.put("widget_index", (long) slotWidget.getIndex());
			return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
				point,
				"Buy",
				"open_buy_offer",
				value,
				entry -> menuMatchesAction(entry, "Buy")));
		}, activityContext);
	}

	static Rectangle buyOfferHitbox(Rectangle slotBounds)
	{
		if (slotBounds == null || slotBounds.width < 1 || slotBounds.height < 1)
		{
			return slotBounds;
		}
		return new Rectangle(
			slotBounds.x + Math.max(1, slotBounds.width * 18 / 100),
			slotBounds.y + Math.max(1, slotBounds.height * 56 / 100),
			Math.max(1, slotBounds.width * 18 / 100),
			Math.max(1, slotBounds.height * 20 / 100));
	}

	private void reportWidgetTree(String description, Widget root)
	{
		List<Widget> widgets = descendants(root);
		reporter.accept("GE_WIDGET_TREE description=" + description + " count=" + widgets.size());
		for (int index = 0; index < Math.min(50, widgets.size()); index++)
		{
			Widget widget = widgets.get(index);
			reporter.accept("GE_WIDGET description=" + description +
				" id=" + widget.getId() +
				" index=" + widget.getIndex() +
				" item=" + widget.getItemId() +
				" text=" + clean(widget.getText()) +
				" name=" + clean(widget.getName()) +
				" actions=" + Arrays.toString(widget.getActions()) +
				" bounds=" + widget.getBounds());
		}
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
			return menuInput.interact(() ->
			{
				Widget target = findByAction(visibleWidget(rootId), action, null);
				return targetForWidget(target, action, null, description);
			}, GenericClientActivityContext.none());
		}).thenCompose(click ->
		{
			if (!wasAccepted(click))
			{
				return CompletableFuture.completedFuture(click);
			}
			return waitUntil(
				() -> client.getVarcIntValue(VarClientID.MESLAYERMODE) == 7,
				description + "_input",
				VERIFY_ATTEMPTS).thenCompose(prompt ->
			{
				if (!wasAccepted(prompt))
				{
					return CompletableFuture.completedFuture(prompt);
				}
				return keyboard.typeAndEnter(text, INPUT_SETTLE_MILLIS)
					.thenCompose(ignored -> waitUntil(
						() -> client.getVarcIntValue(VarClientID.MESLAYERMODE) == 0,
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

	private GenericClientMenuInput.Resolution targetForWidget(
		Widget widget,
		String action,
		Integer itemId,
		String description)
	{
		if (!geOpen())
		{
			return GenericClientMenuInput.Resolution.rejected("grand_exchange_not_open");
		}
		if (widget == null)
		{
			return GenericClientMenuInput.Resolution.rejected(description + "_not_visible");
		}
		Point point = GenericClientMenuInput.randomPointInside(
			resolvedWidgetBounds(widget), client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected(description + "_not_clickable");
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "ge_widget");
		value.put("widget_id", (long) widget.getId());
		value.put("widget_index", (long) widget.getIndex());
		if (itemId != null)
		{
			value.put("item_id", itemId.longValue());
		}
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			action,
			description,
			value,
			entry -> menuMatchesAction(entry, action) &&
				(itemId == null
					? matchesWidget(entry, widget)
					: matchesWidget(entry, widget) ||
						matchesItem(entry, itemId) && matchesWidgetGroup(entry, widget))));
	}

	private Widget findItemSearchResult(int itemId)
	{
		Widget root = visibleWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		List<Widget> widgets = descendants(root);
		boolean exactItemPresent = false;
		for (Widget widget : widgets)
		{
			if (widget.getItemId() == itemId)
			{
				exactItemPresent = true;
				break;
			}
		}
		if (!exactItemPresent)
		{
			return null;
		}
		net.runelite.api.ItemComposition composition = client.getItemDefinition(itemId);
		String expectedName = composition == null ? "" : clean(composition.getName());
		for (Widget widget : widgets)
		{
			if (hasAction(widget, "Select") && expectedName.equalsIgnoreCase(clean(widget.getName())))
			{
				return widget;
			}
		}
		return null;
	}

	private Widget findItemSearchResultItem(int itemId)
	{
		Widget root = visibleWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		for (Widget widget : descendants(root))
		{
			if (widget.getItemId() == itemId)
			{
				return widget;
			}
		}
		return null;
	}

	private boolean setupContainsItem(int itemId)
	{
		for (Widget widget : descendants(visibleWidget(InterfaceID.GeOffers.SETUP)))
		{
			if (widget.getItemId() == itemId)
			{
				return true;
			}
		}
		return false;
	}

	private static Widget findByAction(Widget root, String action, Integer itemId)
	{
		List<Widget> widgets = descendants(root);
		for (Widget widget : widgets)
		{
			if ((itemId == null || widget.getItemId() == itemId) &&
				hasDeclaredAction(widget, action))
			{
				return widget;
			}
		}
		for (Widget widget : widgets)
		{
			if ((itemId == null || widget.getItemId() == itemId) && hasAction(widget, action))
			{
				return widget;
			}
		}
		return null;
	}

	private static List<Widget> descendants(Widget root)
	{
		if (root == null)
		{
			return Collections.emptyList();
		}
		List<Widget> result = new ArrayList<>();
		Set<Widget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Widget> queue = new ArrayDeque<>();
		queue.add(root);
		while (!queue.isEmpty() && result.size() < 512)
		{
			Widget widget = queue.removeFirst();
			if (widget == null || !seen.add(widget))
			{
				continue;
			}
			if (!widget.isHidden() && !widget.isSelfHidden() && widget.getBounds() != null &&
				widget.getBounds().width > 0 && widget.getBounds().height > 0)
			{
				result.add(widget);
			}
			enqueueChildren(queue, widget.getChildren());
			enqueueChildren(queue, widget.getDynamicChildren());
			enqueueChildren(queue, widget.getStaticChildren());
			enqueueChildren(queue, widget.getNestedChildren());
		}
		return result;
	}

	private static void enqueueChildren(ArrayDeque<Widget> queue, Widget[] children)
	{
		if (children == null)
		{
			return;
		}
		for (Widget child : children)
		{
			if (child != null)
			{
				queue.addLast(child);
			}
		}
	}

	private static boolean hasAction(Widget widget, String action)
	{
		if (hasDeclaredAction(widget, action))
		{
			return true;
		}
		String name = clean(widget.getName());
		String text = clean(widget.getText());
		return matchesActionText(name, action) || matchesActionText(text, action);
	}

	private static boolean hasDeclaredAction(Widget widget, String action)
	{
		String[] actions = widget.getActions();
		if (actions != null)
		{
			for (String candidate : actions)
			{
				if (matchesActionText(candidate, action))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static boolean menuMatchesAction(MenuEntry entry, String action)
	{
		return matchesActionText(entry.getOption(), action) ||
			matchesActionText(clean(entry.getOption()) + " " + clean(entry.getTarget()), action);
	}

	static boolean matchesActionText(String candidate, String action)
	{
		String normalizedCandidate = clean(candidate);
		if (normalizedCandidate.equalsIgnoreCase(action))
		{
			return true;
		}
		return "Buy".equalsIgnoreCase(action) &&
			"Create Buy offer".equalsIgnoreCase(normalizedCandidate);
	}

	private static String clean(String value)
	{
		return value == null ? "" : Text.removeTags(value).trim();
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
		clientRead(() -> offerAt(slot)).whenComplete((offer, error) ->
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

	private Preflight preflight(
		int itemId,
		int quantity,
		int maximumUnitPrice,
		long minimumCashReserve)
	{
		if (!geOpen())
		{
			return Preflight.rejected("grand_exchange_not_open");
		}
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null || offers.length < OFFER_WIDGETS.length)
		{
			return Preflight.rejected("grand_exchange_offers_unavailable");
		}
		int empty = -1;
		for (int slot = 0; slot < OFFER_WIDGETS.length; slot++)
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
				offer.getState() == GrandExchangeOfferState.BOUGHT)
			{
				return Preflight.existing(slot);
			}
			return Preflight.rejected("conflicting_existing_offer_for_item");
		}

		long maximumSpend = (long) quantity * maximumUnitPrice;
		Map<?, ?> cash = cashSnapshot();
		String cashRejection = cashRejection(cash, maximumSpend, minimumCashReserve);
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

	private boolean geOpen()
	{
		Widget root = client.getWidget(InterfaceID.GeOffers.UNIVERSE);
		return client.getGameState() == GameState.LOGGED_IN && root != null &&
			!root.isHidden() && !root.isSelfHidden();
	}

	private boolean itemSearchVisible()
	{
		return visibleWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS) != null;
	}

	private Widget visibleWidget(int id)
	{
		Widget widget = client.getWidget(id);
		if (widget == null || widget.isHidden() || widget.isSelfHidden())
		{
			return null;
		}
		Rectangle bounds = widget.getBounds();
		return bounds != null && bounds.width > 0 && bounds.height > 0 ? widget : null;
	}

	private boolean offerMatches(int slot, int itemId, int quantity, int maximumUnitPrice)
	{
		GrandExchangeOffer offer = offerAt(slot);
		return offer != null && offer.getItemId() == itemId &&
			offer.getTotalQuantity() == quantity && offer.getPrice() <= maximumUnitPrice &&
			(offer.getState() == GrandExchangeOfferState.BUYING ||
				offer.getState() == GrandExchangeOfferState.BOUGHT);
	}

	private GrandExchangeOffer offerAt(int slot)
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		return offers == null || slot < 0 || slot >= offers.length ? null : offers[slot];
	}

	private boolean offerEmpty(int slot)
	{
		GrandExchangeOffer offer = offerAt(slot);
		return offer == null || offer.getState() == GrandExchangeOfferState.EMPTY;
	}

	private int inventoryQuantity(int itemId)
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		return inventory == null ? 0 : inventory.count(itemId);
	}

	private static boolean matchesWidget(MenuEntry entry, Widget target)
	{
		Widget widget = entry.getWidget();
		if (widget != null)
		{
			return widget.getId() == target.getId() && widget.getIndex() == target.getIndex();
		}
		return entry.getParam1() == target.getId() &&
			(target.getIndex() < 0 || entry.getParam0() == target.getIndex());
	}

	private static boolean matchesWidgetGroup(MenuEntry entry, Widget target)
	{
		Widget widget = entry.getWidget();
		return widget != null && widget.getId() == target.getId();
	}

	private static boolean matchesItem(MenuEntry entry, int itemId)
	{
		Widget widget = entry.getWidget();
		return entry.getItemId() == itemId || widget != null && widget.getItemId() == itemId;
	}

	static Rectangle resolvedWidgetBounds(Widget widget)
	{
		Rectangle bounds = widget == null ? null : widget.getBounds();
		if (bounds == null || bounds.width < 1 || bounds.height < 1 ||
			bounds.x >= 0 && bounds.y >= 0)
		{
			return bounds;
		}
		int relativeX = widget.getRelativeX();
		int relativeY = widget.getRelativeY();
		Widget parent = widget.getParent();
		while (parent != null)
		{
			Rectangle parentBounds = parent.getBounds();
			if (parentBounds != null && parentBounds.x >= 0 && parentBounds.y >= 0)
			{
				return new Rectangle(
					parentBounds.x + relativeX,
					parentBounds.y + relativeY,
					bounds.width,
					bounds.height);
			}
			relativeX += parent.getRelativeX();
			relativeY += parent.getRelativeY();
			parent = parent.getParent();
		}
		return bounds;
	}

	static Rectangle searchResultHitbox(Widget actionWidget, Widget itemWidget)
	{
		Rectangle itemBounds = resolvedWidgetBounds(itemWidget);
		return itemBounds != null && itemBounds.width > 0 && itemBounds.height > 0
			? itemBounds
			: resolvedWidgetBounds(actionWidget);
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

	private static long longValue(Object value)
	{
		return value instanceof Number ? ((Number) value).longValue() : 0L;
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

	private static final class Preflight
	{
		private final int emptySlot;
		private final int existingSlot;
		private final String rejection;

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

		private static Preflight rejected(String reason)
		{
			return new Preflight(-1, -1, reason);
		}
	}
}
