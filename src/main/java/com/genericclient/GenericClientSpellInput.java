package com.genericclient;

import static com.genericclient.GenericClientWidgets.matchesWidget;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

final class GenericClientSpellInput
{
	private static final long SELECTION_POLL_MILLIS = 50L;
	private static final int SELECTION_POLL_ATTEMPTS = 10;
	private static final long HOME_TELEPORT_POLL_MILLIS = 250L;
	private static final int HOME_TELEPORT_POLL_ATTEMPTS = 120;
	private static final int[] MAGIC_TABS =
	{
		InterfaceID.Toplevel.STONE6,
		InterfaceID.ToplevelOsrsStretch.STONE6,
		InterfaceID.ToplevelPreEoc.ICON6
	};

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final GenericClientMenuInput menuInput;
	private final GenericClientNpcInput npcInput;
	private final GenericClientInventoryInput inventoryInput;

	GenericClientSpellInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientMenuInput menuInput,
		GenericClientNpcInput npcInput,
		GenericClientInventoryInput inventoryInput)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.menuInput = menuInput;
		this.npcInput = npcInput;
		this.inventoryInput = inventoryInput;
	}

	CompletableFuture<Map<String, Object>> homeTeleport(
		GenericClientActivityContext activityContext)
	{
		CompletableFuture<WorldPoint> currentWorld = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			Player player = client.getLocalPlayer();
			currentWorld.complete(player == null ? null : player.getWorldLocation());
		});
		return currentWorld.thenCompose(world ->
		{
			List<Map<String, Object>> steps = new ArrayList<>();
			if (isLumbridge(world))
			{
				return CompletableFuture.completedFuture(
					travelReceipt("complete", "already_at_lumbridge", steps));
			}
			return ensureHomeTeleportVisible(activityContext, steps).thenCompose(visible ->
			{
				if (!visible)
				{
					return CompletableFuture.completedFuture(travelReceipt(
						"rejected", "lumbridge_home_teleport_unavailable", steps));
				}
				return menuInput.interact(this::resolveHomeTeleport, activityContext)
					.thenCompose(clicked ->
					{
						steps.add(clicked);
						if (!wasDispatched(clicked))
						{
							return CompletableFuture.completedFuture(travelReceipt(
								"rejected", "lumbridge_home_teleport_click_failed", steps));
						}
						return waitForLumbridge().thenApply(arrived -> travelReceipt(
							arrived ? "complete" : "rejected",
							arrived
								? "lumbridge_home_teleport_complete"
								: "lumbridge_home_teleport_arrival_unverified",
							steps));
					});
			});
		});
	}

	private CompletableFuture<Boolean> ensureHomeTeleportVisible(
		GenericClientActivityContext activityContext,
		List<Map<String, Object>> steps)
	{
		CompletableFuture<Boolean> visible = new CompletableFuture<>();
		clientThread.invoke(() -> visible.complete(homeTeleportVisible()));
		return visible.thenCompose(alreadyVisible ->
		{
			if (alreadyVisible)
			{
				return CompletableFuture.completedFuture(true);
			}
			return menuInput.interact(this::resolveMagicTab, activityContext).thenCompose(tabReceipt ->
			{
				steps.add(tabReceipt);
				if (!wasDispatched(tabReceipt))
				{
					return CompletableFuture.completedFuture(false);
				}
				CompletableFuture<Boolean> opened = new CompletableFuture<>();
				executor.schedule(() -> clientThread.invoke(() ->
					opened.complete(homeTeleportVisible())), 300L, TimeUnit.MILLISECONDS);
				return opened;
			});
		});
	}

	private boolean homeTeleportVisible()
	{
		return visibleWidget(InterfaceID.MagicSpellbook.UNIVERSE) != null &&
			visibleWidget(InterfaceID.MagicSpellbook.TELEPORT_HOME_STANDARD) != null;
	}

	private GenericClientMenuInput.Resolution resolveHomeTeleport()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		Widget widget = visibleWidget(InterfaceID.MagicSpellbook.TELEPORT_HOME_STANDARD);
		if (widget == null)
		{
			return GenericClientMenuInput.Resolution.rejected(
				"lumbridge_home_teleport_not_visible");
		}
		Point point = GenericClientMenuInput.randomPointInside(
			widget.getBounds(), client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected(
				"lumbridge_home_teleport_not_clickable");
		}
		Map<String, Object> target = new LinkedHashMap<>();
		target.put("kind", "travel");
		target.put("name", "Lumbridge Home Teleport");
		target.put("widget_id", (long) widget.getId());
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			"Cast",
			"travel:lumbridge_home_teleport",
			target,
			entry -> "Cast".equalsIgnoreCase(entry.getOption()) && matchesWidget(entry, widget)));
	}

	private CompletableFuture<Boolean> waitForLumbridge()
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		pollLumbridge(0, result);
		return result;
	}

	private void pollLumbridge(int attempt, CompletableFuture<Boolean> result)
	{
		executor.schedule(() -> clientThread.invoke(() ->
		{
			Player player = client.getLocalPlayer();
			WorldPoint world = player == null ? null : player.getWorldLocation();
			if (client.getGameState() == GameState.LOGGED_IN && isLumbridge(world))
			{
				result.complete(true);
			}
			else if (attempt + 1 >= HOME_TELEPORT_POLL_ATTEMPTS)
			{
				result.complete(false);
			}
			else
			{
				pollLumbridge(attempt + 1, result);
			}
		}), HOME_TELEPORT_POLL_MILLIS, TimeUnit.MILLISECONDS);
	}

	static boolean isLumbridge(WorldPoint world)
	{
		return world != null && world.getPlane() == 0 &&
			world.getX() >= 3200 && world.getX() <= 3245 &&
			world.getY() >= 3190 && world.getY() <= 3245;
	}

	CompletableFuture<Map<String, Object>> castOnNpc(
		String spellName,
		Integer npcId,
		Integer npcIndex, Long identity,
		String npcName,
		int within,
		GenericClientActivityContext activityContext)
	{
		Spell spell = Spell.fromName(spellName);
		if (npcId == null && (npcName == null || npcName.trim().isEmpty()))
		{
			throw new IllegalArgumentException("combat.cast requires npc_id or npc_name");
		}
		if (npcId != null && npcId < 0)
		{
			throw new IllegalArgumentException("combat.cast npc_id cannot be negative");
		}
		if (within < 1 || within > 32)
		{
			throw new IllegalArgumentException("combat.cast within must be between 1 and 32");
		}
		String cleanNpcName = npcName == null || npcName.trim().isEmpty() ? null : npcName.trim();
		return castOnNpc(spell, npcId, npcIndex, identity, cleanNpcName, within, activityContext);
	}

	private CompletableFuture<Map<String, Object>> castOnNpc(
		Spell spell,
		Integer npcId,
		Integer npcIndex, Long identity,
		String npcName,
		int within,
		GenericClientActivityContext activityContext)
	{
		CompletableFuture<SpellbookState> spellbook = new CompletableFuture<>();
		clientThread.invoke(() -> spellbook.complete(new SpellbookState(
			spellbookVisible(spell), spellSelected(spell))));
		return spellbook.thenCompose(state ->
		{
			List<Map<String, Object>> steps = new ArrayList<>();
			SpellbookPath path = spellbookPath(state.visible, state.selected);
			if (path == SpellbookPath.TARGET_SELECTED)
			{
				return castSelectedSpell(
					spell, npcId, npcIndex, identity, npcName, within, activityContext, steps);
			}
			if (path == SpellbookPath.OPEN)
			{
				return openSpellbookAndCast(
					spell, npcId, npcIndex, identity, npcName, within, activityContext);
			}
			return selectAndCast(
				spell, npcId, npcIndex, identity, npcName, within, activityContext, steps);
		});
	}

	CompletableFuture<Map<String, Object>> castOnItem(
		String spellName,
		int itemId,
		Integer requestedSlot,
		GenericClientActivityContext activityContext)
	{
		Spell spell = Spell.fromName(spellName);
		if (itemId < 0)
		{
			throw new IllegalArgumentException("spell.cast_on_item item_id cannot be negative");
		}
		if (requestedSlot != null && (requestedSlot < 0 || requestedSlot >= 28))
		{
			throw new IllegalArgumentException(
				"spell.cast_on_item slot must be between 0 and 27");
		}
		return castOnItem(spell, itemId, requestedSlot, activityContext);
	}

	private CompletableFuture<Map<String, Object>> castOnItem(
		Spell spell,
		int itemId,
		Integer requestedSlot,
		GenericClientActivityContext activityContext)
	{
		CompletableFuture<SpellbookState> spellbook = new CompletableFuture<>();
		clientThread.invoke(() -> spellbook.complete(new SpellbookState(
			spellbookVisible(spell), spellSelected(spell))));
		return spellbook.thenCompose(state ->
		{
			List<Map<String, Object>> steps = new ArrayList<>();
			SpellbookPath path = spellbookPath(state.visible, state.selected);
			if (path == SpellbookPath.TARGET_SELECTED)
			{
				return castSelectedSpellOnItem(
					spell, itemId, requestedSlot, activityContext, steps);
			}
			if (path == SpellbookPath.OPEN)
			{
				return openSpellbookAndCastOnItem(
					spell, itemId, requestedSlot, activityContext);
			}
			return selectAndCastOnItem(
				spell, itemId, requestedSlot, activityContext, steps);
		});
	}

	private CompletableFuture<Map<String, Object>> openSpellbookAndCastOnItem(
		Spell spell,
		int itemId,
		Integer requestedSlot,
		GenericClientActivityContext activityContext)
	{
		return menuInput.interact(this::resolveMagicTab, activityContext).thenCompose(tabReceipt ->
		{
			if (!wasDispatched(tabReceipt))
			{
				return CompletableFuture.completedFuture(tabReceipt);
			}
			List<Map<String, Object>> steps = new ArrayList<>();
			steps.add(tabReceipt);
			return waitForSpellbook(spell).thenCompose(visible -> visible
				? selectAndCastOnItem(
					spell, itemId, requestedSlot, activityContext, steps)
				: CompletableFuture.completedFuture(
					rejected("spellbook_did_not_open", steps)));
		});
	}

	private CompletableFuture<Map<String, Object>> selectAndCastOnItem(
		Spell spell,
		int itemId,
		Integer requestedSlot,
		GenericClientActivityContext activityContext,
		List<Map<String, Object>> steps)
	{
		return menuInput.interact(() -> resolveSpell(spell), activityContext).thenCompose(selection ->
		{
			steps.add(selection);
			if (!wasDispatched(selection))
			{
				return CompletableFuture.completedFuture(compositeReceipt(
					"spell_selection_failed", steps, selection));
			}
			return waitForSpellSelection(spell).thenCompose(selected ->
			{
				if (!selected)
				{
					return CompletableFuture.completedFuture(
						rejected("spell_selection_not_applied", steps));
				}
				return castSelectedSpellOnItem(
					spell, itemId, requestedSlot, activityContext, steps);
			});
		});
	}

	private CompletableFuture<Map<String, Object>> castSelectedSpellOnItem(
		Spell spell,
		int itemId,
		Integer requestedSlot,
		GenericClientActivityContext activityContext,
		List<Map<String, Object>> steps)
	{
		return inventoryInput.castSelectedSpellOnItem(
			itemId,
			requestedSlot,
			spell.widgetId,
			spell.name,
			activityContext).thenCompose(cast ->
			{
				steps.add(cast);
				return finishSelectedSpellAction(
					"spell_cast_on_item", spell, steps, cast);
			});
	}

	private CompletableFuture<Map<String, Object>> openSpellbookAndCast(
		Spell spell,
		Integer npcId,
		Integer npcIndex, Long identity,
		String npcName,
		int within,
		GenericClientActivityContext activityContext)
	{
		return menuInput.interact(this::resolveMagicTab, activityContext).thenCompose(tabReceipt ->
		{
			if (!wasDispatched(tabReceipt))
			{
				return CompletableFuture.completedFuture(tabReceipt);
			}
			List<Map<String, Object>> steps = new ArrayList<>();
			steps.add(tabReceipt);
			return waitForSpellbook(spell).thenCompose(visible -> visible
				? selectAndCast(spell, npcId, npcIndex, identity, npcName, within, activityContext, steps)
				: CompletableFuture.completedFuture(rejected("spellbook_did_not_open", steps)));
		});
	}

	private CompletableFuture<Boolean> waitForSpellbook(Spell spell)
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		executor.schedule(() -> clientThread.invoke(() -> result.complete(spellbookVisible(spell))),
			300L, TimeUnit.MILLISECONDS);
		return result;
	}

	private boolean spellbookVisible(Spell spell)
	{
		return visibleWidget(InterfaceID.MagicSpellbook.UNIVERSE) != null &&
			visibleWidget(spell.widgetId) != null;
	}

	private CompletableFuture<Map<String, Object>> selectAndCast(
		Spell spell,
		Integer npcId,
		Integer npcIndex, Long identity,
		String npcName,
		int within,
		GenericClientActivityContext activityContext,
		List<Map<String, Object>> steps)
	{
		return menuInput.interact(() -> resolveSpell(spell), activityContext).thenCompose(selection ->
		{
			steps.add(selection);
			if (!wasDispatched(selection))
			{
				return CompletableFuture.completedFuture(compositeReceipt(
					"spell_selection_failed", steps, selection));
			}
			return waitForSpellSelection(spell).thenCompose(selected ->
			{
				if (!selected)
				{
					return CompletableFuture.completedFuture(
						rejected("spell_selection_not_applied", steps));
				}
				return castSelectedSpell(
					spell, npcId, npcIndex, identity, npcName, within, activityContext, steps);
			});
		});
	}

	private CompletableFuture<Map<String, Object>> castSelectedSpell(
		Spell spell,
		Integer npcId,
		Integer npcIndex, Long identity,
		String npcName,
		int within,
		GenericClientActivityContext activityContext,
		List<Map<String, Object>> steps)
	{
		return npcInput.castSelectedSpellOnNpc(
			npcId,
			npcIndex, identity,
			npcName,
			within,
			spell.widgetId,
			spell.name,
			activityContext).thenCompose(cast ->
			{
				steps.add(cast);
				return finishSelectedSpellAction(
					"spell_cast_on_npc", spell, steps, cast);
			});
	}

	private CompletableFuture<Map<String, Object>> finishSelectedSpellAction(
		String result,
		Spell spell,
		List<Map<String, Object>> steps,
		Map<String, Object> target)
	{
		Map<String, Object> receipt = compositeReceipt(result, steps, target);
		if (wasDispatched(target))
		{
			return CompletableFuture.completedFuture(receipt);
		}
		return clearSelectedSpell(spell).thenApply(cleanup ->
		{
			receipt.put("selection_cleanup", cleanup);
			return receipt;
		});
	}

	private CompletableFuture<Map<String, Object>> clearSelectedSpell(Spell spell)
	{
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			Map<String, Object> receipt = new LinkedHashMap<>();
			Widget selected = client.getSelectedWidget();
			boolean owned = client.isWidgetSelected() && selected != null &&
				selected.getId() == spell.widgetId;
			if (!owned)
			{
				receipt.put("status", "unchanged");
				receipt.put("result", "requested_spell_selection_already_clear");
			}
			else
			{
				client.setWidgetSelected(false);
				receipt.put("status", client.isWidgetSelected() ? "rejected" : "complete");
				receipt.put("result", client.isWidgetSelected()
					? "requested_spell_selection_clear_failed"
					: "requested_spell_selection_cleared");
			}
			receipt.put("spell", spell.name);
			receipt.put("click_count", 0L);
			result.complete(receipt);
		});
		return result;
	}

	private CompletableFuture<Boolean> waitForSpellSelection(Spell spell)
	{
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		pollSpellSelection(spell, 0, result);
		return result;
	}

	private void pollSpellSelection(
		Spell spell,
		int attempt,
		CompletableFuture<Boolean> result)
	{
		executor.schedule(() -> clientThread.invoke(() ->
		{
			if (spellSelected(spell))
			{
				result.complete(true);
			}
			else if (attempt + 1 >= SELECTION_POLL_ATTEMPTS)
			{
				result.complete(false);
			}
			else
			{
				pollSpellSelection(spell, attempt + 1, result);
			}
		}), SELECTION_POLL_MILLIS, TimeUnit.MILLISECONDS);
	}

	private boolean spellSelected(Spell spell)
	{
		Widget selected = client.getSelectedWidget();
		return client.isWidgetSelected() && selected != null && selected.getId() == spell.widgetId;
	}

	static SpellbookPath spellbookPath(boolean visible, boolean selected)
	{
		if (selected)
		{
			return SpellbookPath.TARGET_SELECTED;
		}
		return visible ? SpellbookPath.SELECT : SpellbookPath.OPEN;
	}

	private GenericClientMenuInput.Resolution resolveMagicTab()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		Widget tab = visibleWidget(MAGIC_TABS);
		if (tab == null)
		{
			return GenericClientMenuInput.Resolution.rejected("magic_tab_not_visible");
		}
		Point point = GenericClientMenuInput.randomPointInside(
			tab.getBounds(), client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected("magic_tab_not_clickable");
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "widget");
		value.put("widget_id", (long) tab.getId());
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			"Magic",
			"magic_tab",
			value,
			entry -> matchesWidget(entry, tab)));
	}

	private GenericClientMenuInput.Resolution resolveSpell(Spell spell)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return GenericClientMenuInput.Resolution.rejected("client_not_logged_in");
		}
		if (visibleWidget(InterfaceID.MagicSpellbook.UNIVERSE) == null)
		{
			return GenericClientMenuInput.Resolution.rejected("spellbook_not_active");
		}
		Widget widget = visibleWidget(spell.widgetId);
		if (widget == null)
		{
			return GenericClientMenuInput.Resolution.rejected("spell_not_visible:" + spell.name);
		}
		Point point = GenericClientMenuInput.randomPointInside(
			widget.getBounds(), client.getCanvasWidth(), client.getCanvasHeight());
		if (point == null)
		{
			return GenericClientMenuInput.Resolution.rejected("spell_not_clickable:" + spell.name);
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "spell");
		value.put("name", spell.name);
		value.put("widget_id", (long) spell.widgetId);
		return GenericClientMenuInput.Resolution.resolved(new GenericClientMenuInput.Target(
			point,
			"Cast",
			"spell:" + spell.name,
			value,
			entry -> "Cast".equalsIgnoreCase(entry.getOption()) && matchesWidget(entry, widget)));
	}

	private Widget visibleWidget(int... ids)
	{
		for (int id : ids)
		{
			Widget widget = client.getWidget(id);
			if (widget == null || widget.isHidden() || widget.isSelfHidden())
			{
				continue;
			}
			Rectangle bounds = widget.getBounds();
			if (bounds != null && bounds.width > 0 && bounds.height > 0)
			{
				return widget;
			}
		}
		return null;
	}

	private static boolean wasDispatched(Map<String, Object> receipt)
	{
		return receipt != null && "dispatched".equals(receipt.get("status"));
	}

	private static Map<String, Object> compositeReceipt(
		String result,
		List<Map<String, Object>> steps,
		Map<String, Object> terminal)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", terminal.get("status"));
		receipt.put("result", result);
		receipt.put("steps", steps);
		long clicks = 0;
		for (Map<String, Object> step : steps)
		{
			Object value = step.get("click_count");
			if (value instanceof Number)
			{
				clicks += ((Number) value).longValue();
			}
		}
		receipt.put("click_count", clicks);
		return receipt;
	}

	private static Map<String, Object> travelReceipt(
		String status,
		String result,
		List<Map<String, Object>> steps)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", status);
		receipt.put("result", result);
		receipt.put("steps", new ArrayList<>(steps));
		long clicks = 0L;
		for (Map<String, Object> step : steps)
		{
			Object value = step.get("click_count");
			if (value instanceof Number)
			{
				clicks += ((Number) value).longValue();
			}
		}
		receipt.put("click_count", clicks);
		return receipt;
	}

	private static Map<String, Object> rejected(
		String reason,
		List<Map<String, Object>> steps)
	{
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("status", "rejected");
		receipt.put("result", reason);
		receipt.put("steps", steps);
		long clicks = 0;
		for (Map<String, Object> step : steps)
		{
			Object value = step.get("click_count");
			if (value instanceof Number)
			{
				clicks += ((Number) value).longValue();
			}
		}
		receipt.put("click_count", clicks);
		return receipt;
	}

	enum SpellbookPath
	{
		TARGET_SELECTED,
		OPEN,
		SELECT
	}

	enum Spell
	{
		WIND_STRIKE(
			"wind_strike", "Wind Strike", InterfaceID.MagicSpellbook.WIND_STRIKE,
			SpriteID.Magicon.WIND_STRIKE, true),
		WATER_STRIKE(
			"water_strike", "Water Strike", InterfaceID.MagicSpellbook.WATER_STRIKE,
			SpriteID.Magicon.WATER_STRIKE, true),
		EARTH_STRIKE(
			"earth_strike", "Earth Strike", InterfaceID.MagicSpellbook.EARTH_STRIKE,
			SpriteID.Magicon.EARTH_STRIKE, true),
		FIRE_STRIKE(
			"fire_strike", "Fire Strike", InterfaceID.MagicSpellbook.FIRE_STRIKE,
			SpriteID.Magicon.FIRE_STRIKE, true),
		WIND_BOLT(
			"wind_bolt", "Wind Bolt", InterfaceID.MagicSpellbook.WIND_BOLT,
			SpriteID.Magicon.WIND_BOLT, true),
		WATER_BOLT(
			"water_bolt", "Water Bolt", InterfaceID.MagicSpellbook.WATER_BOLT,
			SpriteID.Magicon.WATER_BOLT, true),
		EARTH_BOLT(
			"earth_bolt", "Earth Bolt", InterfaceID.MagicSpellbook.EARTH_BOLT,
			SpriteID.Magicon.EARTH_BOLT, true),
		FIRE_BOLT(
			"fire_bolt", "Fire Bolt", InterfaceID.MagicSpellbook.FIRE_BOLT,
			SpriteID.Magicon.FIRE_BOLT, true),
		LOW_ALCHEMY(
			"low_alchemy", "Low Level Alchemy", InterfaceID.MagicSpellbook.LOW_ALCHEMY,
			SpriteID.Magicon.LOW_LEVEL_ALCHEMY, false),
		SUPERHEAT_ITEM(
			"superheat_item", "Superheat Item", InterfaceID.MagicSpellbook.SUPERHEAT,
			SpriteID.Magicon.SUPERHEAT_ITEM, false);

		private final String name;
		private final String label;
		private final int widgetId;
		private final int spriteId;
		private final boolean autocastable;

		Spell(String name, String label, int widgetId, int spriteId, boolean autocastable)
		{
			this.name = name;
			this.label = label;
			this.widgetId = widgetId;
			this.spriteId = spriteId;
			this.autocastable = autocastable;
		}

		String getName()
		{
			return name;
		}

		String getLabel()
		{
			return label;
		}

		int getWidgetId()
		{
			return widgetId;
		}

		int getSpriteId()
		{
			return spriteId;
		}

		boolean isAutocastable()
		{
			return autocastable;
		}

		static Spell fromName(String name)
		{
			if (name != null)
			{
				String normalized = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
				for (Spell spell : values())
				{
					if (spell.name.equals(normalized))
					{
						return spell;
					}
				}
			}
			throw new IllegalArgumentException("Unsupported combat spell: " + name);
		}
	}

	private static final class SpellbookState
	{
		private final boolean visible;
		private final boolean selected;

		private SpellbookState(boolean visible, boolean selected)
		{
			this.visible = visible;
			this.selected = selected;
		}
	}
}
