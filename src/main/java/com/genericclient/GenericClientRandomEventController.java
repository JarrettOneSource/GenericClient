package com.genericclient;

import static com.genericclient.GenericClientErrors.rootMessage;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.gameval.NpcID;

/** Owns GenericClient's random-event detection and latched attention state. */
final class GenericClientRandomEventController
{
	private static final Set<Integer> EVENT_NPCS = Set.of(
		NpcID.MACRO_BEEKEEPER_INVITATION,
		NpcID.MACRO_COMBILOCK_PIRATE,
		NpcID.MACRO_JEKYLL,
		NpcID.MACRO_JEKYLL_UNDERWATER,
		NpcID.MACRO_DWARF,
		NpcID.PATTERN_INVITATION,
		NpcID.MACRO_EVIL_BOB_OUTSIDE,
		NpcID.MACRO_EVIL_BOB_PRISON,
		NpcID.PINBALL_INVITATION,
		NpcID.MACRO_FORESTER_INVITATION,
		NpcID.MACRO_FROG_CRIER,
		NpcID.MACRO_GENI,
		NpcID.MACRO_GENI_UNDERWATER,
		NpcID.MACRO_GILES,
		NpcID.MACRO_GILES_UNDERWATER,
		NpcID.MACRO_GRAVEDIGGER_INVITATION,
		NpcID.MACRO_MILES,
		NpcID.MACRO_MILES_UNDERWATER,
		NpcID.MACRO_MYSTERIOUS_OLD_MAN,
		NpcID.MACRO_MYSTERIOUS_OLD_MAN_UNDERWATER,
		NpcID.MACRO_MAZE_INVITATION,
		NpcID.MACRO_MIME_INVITATION,
		NpcID.MACRO_NILES,
		NpcID.MACRO_NILES_UNDERWATER,
		NpcID.MACRO_PILLORY_GUARD,
		NpcID.GRAB_POSTMAN,
		NpcID.MACRO_MAGNESON_INVITATION,
		NpcID.MACRO_HIGHWAYMAN,
		NpcID.MACRO_HIGHWAYMAN_UNDERWATER,
		NpcID.MACRO_SANDWICH_LADY_NPC,
		NpcID.MACRO_DRILLDEMON_INVITATION,
		NpcID.MACRO_COUNTCHECK_SURFACE,
		NpcID.MACRO_COUNTCHECK_UNDERWATER);

	private final SolverLookup solverLookup;
	private final Runtime runtime;
	private final TalkAction talkAction;
	private final Consumer<String> alert;
	private final Consumer<String> reporter;
	private final Clock clock;

	private volatile Map<String, Object> publishedStatus = idleStatus();
	private EventRecord current;

	GenericClientRandomEventController(
		SolverLookup solverLookup,
		Runtime runtime,
		Consumer<String> alert,
		Consumer<String> reporter)
	{
		this(solverLookup, runtime, null, alert, reporter, Clock.systemUTC());
	}

	GenericClientRandomEventController(
		SolverLookup solverLookup,
		Runtime runtime,
		Consumer<String> alert,
		Consumer<String> reporter,
		Clock clock)
	{
		this(solverLookup, runtime, null, alert, reporter, clock);
	}

	GenericClientRandomEventController(
		SolverLookup solverLookup,
		Runtime runtime,
		TalkAction talkAction,
		Consumer<String> alert,
		Consumer<String> reporter)
	{
		this(solverLookup, runtime, talkAction, alert, reporter, Clock.systemUTC());
	}

	GenericClientRandomEventController(
		SolverLookup solverLookup,
		Runtime runtime,
		TalkAction talkAction,
		Consumer<String> alert,
		Consumer<String> reporter,
		Clock clock)
	{
		this.solverLookup = solverLookup;
		this.runtime = runtime;
		this.talkAction = talkAction;
		this.alert = alert;
		this.reporter = reporter;
		this.clock = clock;
	}

