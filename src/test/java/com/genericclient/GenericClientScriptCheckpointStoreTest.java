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

	@Test public void compiledScriptsUseTheirDefaultClassIdForPersistentAccountCheckpoints() throws Exception
	{
		Path scripts = temporaryFolder.newFolder("java-checkpoints").toPath().resolve("scripts");
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(scripts))
		{
			for (String namespace : java.util.List.of("example","other"))
				host.compile(namespace+".CheckpointProbe","package "+namespace+";\n"+
					GenericClientTestSupport.javaScript("CheckpointProbe","",
						"public int onLoop(){Long previous=Automation.checkpoint(\"progress\");"+
						"Automation.checkpoint(\"progress\",previous==null?1:previous+1);"+
						"Automation.checkpoint(\"scratch\",5);Automation.clearCheckpoint(\"scratch\");"+
						"if(Automation.checkpoint(\"scratch\")!=null)throw new AssertionError(\"Checkpoint clear did not persist\");"+
						"Automation.finish(java.util.Arrays.asList(previous,Automation.checkpoint(\"progress\")));return -1;}"))
					.get(5,java.util.concurrent.TimeUnit.SECONDS);
			host.publishGameTick(checkpointFrame("Account A"));
			assertEquals(java.util.Arrays.asList(null,1L),run(host,"example.CheckpointProbe"));
			assertEquals(java.util.Arrays.asList(null,1L),run(host,"other.CheckpointProbe"));
		}
		try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(scripts))
		{
			host.publishGameTick(checkpointFrame("account a"));
			assertEquals(java.util.List.of(1L,2L),run(host,"example.CheckpointProbe"));
			host.publishGameTick(checkpointFrame("Account B"));
			assertEquals(java.util.Arrays.asList(null,1L),run(host,"example.CheckpointProbe"));
		}
	}

	@Test public void checkpointScriptIdsCannotEscapeTheirDirectory() throws Exception
	{
		GenericClientScriptCheckpointStore store = new GenericClientScriptCheckpointStore(temporaryFolder.newFolder("guarded").toPath());
		for (String id : java.util.Arrays.asList(null,"","..","../escape","folder/script","folder\\script","name:stream"))
			org.junit.Assert.assertThrows(IllegalArgumentException.class,() -> store.set("account",id,"progress",1));
	}

	private static Object run(GenericClientScriptHost host,String id) throws Exception
	{
		host.start(id).get(5,java.util.concurrent.TimeUnit.SECONDS);
		GenericClientScriptHostTest.await(() -> java.util.List.of("COMPLETED","FAULTED").contains(host.getStatus()));
		assertEquals(host.getActiveScriptView().toMap().toString(),"COMPLETED",host.getStatus());
		return host.getActiveScriptView().toMap().get("result");
	}

	private static GenericClientSnapshot checkpointFrame(String account)
	{
		return new GenericClientSnapshot(1,"LOGGED_IN",240,new GenericClientPlayerSnapshot(1L,account,3200,3200,0,-1),java.util.List.of());
	}
}
