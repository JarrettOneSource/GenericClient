package com.genericclient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

final class GenericClientQuestSnapshot
{
	private static final int OBJECT_RADIUS = 32;
	private static final int MAX_QUERY_RESULTS = 100;
	private static final int MAX_VAR_QUERY = 32;
	private static final int[] CAPTURED_VARBITS =
	{
		9110,
		VarbitID.MACRO_PINBALL_CURRENT,
		VarbitID.MACRO_PINBALL_NEXT,
		VarbitID.MACRO_PINBALL_SCORE,
		VarbitID.MACRO_PINBALL_COMPLETE,
		VarbitID.BOLREN_GOT_ORBS,
		VarbitID.GNOMETRACKER_H,
		VarbitID.GNOMETRACKER_Y,
		VarbitID.GNOMETRACKER_X,
		VarbitID.BALLISTA,
		VarbitID.ARENAQUEST_MET_SAMMY,
		VarbitID.ARENAQUEST_SCORPION_CUTSCENE,
		VarbitID.ARENAQUEST_BOUNCER_CUTSCENE,
		VarbitID.ARENAQUEST_KHAZARD_CUTSCENE,
		VarbitID.ARENAQUEST_ATTEMPTED_ENTRY,
		VarbitID.MM_NARNODE,
		VarbitID.MM_CARANOCK,
		VarbitID.MM_DAERO,
		VarbitID.MM_LUMDO,
		VarbitID.MM_GARKOR,
		VarbitID.MM_ZOOKNOCK,
		VarbitID.PRAYER_PROTECTFROMMAGIC,
		VarbitID.PRAYER_PROTECTFROMMISSILES,
		VarbitID.PRAYER_PROTECTFROMMELEE,
		VarbitID.PIRATE_COMBILOCK_LEFT,
		VarbitID.PIRATE_COMBILOCK_CENTRE,
		VarbitID.PIRATE_COMBILOCK_RIGHT
	};

	private final boolean available;
	private final int[] varps;
	private final Map<Integer, Integer> varbits;
	private final List<ObjectSnapshot> objects;
	private final List<GroundItemSnapshot> groundItems;
	private final DialogueSnapshot dialogue;

	GenericClientQuestSnapshot(
		boolean available,
		int[] varps,
		List<ObjectSnapshot> objects,
		DialogueSnapshot dialogue)
	{
		this(
			available,
			varps,
			Collections.emptyMap(),
			objects,
			Collections.emptyList(),
			dialogue);
	}

	GenericClientQuestSnapshot(
		boolean available,
		int[] varps,
		Map<Integer, Integer> varbits,
		List<ObjectSnapshot> objects,
		DialogueSnapshot dialogue)
	{
		this(available, varps, varbits, objects, Collections.emptyList(), dialogue);
	}

	GenericClientQuestSnapshot(
		boolean available,
		int[] varps,
		List<ObjectSnapshot> objects,
		List<GroundItemSnapshot> groundItems,
		DialogueSnapshot dialogue)
	{
		this(available, varps, Collections.emptyMap(), objects, groundItems, dialogue);
	}

	private GenericClientQuestSnapshot(
		boolean available,
		int[] varps,
		Map<Integer, Integer> varbits,
		List<ObjectSnapshot> objects,
		List<GroundItemSnapshot> groundItems,
		DialogueSnapshot dialogue)
	{
		this.available = available;
		this.varps = varps.clone();
		this.varbits = Collections.unmodifiableMap(new LinkedHashMap<>(varbits));
		this.objects = Collections.unmodifiableList(new ArrayList<>(objects));
		this.groundItems = Collections.unmodifiableList(new ArrayList<>(groundItems));
		this.dialogue = dialogue;
	}

	static GenericClientQuestSnapshot empty()
	{
		return new GenericClientQuestSnapshot(
			false,
			new int[0],
			Collections.emptyList(),
			DialogueSnapshot.closed());
	}

	static GenericClientQuestSnapshot capture(Client client, Player player)
	{
		if (player == null || player.getWorldLocation() == null || player.getLocalLocation() == null)
		{
			return empty();
		}
		SceneCapture scene = captureScene(client, player);
		return new GenericClientQuestSnapshot(
			true,
			client.getVarps(),
			captureVarbits(client),
			scene.objects,
			scene.groundItems,
			captureDialogue(client));
	}

	Object read(String subject, Map<?, ?> query)
	{
		switch (subject)
		{
			case "vars":
				return readVars(query);
			case "objects":
				return queryObjects(query);
			case "ground_items":
				return queryGroundItems(query);
			case "dialogue":
				return dialogue.toMap();
			default:
				throw new IllegalArgumentException("unknown quest subject: " + subject);
		}
	}

