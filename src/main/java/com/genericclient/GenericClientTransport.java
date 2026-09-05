package com.genericclient;

import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/** One directed journey edge whose movement is performed by an interaction. */
final class GenericClientTransport
{
	final String id;
	final WorldPoint origin;
	final WorldPoint destination;
	final int cost;
	final WorldArea arrival;
	final List<Step> steps;
	final ConversationStep conversation;
	private final List<Predicate<GenericClientSnapshot>> requirements;

	GenericClientTransport(String id, WorldPoint origin, WorldPoint destination, int cost,
		WorldArea arrival, List<Step> steps, List<Predicate<GenericClientSnapshot>> requirements)
	{
		this.id = Objects.requireNonNull(id);
		this.origin = Objects.requireNonNull(origin);
		this.destination = Objects.requireNonNull(destination);
		if (id.isBlank() || cost <= 0 || origin.equals(destination))
			throw new IllegalArgumentException("Transport requires an id, positive cost and distinct endpoints");
		this.cost = cost;
		if (!arrival.contains(destination) || arrival.contains(origin) || steps.isEmpty())
			throw new IllegalArgumentException("Transport requires an arrival region distinct from its origin and at least one action");
		this.arrival = arrival;
		this.steps = List.copyOf(steps);
		Step last = steps.get(steps.size() - 1);
		this.conversation = last instanceof ConversationStep ? (ConversationStep) last : null;
		this.requirements = List.copyOf(requirements);
	}

	boolean eligible(GenericClientSnapshot snapshot)
	{
		return requirements.stream().allMatch(requirement -> requirement.test(snapshot));
	}

	abstract static class Step
	{
		abstract boolean available(GenericClientSnapshot snapshot);
		abstract CompletableFuture<Map<String, Object>> execute(GenericClientNativeInputs inputs,
			GenericClientSnapshot snapshot, GenericClientActivityContext context);
	}

	static final class ObjectStep extends Step
	{
		final int id;
		final String action;
		private final WorldArea target;

		ObjectStep(int id, String action, WorldArea target)
		{
			this.id = id;
			this.action = action;
			this.target = target;
		}

		GenericClientQuestSnapshot.ObjectSnapshot find(GenericClientSnapshot snapshot)
		{
			for (GenericClientQuestSnapshot.ObjectSnapshot object : snapshot.getObjects())
				if (object.getId() == id && target.contains(object.getWorldPoint()) &&
					object.getActions().stream().anyMatch(action::equalsIgnoreCase)) return object;
			return null;
		}

		@Override boolean available(GenericClientSnapshot snapshot) { return find(snapshot) != null; }

		@Override CompletableFuture<Map<String, Object>> execute(GenericClientNativeInputs inputs,
			GenericClientSnapshot snapshot, GenericClientActivityContext context)
		{
			return inputs.objectInput.interact(id, action, find(snapshot).getWorldPoint(), 8, context);
		}
	}

	static final class WidgetStep extends Step
	{
		private final int id;
		private final String label;

		WidgetStep(int id) { this(id, null); }
		WidgetStep(int id, String label) { this.id = id; this.label = label; }

		@Override boolean available(GenericClientSnapshot snapshot) { return snapshot.getWidgets().contains(id, label); }

		@Override CompletableFuture<Map<String, Object>> execute(GenericClientNativeInputs inputs,
			GenericClientSnapshot snapshot, GenericClientActivityContext context)
		{
			return label == null ? inputs.uiInput.click(id, null, context) : inputs.uiInput.selectDestination(id, label, context);
		}
	}

	static final class NpcStep extends Step
	{
		private final Set<Integer> ids;
		private final String action;
		private final WorldArea target;

		NpcStep(Set<Integer> ids, String action, WorldArea target)
		{
			this.ids = Set.copyOf(ids);
			this.action = action;
			this.target = target;
		}

		private GenericClientWorldSnapshot.NpcSnapshot find(GenericClientSnapshot snapshot)
		{
			for (GenericClientWorldSnapshot.NpcSnapshot npc : snapshot.getNpcs())
				if (ids.contains(npc.getId()) && !npc.isDead() && target.contains(npc.getWorldPoint()) &&
					npc.actions.stream().anyMatch(action::equalsIgnoreCase)) return npc;
			return null;
		}

		@Override boolean available(GenericClientSnapshot snapshot) { return find(snapshot) != null; }

		@Override CompletableFuture<Map<String, Object>> execute(GenericClientNativeInputs inputs,
			GenericClientSnapshot snapshot, GenericClientActivityContext context)
		{
			return inputs.npcInput.interact(find(snapshot).getId(), null, action, 8, context);
		}
	}

	static final class ConversationStep extends Step
	{
		private final String npc;
		private final Set<String> choices;

		ConversationStep(String npc, Set<String> choices) { this.npc = npc; this.choices = Set.copyOf(choices); }

		@Override boolean available(GenericClientSnapshot snapshot)
		{
			GenericClientQuestSnapshot.DialogueSnapshot dialogue = snapshot.getDialogue();
			if ("continue".equals(dialogue.type))
				return npc.equalsIgnoreCase(dialogue.speaker) || snapshot.getPlayer().getName().equalsIgnoreCase(dialogue.speaker);
			return "choice".equals(dialogue.type) && choice(dialogue) != null;
		}

		private String choice(GenericClientQuestSnapshot.DialogueSnapshot dialogue)
		{
			for (GenericClientQuestSnapshot.DialogueOptionSnapshot option : dialogue.options)
				if (choices.contains(option.text)) return option.text;
			return null;
		}

		@Override CompletableFuture<Map<String, Object>> execute(GenericClientNativeInputs inputs,
			GenericClientSnapshot snapshot, GenericClientActivityContext context)
		{
			GenericClientQuestSnapshot.DialogueSnapshot dialogue = snapshot.getDialogue();
			return inputs.dialogueInput.respond(dialogue, choice(dialogue), context);
		}
	}
}
