package com.genericclient;

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
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

final class GenericClientSpellInput
{
	private static final long SELECTION_POLL_MILLIS = 50L;
	private static final int SELECTION_POLL_ATTEMPTS = 10;
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

	GenericClientSpellInput(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientMenuInput menuInput,
		GenericClientNpcInput npcInput)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.menuInput = menuInput;
		this.npcInput = npcInput;
	}

	CompletableFuture<Map<String, Object>> castOnNpc(
		String spellName,
		Integer npcId,
		String npcName,
		int within,
		boolean breaksEnabled)
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
		CompletableFuture<SpellbookState> spellbook = new CompletableFuture<>();
		clientThread.invoke(() -> spellbook.complete(new SpellbookState(
			spellbookVisible(spell), spellSelected(spell))));
		return spellbook.thenCompose(state ->
		{
			if (!state.visible)
			{
				return openSpellbookAndCast(
					spell, npcId, cleanNpcName, within, breaksEnabled);
			}
			List<Map<String, Object>> steps = new ArrayList<>();
			return state.selected
				? castSelectedSpell(spell, npcId, cleanNpcName, within, breaksEnabled, steps)
				: selectAndCast(spell, npcId, cleanNpcName, within, breaksEnabled, steps);
		});
	}

	private CompletableFuture<Map<String, Object>> openSpellbookAndCast(
		Spell spell,
		Integer npcId,
		String npcName,
		int within,
		boolean breaksEnabled)
	{
		return menuInput.interact(this::resolveMagicTab, breaksEnabled).thenCompose(tabReceipt ->
		{
			if (!wasDispatched(tabReceipt))
			{
				return CompletableFuture.completedFuture(tabReceipt);
			}
			List<Map<String, Object>> steps = new ArrayList<>();
			steps.add(tabReceipt);
			return waitForSpellbook(spell).thenCompose(visible -> visible
				? selectAndCast(spell, npcId, npcName, within, breaksEnabled, steps)
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
		String npcName,
		int within,
		boolean breaksEnabled,
		List<Map<String, Object>> steps)
	{
		return menuInput.interact(() -> resolveSpell(spell), breaksEnabled).thenCompose(selection ->
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
					spell, npcId, npcName, within, breaksEnabled, steps);
			});
		});
	}

	private CompletableFuture<Map<String, Object>> castSelectedSpell(
		Spell spell,
		Integer npcId,
		String npcName,
		int within,
		boolean breaksEnabled,
		List<Map<String, Object>> steps)
	{
		return npcInput.castSelectedSpellOnNpc(
			npcId,
			npcName,
			within,
			spell.widgetId,
			spell.name,
			breaksEnabled).thenApply(cast ->
		{
			steps.add(cast);
			return compositeReceipt("spell_cast_on_npc", steps, cast);
		});
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

	private static boolean matchesWidget(MenuEntry entry, Widget target)
	{
		Widget widget = entry.getWidget();
		if (widget != null)
		{
			return widget.getId() == target.getId() && widget.getIndex() == target.getIndex();
		}
		return entry.getParam1() == target.getId() &&
			(target.getIndex() < 0 || entry.getParam0() == target.getIndex());
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
			SpriteID.Magicon.FIRE_STRIKE, true);

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
