package com.genericclient;

import java.awt.Point;
import java.awt.Shape;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/** Player interaction uses the same menu owner and native lifetime checks as other entities. */
final class GenericClientPlayerInput
{
    private final Client client;
    private final GenericClientMenuInput menu;
    private final GenericClientEntityIds identities;

    GenericClientPlayerInput(Client client, GenericClientMenuInput menu, GenericClientEntityIds identities)
    {
        this.client = client;
        this.menu = menu;
        this.identities = identities;
    }

    CompletableFuture<Map<String,Object>> interact(Map<String,Object> arguments, GenericClientActivityContext context)
    {
        int index = ((Number)arguments.get("index")).intValue();
        int worldView = ((Number)arguments.get("world_view")).intValue();
        long identity = ((Number)arguments.get("identity")).longValue();
        int within = ((Number)arguments.get("within")).intValue();
        String action = (String)arguments.get("action");
        if (index < 0 || within < 1 || within > 32 || action == null || action.isBlank())
            throw new IllegalArgumentException("Player interaction requires an index, action and radius between 1 and 32");
        return menu.interact(() -> resolve(index,worldView,identity,action,within),context);
    }

    private GenericClientMenuInput.Resolution resolve(int index, int worldViewId, long identity, String action, int within)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
            return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
        Player local = client.getLocalPlayer();
        if (local == null || local.getWorldLocation() == null)
            return GenericClientMenuInput.Resolution.rejected("local_player_unavailable");
        WorldView world = local.getWorldView();
        if (world.getId() != worldViewId) return GenericClientMenuInput.Resolution.rejected("player_world_changed");
        Player target = world.players().byIndex(index);
        if (target == null || target == local || !identities.matches(target,identity) || target.getWorldLocation() == null)
            return GenericClientMenuInput.Resolution.rejected("matching_player_not_found");
        if (local.getWorldLocation().distanceTo(target.getWorldLocation()) > within)
            return GenericClientMenuInput.Resolution.rejected("player_out_of_range");
        if (Arrays.stream(client.getPlayerOptions()).noneMatch(action::equalsIgnoreCase))
            return GenericClientMenuInput.Resolution.rejected("player_action_unavailable");
        Shape shape = target.getConvexHull();
        if (shape == null) shape = target.getCanvasTilePoly();
        Point point = GenericClientMenuInput.randomPointInside(shape,GenericClientMenuInput.viewportBounds(client));
        if (point == null) return GenericClientMenuInput.Resolution.rejected("player_not_visible");
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("kind","player");
        value.put("index",index);
        value.put("identity",identity);
        value.put("name",target.getName());
        WorldPoint position = target.getWorldLocation();
        value.put("world",GenericClientWorldSnapshot.worldMap(position.getX(),position.getY(),position.getPlane()));
        return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(point,action,"player:"+index,value,
            entry -> identities.matches(target,identity) && matches(entry,target,worldViewId,action),shape));
    }

    private static boolean matches(MenuEntry entry, Player target, int worldViewId, String action)
    {
        int type = entry.getType().getId();
        if (type < MenuAction.PLAYER_FIRST_OPTION.getId() || type > MenuAction.PLAYER_EIGHTH_OPTION.getId() ||
            entry.getWorldViewId() != worldViewId || !action.equalsIgnoreCase(entry.getOption())) return false;
        return entry.getPlayer() == target;
    }
}
