package com.genericclient;

import java.awt.Point;
import java.awt.Shape;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

final class GenericClientNpcInput
{
	private static final int CAMERA_ATTEMPTS = 1;
	private static final int CAMERA_SETTLED_UNITS = 64;
	private static final long CAMERA_POLL_MILLIS = 40L;
	private static final long CAMERA_SETTLE_TIMEOUT_MILLIS = 3_000L;
	private static final long SCENE_RETRY_MILLIS = 600L;
	private static final int MAX_SCENE_RETRIES = 2;

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientMenuInput menuInput;
	private final GenericClientCameraOwner cameraOwner;
	private final Consumer<String> reporter;
	private final GenericClientEntityIds identities;

	GenericClientNpcInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientMenuInput menuInput,
		GenericClientCameraOwner cameraOwner,
		Consumer<String> reporter, GenericClientEntityIds identities)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.menuInput = menuInput;
		this.cameraOwner = cameraOwner;
		this.reporter = reporter;
		this.identities = identities;
	}

	CompletableFuture<Map<String, Object>> interact(
		Integer id,
		Integer index, Long identity,
		String name,
		String action,
		int within,
		GenericClientActivityContext activityContext)
	{
		if (id != null && id < 0)
		{
			throw new IllegalArgumentException("NPC id cannot be negative");
		}
		String cleanName = name == null ? null : requireText(name, "NPC name");
		if (id == null && cleanName == null)
		{
			throw new IllegalArgumentException("NPC id or name is required");
		}
		String cleanAction = requireText(action, "NPC action");
		validateRadius(within);
		return interactWithCamera(
			cleanName, id, index, identity, cleanAction, within, null, activityContext);
	}

	CompletableFuture<Map<String, Object>> useSelectedItemOnNpc(
		Integer npcId,
		Integer npcIndex, Long identity,
		String npcName,
		int within,
		int itemId,
		String itemName,
		GenericClientActivityContext activityContext)
	{
		validateRadius(within);
		return interactWithCamera(
			npcName,
			npcId,
			npcIndex, identity,
			"Use",
			within,
			SelectedWidget.item(itemId, itemName),
			activityContext);
	}

	CompletableFuture<Map<String, Object>> castSelectedSpellOnNpc(
		Integer npcId,
		Integer npcIndex, Long identity,
		String npcName,
		int within,
		int spellWidgetId,
		String spellName,
		GenericClientActivityContext activityContext)
	{
		validateRadius(within);
		return interactWithCamera(
			npcName,
			npcId,
			npcIndex, identity,
			"Cast",
			within,
			SelectedWidget.spell(spellWidgetId, spellName),
			activityContext);
	}

	private CompletableFuture<Map<String, Object>> interactWithCamera(
		String name,
		Integer id,
		Integer index, Long identity,
		String action,
		int within,
		SelectedWidget requestedSelection,
		GenericClientActivityContext activityContext)
	{
		GenericClientCameraOwner.Operation cameraOperation = cameraOwner.begin(activityContext);
		GenericClientMenuInput.TargetResolver resolver = () -> resolveNpc(
			name, id, index, identity, action, within, requestedSelection);
		return menuInput.interact(resolver, activityContext).thenCompose(receipt ->
		{
			if (!cameraOperation.isActive())
			{
				return CompletableFuture.completedFuture(receipt);
			}
			return retryWithCamera(
				resolver, receipt, name, id, index, identity, within, activityContext,
				cameraOperation, 0, 0);
		});
	}

	private CompletableFuture<Map<String, Object>> retryWithCamera(
		GenericClientMenuInput.TargetResolver resolver,
		Map<String, Object> receipt,
		String name,
		Integer id,
		Integer index, Long identity,
		int within,
		GenericClientActivityContext activityContext,
		GenericClientCameraOwner.Operation cameraOperation,
		int cameraAttempt,
		int sceneAttempt)
	{
		if (!cameraOperation.isActive())
		{
			return CompletableFuture.completedFuture(receipt);
		}
		Object result = receipt.get("result");
		if (!isCameraRetryable(result))
		{
			if (isSceneRetryable(result) && sceneAttempt < MAX_SCENE_RETRIES)
			{
				return retryAfterSceneSettles(
					resolver, receipt, name, id, index, identity, within, activityContext,
					cameraOperation, sceneAttempt);
			}
			return CompletableFuture.completedFuture(receipt);
		}
		if (cameraAttempt >= CAMERA_ATTEMPTS)
		{
			return CompletableFuture.completedFuture(receipt);
		}
		return faceNpc(name, id, index, identity, within, cameraOperation).thenCompose(faced ->
		{
			if (!faced)
			{
				if (sceneAttempt < MAX_SCENE_RETRIES)
				{
					return retryAfterSceneSettles(
						resolver, receipt, name, id, index, identity, within, activityContext,
						cameraOperation, sceneAttempt);
				}
				return CompletableFuture.completedFuture(receipt);
			}
			if (!cameraOperation.isActive())
			{
				return CompletableFuture.completedFuture(receipt);
			}
			return menuInput.interact(resolver, activityContext)
				.thenCompose(next -> retryWithCamera(
					resolver, next, name, id, index, identity, within, activityContext,
					cameraOperation, cameraAttempt + 1, sceneAttempt));
		});
	}

	private CompletableFuture<Map<String, Object>> retryAfterSceneSettles(
		GenericClientMenuInput.TargetResolver resolver,
		Map<String, Object> previousReceipt,
		String name,
		Integer id,
		Integer index, Long identity,
		int within,
		GenericClientActivityContext activityContext,
		GenericClientCameraOwner.Operation cameraOperation,
		int sceneAttempt)
	{
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		int nextAttempt = sceneAttempt + 1;
		reporter.accept("NPC_SCENE_RETRY_SCHEDULED attempt=" + nextAttempt +
			" result=" + previousReceipt.get("result"));
		executor.schedule(() ->
		{
			if (!cameraOperation.isActive())
			{
				result.complete(previousReceipt);
				return;
			}
			menuInput.interact(resolver, activityContext)
				.thenCompose(next -> retryWithCamera(
					resolver, next, name, id, index, identity, within, activityContext,
					cameraOperation, 0, nextAttempt))
				.whenComplete((receipt, error) ->
				{
					if (error == null)
					{
						result.complete(receipt);
					}
					else
					{
						result.completeExceptionally(error);
					}
				});
		},
			SCENE_RETRY_MILLIS,
			TimeUnit.MILLISECONDS);
		return result;
	}

	static boolean isCameraRetryable(Object result)
	{
		return "npc_not_visible".equals(result) ||
			"hover_has_no_matching_action".equals(result);
	}

	static boolean isSceneRetryable(Object result)
	{
		return "matching_npc_not_found".equals(result) ||
			"client_not_logged_in".equals(result);
	}

	private CompletableFuture<Boolean> faceNpc(
		String name,
		Integer id,
		Integer index, Long identity,
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
			if (player == null || player.getWorldLocation() == null)
			{
				result.complete(false);
				return;
			}
			NPC target = closestNpc(player, name, id, index, identity, within);
			if (target == null)
			{
				result.complete(false);
				return;
			}
			int targetYaw = GenericClientGameInput.yawToward(
				player.getWorldLocation(), target.getWorldLocation());
			boolean started = cameraOperation.face(
				targetYaw, GenericClientGameInput.CAMERA_INTERACTION_PITCH);
			if (started)
			{
				reporter.accept("NPC_CAMERA_TURN_STARTED id=" + target.getId() +
					" yaw=" + client.getCameraYaw() + " targetYaw=" + targetYaw +
					" pitch=" + client.getCameraPitch() +
					" targetPitch=" + GenericClientGameInput.CAMERA_INTERACTION_PITCH);
			}
			if (!started)
			{
				result.complete(false);
				return;
			}
			awaitCameraSettled(
				target.getId(),
				targetYaw,
				System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CAMERA_SETTLE_TIMEOUT_MILLIS),
				cameraOperation,
				result);
		});
		return result;
	}

	private NPC closestNpc(Player player, String name, Integer id, Integer index, Long identity, int within)
	{
		NPC closest = null;
		int closestDistance = Integer.MAX_VALUE;
		for (NPC npc : player.getWorldView().npcs())
		{
			if (npc == null || npc.getWorldLocation() == null ||
				(id != null && npc.getId() != id) ||
			(index != null && npc.getIndex() != index) ||
			(identity != null && !identities.matches(npc,identity)) ||
				(name != null && !name.equalsIgnoreCase(Objects.toString(npc.getName(), ""))))
			{
				continue;
			}
			int distance = player.getWorldLocation().distanceTo(npc.getWorldLocation());
			if (distance <= within && distance < closestDistance)
			{
				closest = npc;
				closestDistance = distance;
			}
		}
		return closest;
	}

	private void awaitCameraSettled(
		int npcId,
		int targetYaw,
		long deadlineNanos,
		GenericClientCameraOwner.Operation cameraOperation,
		CompletableFuture<Boolean> result)
	{
		if (result.isDone())
		{
			return;
		}
		if (!cameraOperation.isActive())
		{
			result.complete(false);
			return;
		}
		int yaw = client.getCameraYaw();
		int pitch = client.getCameraPitch();
		int yawRemaining = GenericClientGameInput.angularDistance(yaw, targetYaw);
		int pitchRemaining = Math.abs(pitch - GenericClientGameInput.CAMERA_INTERACTION_PITCH);
		boolean timedOut = System.nanoTime() >= deadlineNanos;
		if ((yawRemaining <= CAMERA_SETTLED_UNITS && pitchRemaining <= CAMERA_SETTLED_UNITS) || timedOut)
		{
			reporter.accept("NPC_CAMERA_TURN_" + (timedOut ? "TIMED_OUT" : "COMPLETED") +
				" id=" + npcId + " yaw=" + yaw + " targetYaw=" + targetYaw +
				" pitch=" + pitch + " targetPitch=" + GenericClientGameInput.CAMERA_INTERACTION_PITCH +
				" yawRemaining=" + yawRemaining + " pitchRemaining=" + pitchRemaining);
			result.complete(true);
			return;
		}
		executor.schedule(
			() -> clientThread.invoke(() ->
				awaitCameraSettled(
					npcId, targetYaw, deadlineNanos, cameraOperation, result)),
			CAMERA_POLL_MILLIS,
			TimeUnit.MILLISECONDS);
	}

	private GenericClientMenuInput.Resolution resolveNpc(
		String name,
		Integer id,
		Integer index, Long identity,
		String action,
		int within,
		SelectedWidget requestedSelection)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		String selectionFailure = selectionFailure(requestedSelection);
		if (selectionFailure != null)
		{
			return GenericClientMenuInput.Resolution.rejected(selectionFailure);
		}
		Player player = client.getLocalPlayer();
		if (player == null || player.getWorldLocation() == null)
		{
			return GenericClientMenuInput.Resolution.rejected("local_player_unavailable");
		}

		NpcSearch search = findNpc(player, name, id, index, identity, action, within, requestedSelection);
		if (search.npc == null)
		{
			return missingNpc(search, requestedSelection);
		}

		NPC targetNpc = search.npc;
		long resolvedIdentity = identities.identify(targetNpc);
		Map<String, Object> value = npcMap(targetNpc);
		if (requestedSelection != null)
		{
			value.put("selected_" + requestedSelection.kind, requestedSelection.toMap());
		}
		String description = "npc:" + targetNpc.getId() + ":" + targetNpc.getIndex();
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			search.point,
			action,
			description,
			value,
			entry -> identities.matches(targetNpc,resolvedIdentity) && menuEntryMatches(entry, targetNpc, action, requestedSelection),
			search.shape));
	}

	private String selectionFailure(SelectedWidget requestedSelection)
	{
		if (requestedSelection == null)
		{
			return null;
		}
		Widget selected = client.getSelectedWidget();
		return client.isWidgetSelected() && selected != null && requestedSelection.matches(selected)
			? null
			: "requested_" + requestedSelection.kind + "_not_selected";
	}

	private NpcSearch findNpc(
		Player player,
		String name,
		Integer id,
		Integer index, Long identity,
		String action,
		int within,
		SelectedWidget requestedSelection)
	{
		NpcSearch search = new NpcSearch();
		WorldView worldView = player.getWorldView();
		for (NPC npc : worldView.npcs())
		{
			if (!matchesRequest(npc, player, name, id, index, identity, action, requestedSelection))
			{
				continue;
			}
			int distance = player.getWorldLocation().distanceTo(npc.getWorldLocation());
			if (distance > within)
			{
				continue;
			}
			search.matchingNpcExists = true;
			if (usesSelectedSpell(requestedSelection) && !hasLineOfSight(player, npc))
			{
				continue;
			}
			search.matchingNpcHasLineOfSight = true;
			Shape shape = npc.getConvexHull();
			if (shape == null)
			{
				shape = npc.getCanvasTilePoly();
			}
			Point point = GenericClientMenuInput.randomPointInside(
				shape, GenericClientMenuInput.viewportBounds(client));
			search.consider(npc, point, shape, distance);
		}
		return search;
	}

	private boolean matchesRequest(
		NPC npc,
		Player player,
		String name,
		Integer id,
		Integer index, Long identity,
		String action,
		SelectedWidget requestedSelection)
	{
		if (npc == null || npc.getWorldLocation() == null ||
			(id != null && npc.getId() != id) ||
			(index != null && npc.getIndex() != index) ||
			(identity != null && !identities.matches(npc,identity)) ||
			(name != null && !name.equalsIgnoreCase(Objects.toString(npc.getName(), ""))) ||
			(requestedSelection == null && !hasAction(npc, action)))
		{
			return false;
		}
		return npc.getInteracting() == null || npc.getInteracting() == player ||
			(!usesSelectedSpell(requestedSelection) && !"Attack".equalsIgnoreCase(action));
	}

	private static GenericClientMenuInput.Resolution missingNpc(
		NpcSearch search,
		SelectedWidget requestedSelection)
	{
		if (search.matchingNpcExists && usesSelectedSpell(requestedSelection) &&
			!search.matchingNpcHasLineOfSight)
		{
			return GenericClientMenuInput.Resolution.rejected("npc_no_line_of_sight");
		}
		return GenericClientMenuInput.Resolution.rejected(
			search.matchingNpcExists ? "npc_not_visible" : "matching_npc_not_found");
	}

	private static boolean menuEntryMatches(
		MenuEntry entry,
		NPC npc,
		String action,
		SelectedWidget requestedSelection)
	{
		return entry.getNpc() == npc && (requestedSelection == null
			? action.equalsIgnoreCase(entry.getOption())
			: entry.getType() == MenuAction.WIDGET_TARGET_ON_NPC);
	}

	private static boolean usesSelectedSpell(SelectedWidget requestedSelection)
	{
		return requestedSelection != null && "spell".equals(requestedSelection.kind);
	}

	static boolean hasLineOfSight(Player player, NPC npc)
	{
		return player != null && npc != null &&
			player.getWorldArea() != null && npc.getWorldArea() != null &&
			player.getWorldArea().hasLineOfSightTo(player.getWorldView(), npc.getWorldArea());
	}

	private static boolean hasAction(NPC npc, String action)
	{
		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null)
		{
			composition = npc.getComposition();
		}
		if (composition == null || composition.getActions() == null)
		{
			return false;
		}
		for (String candidate : composition.getActions())
		{
			if (candidate != null && candidate.equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}

	private static Map<String, Object> npcMap(NPC npc)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "npc");
		value.put("index", (long) npc.getIndex());
		value.put("id", (long) npc.getId());
		value.put("name", npc.getName());
		WorldPoint world = npc.getWorldLocation();
		if (world != null)
		{
			Map<String, Object> point = new LinkedHashMap<>();
			point.put("x", (long) world.getX());
			point.put("y", (long) world.getY());
			point.put("plane", (long) world.getPlane());
			value.put("world", Collections.unmodifiableMap(point));
		}
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
			throw new IllegalArgumentException("NPC interaction radius must be between 1 and 32 tiles");
		}
	}

	private static final class NpcSearch
	{
		private NPC npc;
		private Point point;
		private Shape shape;
		private int distance = Integer.MAX_VALUE;
		private boolean matchingNpcExists;
		private boolean matchingNpcHasLineOfSight;

		private void consider(NPC candidate, Point candidatePoint, Shape candidateShape, int candidateDistance)
		{
			if (candidatePoint == null || candidateDistance >= distance)
			{
				return;
			}
			npc = candidate;
			point = candidatePoint;
			shape = candidateShape;
			distance = candidateDistance;
		}
	}

	private static final class SelectedWidget
	{
		private final String kind;
		private final int id;
		private final String name;

		private SelectedWidget(String kind, int id, String name)
		{
			this.kind = kind;
			this.id = id;
			this.name = name;
		}

		private static SelectedWidget item(int itemId, String itemName)
		{
			return new SelectedWidget("item", itemId, itemName);
		}

		private static SelectedWidget spell(int widgetId, String spellName)
		{
			return new SelectedWidget("spell", widgetId, spellName);
		}

		private boolean matches(Widget widget)
		{
			return "item".equals(kind) ? widget.getItemId() == id : widget.getId() == id;
		}

		private Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("id", (long) id);
			value.put("name", name);
			return value;
		}
	}
}
