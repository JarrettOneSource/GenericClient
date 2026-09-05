package org.dreambot.api;

import com.genericclient.script.SnapshotData;

public final class Client
{
	private Client() {}
	public static boolean isLoggedIn() { return Boolean.TRUE.equals(SnapshotData.read("player").get("logged_in")); }
}
