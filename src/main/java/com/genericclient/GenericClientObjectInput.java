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
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

final class GenericClientObjectInput implements GenericClientWalker.ObstacleInput
{
	private static final int CAMERA_ATTEMPTS = 1;
	private static final int CAMERA_POLL_ATTEMPTS = 30;
	private static final long CAMERA_POLL_MILLIS = 100L;
	private static final int CAMERA_SETTLED_UNITS = 384;

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientMenuInput menuInput;
	private final GenericClientCameraOwner cameraOwner;
	private final GenericClientEntityIds identities;

	GenericClientObjectInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientMenuInput menuInput,
		GenericClientCameraOwner cameraOwner, GenericClientEntityIds identities)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.menuInput = menuInput;
		this.cameraOwner = cameraOwner;
		this.identities = identities;
	}

	@Override
	public CompletableFuture<Map<String,Object>> interact(int id, String action, WorldPoint world, int within,
		GenericClientActivityContext context)
	{
		return interact(id,null,action,world,within,context);
	}

	public CompletableFuture<Map<String, Object>> interact(
		int objectId, Long identity,
		String action,
		WorldPoint world,
		int within,
		GenericClientActivityContext activityContext)
	{
		if (objectId < 0)
		{
			throw new IllegalArgumentException("Object id cannot be negative");
		}
		String cleanAction = requireText(action, "Object action");
		validateRadius(within);
		return interactWithCamera(
			objectId, identity, cleanAction, world, within, false, -1, null, activityContext);
	}

	CompletableFuture<Map<String, Object>> useSelectedItemOnObject(
		int objectId, Long identity,
		WorldPoint world,
		int within,
		int itemId,
		String itemName,
		GenericClientActivityContext activityContext)
	{
		if (objectId < 0 || itemId < 0)
		{
			throw new IllegalArgumentException("Object and item ids cannot be negative");
		}
		validateRadius(within);
		return interactWithCamera(
			objectId, identity, "Use", world, within, true, itemId, itemName, activityContext);
	}

	public void cancel(String reason, GenericClientActivityContext owner)
	{
		cameraOwner.cancel(owner);
		menuInput.cancel(reason, owner);
	}

	private CompletableFuture<Map<String, Object>> interactWithCamera(
		int objectId, Long identity,
		String action,
		WorldPoint world,
		int within,
		boolean selectedItem,
		int itemId,
		String itemName,
		GenericClientActivityContext activityContext)
	{
		GenericClientCameraOwner.Operation cameraOperation = cameraOwner.begin(activityContext);
		GenericClientMenuInput.TargetResolver resolver = () -> resolveObject(
			objectId, identity, action, world, within, selectedItem, itemId, itemName);
		return menuInput.interact(resolver, activityContext).thenCompose(receipt ->
		{
			if (!cameraOperation.isActive() || !shouldFaceAndRetry(receipt))
			{
				return CompletableFuture.completedFuture(receipt);
			}
			return retryWithCamera(
					resolver,
					objectId, identity,
					world,
					within,
					action,
					selectedItem,
					activityContext,
					cameraOperation,
					0,
					receipt);
		});
	}

	private CompletableFuture<Map<String, Object>> retryWithCamera(
		GenericClientMenuInput.TargetResolver resolver,
		int objectId, Long identity,
		WorldPoint world,
		int within,
		String action,
		boolean selectedItem,
		GenericClientActivityContext activityContext,
		GenericClientCameraOwner.Operation cameraOperation,
		int attempt,
		Map<String, Object> previousReceipt)
	{
		if (!cameraOperation.isActive() || attempt >= CAMERA_ATTEMPTS)
		{
			return CompletableFuture.completedFuture(previousReceipt);
		}
		return faceObject(
			objectId, identity, world, within, action, selectedItem, cameraOperation).thenCompose(target ->
		{
			if (target == null)
			{
				return CompletableFuture.completedFuture(previousReceipt);
			}
			return waitForCamera(target, cameraOperation, 0).thenCompose(ignored ->
			{
				if (!cameraOperation.isActive())
				{
					return CompletableFuture.completedFuture(previousReceipt);
				}
				return menuInput.interact(resolver, activityContext).thenCompose(receipt ->
					shouldFaceAndRetry(receipt)
						? retryWithCamera(
							resolver,
							objectId, identity,
							world,
							within,
							action,
							selectedItem,
							activityContext,
							cameraOperation,
							attempt + 1,
							receipt)
						: CompletableFuture.completedFuture(receipt));
			});
		});
	}

	static boolean shouldFaceAndRetry(Map<String, Object> receipt)
	{
		Object result = receipt == null ? null : receipt.get("result");
		return "object_not_visible".equals(result) ||
			"hover_has_no_matching_action".equals(result) ||
			"context_menu_has_no_matching_action".equals(result);
	}

	private CompletableFuture<CameraTarget> faceObject(
		int objectId, Long identity,
		WorldPoint world,
		int within,
		String action,
		boolean selectedItem,
		GenericClientCameraOwner.Operation cameraOperation)
	{
		CompletableFuture<CameraTarget> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			if (!cameraOperation.isActive())
			{
				result.complete(null);
				return;
			}
			Player player = client.getLocalPlayer();
			if (player == null || player.getWorldLocation() == null)
			{
				result.complete(null);
				return;
			}
			List<TileObject> matches = findObjects(
				player, objectId, identity, world, within, action, selectedItem);
			if (matches.isEmpty() || matches.get(0).getWorldLocation() == null)
			{
				result.complete(null);
				return;
			}
			int targetYaw = cameraYawTarget(GenericClientGameInput.yawToward(
				player.getWorldLocation(), matches.get(0).getWorldLocation()));
			boolean started = cameraOperation.face(
				targetYaw, GenericClientGameInput.CAMERA_INTERACTION_PITCH);
			result.complete(started ? new CameraTarget(targetYaw) : null);
		});
		return result;
	}

	private CompletableFuture<Void> waitForCamera(
		CameraTarget target,
		GenericClientCameraOwner.Operation cameraOperation,
		int attempt)
	{
		CompletableFuture<Void> result = new CompletableFuture<>();
		executor.schedule(() -> clientThread.invoke(() ->
		{
			if (!cameraOperation.isActive())
			{
				result.complete(null);
				return;
			}
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
				waitForCamera(target, cameraOperation, attempt + 1).whenComplete((ignored, error) ->
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

	static int cameraYawTarget(int baseYaw)
	{
		return baseYaw & GenericClientGameInput.CAMERA_YAW_MASK;
	}

	private static final class CameraTarget
	{
		private final int yaw;

		private CameraTarget(int yaw)
		{
			this.yaw = yaw;
		}
	}


	private GenericClientMenuInput.Resolution resolveObject(
		int objectId, Long identity,
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

		List<TileObject> matches = findObjects(player, objectId, identity, requestedWorld, within, action, selectedItem);
		if (matches.isEmpty())
		{
			return GenericClientMenuInput.Resolution.rejected("matching_object_not_found");
		}
		TileObject object = matches.get(0);
		long resolvedIdentity = identities.identify(object);
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
			entry -> identities.matches(object,resolvedIdentity) && matchesObject(entry,object) &&
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
		int objectId, Long identity,
		WorldPoint requestedWorld,
		int within,
		String action,
		boolean selectedItem)
	{
		Set<TileObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<TileObject> result = new ArrayList<>();
		GenericClientSceneTiles.visitNearby(player, within, tile ->
		{
			addIfMatches(result, seen, tile.getWallObject(), objectId, identity, requestedWorld, action, selectedItem);
			addIfMatches(result, seen, tile.getGroundObject(), objectId, identity, requestedWorld, action, selectedItem);
			addIfMatches(result, seen, tile.getDecorativeObject(), objectId, identity, requestedWorld, action, selectedItem);
			GameObject[] gameObjects = tile.getGameObjects();
			if (gameObjects != null)
			{
				for (GameObject gameObject : gameObjects)
				{
					addIfMatches(result, seen, gameObject, objectId, identity, requestedWorld, action, selectedItem);
				}
			}
		});
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
		int objectId, Long identity,
		WorldPoint requestedWorld,
		String action,
		boolean selectedItem)
	{
		if (object == null || !seen.add(object) || object.getId() != objectId ||
			(identity != null && !identities.matches(object,identity)) ||
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

	static boolean matchesObject(MenuEntry entry, TileObject object)
	{
		switch (entry.getType())
		{
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case WIDGET_TARGET_ON_GAME_OBJECT:
			case EXAMINE_OBJECT:
				break;
			default: return false;
		}
		net.runelite.api.Point scene = object instanceof GameObject ? ((GameObject)object).getSceneMinLocation() :
			new net.runelite.api.Point(object.getLocalLocation().getSceneX(),object.getLocalLocation().getSceneY());
		return entry.getIdentifier() == object.getId() && entry.getWorldViewId() == object.getWorldView().getId() &&
			entry.getParam0() == scene.getX() && entry.getParam1() == scene.getY();
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