	List<ObjectSnapshot> getObjects()
	{
		return objects;
	}

	private Map<String, Object> readVars(Map<?, ?> query)
	{
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("available", available);
		Map<Long, Object> selectedVarps = new LinkedHashMap<>();
		Map<Long, Object> selectedVarbits = new LinkedHashMap<>();
		if (!available)
		{
			value.put("varps", selectedVarps);
			value.put("varbits", selectedVarbits);
			return value;
		}
		List<Integer> ids = numericValues(query == null ? null : query.get("varps"));
		if (ids.size() > MAX_VAR_QUERY)
		{
			throw new IllegalArgumentException("vars reads support at most 32 varps");
		}
		for (int id : ids)
		{
			if (id < 0 || id >= varps.length)
			{
				throw new IllegalArgumentException("varp id is outside the copied client array: " + id);
			}
			selectedVarps.put((long) id, (long) varps[id]);
		}
		value.put("varps", selectedVarps);
		List<Integer> varbitIds = numericValues(query == null ? null : query.get("varbits"));
		if (varbitIds.size() > MAX_VAR_QUERY)
		{
			throw new IllegalArgumentException("vars reads support at most 32 varbits");
		}
		for (int id : varbitIds)
		{
			Integer selected = varbits.get(id);
			if (selected == null)
			{
				throw new IllegalArgumentException("varbit id is not captured: " + id);
			}
			selectedVarbits.put((long) id, (long) selected);
		}
		value.put("varbits", selectedVarbits);
		return value;
	}

	private static Map<Integer, Integer> captureVarbits(Client client)
	{
		Map<Integer, Integer> captured = new LinkedHashMap<>();
		for (int id : CAPTURED_VARBITS)
		{
			captured.put(id, client.getVarbitValue(id));
		}
		return captured;
	}

	private List<Map<String, Object>> queryObjects(Map<?, ?> query)
	{
		SceneQuery criteria = SceneQuery.from(query);
		if (criteria.limit == 0)
		{
			return Collections.emptyList();
		}

		List<Map<String, Object>> result = new ArrayList<>();
		for (ObjectSnapshot object : objects)
		{
			if (!criteria.matches(object))
			{
				continue;
			}
			result.add(object.toMap());
			if (result.size() == criteria.limit)
			{
				break;
			}
		}
		return result;
	}

	private List<Map<String, Object>> queryGroundItems(Map<?, ?> query)
	{
		SceneQuery criteria = SceneQuery.from(query);
		if (criteria.limit == 0)
		{
			return Collections.emptyList();
		}
		List<Map<String, Object>> result = new ArrayList<>();
		for (GroundItemSnapshot item : groundItems)
		{
			if (!criteria.matches(item))
			{
				continue;
			}
			result.add(item.toMap());
			if (result.size() == criteria.limit)
			{
				break;
			}
		}
		return result;
	}

	private static SceneCapture captureScene(Client client, Player player)
	{
		WorldView worldView = player.getWorldView();
		Scene scene = worldView.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		LocalPoint local = player.getLocalLocation();
		WorldPoint playerWorld = player.getWorldLocation();
		if (tiles == null || local == null || playerWorld == null)
		{
			return SceneCapture.empty();
		}

		int plane = Math.max(0, Math.min(tiles.length - 1, playerWorld.getPlane()));
		int minX = Math.max(0, local.getSceneX() - OBJECT_RADIUS);
		int maxX = Math.min(tiles[plane].length - 1, local.getSceneX() + OBJECT_RADIUS);
		int minY = Math.max(0, local.getSceneY() - OBJECT_RADIUS);
		int maxY = Math.min(tiles[plane][0].length - 1, local.getSceneY() + OBJECT_RADIUS);
		Set<TileObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<ObjectSnapshot> result = new ArrayList<>();
		List<GroundItemSnapshot> groundItems = new ArrayList<>();

		for (int sceneX = minX; sceneX <= maxX; sceneX++)
		{
			for (int sceneY = minY; sceneY <= maxY; sceneY++)
			{
				captureTile(
					client,
					playerWorld,
					tiles[plane][sceneX][sceneY],
					seen,
					result,
					groundItems);
			}
		}
		result.sort(Comparator
			.comparingInt(ObjectSnapshot::getDistance)
			.thenComparingInt(ObjectSnapshot::getId)
			.thenComparingInt(ObjectSnapshot::getX)
			.thenComparingInt(ObjectSnapshot::getY));
		groundItems.sort(Comparator
			.comparingInt(GroundItemSnapshot::getDistance)
			.thenComparingInt(GroundItemSnapshot::getId));
		return new SceneCapture(result, groundItems);
	}

