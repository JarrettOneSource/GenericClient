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
	private static final int[] CAMERA_YAW_OFFSETS = {
		0,
		GenericClientGameInput.CAMERA_QUARTER_TURN,
		GenericClientGameInput.CAMERA_QUARTER_TURN * 2,
		GenericClientGameInput.CAMERA_QUARTER_TURN * 3,
	};
	private static final int CAMERA_SETTLED_UNITS = 64;
	private static final long CAMERA_POLL_MILLIS = 40L;
	private static final long CAMERA_SETTLE_TIMEOUT_MILLIS = 3_000L;

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientMenuInput menuInput;
	private final Consumer<String> reporter;

	GenericClientNpcInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientMenuInput menuInput,
		Consumer<String> reporter)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.menuInput = menuInput;
		this.reporter = reporter;
	}

	CompletableFuture<Map<String, Object>> interact(
		String name,
		String action,
		int within,
		GenericClientActivityContext activityContext)
	{
		return interact(null, name, action, within, activityContext);
	}

	CompletableFuture<Map<String, Object>> interact(
		Integer id,
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
			cleanName, id, cleanAction, within, null, activityContext);
	}

	CompletableFuture<Map<String, Object>> useSelectedItemOnNpc(
		Integer npcId,
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
			"Use",
			within,
			SelectedWidget.item(itemId, itemName),
			activityContext);
	}

	CompletableFuture<Map<String, Object>> castSelectedSpellOnNpc(
		Integer npcId,
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
			"Cast",
			within,
			SelectedWidget.spell(spellWidgetId, spellName),
			activityContext);
	}

	private CompletableFuture<Map<String, Object>> interactWithCamera(
		String name,
		Integer id,
		String action,
		int within,
		SelectedWidget requestedSelection,
		GenericClientActivityContext activityContext)
	{
		GenericClientMenuInput.TargetResolver resolver = () -> resolveNpc(
			name, id, action, within, requestedSelection);
		return menuInput.interact(resolver, activityContext).thenCompose(receipt ->
			retryWithCamera(
				resolver, receipt, name, id, within, activityContext, 0));
	}

	private CompletableFuture<Map<String, Object>> retryWithCamera(
		GenericClientMenuInput.TargetResolver resolver,
		Map<String, Object> receipt,
		String name,
		Integer id,
		int within,
		GenericClientActivityContext activityContext,
		int cameraAttempt)
	{
		if (!isCameraRetryable(receipt.get("result")) ||
			cameraAttempt >= CAMERA_YAW_OFFSETS.length)
		{
			return CompletableFuture.completedFuture(receipt);
		}
		return faceNpc(name, id, within, CAMERA_YAW_OFFSETS[cameraAttempt]).thenCompose(faced ->
		{
			if (!faced)
			{
				return CompletableFuture.completedFuture(receipt);
			}
			return menuInput.interact(resolver, activityContext)
				.thenCompose(next -> retryWithCamera(
					resolver, next, name, id, within, activityContext, cameraAttempt + 1));
		});
	}

	static boolean isCameraRetryable(Object result)
	{
		return "npc_not_visible".equals(result) ||
			"hover_has_no_matching_action".equals(result);
	}

	private CompletableFuture<Boolean> faceNpc(
		String name,
		Integer id,
		int within,
		int yawOffset)
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			Player player = client.getLocalPlayer();
			if (player == null || player.getWorldLocation() == null)
			{
				result.complete(false);
				return;
			}
			NPC closest = null;
			int closestDistance = Integer.MAX_VALUE;
			for (NPC npc : player.getWorldView().npcs())
			{
				if (npc == null || npc.getWorldLocation() == null ||
					(id != null && npc.getId() != id) ||
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
			if (closest == null)
			{
				result.complete(false);
				return;
			}
			int targetYaw = (GenericClientGameInput.yawToward(
				player.getWorldLocation(), closest.getWorldLocation()) + yawOffset) &
				GenericClientGameInput.CAMERA_YAW_MASK;
			client.setCameraYawTarget(targetYaw);
			client.setCameraPitchTarget(GenericClientGameInput.CAMERA_INTERACTION_PITCH);
			reporter.accept("NPC_CAMERA_TURN_STARTED id=" + closest.getId() +
				" yaw=" + client.getCameraYaw() + " targetYaw=" + targetYaw +
				" pitch=" + client.getCameraPitch() +
				" targetPitch=" + GenericClientGameInput.CAMERA_INTERACTION_PITCH);
			awaitCameraSettled(
				closest.getId(),
				targetYaw,
				System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CAMERA_SETTLE_TIMEOUT_MILLIS),
				result);
		});
		return result;
	}

	private void awaitCameraSettled(
		int npcId,
		int targetYaw,
		long deadlineNanos,
		CompletableFuture<Boolean> result)
	{
		if (result.isDone())
		{
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
				awaitCameraSettled(npcId, targetYaw, deadlineNanos, result)),
			CAMERA_POLL_MILLIS,
			TimeUnit.MILLISECONDS);
	}

	private GenericClientMenuInput.Resolution resolveNpc(
		String name,
		Integer id,
		String action,
		int within,
		SelectedWidget requestedSelection)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		if (requestedSelection != null)
		{
			Widget selected = client.getSelectedWidget();
			if (!client.isWidgetSelected() || selected == null || !requestedSelection.matches(selected))
			{
				return GenericClientMenuInput.Resolution.rejected(
					"requested_" + requestedSelection.kind + "_not_selected");
			}
		}
		Player player = client.getLocalPlayer();
		if (player == null || player.getWorldLocation() == null)
		{
			return GenericClientMenuInput.Resolution.rejected("local_player_unavailable");
		}

		WorldView worldView = player.getWorldView();
		NPC nearest = null;
		Point nearestPoint = null;
		Shape nearestShape = null;
		int nearestDistance = Integer.MAX_VALUE;
		boolean matchingNpcExists = false;
		boolean matchingNpcHasLineOfSight = false;
		for (NPC npc : worldView.npcs())
		{
			if (npc == null || npc.getWorldLocation() == null ||
				(id != null && npc.getId() != id) ||
				(name != null && !name.equalsIgnoreCase(Objects.toString(npc.getName(), ""))) ||
				(requestedSelection == null && !hasAction(npc, action)) ||
				(requestedSelection != null && "spell".equals(requestedSelection.kind) &&
					npc.getInteracting() != null && npc.getInteracting() != player) ||
				("Attack".equalsIgnoreCase(action) &&
					npc.getInteracting() != null && npc.getInteracting() != player))
			{
				continue;
			}
			int distance = player.getWorldLocation().distanceTo(npc.getWorldLocation());
			if (distance > within)
			{
				continue;
			}
			matchingNpcExists = true;
			if (requestedSelection != null && "spell".equals(requestedSelection.kind) &&
				!hasLineOfSight(player, npc))
			{
				continue;
			}
			matchingNpcHasLineOfSight = true;
			Shape candidateShape = npc.getConvexHull();
			if (candidateShape == null)
			{
				candidateShape = npc.getCanvasTilePoly();
			}
			Point candidatePoint = GenericClientMenuInput.randomPointInside(
				candidateShape, GenericClientMenuInput.viewportBounds(client));
			if (candidatePoint != null && distance < nearestDistance)
			{
				nearest = npc;
				nearestPoint = candidatePoint;
				nearestShape = candidateShape;
				nearestDistance = distance;
			}
		}
		if (nearest == null)
		{
			if (matchingNpcExists && requestedSelection != null &&
				"spell".equals(requestedSelection.kind) && !matchingNpcHasLineOfSight)
			{
				return GenericClientMenuInput.Resolution.rejected("npc_no_line_of_sight");
			}
			return GenericClientMenuInput.Resolution.rejected(
				matchingNpcExists ? "npc_not_visible" : "matching_npc_not_found");
		}

		NPC targetNpc = nearest;
		Map<String, Object> value = npcMap(targetNpc);
		if (requestedSelection != null)
		{
			value.put("selected_" + requestedSelection.kind, requestedSelection.toMap());
		}
		String description = "npc:" + targetNpc.getId() + ":" + targetNpc.getIndex();
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			nearestPoint,
			action,
			description,
			value,
			entry -> requestedSelection != null
				? matchesNpc(entry, targetNpc) && entry.getType() == MenuAction.WIDGET_TARGET_ON_NPC
				: matchesNpc(entry, targetNpc) && action.equalsIgnoreCase(entry.getOption()),
			nearestShape));
	}

	static boolean hasLineOfSight(Player player, NPC npc)
	{
		return player != null && npc != null &&
			player.getWorldArea() != null && npc.getWorldArea() != null &&
			player.getWorldArea().hasLineOfSightTo(player.getWorldView(), npc.getWorldArea());
	}

	static int findNpcEntryIndex(MenuEntry[] entries, int npcIndex, String action)
	{
		for (int index = entries.length - 1; index >= 0; index--)
		{
			MenuEntry entry = entries[index];
			NPC resolvedNpc = entry.getNpc();
			boolean sameNpc = resolvedNpc == null
				? entry.getIdentifier() == npcIndex
				: resolvedNpc.getIndex() == npcIndex;
			if (sameNpc && action.equalsIgnoreCase(entry.getOption()))
			{
				return index;
			}
		}
		return -1;
	}

	private static boolean matchesNpc(MenuEntry entry, NPC npc)
	{
		NPC resolved = entry.getNpc();
		return resolved == null
			? entry.getIdentifier() == npc.getIndex()
			: resolved.getIndex() == npc.getIndex();
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
