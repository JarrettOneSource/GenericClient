package com.genericclient;

import static org.junit.Assert.*;
import static com.genericclient.GenericClientScriptHostTest.await;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientRandomEventScriptHostTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void operatorCanInspectALatchedEventBeforeTheInterruptedScriptRestarts() throws Exception
    {
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            host.compile("Training",GenericClientTestSupport.javaScript("Training","",
                "public int onLoop(){return 10;} public void onStart(String... inputs){log(inputs[0]);}" )).get();
            host.setRandomEventHooks(() -> Map.of("active",true),(key,status,error) -> {});
            host.start("Training",Map.of("target","50")).get();
            await(() -> host.getRecentLogs().contains("Training: target=50"));
            host.interruptForRandomEvent("event:5436:1").get();
            assertTrue(host.isRandomEventBlocked());
            Map<String,Object> inspection = host.evaluate("return com.genericclient.script.SnapshotData.read(\"random_event\").get(\"active\");").get();
            assertEquals(true,inspection.get("value"));
            assertTrue(host.isRandomEventBlocked());
            host.releaseRandomEvent("event:5436:1",true).get();
            await(() -> host.getRecentLogs().lines().filter("Training: target=50"::equals).count()==2);
            assertEquals("Training",host.getActiveScript());
            assertFalse(host.isRandomEventBlocked());
        }
    }
}