	void onInteractingChanged(Player localPlayer, InteractingChanged event, long gameTick)
	{
		Actor source = event.getSource();
		if (!isOwnedRandomEvent(localPlayer, source, event.getTarget()))
		{
			return;
		}

		NPC npc = (NPC) source;
		EventRecord detected;
		synchronized (this)
		{
			if (current != null && current.active && current.npc == npc)
			{
				current.present = true;
				publishCurrent();
				return;
			}
			if (current != null && current.active)
			{
				reporter.accept("RANDOM_EVENT_ADDITIONAL_IGNORED npc_id=" + npc.getId() +
					" pending_event=" + current.eventKey);
				return;
			}

			String solverScript = solverLookup.find(npc.getId());
			detected = EventRecord.capture(npc, gameTick, clock.instant(), solverScript);
			current = detected;
			publishCurrent();
		}

		reporter.accept("RANDOM_EVENT_DETECTED event=" + detected.eventKey +
			" npc_id=" + detected.npcId +
			" npc=" + detected.npcName +
			" solver=" + (detected.solverScript == null ? "unregistered" : detected.solverScript));
		alert.accept("Random event detected: " + detected.npcName);
		String deferredActivity = runtime.randomEventDeferralActivity();
		if (deferredActivity != null && !deferredActivity.isBlank())
		{
			synchronized (this)
			{
				detected.state = "deferred_activity";
				detected.solverStatus = "deferred_activity";
				detected.deferredActivity = deferredActivity;
				detected.autoTalkStatus = "not_started";
				publishCurrent();
			}
			reporter.accept("RANDOM_EVENT_DEFERRED event=" + detected.eventKey +
				" activity=" + deferredActivity);
			return;
		}
		if (!runtime.isAutomationActive())
		{
			synchronized (this)
			{
				detected.state = "attention_required";
				detected.solverStatus = "deferred_idle";
				detected.autoTalkStatus = "not_started";
				publishCurrent();
			}
			reporter.accept("RANDOM_EVENT_DEFERRED_IDLE event=" + detected.eventKey);
			return;
		}
		try
		{
			runtime.interrupt(detected.eventKey, detected.solverScript)
				.whenComplete((result, error) ->
					interruptionFinished(detected.eventKey, result, error));
		}
		catch (RuntimeException exception)
		{
			interruptionFinished(detected.eventKey, null, exception);
		}
	}

	void onNpcDespawned(NpcDespawned event, long gameTick)
	{
		synchronized (this)
		{
			if (current == null || !current.active || current.npc != event.getNpc())
			{
				return;
			}
			current.present = false;
			current.despawnedTick = gameTick;
			if ("deferred_activity".equals(current.state))
			{
				current.active = false;
				current.state = "completed";
				current.resolution = "deferred_activity_despawned";
				current.completedAt = clock.instant();
				reporter.accept("RANDOM_EVENT_DEFERRED_COMPLETED event=" +
					current.eventKey + " activity=" + current.deferredActivity +
					" despawn_tick=" + gameTick);
			}
			publishCurrent();
		}
	}

	CompletableFuture<Map<String, Object>> acknowledge()
	{
		synchronized (this)
		{
			ensureActive();
			current.acknowledged = true;
			publishCurrent();
			reporter.accept("RANDOM_EVENT_ACKNOWLEDGED event=" + current.eventKey);
			return CompletableFuture.completedFuture(status());
		}
	}

	CompletableFuture<Map<String, Object>> complete(String reason, boolean resumeInterrupted)
	{
		return resolve(requireReason(reason), resumeInterrupted);
	}

	void solverFinished(String eventKey, String terminalStatus, String error)
	{
		if ("COMPLETED".equals(terminalStatus))
		{
			resolveForSolver(eventKey);
			return;
		}

		synchronized (this)
		{
			if (!matchesActive(eventKey))
			{
				return;
			}
			current.state = "attention_required";
			current.solverStatus = "faulted";
			current.lastError = error == null ? "solver_" + terminalStatus.toLowerCase() : error;
			publishCurrent();
			reporter.accept("RANDOM_EVENT_SOLVER_FAILED event=" + eventKey +
				" status=" + terminalStatus + " message=" + current.lastError);
		}
	}

	Map<String, Object> status()
	{
		return new LinkedHashMap<>(publishedStatus);
	}

	static boolean isRandomEventNpcId(int npcId)
	{
		return EVENT_NPCS.contains(npcId);
	}

	static boolean isOwnedRandomEvent(Player localPlayer, Actor source, Actor target)
	{
		return localPlayer != null &&
			target == localPlayer &&
			localPlayer.getInteracting() != source &&
			source instanceof NPC &&
			EVENT_NPCS.contains(((NPC) source).getId());
	}

	private void interruptionFinished(String eventKey, String result, Throwable error)
	{
		Integer npcIdToTalk = null;
		synchronized (this)
		{
			if (!matchesActive(eventKey))
			{
				return;
			}
			if (error != null)
			{
				current.state = "attention_required";
				current.solverStatus = "start_failed";
				current.lastError = rootMessage(error);
				reporter.accept("RANDOM_EVENT_INTERRUPT_FAILED event=" + eventKey +
					" message=" + current.lastError);
			}
			else if (current.solverScript == null)
			{
				current.state = "attention_required";
				current.solverStatus = "unregistered";
				if (talkAction == null)
				{
					current.autoTalkStatus = "unavailable";
				}
				else
				{
					current.autoTalkStatus = "starting";
					npcIdToTalk = current.npcId;
				}
			}
			else if ("solver_starting".equals(current.state))
			{
				current.state = "solver_running";
				current.solverStatus = "running";
				reporter.accept("RANDOM_EVENT_SOLVER_STARTED event=" + eventKey +
					" script=" + current.solverScript + " result=" + result);
			}
			publishCurrent();
		}
		if (npcIdToTalk != null)
		{
			startAutoTalk(eventKey, npcIdToTalk);
		}
	}

