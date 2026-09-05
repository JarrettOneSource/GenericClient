package com.genericclient;

import static org.junit.Assert.*;
import static com.genericclient.GenericClientScriptHostTest.await;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientTaskScriptTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void selectsHighestPriorityResetsFailuresAndStopsAfterExceedingTheLimit() throws Exception
    {
        try (GenericClientScriptHost host=GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            String source=GenericClientTestSupport.javaScript("TaskSelection","",
                "private int visits;" +
                "private final TaskNode low=new TaskNode(){public boolean accept(){return visits==1||visits==3;}" +
                "public int execute(){log(\"low\");return 17;}};" +
                "private final TaskNode high=new TaskNode(){public boolean accept(){return visits==1;}" +
                "public int priority(){return 10;} public int execute(){removeNodes(this);log(\"high\");return 23;}};" +
                "public void onStart(){addNodes(low,high);setFailLimit(1);}" +
                "public int onLoop(){if(++visits>5)throw new AssertionError(\"Failure limit did not stop the script\");int delay=super.onLoop();log(visits+\":\"+delay);" +
                "if(getNodes().length!=1)throw new AssertionError(\"Removed node remained\");" +
                "if(getLastTaskNode()!=(visits<3?high:low))throw new AssertionError(\"Wrong last node\");return 0;}" +
                "public void onExit(){log(\"exit\");}")
                .replace("extends AbstractScript","extends org.dreambot.api.script.impl.TaskScript");
            host.compile("TaskSelection",source).get();
            host.start("TaskSelection").get();
            await(() -> host.getRecentLogs().contains("exit"));
            assertEquals("TaskSelection: high\nTaskSelection: 1:23\nTaskSelection: 2:1000\n" +
                "TaskSelection: low\nTaskSelection: 3:17\nTaskSelection: 4:1000\nTaskSelection: 5:1000\nTaskSelection: exit",
                host.getRecentLogs());
            assertEquals("STOPPED",host.getStatus());
        }
    }

    @Test public void equalPrioritiesKeepInsertionOrderAndZeroFailuresMeansAnImmediateStop() throws Exception
    {
        try (GenericClientScriptHost host=GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            String source=GenericClientTestSupport.javaScript("TaskTies","",
                "private int visits; public void onStart(){addNodes("+
                "new TaskNode(){public boolean accept(){return true;}public int execute(){log(\"first\");return 11;}},"+
                "new TaskNode(){public boolean accept(){return true;}public int execute(){throw new AssertionError(\"Equal priority replaced first\");}},"+
                "new TaskNode(){public boolean accept(){return true;}public int priority(){return -1;}public int execute(){throw new AssertionError(\"Lower priority selected\");}});}"+
                "public int onLoop(){if(++visits==1){log(\"delay:\"+super.onLoop());removeNodes(getNodes());return 0;}"+
                "if(visits>4)throw new AssertionError(\"Zero fail limit did not stop the script\");"+
                "if(visits==4)setFailLimit(0);log(\"idle:\"+super.onLoop());return 0;}")
                .replace("extends AbstractScript","extends org.dreambot.api.script.impl.TaskScript");
            host.compile("TaskTies",source).get();
            host.start("TaskTies").get();
            await(() -> host.getStatus().equals("STOPPED") || host.getStatus().equals("FAULTED"));
            assertEquals("TaskTies: first\nTaskTies: delay:11\nTaskTies: idle:1000\nTaskTies: idle:1000\nTaskTies: idle:1000",host.getRecentLogs());
            assertEquals("STOPPED",host.getStatus());
        }
    }
}
