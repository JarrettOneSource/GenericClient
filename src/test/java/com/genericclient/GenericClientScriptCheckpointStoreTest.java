package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientScriptCheckpointStoreTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void persistsAndIsolatesIntegerCheckpoints() throws Exception
	{
		Path directory = temporaryFolder.newFolder("checkpoints").toPath();
		GenericClientScriptCheckpointStore first =
			new GenericClientScriptCheckpointStore(directory);

		first.set("genericBoss", "quest-runner", "route.zooknock", 29L);
		GenericClientScriptCheckpointStore reloaded =
			new GenericClientScriptCheckpointStore(directory);

		assertEquals((Long) 29L,
			reloaded.get("genericBoss", "quest-runner", "route.zooknock"));
		assertNull(reloaded.get("otherAccount", "quest-runner", "route.zooknock"));
		assertNull(reloaded.get("genericBoss", "walker", "route.zooknock"));
		assertTrue(reloaded.clear("genericBoss", "quest-runner", "route.zooknock"));
		assertNull(reloaded.get("genericBoss", "quest-runner", "route.zooknock"));
		assertFalse(reloaded.clear("genericBoss", "quest-runner", "route.zooknock"));
	}
}