	private void startAutoTalk(String eventKey, int npcId)
	{
		reporter.accept("RANDOM_EVENT_AUTO_TALK_START event=" + eventKey + " npc_id=" + npcId);
		try
		{
			talkAction.talk(npcId).whenComplete((receipt, error) ->
				autoTalkFinished(eventKey, receipt, error));
		}
		catch (RuntimeException exception)
		{
			autoTalkFinished(eventKey, null, exception);
		}
	}

	private void autoTalkFinished(
		String eventKey,
		Map<String, Object> receipt,
		Throwable error)
	{
		synchronized (this)
		{
			if (!matchesActive(eventKey))
			{
				return;
			}
			if (error != null)
			{
				current.autoTalkStatus = "failed";
				current.autoTalkResult = rootMessage(error);
			}
			else
			{
				Object status = receipt == null ? null : receipt.get("status");
				Object result = receipt == null ? null : receipt.get("result");
				current.autoTalkStatus = "dispatched".equals(status)
					? "dispatched"
					: "rejected";
				Object detail = result == null ? status : result;
				current.autoTalkResult = detail == null ? "no_receipt" : String.valueOf(detail);
			}
			publishCurrent();
			reporter.accept("RANDOM_EVENT_AUTO_TALK_FINISHED event=" + eventKey +
				" status=" + current.autoTalkStatus +
				" result=" + current.autoTalkResult);
		}
	}

	private void resolveForSolver(String eventKey)
	{
		synchronized (this)
		{
			if (!matchesActive(eventKey))
			{
				return;
			}
		}
		resolve("solver_completed", true);
	}

	private CompletableFuture<Map<String, Object>> resolve(
		String reason,
		boolean resumeInterrupted)
	{
		EventRecord resolving;
		boolean runtimeOwned;
		synchronized (this)
		{
			ensureActive();
			resolving = current;
			runtimeOwned = !"deferred_idle".equals(resolving.solverStatus) &&
				!"deferred_activity".equals(resolving.solverStatus);
			resolving.state = "releasing";
			resolving.resolution = reason;
			publishCurrent();
		}

		final CompletableFuture<String> release;
		try
		{
			release = runtimeOwned
				? runtime.release(resolving.eventKey, resumeInterrupted)
				: CompletableFuture.completedFuture(
					"RANDOM_EVENT_RELEASED event=" + resolving.eventKey +
						" deferred_idle=true");
		}
		catch (RuntimeException exception)
		{
			return releaseFailed(resolving, exception);
		}
		return release.handle((result, error) ->
		{
			synchronized (GenericClientRandomEventController.this)
			{
				if (current != resolving)
				{
					return status();
				}
				if (error != null)
				{
					resolving.state = "attention_required";
					resolving.lastError = rootMessage(error);
					publishCurrent();
					reporter.accept("RANDOM_EVENT_RELEASE_FAILED event=" + resolving.eventKey +
						" message=" + resolving.lastError);
					return status();
				}
				resolving.active = false;
				resolving.state = "completed";
				resolving.solverStatus = resolving.solverScript == null
					? "unregistered"
					: "completed";
				resolving.completedAt = clock.instant();
				resolving.lastError = null;
				publishCurrent();
				reporter.accept("RANDOM_EVENT_COMPLETED event=" + resolving.eventKey +
					" resolution=" + reason + " resume=" + resumeInterrupted + " result=" + result);
				return status();
			}
		});
	}

	private CompletableFuture<Map<String, Object>> releaseFailed(
		EventRecord resolving,
		RuntimeException error)
	{
		synchronized (this)
		{
			resolving.state = "attention_required";
			resolving.lastError = rootMessage(error);
			publishCurrent();
			reporter.accept("RANDOM_EVENT_RELEASE_FAILED event=" + resolving.eventKey +
				" message=" + resolving.lastError);
			return CompletableFuture.completedFuture(status());
		}
	}

	private boolean matchesActive(String eventKey)
	{
		return current != null && current.active && current.eventKey.equals(eventKey);
	}

	private void ensureActive()
	{
		if (current == null || !current.active)
		{
			throw new IllegalStateException("No random event is awaiting completion");
		}
	}

	private void publishCurrent()
	{
		publishedStatus = Collections.unmodifiableMap(current.toMap());
	}

