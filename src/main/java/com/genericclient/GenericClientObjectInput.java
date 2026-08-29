package com.genericclient;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.ScriptID;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

final class GenericClientObjectInput
{
	private static final int CAMERA_ATTEMPTS = 4;
	private static final int CAMERA_POLL_ATTEMPTS = 30;
	private static final long CAMERA_POLL_MILLIS = 100L;
	private static final int CAMERA_SETTLED_UNITS = 384;
	private static final int OUTER_CAMERA_ZOOM = -400;

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientMenuInput menuInput;

	GenericClientObjectInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientMenuInput menuInput)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.menuInput = menuInput;
	}

	CompletableFuture<Map<String, Object>> interact(
		int objectId,
		String action,
		WorldPoint world,
		int within,
		boolean breaksEnabled)
	{
		if (objectId < 0)
		{
			throw new IllegalArgumentException("Object id cannot be negative");
		}
		String cleanAction = requireText(action, "Object action");
		validateRadius(within);
		return interactWithCamera(
			objectId, cleanAction, world, within, false, -1, null, breaksEnabled);
	}

	CompletableFuture<Map<String, Object>> useSelectedItemOnObject(
		int objectId,
		WorldPoint world,
		int within,
		int itemId,
		String itemName,
		boolean breaksEnabled)
	{
		if (objectId < 0 || itemId < 0)
		{
			throw new IllegalArgumentException("Object and item ids cannot be negative");
		}
		validateRadius(within);
		return interactWithCamera(
			objectId, "Use", world, within, true, itemId, itemName, breaksEnabled);
	}

	void cancel(String reason)
	{
		menuInput.cancel(reason);
	}

	private CompletableFuture<Map<String, Object>> interactWithCamera(
		int objectId,
		String action,
		WorldPoint world,
		int within,
		boolean selectedItem,
		int itemId,
		String itemName,
		boolean breaksEnabled)
	{
		GenericClientMenuInput.TargetResolver resolver = () -> resolveObject(
			objectId, action, world, within, selectedItem, itemId, itemName);
		return menuInput.interact(resolver, breaksEnabled).thenCompose(receipt ->
		{
			if (!shouldFaceAndRetry(receipt))
			{
				return CompletableFuture.completedFuture(receipt);
			}
			return prepareZoom().thenCompose(zoom -> retryWithCamera(
					resolver,
					objectId,
					world,
					within,
					action,
					selectedItem,
					breaksEnabled,
					0,
					receipt)
				.whenComplete((ignored, error) -> restoreZoom(zoom)));
		});
	}

	private CompletableFuture<CameraZoom> prepareZoom()
	{
		CompletableFuture<CameraZoom> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			CameraZoom previous = new CameraZoom(
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL),
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG),
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL_MIN),
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG_MIN));
			client.setVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL_MIN, OUTER_CAMERA_ZOOM);
			client.setVarcIntValue(VarClientID.CAMERA_ZOOM_BIG_MIN, OUTER_CAMERA_ZOOM);
			client.runScript(ScriptID.CAMERA_DO_ZOOM, OUTER_CAMERA_ZOOM, OUTER_CAMERA_ZOOM);
			result.complete(previous);
		});
		return result;
	}

	private void restoreZoom(CameraZoom previous)
	{
		clientThread.invoke(() ->
		{
			client.setVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL_MIN, previous.smallMinimum);
			client.setVarcIntValue(VarClientID.CAMERA_ZOOM_BIG_MIN, previous.bigMinimum);
			client.runScript(ScriptID.CAMERA_DO_ZOOM, previous.small, previous.big);
		});
	}

	private CompletableFuture<Map<String, Object>> retryWithCamera(
		GenericClientMenuInput.TargetResolver resolver,
		int objectId,
		WorldPoint world,
		int within,
		String action,
		boolean selectedItem,
		boolean breaksEnabled,
		int attempt,
		Map<String, Object> previousReceipt)
	{
		if (attempt >= CAMERA_ATTEMPTS)
		{
			return CompletableFuture.completedFuture(previousReceipt);
		}
		return faceObject(objectId, world, within, action, selectedItem, attempt).thenCompose(target ->
		{
			if (target == null)
			{
				return CompletableFuture.completedFuture(previousReceipt);
			}
			return waitForCamera(target, 0).thenCompose(ignored ->
				menuInput.interact(resolver, breaksEnabled).thenCompose(receipt ->
					shouldFaceAndRetry(receipt)
						? retryWithCamera(
							resolver,
							objectId,
							world,
							within,
							action,
							selectedItem,
							breaksEnabled,
							attempt + 1,
							receipt)
						: CompletableFuture.completedFuture(receipt)));
		});
	}

	static boolean shouldFaceAndRetry(Map<String, Object> receipt)
	{
		Object result = receipt == null ? null : receipt.get("result");
		return "object_not_visible".equals(result) ||
			"hover_has_no_matching_action".equals(result);
	}

	private CompletableFuture<CameraTarget> faceObject(
		int objectId,
		WorldPoint world,
		int within,
		String action,
		boolean selectedItem,
		int attempt)
	{
		CompletableFuture<CameraTarget> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			Player player = client.getLocalPlayer();
			if (player == null || player.getWorldLocation() == null)
			{
				result.complete(null);
				return;
			}
			List<TileObject> matches = findObjects(
				player, objectId, world, within, action, selectedItem);
			if (matches.isEmpty() || matches.get(0).getWorldLocation() == null)
			{
				result.complete(null);
				return;
			}
			int targetYaw = cameraYawForAttempt(
				GenericClientGameInput.yawToward(
					player.getWorldLocation(), matches.get(0).getWorldLocation()),
				attempt);
			client.setCameraYawTarget(targetYaw);
			client.setCameraPitchTarget(GenericClientGameInput.CAMERA_INTERACTION_PITCH);
			result.complete(new CameraTarget(targetYaw));
		});
		return result;
	}

	private CompletableFuture<Void> waitForCamera(CameraTarget target, int attempt)
	{
		CompletableFuture<Void> result = new CompletableFuture<>();
			executor.schedule(() -> clientThread.invoke(() ->
		{
			boolean settled = GenericClientGameInput.angularDistance(
				client.getCameraYaw(), target.yaw) <= CAMERA_SETTLED_UNITS &&
				Math.abs(client.getCameraPitch() - GenericClientGameInput.CAMERA_INTERACTION_PITCH) <=
					CAMERA_SETTLED_UNITS;
			if (settled || attempt + 1 >= CAMERA_POLL_ATTEMPTS)
			{
				result.complete(null);
			}
			else
			{
				waitForCamera(target, attempt + 1).whenComplete((ignored, error) ->
				{
					if (error == null)
					{
						result.complete(null);
					}
					else
					{
						result.completeExceptionally(error);
					}
				});
			}
		}), CAMERA_POLL_MILLIS, TimeUnit.MILLISECONDS);
		return result;
	}

	static int cameraYawForAttempt(int baseYaw, int attempt)
	{
		return (baseYaw + (attempt & 3) * GenericClientGameInput.CAMERA_QUARTER_TURN) &
			GenericClientGameInput.CAMERA_YAW_MASK;
	}

	private static final class CameraTarget
	{
		private final int yaw;

		private CameraTarget(int yaw)
		{
			this.yaw = yaw;
		}
	}

	private static final class CameraZoom
	{
		private final int small;
		private final int big;
		private final int smallMinimum;
		private final int bigMinimum;

		private CameraZoom(int small, int big, int smallMinimum, int bigMinimum)
		{
			this.small = small;
			this.big = big;
			this.smallMinimum = smallMinimum;
			this.bigMinimum = bigMinimum;
		}
	}

	private GenericClientMenuInput.Resolution resolveObject(
		int objectId,
		String action,
		WorldPoint requestedWorld,
		int within,
		boolean selectedItem,
		int itemId,
		String itemName)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		if (selectedItem)
		{
			Widget selected = client.getSelectedWidget();
			if (!client.isWidgetSelected() || selected == null || selected.getItemId() != itemId)
			{
				return GenericClientMenuInput.Resolution.rejected("requested_item_not_selected");
			}
		}
		Player player = client.getLocalPlayer();
		if (player == null || player.getWorldLocation() == null || player.getLocalLocation() == null)
		{
			return GenericClientMenuInput.Resolution.rejected("local_player_unavailable");
		}

		List<TileObject> matches = findObjects(player, objectId, requestedWorld, within, action, selectedItem);
		if (matches.isEmpty())
		{
			return GenericClientMenuInput.Resolution.rejected("matching_object_not_found");
		}
		TileObject object = matches.get(0);
		net.runelite.api.Point canvasLocation = object.getCanvasLocation();
		Shape shape = object.getClickbox();
		if (shape == null)
		{
			shape = object.getCanvasTilePoly();
		}
		Point point = clickPoint(
			shape, canvasLocation, GenericClientMenuInput.viewportBounds(client));
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected("object_not_visible");
		}

		WorldPoint world = object.getWorldLocation();
		Map<String, Object> value = objectMap(object, objectName(object));
		if (selectedItem)
		{
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", (long) itemId);
			item.put("name", itemName);
			value.put("selected_item", item);
		}
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			action,
			"object:" + objectId + ":" + world.getX() + "," + world.getY() + "," + world.getPlane(),
			value,
			entry -> matchesObject(entry, objectId) &&
				(selectedItem
					? entry.getType() == MenuAction.WIDGET_TARGET_ON_GAME_OBJECT
					: action.equalsIgnoreCase(entry.getOption()))));
	}

	static Point clickPoint(
		Shape clickShape,
		net.runelite.api.Point canvasLocation,
		Rectangle viewport)
	{
		Point point = GenericClientMenuInput.randomPointInside(
			clickShape, viewport);
		if (point != null)
		{
			return point;
		}
		if (canvasLocation == null ||
			!viewport.contains(canvasLocation.getX(), canvasLocation.getY()))
		{
			return null;
		}
		return new Point(canvasLocation.getX(), canvasLocation.getY());
	}

	private List<TileObject> findObjects(
		Player player,
		int objectId,
		WorldPoint requestedWorld,
		int within,
		String action,
		boolean selectedItem)
	{
		WorldView worldView = player.getWorldView();
		Scene scene = worldView.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		if (tiles == null || tiles.length == 0)
		{
			return Collections.emptyList();
		}
		int plane = Math.max(0, Math.min(tiles.length - 1, player.getWorldLocation().getPlane()));
		if (tiles[plane].length == 0 || tiles[plane][0].length == 0)
		{
			return Collections.emptyList();
		}
		int minX = Math.max(0, player.getLocalLocation().getSceneX() - within);
		int maxX = Math.min(tiles[plane].length - 1, player.getLocalLocation().getSceneX() + within);
		int minY = Math.max(0, player.getLocalLocation().getSceneY() - within);
		int maxY = Math.min(tiles[plane][0].length - 1, player.getLocalLocation().getSceneY() + within);
		Set<TileObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<TileObject> result = new ArrayList<>();
		for (int sceneX = minX; sceneX <= maxX; sceneX++)
		{
			for (int sceneY = minY; sceneY <= maxY; sceneY++)
			{
				Tile tile = tiles[plane][sceneX][sceneY];
				if (tile == null)
				{
					continue;
				}
				addIfMatches(result, seen, tile.getWallObject(), objectId, requestedWorld, action, selectedItem);
				addIfMatches(result, seen, tile.getGroundObject(), objectId, requestedWorld, action, selectedItem);
				addIfMatches(result, seen, tile.getDecorativeObject(), objectId, requestedWorld, action, selectedItem);
				GameObject[] gameObjects = tile.getGameObjects();
				if (gameObjects != null)
				{
					for (GameObject gameObject : gameObjects)
					{
						addIfMatches(result, seen, gameObject, objectId, requestedWorld, action, selectedItem);
					}
				}
			}
		}
		WorldPoint playerWorld = player.getWorldLocation();
		result.sort(Comparator
			.comparingInt((TileObject object) -> playerWorld.distanceTo(object.getWorldLocation()))
			.thenComparingLong(TileObject::getHash));
		return result;
	}

	private void addIfMatches(
		List<TileObject> result,
		Set<TileObject> seen,
		TileObject object,
		int objectId,
		WorldPoint requestedWorld,
		String action,
		boolean selectedItem)
	{
		if (object == null || !seen.add(object) || object.getId() != objectId ||
			object.getWorldLocation() == null || object.getLocalLocation() == null ||
			(requestedWorld != null && !requestedWorld.equals(object.getWorldLocation())) ||
			(!selectedItem && !hasAction(object, action)))
		{
			return;
		}
		result.add(object);
	}

	private boolean hasAction(TileObject object, String action)
	{
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition != null && composition.getImpostorIds() != null)
		{
			ObjectComposition transformed = composition.getImpostor();
			if (transformed != null)
			{
				composition = transformed;
			}
		}
		String[] actions = composition == null ? null : composition.getActions();
		for (int index = 0; index < 5; index++)
		{
			String override = object.getOpOverride(index);
			String candidate = override != null
				? override
				: actions != null && index < actions.length ? actions[index] : null;
			if (candidate != null && candidate.equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}

	private String objectName(TileObject object)
	{
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition != null && composition.getImpostorIds() != null && composition.getImpostor() != null)
		{
			composition = composition.getImpostor();
		}
		return composition == null ? "<unknown>" : composition.getName();
	}

	static boolean matchesObject(MenuEntry entry, int objectId)
	{
		return entry.getIdentifier() == objectId;
	}

	private static Map<String, Object> objectMap(TileObject object, String name)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "object");
		value.put("id", (long) object.getId());
		value.put("name", name);
		WorldPoint world = object.getWorldLocation();
		Map<String, Object> point = new LinkedHashMap<>();
		point.put("x", (long) world.getX());
		point.put("y", (long) world.getY());
		point.put("plane", (long) world.getPlane());
		value.put("world", point);
		return value;
	}

	private static String requireText(String value, String label)
	{
		if (value == null || value.trim().isEmpty())
		{
			throw new IllegalArgumentException(label + " cannot be empty");
		}
		return value.trim();
	}

	private static void validateRadius(int within)
	{
		if (within < 1 || within > 32)
		{
			throw new IllegalArgumentException("Object interaction radius must be between 1 and 32 tiles");
		}
	}
}
