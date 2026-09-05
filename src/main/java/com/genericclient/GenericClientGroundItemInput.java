package com.genericclient;

import java.awt.Point;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemLayer;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;

final class GenericClientGroundItemInput
{
	private static final int CAMERA_ATTEMPTS = 1;
	private static final long CAMERA_SETTLE_MILLIS = 1_600L;

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientMenuInput menuInput;
	private final GenericClientCameraOwner cameraOwner;

	GenericClientGroundItemInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientMenuInput menuInput,
		GenericClientCameraOwner cameraOwner)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.menuInput = menuInput;
		this.cameraOwner = cameraOwner;
	}

	CompletableFuture<Map<String, Object>> take(
		int itemId,
		WorldPoint world,
		int within,
		GenericClientActivityContext activityContext)
	{
		if (itemId < 0)
		{
			throw new IllegalArgumentException("Ground item id cannot be negative");
		}
		if (within < 1 || within > 32)
		{
			throw new IllegalArgumentException("Ground item radius must be between 1 and 32 tiles");
		}
		GenericClientCameraOwner.Operation cameraOperation = cameraOwner.begin();
		GenericClientMenuInput.TargetResolver resolver =
			() -> resolveGroundItem(itemId, world, within);
		return menuInput.interact(resolver, activityContext).thenCompose(receipt ->
			retryWithCamera(
				resolver, receipt, itemId, world, within, activityContext,
				cameraOperation, 0));
	}

	private CompletableFuture<Map<String, Object>> retryWithCamera(
		GenericClientMenuInput.TargetResolver resolver,
		Map<String, Object> receipt,
		int itemId,
		WorldPoint world,
		int within,
		GenericClientActivityContext activityContext,
		GenericClientCameraOwner.Operation cameraOperation,
		int cameraAttempt)
	{
		if (!cameraOperation.isActive())
		{
			return CompletableFuture.completedFuture(receipt);
		}
		Object result = receipt.get("result");
		if ((!"ground_item_not_visible".equals(result) &&
			!"hover_has_no_matching_action".equals(result)) ||
			cameraAttempt >= CAMERA_ATTEMPTS)
		{
			return CompletableFuture.completedFuture(receipt);
		}
		return faceGroundItem(itemId, world, within, cameraOperation).thenCompose(faced ->
		{
			if (!faced)
			{
				return CompletableFuture.completedFuture(receipt);
			}
			CompletableFuture<Void> settled = new CompletableFuture<>();
			executor.schedule(
				() -> settled.complete(null), CAMERA_SETTLE_MILLIS, TimeUnit.MILLISECONDS);
			return settled
				.thenCompose(ignored -> cameraOperation.isActive()
					? menuInput.interact(resolver, activityContext)
					: CompletableFuture.completedFuture(receipt))
				.thenCompose(next -> retryWithCamera(
					resolver,
					next,
					itemId,
					world,
					within,
					activityContext,
					cameraOperation,
					cameraAttempt + 1));
		});
	}

	private CompletableFuture<Boolean> faceGroundItem(
		int itemId,
		WorldPoint requestedWorld,
		int within,
		GenericClientCameraOwner.Operation cameraOperation)
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			if (!cameraOperation.isActive())
			{
				result.complete(false);
				return;
			}
			Player player = client.getLocalPlayer();
			if (player == null || player.getWorldLocation() == null || player.getLocalLocation() == null)
			{
				result.complete(false);
				return;
			}
			List<GroundTarget> targets = findTargets(player, itemId, requestedWorld, within);
			if (targets.isEmpty() || targets.get(0).tile.getWorldLocation() == null)
			{
				result.complete(false);
				return;
			}
			int targetYaw = GenericClientGameInput.yawToward(
				player.getWorldLocation(), targets.get(0).tile.getWorldLocation());
			result.complete(cameraOperation.face(
				targetYaw, GenericClientGameInput.CAMERA_INTERACTION_PITCH));
		});
		return result;
	}

	private GenericClientMenuInput.Resolution resolveGroundItem(
		int itemId,
		WorldPoint requestedWorld,
		int within)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		Player player = client.getLocalPlayer();
		if (player == null || player.getWorldLocation() == null || player.getLocalLocation() == null)
		{
			return GenericClientMenuInput.Resolution.rejected("local_player_unavailable");
		}
		List<GroundTarget> targets = findTargets(player, itemId, requestedWorld, within);
		if (targets.isEmpty())
		{
			return GenericClientMenuInput.Resolution.rejected("matching_ground_item_not_found");
		}
		GroundTarget target = targets.get(0);
		ItemLayer itemLayer = target.tile.getItemLayer();
		Shape shape = itemLayer == null ? null : itemLayer.getClickbox();
		if (shape == null && itemLayer != null)
		{
			shape = itemLayer.getCanvasTilePoly();
		}
		Point point = GenericClientMenuInput.randomPointInside(
			shape, GenericClientMenuInput.viewportBounds(client));
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected("ground_item_not_visible");
		}

		WorldPoint world = target.tile.getWorldLocation();
		LocalPoint local = target.tile.getLocalLocation();
		int sceneX = local.getSceneX();
		int sceneY = local.getSceneY();
		int worldViewId = itemLayer.getWorldView().getId();
		ItemComposition composition = client.getItemDefinition(itemId);
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "ground_item");
		value.put("id", (long) itemId);
		value.put("name", composition == null ? "<unknown>" : composition.getName());
		value.put("quantity", (long) target.item.getQuantity());
		Map<String, Object> pointValue = new LinkedHashMap<>();
		pointValue.put("x", (long) world.getX());
		pointValue.put("y", (long) world.getY());
		pointValue.put("plane", (long) world.getPlane());
		value.put("world", pointValue);
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			"Take",
			"ground_item:" + itemId + ":" + world.getX() + "," + world.getY() + "," + world.getPlane(),
			value,
			entry -> matchesGroundItem(entry, itemId, sceneX, sceneY, worldViewId) &&
				"Take".equalsIgnoreCase(entry.getOption())));
	}

	private static List<GroundTarget> findTargets(
		Player player,
		int itemId,
		WorldPoint requestedWorld,
		int within)
	{
		List<GroundTarget> result = new ArrayList<>();
		GenericClientSceneTiles.visitNearby(player, within, tile -> addGroundTargets(tile, itemId, requestedWorld, result));
		WorldPoint playerWorld = player.getWorldLocation();
		result.sort(Comparator.comparingInt(target ->
			playerWorld.distanceTo(target.tile.getWorldLocation())));
		return result;
	}

	private static void addGroundTargets(
		Tile tile,
		int itemId,
		WorldPoint requestedWorld,
		List<GroundTarget> result)
	{
		if (tile.getItemLayer() == null || tile.getWorldLocation() == null ||
			(requestedWorld != null && !requestedWorld.equals(tile.getWorldLocation())))
		{
			return;
		}
		List<TileItem> groundItems = tile.getGroundItems();
		if (groundItems == null)
		{
			return;
		}
		for (TileItem item : groundItems)
		{
			if (item != null && item.getId() == itemId)
			{
				result.add(new GroundTarget(tile, item));
			}
		}
	}

	static boolean matchesGroundItem(
		MenuEntry entry,
		int itemId,
		int sceneX,
		int sceneY,
		int worldViewId)
	{
		switch (entry.getType())
		{
			case GROUND_ITEM_FIRST_OPTION:
			case GROUND_ITEM_SECOND_OPTION:
			case GROUND_ITEM_THIRD_OPTION:
			case GROUND_ITEM_FOURTH_OPTION:
			case GROUND_ITEM_FIFTH_OPTION:
			case WIDGET_TARGET_ON_GROUND_ITEM:
			case EXAMINE_ITEM_GROUND:
				return entry.getIdentifier() == itemId && entry.getParam0() == sceneX &&
					entry.getParam1() == sceneY && entry.getWorldViewId() == worldViewId;
			default: return false;
		}
	}

	private static final class GroundTarget
	{
		private final Tile tile;
		private final TileItem item;

		private GroundTarget(Tile tile, TileItem item)
		{
			this.tile = tile;
			this.item = item;
		}
	}
}
