package com.genericclient;

import java.util.concurrent.CompletableFuture;

interface GenericClientDashboardActions
{
	void printDiagnostics();

	void walkToRandomTile();

	void setMouseProfile(String file);

	void setMouseEffect(GenericClientMouseEffect effect);

	void reloadMouseProfile();

	void startMouseRecording();

	void stopMouseRecording();

	String saveBehaviorOverrides(GenericClientBehaviorOverrides overrides);

	String resetBehaviorOverrides();

	CompletableFuture<String> endLongBreak();
}
