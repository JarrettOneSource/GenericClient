package com.genericclient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class GenericClientUiInput
{
	private final GenericClientSyntheticKeyboard keyboard;
	private final GenericClientBehaviorController behavior;

	GenericClientUiInput(
		GenericClientSyntheticKeyboard keyboard,
		GenericClientBehaviorController behavior)
	{
		this.keyboard = keyboard;
		this.behavior = behavior;
	}

	CompletableFuture<Map<String, Object>> closeTopLevel(boolean breaksEnabled)
	{
		return behavior.beforeAction(breaksEnabled).thenCompose(before ->
			keyboard.pressEscape().thenCompose(keyboardReceipt ->
				behavior.afterAction(breaksEnabled).thenApply(after ->
				{
					Map<String, Object> receipt = new LinkedHashMap<>();
					receipt.put("status", "dispatched");
					receipt.put("result", "escape_dispatched");
					receipt.put("keyboard", keyboardReceipt);
					receipt.put("behavior_before", before);
					receipt.put("behavior_after", after);
					receipt.put("click_count", 0L);
					return receipt;
				})));
	}
}
