package com.genericclient;

interface GenericClientDashboardActions
{
	void printDiagnostics();

	void logNearbyNpcs();

	void walkToRandomTile();

	void setMouseProfile(String file);

	void setMouseEffect(GenericClientMouseEffect effect);

	void reloadMouseProfile();

	void startMouseRecording();

	void stopMouseRecording();

	String saveBehaviorOverrides(GenericClientBehaviorOverrides overrides);

	String resetBehaviorOverrides();
}
