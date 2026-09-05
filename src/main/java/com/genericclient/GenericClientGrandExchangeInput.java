package com.genericclient;

import static com.genericclient.GenericClientErrors.rootMessage;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

final class GenericClientGrandExchangeInput
implements AutoCloseable {
    private static final long INPUT_SETTLE_MILLIS = 300L;
    private static final int VERIFY_ATTEMPTS = 50;
    private static final int IMMEDIATE_BUY_ATTEMPTS = 10;
    private static final int FIVE_PERCENT_BUY_ATTEMPTS = 300;
    private static final int SEARCH_RESULT_CELL_LIMIT = 9;
    private final GenericClientGrandExchangeView view;
    private final ClientThread clientThread;
    private final ScheduledExecutorService executor;
    private final GenericClientMenuInput menuInput;
    private final GenericClientSyntheticKeyboard keyboard;
    private final GenericClientGrandExchangePolicy policy;
    private final Consumer<String> reporter;
    private final AtomicBoolean running = new AtomicBoolean();
    private final List<ScheduledFuture<?>> pending = new CopyOnWriteArrayList<>();
    private volatile CompletableFuture<Map<String, Object>> activeResult;
    private volatile boolean closed;

    private enum PricingStage {
        GUIDE,
        FIVE_PERCENT,
        FINAL
    }

    GenericClientGrandExchangeInput(Client client, ClientThread clientThread, ScheduledExecutorService executor, GenericClientMenuInput menuInput, GenericClientSyntheticKeyboard keyboard, Supplier<GenericClientSnapshot> snapshotSupplier, Consumer<String> reporter) {
        this.view = new GenericClientGrandExchangeView(client, snapshotSupplier);
        this.clientThread = clientThread;
        this.executor = executor;
        this.menuInput = menuInput;
        this.keyboard = keyboard;
        this.policy = new GenericClientGrandExchangePolicy(client, snapshotSupplier);
        this.reporter = reporter;
    }

    synchronized CompletableFuture<Map<String, Object>> buy(int itemId, String itemName, int quantity, int maximumUnitPrice, long minimumCashReserve, String requestedCollectMode, GenericClientActivityContext activityContext) {
        GenericClientGrandExchangePolicy.validateRequest(itemId, itemName, quantity, maximumUnitPrice, minimumCashReserve);
        String collectMode = GenericClientGrandExchangePolicy.collectMode(requestedCollectMode);
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        if (this.closed || !activityContext.isInputAllowed()) {
            result.complete(GenericClientGrandExchangeInput.rejected(this.closed ? "grand_exchange_input_closed" : "action_cancelled"));
            return result;
        }
        if (!this.running.compareAndSet(false, true)) {
            result.complete(GenericClientGrandExchangeInput.rejected("interaction_already_running"));
            return result;
        }
        this.activeResult = result;
        this.reporter.accept("GE_BUY_STARTED item=" + itemId + " quantity=" + quantity + " maxUnitPrice=" + maximumUnitPrice + " reserve=" + minimumCashReserve + " collectMode=" + collectMode);
        this.clientRead(() -> this.view.preflight(itemId, quantity, maximumUnitPrice, minimumCashReserve)).thenCompose(preflight -> {
            if (preflight.rejection != null) {
                return CompletableFuture.completedFuture(GenericClientGrandExchangeInput.rejected(preflight.rejection));
            }
            if (preflight.existingSlot >= 0) {
                return this.finishExistingOffer(preflight.existingSlot, itemId, itemName.trim(), quantity, maximumUnitPrice, minimumCashReserve, collectMode, activityContext);
            }
            return this.placeOffer(preflight.emptySlot, itemId, itemName.trim(), quantity, maximumUnitPrice, minimumCashReserve, collectMode, activityContext);
        }).whenComplete((receipt, error) -> {
            Map<String, Object> completed = receipt;
            if (error != null) {
                completed = GenericClientGrandExchangeInput.rejected(rootMessage(error));
            }
            this.finishOwned(result, completed);
        });
        return result;
    }

    boolean isRunning() {
        return this.running.get();
    }

    synchronized void cancel(String reason) {
        if (this.running.get()) {
            this.finish(GenericClientGrandExchangeInput.rejected("cancelled: " + reason));
        }
    }

    private CompletableFuture<Map<String, Object>> placeOffer(int slot, int itemId, String itemName, int quantity, int maximumUnitPrice, long minimumCashReserve, String collectMode, GenericClientActivityContext activityContext) {
        return this.placeOffer(slot, itemId, itemName, quantity, maximumUnitPrice,
            minimumCashReserve, collectMode, activityContext, PricingStage.GUIDE);
    }

    private CompletableFuture<Map<String, Object>> placeOffer(int slot, int itemId,
        String itemName, int quantity, int maximumUnitPrice, long minimumCashReserve,
        String collectMode, GenericClientActivityContext activityContext,
        PricingStage pricingStage) {
        CompletableFuture<List<Map<String, Object>>> flow =
            CompletableFuture.completedFuture(new ArrayList<>());
        flow = GenericClientGrandExchangeInput.append(flow, () -> this.ensureOfferIndex(activityContext));
        flow = GenericClientGrandExchangeInput.append(flow, () -> this.clickBuyOffer(slot, activityContext));
        flow = GenericClientGrandExchangeInput.append(flow, () -> this.waitUntil(this.view::itemSearchVisible, "ge_item_search", 50));
        flow = GenericClientGrandExchangeInput.append(flow, () -> this.typeText(itemName, false, "ge_item_search_text", activityContext));
        flow = GenericClientGrandExchangeInput.append(flow, () -> this.waitUntil(() -> this.view.findItemSearchResult(itemId) != null, "ge_exact_search_result", 50));
        flow = GenericClientGrandExchangeInput.append(flow, () -> this.clickSearchResult(itemId, activityContext));
        flow = GenericClientGrandExchangeInput.append(flow, () -> this.waitUntil(() -> this.view.setupContainsItem(itemId), "ge_offer_item", 50));
        flow = GenericClientGrandExchangeInput.append(flow, () -> this.clickAndType(InterfaceID.GeOffers.SETUP, "Enter quantity", Integer.toString(quantity), "ge_quantity", activityContext));
        return flow.thenCompose(steps -> this.acceptGuidePrice(maximumUnitPrice).thenCompose(guidePrice -> {
            steps.add(guidePrice);
            if (!GenericClientGrandExchangeInput.wasAccepted(guidePrice)) {
                return CompletableFuture.completedFuture(guidePrice);
            }
            int guideUnitPrice = (int) GenericClientGrandExchangeInput.longValue(
                guidePrice.get("unit_price"));
            CompletableFuture<Map<String, Object>> priceReady = stagePrice(pricingStage, guideUnitPrice, maximumUnitPrice, activityContext, guidePrice);
            return priceReady.thenCompose(price -> {
                if (pricingStage != PricingStage.GUIDE) {
                    steps.add(price);
                }
                if (!GenericClientGrandExchangeInput.wasAccepted(price)) {
                    return CompletableFuture.completedFuture(price);
                }
                int unitPrice = (int)GenericClientGrandExchangeInput.longValue(price.get("unit_price"));
                CompletableFuture<List<Map<String, Object>>> submission = CompletableFuture.completedFuture(steps);
                submission = GenericClientGrandExchangeInput.append(submission, () -> this.clickWidgetAction(InterfaceID.GeOffers.SETUP_CONFIRM, "Confirm", null, "confirm_buy_offer", activityContext));
                submission = GenericClientGrandExchangeInput.append(submission,
                    () -> this.waitUntil(
                        () -> this.policy.matchingOfferSlot(itemId, quantity, unitPrice) >= 0,
                        "ge_offer_placed",
                        50));
                return submission.thenCompose(submitted ->
                    this.clientRead(() -> this.policy.matchingOfferSlot(itemId, quantity, unitPrice))
                        .thenCompose(placedSlot -> placedSlot < 0
                            ? CompletableFuture.completedFuture(
                                GenericClientGrandExchangeInput.rejected(
                                    "matching_buy_offer_disappeared"))
                            : this.finishPlacedOffer(
                                placedSlot, itemId, itemName, quantity, unitPrice,
                                maximumUnitPrice, minimumCashReserve, collectMode,
                                activityContext, pricingStage, submitted)));
            });
        }));
    }

    private CompletableFuture<Map<String, Object>> stagePrice(PricingStage stage, int guideUnitPrice, int maximumUnitPrice,
        GenericClientActivityContext context, Map<String, Object> guidePrice) {
        if (stage == PricingStage.FIVE_PERCENT) return increasePriceFivePercent(maximumUnitPrice, context);
        if (stage == PricingStage.FINAL) return setFinalPrice(guideUnitPrice, maximumUnitPrice, context);
        return CompletableFuture.completedFuture(guidePrice);
    }

    private CompletableFuture<Map<String, Object>> finishPlacedOffer(int slot, int itemId,
        String itemName, int quantity, int unitPrice, int maximumUnitPrice,
        long minimumCashReserve, String collectMode,
        GenericClientActivityContext activityContext, PricingStage pricingStage,
        List<Map<String, Object>> steps) {
        int attempts = pricingStage == PricingStage.GUIDE
            ? IMMEDIATE_BUY_ATTEMPTS
            : pricingStage == PricingStage.FIVE_PERCENT
                ? FIVE_PERCENT_BUY_ATTEMPTS
                : VERIFY_ATTEMPTS;
        return this.waitForPurchase(slot, itemId, attempts).thenCompose(purchase -> {
            steps.add(purchase);
            if ("complete".equals(purchase.get("status"))) {
                return this.collect(slot, itemId, quantity, collectMode, activityContext).thenApply(collection -> {
                    steps.add(collection);
                    return GenericClientGrandExchangeInput.offerReceipt(collection, steps, slot, itemId, quantity, unitPrice, maximumUnitPrice, minimumCashReserve);
                });
            }
            return this.clientRead(() -> this.view.offerAt(slot)).thenCompose(offer -> {
                if (pricingStage == PricingStage.FINAL || !GenericClientGrandExchangePolicy.isCompletelyUnfilled(offer)) {
                    return CompletableFuture.completedFuture(GenericClientGrandExchangeInput.offerReceipt(purchase, steps, slot, itemId, quantity, unitPrice, maximumUnitPrice, minimumCashReserve));
                }
                    return this.cancelUnfilledOffer(slot, activityContext).thenCompose(cancelled -> {
                        steps.add(cancelled);
                        if (!GenericClientGrandExchangeInput.wasAccepted(cancelled)) {
                            return CompletableFuture.completedFuture(GenericClientGrandExchangeInput.offerReceipt(cancelled, steps, slot, itemId, quantity, unitPrice, maximumUnitPrice, minimumCashReserve));
                        }
                        if (Boolean.TRUE.equals(cancelled.get("offer_filled"))) {
                            return this.collect(slot, itemId, quantity, collectMode, activityContext)
                                .thenApply(collection -> {
                                    steps.add(collection);
                                    return GenericClientGrandExchangeInput.offerReceipt(
                                        collection, steps, slot, itemId, quantity, unitPrice,
                                        maximumUnitPrice, minimumCashReserve);
                                });
                        }
                        Map<String, Object> initial = GenericClientGrandExchangeInput.offerReceipt(cancelled, steps, slot, itemId, quantity, unitPrice, maximumUnitPrice, minimumCashReserve);
                        PricingStage nextStage = pricingStage == PricingStage.GUIDE
                            ? PricingStage.FIVE_PERCENT
                            : PricingStage.FINAL;
                        return this.placeOffer(slot, itemId, itemName, quantity,
                            maximumUnitPrice, minimumCashReserve, collectMode,
                            activityContext, nextStage).thenApply(retried -> {
                            retried.put("initial_offer", initial);
                            retried.put("click_count", GenericClientGrandExchangeInput.longValue(retried.get("click_count")) + GenericClientGrandExchangeInput.clickCount(initial));
                            return retried;
                        });
                    });

            });
        });
    }

    private CompletableFuture<Map<String, Object>> ensureOfferIndex(GenericClientActivityContext activityContext) {
        return this.clientRead(() -> this.view.visibleWidget(InterfaceID.GeOffers.INDEX) != null).thenCompose(visible -> {
            if (visible) {
                LinkedHashMap<String, Object> receipt = new LinkedHashMap<String, Object>();
                receipt.put("status", "complete");
                receipt.put("result", "ge_offer_index_visible");
                receipt.put("click_count", 0L);
                return CompletableFuture.completedFuture(receipt);
            }
            return this.clickWidgetAction(InterfaceID.GeOffers.BACK, "Back", null, "return_to_offer_index", activityContext).thenCompose(back -> {
                if (!GenericClientGrandExchangeInput.wasAccepted(back)) {
                    return CompletableFuture.completedFuture(back);
                }
                return this.waitUntil(() -> this.view.visibleWidget(InterfaceID.GeOffers.INDEX) != null, "ge_offer_index", 50);
            });
        });
    }

    private CompletableFuture<Map<String, Object>> acceptGuidePrice(int maximumUnitPrice) {
        return this.clientRead(this.view::visibleSetupUnitPrice).thenApply(unitPrice -> {
            if (unitPrice < 1) {
                return GenericClientGrandExchangeInput.rejected("ge_guide_price_unavailable");
            }
            if (unitPrice > maximumUnitPrice) {
                return GenericClientGrandExchangeInput.rejected("ge_guide_price_exceeds_maximum");
            }
            Map<String, Object> receipt = GenericClientGrandExchangeInput.complete("ge_guide_price_accepted");
            receipt.put("unit_price", unitPrice);
            return receipt;
        });
    }

    private CompletableFuture<Map<String, Object>> increasePriceFivePercent(int maximumUnitPrice, GenericClientActivityContext activityContext) {
        return this.clientRead(this.view::visibleSetupUnitPrice).thenCompose(before -> {
            if (before < 1) {
                return CompletableFuture.completedFuture(GenericClientGrandExchangeInput.rejected("ge_guide_price_unavailable"));
            }
            return this.menuInput.interactDirect(() -> {
                Widget setup = this.view.visibleWidget(InterfaceID.GeOffers.SETUP);
                Widget button = GenericClientGrandExchangeWidgets.findByText(setup, "+5%");
                return this.view.directTargetForWidget(button, "ge_price_add_five_percent");
            }, activityContext).thenCompose(clicked -> {
                if (!GenericClientGrandExchangeInput.wasAccepted(clicked)) {
                    return CompletableFuture.completedFuture(clicked);
                }
                return this.waitUntil(() -> this.view.visibleSetupUnitPrice() > before, "ge_price_add_five_percent", 50).thenCompose(observed -> {
                    if (!GenericClientGrandExchangeInput.wasAccepted(observed)) {
                        return CompletableFuture.completedFuture(observed);
                    }
                    return this.clientRead(this.view::visibleSetupUnitPrice).thenApply(unitPrice -> {
                        if (unitPrice > maximumUnitPrice) {
                            return GenericClientGrandExchangeInput.rejected("ge_five_percent_price_exceeds_maximum");
                        }
                        Map<String, Object> receipt = GenericClientGrandExchangeInput.complete("ge_price_increased_five_percent");
                        receipt.put("previous_unit_price", before);
                        receipt.put("unit_price", unitPrice);
                        receipt.put("button", clicked);
                        receipt.put("click_count", GenericClientGrandExchangeInput.clickCount(clicked));
                        return receipt;
                    });
                });
            });
        });
    }

    private CompletableFuture<Map<String, Object>> setFinalPrice(
        int guideUnitPrice,
        int maximumUnitPrice,
        GenericClientActivityContext activityContext) {
        int unitPrice = GenericClientGrandExchangePolicy.finalUnitPrice(
            guideUnitPrice, maximumUnitPrice);
        return this.clickAndType(
            InterfaceID.GeOffers.SETUP,
            "Enter price",
            Integer.toString(unitPrice),
            "ge_final_price",
            activityContext).thenCompose(entered -> {
                if (!GenericClientGrandExchangeInput.wasAccepted(entered)) {
                    return CompletableFuture.completedFuture(entered);
                }
                return this.waitUntil(
                    () -> this.view.visibleSetupUnitPrice() == unitPrice,
                    "ge_final_price",
                    VERIFY_ATTEMPTS).thenApply(observed -> {
                        if (!GenericClientGrandExchangeInput.wasAccepted(observed)) {
                            return observed;
                        }
                        Map<String, Object> receipt =
                            GenericClientGrandExchangeInput.complete(
                                "ge_final_price_set");
                        receipt.put("guide_unit_price", guideUnitPrice);
                        receipt.put("unit_price", unitPrice);
                        receipt.put("entry", entered);
                        receipt.put("click_count",
                            GenericClientGrandExchangeInput.clickCount(entered));
                        return receipt;
                    });
            });
    }

    private CompletableFuture<Map<String, Object>> finishExistingOffer(int slot, int itemId, String itemName, int quantity, int maximumUnitPrice, long minimumCashReserve, String collectMode, GenericClientActivityContext activityContext) {
        return this.clientRead(() -> this.view.offerAt(slot)).thenCompose(existing -> {
            if (existing != null && existing.getState() == GrandExchangeOfferState.CANCELLED_BUY) {
                return this.collectRefund(slot, activityContext).thenCompose(collected -> {
                    if (!GenericClientGrandExchangeInput.wasAccepted(collected)) {
                        return CompletableFuture.completedFuture(collected);
                    }
                    return this.placeOffer(slot, itemId, itemName, quantity, maximumUnitPrice,
                        minimumCashReserve, collectMode, activityContext, PricingStage.FINAL);
                });
            }
            if (!GenericClientGrandExchangePolicy.matchesRequestedOffer(existing, quantity, maximumUnitPrice)) {
                return CompletableFuture.completedFuture(GenericClientGrandExchangeInput.rejected("conflicting_existing_offer_for_item"));
            }
            int unitPrice = existing.getPrice();
            return this.waitForPurchase(slot, itemId, FIVE_PERCENT_BUY_ATTEMPTS).thenCompose(purchase -> {
                ArrayList<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
                steps.add(purchase);
                if (!"complete".equals(purchase.get("status"))) {
                    return CompletableFuture.completedFuture(GenericClientGrandExchangeInput.offerReceipt(purchase, steps, slot, itemId, quantity, unitPrice, maximumUnitPrice, minimumCashReserve));
                }
                return this.collect(slot, itemId, quantity, collectMode, activityContext).thenApply(collection -> {
                    steps.add(collection);
                    return GenericClientGrandExchangeInput.offerReceipt(collection, steps, slot, itemId, quantity, unitPrice, maximumUnitPrice, minimumCashReserve);
                });
            });
        });
    }

    private CompletableFuture<Map<String, Object>> cancelUnfilledOffer(int slot, GenericClientActivityContext activityContext) {
        return this.ensureOfferIndex(activityContext).thenCompose(index -> {
            if (!GenericClientGrandExchangeInput.wasAccepted(index)) {
                return CompletableFuture.completedFuture(index);
            }
            return this.clickOfferAction(slot, "Abort offer", "abort_unfilled_offer", activityContext).thenCompose(aborted -> {
                if (!GenericClientGrandExchangeInput.wasAccepted(aborted)) {
                    return CompletableFuture.completedFuture(aborted);
                }
                return this.waitUntil(() -> {
                    GrandExchangeOffer offer = this.view.offerAt(slot);
                    return GenericClientGrandExchangeInput.isCancellationSettled(offer);
                }, "ge_offer_cancel_settled", 50).thenCompose(settled -> {
                    if (!GenericClientGrandExchangeInput.wasAccepted(settled)) {
                        return CompletableFuture.completedFuture(settled);
                    }
                    return this.clientRead(() -> this.view.offerAt(slot)).thenCompose(offer -> {
                        if (offer != null && offer.getState() == GrandExchangeOfferState.BOUGHT) {
                            Map<String, Object> filled = GenericClientGrandExchangeInput.complete(
                                "ge_offer_completed_during_cancel");
                            filled.put("offer_filled", true);
                            filled.put("click_count",
                                GenericClientGrandExchangeInput.clickCount(aborted) +
                                    GenericClientGrandExchangeInput.clickCount(settled));
                            return CompletableFuture.completedFuture(filled);
                        }
                        return this.collectRefund(slot, activityContext).thenApply(refund -> {
                            refund.put("click_count",
                                GenericClientGrandExchangeInput.clickCount(refund) +
                                    GenericClientGrandExchangeInput.clickCount(aborted) +
                                    GenericClientGrandExchangeInput.clickCount(settled));
                            return refund;
                        });
                    });
                });
            });
        });
    }

    private CompletableFuture<Map<String, Object>> collectRefund(int slot, GenericClientActivityContext activityContext) {
        return this.clickOfferAction(slot, "View offer", "view_cancelled_offer", activityContext).thenCompose(view -> {
            if (!GenericClientGrandExchangeInput.wasAccepted(view)) {
                return CompletableFuture.completedFuture(view);
            }
            return this.waitUntil(() -> this.view.visibleWidget(InterfaceID.GeOffers.DETAILS_COLLECT) != null, "ge_cancelled_offer_details", 50).thenCompose(details -> {
                if (!GenericClientGrandExchangeInput.wasAccepted(details)) {
                    return CompletableFuture.completedFuture(details);
                }
                return this.clickCancelledOfferRefund(activityContext).thenCompose(collected -> {
                    if (!GenericClientGrandExchangeInput.wasAccepted(collected)) {
                        return CompletableFuture.completedFuture(collected);
                    }
                    return this.waitUntil(() -> this.view.offerEmpty(slot), "ge_cancelled_offer_collected", 50);
                });
            });
        });
    }

    private CompletableFuture<Map<String, Object>> collect(int slot, int itemId, int quantity, String collectMode, GenericClientActivityContext activityContext) {
        return this.clientRead(() -> this.view.inventoryQuantity(itemId)).thenCompose(before -> this.clickOfferAction(slot, "View offer", "view_completed_offer", activityContext).thenCompose(view -> {
            if (!GenericClientGrandExchangeInput.wasAccepted(view)) {
                return CompletableFuture.completedFuture(view);
            }
            return this.waitUntil(
                () -> this.view.offerEmpty(slot) || this.view.collectItemPresent(itemId),
                "ge_offer_collection_ready",
                50).thenCompose(ready -> {
                if (!GenericClientGrandExchangeInput.wasAccepted(ready)) {
                    return CompletableFuture.completedFuture(ready);
                }
                return this.collectCompletedOffer(slot, itemId, quantity, collectMode, (int)before, activityContext);
            });
        }));
    }

    private CompletableFuture<Map<String, Object>> collectCompletedOffer(int slot, int itemId, int quantity, String collectMode, int before, GenericClientActivityContext activityContext) {
        return this.clientRead(() -> this.view.collectItemPresent(itemId)).thenCompose(itemPresent -> {
            CompletableFuture<Map<String, Object>> itemCollection = itemPresent ? this.collectItem(itemId, collectMode, activityContext) : CompletableFuture.completedFuture(GenericClientGrandExchangeInput.complete("ge_item_already_collected"));
            return itemCollection.thenCompose(collected -> {
                if (!GenericClientGrandExchangeInput.wasAccepted(collected)) {
                    return CompletableFuture.completedFuture(collected);
                }
                return this.waitUntil(() -> this.view.inventoryQuantity(itemId) >= before + quantity || this.view.offerEmpty(slot) || !this.view.collectItemPresent(itemId), "ge_collection", 50).thenCompose(itemCollected -> {
                    if (!GenericClientGrandExchangeInput.wasAccepted(itemCollected)) {
                        return CompletableFuture.completedFuture(itemCollected);
                    }
                    return collectRemainingRefund(slot, activityContext, itemCollected);
                });
            });
        });
    }

    private CompletableFuture<Map<String, Object>> collectRemainingRefund(int slot, GenericClientActivityContext activityContext,
        Map<String, Object> itemCollected) {
        return this.clientRead(() -> this.view.offerEmpty(slot)).thenCompose(empty -> {
            if (empty) {
                return CompletableFuture.completedFuture(itemCollected);
            }
            return this.waitUntil(
                () -> this.view.offerEmpty(slot) || this.view.collectItemPresent(ItemID.COINS),
                "ge_refund_ready",
                50).thenCompose(refundReady -> {
                if (!GenericClientGrandExchangeInput.wasAccepted(refundReady)) {
                    return CompletableFuture.completedFuture(refundReady);
                }
                return this.clientRead(() -> this.view.offerEmpty(slot)).thenCompose(nowEmpty -> {
                    if (nowEmpty) {
                        return CompletableFuture.completedFuture(refundReady);
                    }
                    return this.collectItem(ItemID.COINS, "items", activityContext).thenCompose(refund -> {
                        if (!GenericClientGrandExchangeInput.wasAccepted(refund)) {
                            return CompletableFuture.completedFuture(refund);
                        }
                        return this.waitUntil(() -> this.view.offerEmpty(slot), "ge_refund_collection", 50);
                    });
                });
            });
        });
    }

    private CompletableFuture<Map<String, Object>> collectItem(int itemId, String collectMode, GenericClientActivityContext activityContext) {
        return menuInput.interact(() -> view.resolveCollectItem(itemId, collectMode), activityContext);
    }

    private CompletableFuture<Map<String, Object>> clickCancelledOfferRefund(
        GenericClientActivityContext activityContext) {
        return this.menuInput.interactDirect(() -> {
            Rectangle hitbox = GenericClientGrandExchangeWidgets.cancelledRefundHitbox(
                GenericClientGrandExchangeWidgets.resolvedBounds(
                    this.view.visibleWidget(InterfaceID.GeOffers.DETAILS)));
            return this.view.directTargetForHitbox(hitbox, "collect_cancelled_offer_refund");
        }, activityContext);
    }

    private CompletableFuture<Map<String, Object>> clickSearchResult(int itemId, GenericClientActivityContext activityContext) {
        return this.clickSearchResultCell(itemId, activityContext, 0);
    }

    private CompletableFuture<Map<String, Object>> clickSearchResultCell(
        int itemId,
        GenericClientActivityContext activityContext,
        int cell) {
        return this.menuInput.interact(() -> {
            Widget widget = this.view.findItemSearchResult(itemId);
            return this.view.targetForSearchResult(widget, itemId, cell);
        }, activityContext).thenCompose(receipt -> {
            if (cell + 1 < SEARCH_RESULT_CELL_LIMIT &&
                "hover_has_no_matching_action".equals(receipt.get("result"))) {
                return this.clickSearchResultCell(itemId, activityContext, cell + 1);
            }
            return CompletableFuture.completedFuture(receipt);
        });
    }

    private CompletableFuture<Map<String, Object>> clickWidgetAction(int rootId, String action, Integer itemId, String description, GenericClientActivityContext activityContext) {
        return this.menuInput.interact(() -> {
            Widget root = this.view.visibleWidget(rootId);
            Widget target = GenericClientGrandExchangeWidgets.findByAction(root, action, itemId);
            return this.view.targetForWidget(target, action, itemId, description);
        }, activityContext);
    }

    private CompletableFuture<Map<String, Object>> clickOfferAction(
        int slot,
        String action,
        String description,
        GenericClientActivityContext activityContext) {
        return this.menuInput.interact(() -> {
            Widget slotWidget = this.view.visibleWidget(InterfaceID.GeOffers.INDEX_0 + slot);
            Widget target = GenericClientGrandExchangeWidgets.findByAction(
                slotWidget, action, null);
            return this.view.targetForWidget(target, action, null, description);
        }, activityContext);
    }

    private CompletableFuture<Map<String, Object>> clickBuyOffer(int slot, GenericClientActivityContext activityContext) {
        return this.menuInput.interact(() -> {
            Widget index = this.view.visibleWidget(InterfaceID.GeOffers.INDEX);
            if (!this.view.isOpen()) {
                return GenericClientMenuInput.Resolution.rejected("grand_exchange_not_open");
            }
            if (index == null) {
                return GenericClientMenuInput.Resolution.rejected("open_buy_offer_not_visible");
            }
            Rectangle slotHitbox = GenericClientGrandExchangeWidgets.offerSlotHitbox(
                GenericClientGrandExchangeWidgets.resolvedBounds(index), slot);
            Rectangle buyHitbox = GenericClientGrandExchangeWidgets.buyOfferHitbox(slotHitbox);
            return this.view.targetForOfferSlot(
                buyHitbox, slot, "Buy", "open_buy_offer");
        }, activityContext);
    }

    private CompletableFuture<Map<String, Object>> clickAndType(int rootId, String action,
        String text, String description, GenericClientActivityContext activityContext) {
        return menuInput.interact(() -> {
            Widget target = GenericClientGrandExchangeWidgets.findByAction(view.visibleWidget(rootId), action, null);
            return view.targetForWidget(target, action, null, description);
        }, activityContext).thenCompose(click -> {
            if (!wasAccepted(click)) return CompletableFuture.completedFuture(click);
            return waitUntil(() -> view.inputModeIs(7),
                description + "_input", 50).thenCompose(prompt -> {
                if (!wasAccepted(prompt)) return CompletableFuture.completedFuture(prompt);
                return keyboard.type(text, true, INPUT_SETTLE_MILLIS, activityContext)
                    .thenCompose(ignored -> waitUntil(() -> view.inputModeIs(0),
                        description + "_accepted", 50))
                    .thenApply(accepted -> {
                        if (!wasAccepted(accepted)) return accepted;
                        Map<String, Object> receipt = new LinkedHashMap<>();
                        receipt.put("status", "complete");
                        receipt.put("result", description + "_submitted");
                        receipt.put("menu_receipt", click);
                        receipt.put("click_count", clickCount(click));
                        return receipt;
                    });
            });
        });
    }

    private CompletableFuture<Map<String, Object>> typeText(String text, boolean submit, String description, GenericClientActivityContext activityContext) {
        return keyboard.type(text, submit, 0L, activityContext).thenApply(ignored -> {
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("status", "complete");
            receipt.put("result", description + "_submitted");
            receipt.put("click_count", 0L);
            return receipt;
        });
    }

    private CompletableFuture<Map<String, Object>> waitForPurchase(int slot, int itemId, int attempts) {
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<Map<String, Object>>();
        this.pollPurchase(slot, itemId, attempts, result);
        return result;
    }

    private void pollPurchase(int slot, int itemId, int attemptsRemaining, CompletableFuture<Map<String, Object>> result) {
        this.clientRead(() -> this.view.offerAt(slot)).whenComplete((offer, error) -> {
            if (error != null) {
                result.complete(GenericClientGrandExchangeInput.rejected(rootMessage(error)));
                return;
            }
            if (offer == null || offer.getItemId() != itemId) {
                result.complete(GenericClientGrandExchangeInput.rejected("matching_buy_offer_disappeared"));
                return;
            }
            if (offer.getState() == GrandExchangeOfferState.BOUGHT) {
                LinkedHashMap<String, Object> receipt = new LinkedHashMap<String, Object>();
                receipt.put("status", "complete");
                receipt.put("result", "ge_purchase_complete");
                receipt.put("quantity_bought", Long.valueOf(offer.getQuantitySold()));
                receipt.put("spent", Long.valueOf(offer.getSpent()));
                receipt.put("click_count", 0L);
                result.completeAsync(() -> receipt);
                return;
            }
            if (offer.getState() != GrandExchangeOfferState.BUYING) {
                result.complete(GenericClientGrandExchangeInput.rejected("unexpected_buy_offer_state:" + offer.getState().name()));
                return;
            }
            if (attemptsRemaining <= 1) {
                LinkedHashMap<String, Object> receipt = new LinkedHashMap<String, Object>();
                receipt.put("status", "placed");
                receipt.put("result", "ge_offer_pending");
                receipt.put("quantity_bought", Long.valueOf(offer.getQuantitySold()));
                receipt.put("spent", Long.valueOf(offer.getSpent()));
                receipt.put("click_count", 0L);
                result.completeAsync(() -> receipt);
                return;
            }
            this.schedule(() -> this.pollPurchase(slot, itemId, attemptsRemaining - 1, result));
        });
    }

    private CompletableFuture<Map<String, Object>> waitUntil(Supplier<Boolean> condition, String description, int attempts) {
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<Map<String, Object>>();
        this.poll(condition, description, attempts, result);
        return result;
    }

    private void poll(Supplier<Boolean> condition, String description, int attemptsRemaining, CompletableFuture<Map<String, Object>> result) {
        if (!this.running.get() || this.closed) {
            result.complete(GenericClientGrandExchangeInput.rejected("cancelled_while_waiting_for_" + description));
            return;
        }
        this.clientRead(condition).whenComplete((satisfied, error) -> {
            if (error != null) {
                result.complete(GenericClientGrandExchangeInput.rejected(rootMessage(error)));
            } else if (Boolean.TRUE.equals(satisfied)) {
                LinkedHashMap<String, Object> receipt = new LinkedHashMap<String, Object>();
                receipt.put("status", "complete");
                receipt.put("result", description + "_verified");
                receipt.put("click_count", 0L);
                result.completeAsync(() -> receipt);
            } else if (attemptsRemaining <= 1) {
                result.complete(GenericClientGrandExchangeInput.rejected(description + "_verification_timeout"));
            } else {
                this.schedule(() -> this.poll(
                    condition, description, attemptsRemaining - 1, result));
            }
        });
    }

    private void schedule(Runnable runnable) {
        ScheduledFuture<?> future = this.executor.schedule(runnable, 200L, TimeUnit.MILLISECONDS);
        this.pending.add(future);
    }

    private <T> CompletableFuture<T> clientRead(Supplier<T> reader) {
        CompletableFuture<T> result = new CompletableFuture<>();
        this.clientThread.invoke(() -> {
            try {
                result.complete(reader.get());
            }
            catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    static boolean isCancellationSettled(GrandExchangeOffer offer) {
        return offer != null && (offer.getState() == GrandExchangeOfferState.CANCELLED_BUY ||
            offer.getState() == GrandExchangeOfferState.BOUGHT);
    }

    private static CompletableFuture<List<Map<String, Object>>> append(CompletableFuture<List<Map<String, Object>>> flow, Supplier<CompletableFuture<Map<String, Object>>> step) {
        return flow.thenCompose(receipts -> step.get().thenApply(receipt -> {
            receipts.add(receipt);
            if (!GenericClientGrandExchangeInput.wasAccepted(receipt)) {
                throw new IllegalStateException(String.valueOf(receipt.get("result")));
            }
            return receipts;
        }));
    }

    private static boolean wasAccepted(Map<String, Object> receipt) {
        if (receipt == null) {
            return false;
        }
        Object status = receipt.get("status");
        return "dispatched".equals(status) || "complete".equals(status);
    }

    private static long longValue(Object value) {
        return value instanceof Number ? ((Number)value).longValue() : 0L;
    }

    private static long clickCount(Map<String, Object> receipt) {
        Object value = receipt.get("click_count");
        return value instanceof Number ? ((Number)value).longValue() : 0L;
    }

    private static Map<String, Object> offerReceipt(Map<String, Object> terminal, List<Map<String, Object>> steps, int slot, int itemId, int quantity, int unitPrice, int maximumUnitPrice, long minimumCashReserve) {
        LinkedHashMap<String, Object> receipt = new LinkedHashMap<String, Object>(terminal);
        receipt.put("slot", slot);
        receipt.put("item_id", itemId);
        receipt.put("quantity", quantity);
        receipt.put("unit_price", unitPrice);
        receipt.put("maximum_unit_price", maximumUnitPrice);
        receipt.put("minimum_cash_reserve", minimumCashReserve);
        receipt.put("steps", steps);
        long clicks = 0L;
        for (Map<String, Object> step : steps) {
            clicks += GenericClientGrandExchangeInput.clickCount(step);
        }
        receipt.put("click_count", clicks);
        return receipt;
    }

    private static Map<String, Object> rejected(String reason) {
        LinkedHashMap<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("status", "rejected");
        receipt.put("result", reason);
        receipt.put("click_count", 0L);
        return receipt;
    }

    private static Map<String, Object> complete(String result) {
        LinkedHashMap<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("status", "complete");
        receipt.put("result", result);
        receipt.put("click_count", 0L);
        return receipt;
    }

    private synchronized void finishOwned(CompletableFuture<Map<String, Object>> owner, Map<String, Object> receipt) {
        if (activeResult == owner) finish(receipt);
    }

    private synchronized void finish(Map<String, Object> receipt) {
        if (!this.running.getAndSet(false)) {
            return;
        }
        for (ScheduledFuture<?> future : this.pending) {
            future.cancel(false);
        }
        this.pending.clear();
        this.reporter.accept("GE_BUY_COMPLETED status=" + String.valueOf(receipt.get("status")) + " result=" + String.valueOf(receipt.get("result")) + " clicks=" + String.valueOf(receipt.get("click_count")));
        CompletableFuture<Map<String, Object>> result = this.activeResult;
        this.activeResult = null;
        if (result != null) {
            result.completeAsync(() -> receipt);
        }
    }


    @Override
    public synchronized void close() {
        this.closed = true;
        this.cancel("input_closed");
    }
}