	private static Map<String, Object> idleStatus()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("available", true);
		value.put("active", false);
		value.put("blocks_automation", false);
		value.put("attention_required", false);
		value.put("state", "idle");
		return Collections.unmodifiableMap(value);
	}

	private static String requireReason(String reason)
	{
		if (reason == null || reason.trim().isEmpty())
		{
			throw new IllegalArgumentException("Random-event completion requires a reason");
		}
		return reason.trim();
	}


	@FunctionalInterface
	interface SolverLookup
	{
		String find(int npcId);
	}

	@FunctionalInterface
	interface TalkAction
	{
		CompletableFuture<Map<String, Object>> talk(int npcId);
	}

	interface Runtime
	{
		default String randomEventDeferralActivity()
		{
			return null;
		}

		default boolean isAutomationActive()
		{
			return true;
		}

		CompletableFuture<String> interrupt(String eventKey, String solverScript);

		CompletableFuture<String> release(String eventKey, boolean resumeInterrupted);
	}

	private static final class EventRecord
	{
		private final NPC npc;
		private final String eventKey;
		private final int npcId;
		private final String npcName;
		private final int npcIndex;
		private final WorldPoint world;
		private final List<String> actions;
		private final long detectedTick;
		private final Instant detectedAt;
		private final String solverScript;
		private boolean active = true;
		private boolean present = true;
		private boolean acknowledged;
		private long despawnedTick = -1L;
		private String state;
		private String solverStatus;
		private String autoTalkStatus = "not_needed";
		private String autoTalkResult;
		private String deferredActivity;
		private String lastError;
		private String resolution;
		private Instant completedAt;

		private EventRecord(
			NPC npc,
			String eventKey,
			int npcId,
			String npcName,
			int npcIndex,
			WorldPoint world,
			List<String> actions,
			long detectedTick,
			Instant detectedAt,
			String solverScript)
		{
			this.npc = npc;
			this.eventKey = eventKey;
			this.npcId = npcId;
			this.npcName = npcName;
			this.npcIndex = npcIndex;
			this.world = world;
			this.actions = actions;
			this.detectedTick = detectedTick;
			this.detectedAt = detectedAt;
			this.solverScript = solverScript;
			this.state = solverScript == null ? "attention_required" : "solver_starting";
			this.solverStatus = solverScript == null ? "unregistered" : "starting";
		}

		private static EventRecord capture(
			NPC npc,
			long detectedTick,
			Instant detectedAt,
			String solverScript)
		{
			String name = npc.getName();
			if (name == null || name.trim().isEmpty())
			{
				name = "NPC " + npc.getId();
			}
			return new EventRecord(
				npc,
				detectedTick + ":" + npc.getId() + ":" + npc.getIndex(),
				npc.getId(),
				name,
				npc.getIndex(),
				npc.getWorldLocation(),
				actions(npc),
				detectedTick,
				detectedAt,
				solverScript);
		}

		private Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("available", true);
			value.put("active", active);
			boolean blocksAutomation = active && !"deferred_activity".equals(state);
			value.put("blocks_automation", blocksAutomation);
			value.put("attention_required", active &&
				!"deferred_activity".equals(state) &&
				("attention_required".equals(state) || "start_failed".equals(solverStatus)));
			value.put("state", state);
			value.put("event_key", eventKey);
			value.put("npc_id", (long) npcId);
			value.put("npc_name", npcName);
			value.put("npc_index", (long) npcIndex);
			value.put("world", worldValue(world));
			value.put("actions", actions);
			value.put("present", present);
			value.put("acknowledged", acknowledged);
			value.put("detected_tick", detectedTick);
			value.put("detected_at", detectedAt.toString());
			value.put("despawned_tick", despawnedTick < 0L ? null : despawnedTick);
			value.put("solver_script", solverScript);
			value.put("solver_status", solverStatus);
			value.put("auto_talk_status", autoTalkStatus);
			value.put("auto_talk_result", autoTalkResult);
			value.put("deferred_activity", deferredActivity);
			value.put("last_error", lastError);
			value.put("resolution", resolution);
			value.put("completed_at", completedAt == null ? null : completedAt.toString());
			return value;
		}

		private static List<String> actions(NPC npc)
		{
			NPCComposition composition = npc.getTransformedComposition();
			if (composition == null)
			{
				composition = npc.getComposition();
			}
			String[] raw = composition == null ? null : composition.getActions();
			if (raw == null)
			{
				return Collections.emptyList();
			}
			List<String> value = new ArrayList<>();
			for (String action : raw)
			{
				if (action != null && !action.trim().isEmpty())
				{
					value.add(action);
				}
			}
			return Collections.unmodifiableList(value);
		}

		private static Map<String, Object> worldValue(WorldPoint world)
		{
			if (world == null)
			{
				return null;
			}
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("x", (long) world.getX());
			value.put("y", (long) world.getY());
			value.put("plane", (long) world.getPlane());
			return Collections.unmodifiableMap(value);
		}
	}
}