	private static void captureTile(
		Client client,
		WorldPoint player,
		Tile tile,
		Set<TileObject> seen,
		List<ObjectSnapshot> objects,
		List<GroundItemSnapshot> groundItems)
	{
		if (tile == null)
		{
			return;
		}
		addObject(client, player, tile.getWallObject(), "wall", seen, objects);
		addObject(client, player, tile.getGroundObject(), "ground", seen, objects);
		addObject(client, player, tile.getDecorativeObject(), "decorative", seen, objects);
		GameObject[] gameObjects = tile.getGameObjects();
		if (gameObjects != null)
		{
			for (GameObject gameObject : gameObjects)
			{
				addObject(client, player, gameObject, "game", seen, objects);
			}
		}
		captureGroundItems(client, player, tile, groundItems);
	}

	private static void captureGroundItems(
		Client client,
		WorldPoint player,
		Tile tile,
		List<GroundItemSnapshot> result)
	{
		List<TileItem> tileItems = tile.getGroundItems();
		if (tileItems == null)
		{
			return;
		}
		for (TileItem item : tileItems)
		{
			if (item == null || item.getId() < 0)
			{
				continue;
			}
			WorldPoint world = tile.getWorldLocation();
			net.runelite.api.ItemComposition composition = client.getItemDefinition(item.getId());
			result.add(new GroundItemSnapshot(
				item.getId(),
				composition == null ? "<unknown>" : Objects.toString(composition.getName(), "<unknown>"),
				item.getQuantity(),
				world.getX(),
				world.getY(),
				world.getPlane(),
				player.distanceTo(world),
				item.getOwnership(),
				item.isPrivate()));
		}
	}

