package com.genericclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import net.runelite.api.coords.WorldPoint;

final class GenericClientEmergencyEscape
{
	private final Type type;
	private final WorldPoint destination;
	private final int within;
	private final List<Integer> itemIds;
	private final String itemAction;
	private final String dialogueChoice;

	GenericClientEmergencyEscape(WorldPoint destination, int within)
	{
		this(Type.WALK, destination, within, Collections.emptyList(), null, null);
	}

	private GenericClientEmergencyEscape(
		Type type,
		WorldPoint destination,
		int within,
		List<Integer> itemIds,
		String itemAction,
		String dialogueChoice)
	{
		if (destination == null)
		{
			throw new IllegalArgumentException("Emergency escape destination is required");
		}
		if (within < 0 || within > 10)
		{
			throw new IllegalArgumentException(
				"Emergency escape radius must be between 0 and 10");
		}
		this.type = type;
		this.destination = destination;
		this.within = within;
		this.itemIds = Collections.unmodifiableList(new ArrayList<>(itemIds));
		this.itemAction = itemAction;
		this.dialogueChoice = dialogueChoice;
	}

	static GenericClientEmergencyEscape inventoryDialogue(
		int itemId,
		String itemAction,
		String dialogueChoice,
		WorldPoint destination,
		int within)
	{
		return inventoryDialogue(
			Collections.singletonList(itemId),
			itemAction,
			dialogueChoice,
			destination,
			within);
	}

	static GenericClientEmergencyEscape inventoryDialogue(
		List<Integer> itemIds,
		String itemAction,
		String dialogueChoice,
		WorldPoint destination,
		int within)
	{
		if (itemIds == null || itemIds.isEmpty())
		{
			throw new IllegalArgumentException("Emergency escape item ids are required");
		}
		List<Integer> candidates = new ArrayList<>();
		for (Integer itemId : itemIds)
		{
			if (itemId == null || itemId < 0)
			{
				throw new IllegalArgumentException(
					"Emergency escape item id cannot be negative");
			}
			if (!candidates.contains(itemId))
			{
				candidates.add(itemId);
			}
		}
		if (itemAction == null || itemAction.trim().isEmpty())
		{
			throw new IllegalArgumentException("Emergency escape item action is required");
		}
		if (dialogueChoice == null || dialogueChoice.trim().isEmpty())
		{
			throw new IllegalArgumentException("Emergency escape dialogue choice is required");
		}
		return new GenericClientEmergencyEscape(
			Type.INVENTORY_DIALOGUE,
			destination,
			within,
			candidates,
			itemAction.trim(),
			dialogueChoice.trim());
	}

	Type getType()
	{
		return type;
	}

	WorldPoint getDestination()
	{
		return destination;
	}

	int getWithin()
	{
		return within;
	}

	int getItemId()
	{
		return itemIds.isEmpty() ? -1 : itemIds.get(0);
	}

	List<Integer> getItemIds()
	{
		return itemIds;
	}

	int resolveItemId(IntPredicate carried)
	{
		for (int itemId : itemIds)
		{
			if (carried.test(itemId))
			{
				return itemId;
			}
		}
		return -1;
	}

	String getItemAction()
	{
		return itemAction;
	}

	String getDialogueChoice()
	{
		return dialogueChoice;
	}

	String describe()
	{
		return type == Type.WALK
			? destination.toString()
			: "items:" + itemIds + " choice:" + dialogueChoice;
	}

	Map<String, Object> toMap()
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("type", type.name().toLowerCase(java.util.Locale.ROOT));
		value.put("x", (long) destination.getX());
		value.put("y", (long) destination.getY());
		value.put("plane", (long) destination.getPlane());
		value.put("within", (long) within);
		if (type == Type.INVENTORY_DIALOGUE)
		{
			value.put("item_id", (long) getItemId());
			List<Long> alternatives = new ArrayList<>();
			for (int index = 1; index < itemIds.size(); index++)
			{
				alternatives.add((long) itemIds.get(index));
			}
			value.put("alternative_item_ids", alternatives);
			value.put("action", itemAction);
			value.put("choice", dialogueChoice);
		}
		return value;
	}
	enum Type
	{
		WALK,
		INVENTORY_DIALOGUE
	}

}
