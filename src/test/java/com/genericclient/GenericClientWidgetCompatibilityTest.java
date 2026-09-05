package com.genericclient;

import static org.junit.Assert.*;

import java.awt.Rectangle;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientWidgetCompatibilityTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String WIDGETS = "org.dreambot.api.methods.widget.Widgets.";

    @Test public void hiddenWidgetLookupsKeepExistenceSeparateFromVisibility() throws Exception
    {
        Node hidden = new Node(26,22,-1,"Coins reserve");
        hidden.hidden=true;
        Node group = new Node(26,0,-1,"Root").children(hidden);
        Node hiddenGroup = new Node(27,0,-1,"Other interface");
        hiddenGroup.hidden=true;
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            host.publishGameTick(frame(1,group,hiddenGroup));
            assertEquals(List.of(true,false,"Coins reserve",true,1,false,false,true),value(host,
                "var hidden=" + WIDGETS + "get(26,22); return List.of(hidden!=null,hidden!=null&&hidden.isVisible()," +
                "hidden==null?\"missing\":hidden.getText()," + WIDGETS + "getWidget(27)!=null," + WIDGETS + "getAllContainingText(\"Coins\").size(),"+
                WIDGETS+"isVisible(26,22),"+WIDGETS+"getWidget(27).isVisible(),"+WIDGETS+"getWidget(26).isVisible());"));
            List<?> visible=(List<?>)host.read("widgets",Map.of("limit",Integer.MAX_VALUE));
            assertEquals("The semantic widget query still defaults to visible controls",1,visible.size());
            assertEquals(List.of(true,-1,-1),value(host,"return List.of("+WIDGETS+"getWidget(999)==null,"+
                WIDGETS+"get(26,0).getItemId(),"+WIDGETS+"get(26,0).getModelId());"));
            assertEquals(1703958,value(host,"return "+WIDGETS+"getAllContainingText(\"Coins\").get(0).getRawId();"));
            for (String ids:List.of("","26","26,22,1,0"))
                assertThrows(java.util.concurrent.ExecutionException.class,()->value(host,"return "+WIDGETS+"get("+ids+");"));
        }
    }

    @Test public void widgetQueriesIncludeEveryLoadedNodeBeyondTheOldCaptureLimit() throws Exception
    {
        Node[] children = java.util.stream.IntStream.rangeClosed(1,1100)
            .mapToObj(id -> new Node(26,id,-1,"Entry "+id)).toArray(Node[]::new);
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary.newFolder().toPath()))
        {
            host.publishGameTick(frame(1,new Node(26,0,-1,"Root").children(children)));
            assertEquals(List.of(1101,1100,true),value(host,
                "return List.of("+WIDGETS+"getWidget(26).getChildren().size(),"+WIDGETS+
                "getAllContainingText(\"Entry\").size(),"+WIDGETS+"get(26,1100)!=null);"));
        }
    }

    @Test public void retainedWidgetMetadataAndInputFollowTheNextObservedFrame() throws Exception
    {
        Node nested = new Node(26,22,3,"Detail");
        Node label = new Node(26,22,-1,"<col=ffffff>Coins</col>").children(nested);
        label.item=995;
        label.model=3141;
        Node root = new Node(26,0,-1,"Root").children(label);
        List<Map<String,Object>> inputs = new ArrayList<>();
        AtomicReference<GenericClientScriptHost> current = new AtomicReference<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"widget-live")
            .questAction((type,arguments,context) -> {
                inputs.add(Map.of("type",type,"arguments",arguments));
                label.text="Updated";
                label.bounds.translate(50,20);
                current.get().publishGameTick(type.equals("ui.close") ? frame(4) : frame(inputs.size()+1,root));
                return CompletableFuture.completedFuture(Map.of("status","complete"));
            }).build())
        {
            current.set(host);
            host.publishGameTick(frame(1,root));
            assertEquals(List.of(0,22),value(host,"List<Integer> children=new ArrayList<>();for(var child:"+WIDGETS+
                "getWidget(26).getChildren())children.add(child.getID());return children;"));
            assertEquals("Detail",value(host,"return "+WIDGETS+"get(26,22).getChild(3).getText();"));
            assertEquals(List.of(22,1703958,26,-1,-1,26,22,4,995,3141,"Target",List.of("Select"),new Rectangle(10,20,30,40)),value(host,
                "var widget="+WIDGETS+"getWidget(26).getChild(22); String[] actions=widget.getActions();actions[0]=\"changed\";"+
                "java.awt.Rectangle bounds=widget.getRectangle();bounds.x=999;return List.of(widget.getID(),widget.getRawId(),widget.getWidgetId(),"+
                "widget.getGrandChildId(),widget.getIndex(),widget.getParentID(),widget.getChildId(),widget.getType(),widget.getItemId(),"+
                "widget.getModelId(),widget.getName(),Arrays.asList(widget.getActions()),widget.getRectangle());"));
            assertEquals(List.of(true,"Updated",true,true,false,false,false),value(host,
                "var parent="+WIDGETS+"getWidgetChild(26,22);var nested="+WIDGETS+"getWidgetChild(26,22,3);"+
                "boolean clicked=parent.interact(\"Select\");String text=parent.getText();boolean child=nested.interact();boolean closed="+WIDGETS+"closeAll();"+
                "try{parent.getText();throw new AssertionError();}catch(IllegalStateException expected){}"+
                "return List.of(clicked,text,child,closed,parent.isVisible(),parent.interact(),parent.getChild(3)!=null);"));
            assertEquals(List.of(
                Map.of("type","ui.click","arguments",Map.of("widget_id",1703958,"action","Select")),
                Map.of("type","ui.click","arguments",Map.of("widget_id",1703958,"widget_index",3)),
                Map.of("type","ui.close","arguments",Map.of())),inputs);
        }
    }

    @Test public void aRejectedSubchildZeroClickAndCloseRemainFailures() throws Exception
    {
        Node zero = new Node(26,22,0,"First option");
        Node parent = new Node(26,22,-1,"Options").children(zero);
        List<Map<String,Object>> inputs = new ArrayList<>();
        try (GenericClientScriptHost host = GenericClientTestSupport.scriptHost(temporary,"widget-rejection")
            .questAction((type,arguments,context) -> {
                inputs.add(Map.of("type",type,"arguments",arguments));
                return CompletableFuture.completedFuture(Map.of("status","rejected"));
            }).build())
        {
            host.publishGameTick(frame(1,parent));
            assertEquals(List.of(false,false),value(host,"return List.of("+WIDGETS+"get(26,22,0).interact(),"+WIDGETS+"closeAll());"));
            assertEquals(List.of(Map.of("type","ui.click","arguments",Map.of("widget_id",1703958,"widget_index",0)),
                Map.of("type","ui.close","arguments",Map.of())),inputs);
        }
    }

    private static Object value(GenericClientScriptHost host,String body) throws Exception
    {
        return host.evaluate(body).get(5,TimeUnit.SECONDS).get("value");
    }

    private static GenericClientSnapshot frame(long tick,Node... roots)
    {
        Client client=proxy(Client.class,(method,arguments) -> method.getName().equals("getWidgetRoots")
            ? Arrays.stream(roots).map(node->node.widget).toArray(Widget[]::new) : defaultValue(method.getReturnType()));
        return new GenericClientSnapshot(tick,"LOGGED_IN",240,
            new GenericClientPlayerSnapshot(1L,"Player",3200,3200,0,-1),List.of(),
            GenericClientAccountSnapshot.empty(),GenericClientQuestSnapshot.empty(),List.of(),
            GenericClientSceneCollision.empty(),GenericClientWidgetSnapshot.capture(client));
    }

    private static final class Node
    {
        final int id;
        final int index;
        final Widget widget;
        final Rectangle bounds = new Rectangle(10,20,30,40);
        String text;
        boolean hidden;
        int item=-1;
        int model=-1;
        Node parent;
        Node[] children=new Node[0];

        Node(int group,int child,int index,String text)
        {
            id=(group<<16)|child;
            this.index=index;
            this.text=text;
            widget=proxy(Widget.class,(method,arguments)->read(method));
        }

        Node children(Node... values)
        {
            children=values;
            for (Node child:children) child.parent=this;
            return this;
        }

        private Object read(Method method)
        {
            switch (method.getName())
            {
                case "getId":return id;
                case "getIndex":return index;
                case "getText":return text;
                case "getName":return "Target";
                case "getType":return 4;
                case "getBounds":return bounds;
                case "isHidden":
                case "isSelfHidden":return hidden;
                case "getParent":return parent==null?null:parent.widget;
                case "getParentId":return parent==null?-1:parent.id;
                case "getItemId":return item;
                case "getModelId":return model;
                case "getActions":return new String[]{"Select"};
                case "getChildren":
                case "getDynamicChildren":return Arrays.stream(children).map(child->child.widget).toArray(Widget[]::new);
                default:return defaultValue(method.getReturnType());
            }
        }
    }

    @FunctionalInterface private interface Read { Object value(Method method,Object[] arguments); }

    private static <T> T proxy(Class<T> type,Read reader)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},
            (proxy,method,arguments)->reader.value(method,arguments)));
    }

    private static Object defaultValue(Class<?> type)
    {
        if (!type.isPrimitive()) return null;
        return type==boolean.class?false:0;
    }
}