	private static void addObject(
		Client client,
		WorldPoint player,
		TileObject object,
		String kind,
		Set<TileObject> seen,
		List<ObjectSnapshot> result)
	{
		if (object == null || object.getId() < 0 || object.getWorldLocation() == null || !seen.add(object))
		{
			return;
		}
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition != null && composition.getImpostorIds() != null)
		{
			ObjectComposition transformed = composition.getImpostor();
			if (transformed != null)
			{
				composition = transformed;
			}
		}
		List<String> actions = composition == null || composition.getActions() == null
			? Collections.emptyList()
			: Arrays.stream(composition.getActions())
				.filter(Objects::nonNull)
				.collect(Collectors.toList());
		WorldPoint world = object.getWorldLocation();
		result.add(new ObjectSnapshot(
			object.getId(),
			composition == null ? "<unknown>" : Objects.toString(composition.getName(), "<unknown>"),
			kind,
			world.getX(),
			world.getY(),
			world.getPlane(),
			player.distanceTo(world),
			actions,
			object instanceof WallObject ? ((WallObject) object).getOrientationA() : 0,
			object instanceof WallObject ? ((WallObject) object).getOrientationB() : 0));
	}

	private static DialogueSnapshot captureDialogue(Client client)
	{
		Widget options = visibleWidget(client, InterfaceID.Chatmenu.OPTIONS);
		List<DialogueOptionSnapshot> optionValues = childOptions(options);
		if (!optionValues.isEmpty())
		{
			return DialogueSnapshot.choiceOptions(optionValues);
		}

		Widget visibleContinue = GenericClientDialogueInput.visibleContinueWidget(client);
		if (visibleContinue == null)
		{
			return DialogueSnapshot.closed();
		}
		String speaker = firstText(client,
			InterfaceID.ChatLeft.NAME,
			InterfaceID.ChatRight.NAME,
			InterfaceID.ChatBoth.NAMES);
		String text = firstText(client,
			InterfaceID.ChatLeft.TEXT,
			InterfaceID.ChatRight.TEXT,
			InterfaceID.ChatBoth.TEXT,
			InterfaceID.Objectbox.TEXT,
			InterfaceID.ObjectboxDouble.TEXT);
		if (text == null || text.isEmpty())
		{
			text = cleanText(visibleContinue.getText());
		}
		return DialogueSnapshot.continueDialogue(speaker, text);
	}

	private static Widget visibleWidget(Client client, int id)
	{
		Widget widget = client.getWidget(id);
		return widget != null && !widget.isHidden() && !widget.isSelfHidden() ? widget : null;
	}

	private static String firstText(Client client, int... ids)
	{
		for (int id : ids)
		{
			Widget widget = visibleWidget(client, id);
			String text = widget == null ? null : cleanText(widget.getText());
			if (text != null && !text.isEmpty())
			{
				return text;
			}
		}
		return null;
	}

	private static List<DialogueOptionSnapshot> childOptions(Widget parent)
	{
		if (parent == null)
		{
			return Collections.emptyList();
		}
		Widget[] children = parent.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			children = parent.getChildren();
		}
		if (children == null)
		{
			return Collections.emptyList();
		}
		List<DialogueOptionSnapshot> result = new ArrayList<>();
		for (Widget child : children)
		{
			String text = child == null || child.isHidden() ? null : cleanText(child.getText());
			if (text != null && !text.isEmpty() && child.getIndex() > 0)
			{
				result.add(new DialogueOptionSnapshot(child.getIndex(), text));
			}
		}
		result.sort(Comparator.comparingInt(DialogueOptionSnapshot::getIndex));
		return result;
	}

	private static String cleanText(String value)
	{
		return value == null ? null : Text.removeTags(value).trim();
	}

	private static List<Integer> numericValues(Object value)
	{
		Collection<?> values;
		if (value instanceof Collection)
		{
			values = (Collection<?>) value;
		}
		else if (value instanceof Map)
		{
			values = ((Map<?, ?>) value).values();
		}
		else if (value instanceof Number)
		{
			values = Collections.singletonList(value);
		}
		else if (value == null)
		{
			return Collections.emptyList();
		}
		else
		{
			throw new IllegalArgumentException("varps must be a numeric array");
		}
		List<Integer> result = new ArrayList<>();
		for (Object item : values)
		{
			if (!(item instanceof Number))
			{
				throw new IllegalArgumentException("varps must contain only numbers");
			}
			result.add(((Number) item).intValue());
		}
		return result;
	}

	private static int intValue(Object value, int defaultValue)
	{
		return value instanceof Number ? ((Number) value).intValue() : defaultValue;
	}

	private static String stringValue(Object value)
	{
		return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
	}

	private static final class SceneQuery
	{
		private final int within;
		private final int limit;
		private final String action;
		private final String name;
		private final Integer id;

		private SceneQuery(int within, int limit, String action, String name, Integer id)
		{
			this.within = within;
			this.limit = limit;
			this.action = action;
			this.name = name;
			this.id = id;
		}

		private static SceneQuery from(Map<?, ?> query)
		{
			int within = Math.min(
				OBJECT_RADIUS,
				Math.max(0, intValue(query == null ? null : query.get("within"), OBJECT_RADIUS)));
			int limit = Math.min(
				MAX_QUERY_RESULTS,
				Math.max(0, intValue(query == null ? null : query.get("limit"), 50)));
			Map<?, ?> where = query != null && query.get("where") instanceof Map
				? (Map<?, ?>) query.get("where")
				: Collections.emptyMap();
			Integer id = where.get("id") instanceof Number
				? ((Number) where.get("id")).intValue()
				: null;
			if (query != null && query.get("id") instanceof Number)
			{
				id = ((Number) query.get("id")).intValue();
			}
			return new SceneQuery(
				within,
				limit,
				stringValue(query == null ? null : query.get("action")),
				stringValue(where.get("name")),
				id);
		}

		private boolean matches(ObjectSnapshot object)
		{
			return object.distance <= within &&
				(id == null || object.id == id) &&
				(name == null || object.name.equalsIgnoreCase(name)) &&
				(action == null || object.actions.stream()
					.anyMatch(candidate -> candidate.equalsIgnoreCase(action)));
		}

		private boolean matches(GroundItemSnapshot item)
		{
			return item.distance <= within &&
				(id == null || item.id == id) &&
				(name == null || item.name.equalsIgnoreCase(name));
		}
	}

	static final class ObjectSnapshot
	{
		private final int id;
		private final String name;
		private final String kind;
		private final int x;
		private final int y;
		private final int plane;
		private final int distance;
		private final List<String> actions;
		private final int orientationA;
		private final int orientationB;

		ObjectSnapshot(
			int id,
			String name,
			String kind,
			int x,
			int y,
			int plane,
			int distance,
			List<String> actions)
		{
			this(id, name, kind, x, y, plane, distance, actions, 0, 0);
		}

		ObjectSnapshot(
			int id,
			String name,
			String kind,
			int x,
			int y,
			int plane,
			int distance,
			List<String> actions,
			int orientationA,
			int orientationB)
		{
			this.id = id;
			this.name = name;
			this.kind = kind;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.distance = distance;
			this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
			this.orientationA = orientationA;
			this.orientationB = orientationB;
		}

		int getId()
		{
			return id;
		}

		int getDistance()
		{
			return distance;
		}

		int getX()
		{
			return x;
		}

		int getY()
		{
			return y;
		}

		int getPlane()
		{
			return plane;
		}

		String getName()
		{
			return name;
		}

		String getKind()
		{
			return kind;
		}

		List<String> getActions()
		{
			return actions;
		}

		WorldPoint getWorldPoint()
		{
			return new WorldPoint(x, y, plane);
		}

		int getOrientationA()
		{
			return orientationA;
		}

		int getOrientationB()
		{
			return orientationB;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("id", (long) id);
			value.put("name", name);
			value.put("kind", kind);
			value.put("world", worldMap(x, y, plane));
			value.put("distance", (long) distance);
			value.put("actions", actions);
			value.put("orientation_a", (long) orientationA);
			value.put("orientation_b", (long) orientationB);
			return value;
		}
	}

	static final class GroundItemSnapshot
	{
		private final int id;
		private final String name;
		private final int quantity;
		private final int x;
		private final int y;
		private final int plane;
		private final int distance;
		private final int ownership;
		private final boolean privateItem;

		GroundItemSnapshot(
			int id,
			String name,
			int quantity,
			int x,
			int y,
			int plane,
			int distance,
			int ownership,
			boolean privateItem)
		{
			this.id = id;
			this.name = name;
			this.quantity = quantity;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.distance = distance;
			this.ownership = ownership;
			this.privateItem = privateItem;
		}

		int getId()
		{
			return id;
		}

		int getDistance()
		{
			return distance;
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("id", (long) id);
			value.put("name", name);
			value.put("quantity", (long) quantity);
			value.put("world", worldMap(x, y, plane));
			value.put("distance", (long) distance);
			value.put("ownership", (long) ownership);
			value.put("private", privateItem);
			value.put("actions", Collections.singletonList("Take"));
			return value;
		}
	}

	private static final class SceneCapture
	{
		private final List<ObjectSnapshot> objects;
		private final List<GroundItemSnapshot> groundItems;

		private SceneCapture(List<ObjectSnapshot> objects, List<GroundItemSnapshot> groundItems)
		{
			this.objects = objects;
			this.groundItems = groundItems;
		}

		private static SceneCapture empty()
		{
			return new SceneCapture(Collections.emptyList(), Collections.emptyList());
		}
	}

	static final class DialogueSnapshot
	{
		private final boolean open;
		private final String type;
		private final String speaker;
		private final String text;
		private final List<DialogueOptionSnapshot> options;

		DialogueSnapshot(
			boolean open,
			String type,
			String speaker,
			String text,
			List<DialogueOptionSnapshot> options)
		{
			this.open = open;
			this.type = type;
			this.speaker = speaker;
			this.text = text;
			this.options = Collections.unmodifiableList(new ArrayList<>(options));
		}

		static DialogueSnapshot closed()
		{
			return new DialogueSnapshot(false, "closed", null, null, Collections.emptyList());
		}

		static DialogueSnapshot continueDialogue(String speaker, String text)
		{
			return new DialogueSnapshot(true, "continue", speaker, text, Collections.emptyList());
		}

		static DialogueSnapshot choice(List<String> options)
		{
			List<DialogueOptionSnapshot> values = new ArrayList<>();
			for (int index = 0; index < options.size(); index++)
			{
				values.add(new DialogueOptionSnapshot(index + 1, options.get(index)));
			}
			return choiceOptions(values);
		}

		static DialogueSnapshot choiceOptions(List<DialogueOptionSnapshot> options)
		{
			return new DialogueSnapshot(true, "choice", null, null, options);
		}

		Map<String, Object> toMap()
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("open", open);
			value.put("type", type);
			value.put("speaker", speaker);
			value.put("text", text);
			List<Map<String, Object>> optionValues = new ArrayList<>();
			for (DialogueOptionSnapshot dialogueOption : options)
			{
				Map<String, Object> option = new LinkedHashMap<>();
				option.put("index", (long) dialogueOption.index);
				option.put("text", dialogueOption.text);
				optionValues.add(option);
			}
			value.put("options", optionValues);
			return value;
		}
	}

	static final class DialogueOptionSnapshot
	{
		private final int index;
		private final String text;

		DialogueOptionSnapshot(int index, String text)
		{
			this.index = index;
			this.text = text;
		}

		int getIndex()
		{
			return index;
		}
	}

	private static Map<String, Object> worldMap(int x, int y, int plane)
	{
		Map<String, Object> world = new LinkedHashMap<>();
		world.put("x", (long) x);
		world.put("y", (long) y);
		world.put("plane", (long) plane);
		return world;
	}
}
