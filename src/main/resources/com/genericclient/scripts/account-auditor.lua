-- genericclient-interface: 2

local tracked_skills = {
  "attack",
  "strength",
  "defence",
  "hitpoints",
  "ranged",
  "prayer",
  "magic",
  "agility",
  "thieving",
  "slayer",
  "firemaking",
}

local tracked_quests = {
  "waterfall_quest",
  "witchs_house",
  "tree_gnome_village",
  "the_grand_tree",
  "fight_arena",
  "desert_treasure_i",
  "holy_grail",
  "kings_ransom",
}

local function audit()
  local account = gc.read("account")
  local skill_summary = {}
  for _, name in ipairs(tracked_skills) do
    skill_summary[name] = account.skills[name]
  end

  local quest_summary = {}
  for _, name in ipairs(tracked_quests) do
    quest_summary[name] = account.quests[name]
  end

  local cash_label = tostring(account.cash.known_total_value)
  if not account.cash.complete then
    cash_label = cash_label .. " known; bank unseen"
  end

  gc.overlay {
    { label = "State", value = "Audited" },
    { label = "Cash", value = cash_label },
    { label = "Bank", value = account.bank.state },
  }

  gc.log("info", "account-audit", {
    game_tick = account.runtime.game_tick,
    player = account.player,
    skills = skill_summary,
    inventory = account.inventory,
    equipment = account.equipment,
    bank = account.bank,
    quests = quest_summary,
    quest_counts = {
      finished = account.quests.finished_count,
      in_progress = account.quests.in_progress_count,
      total = account.quests.total_count,
    },
    grand_exchange = account.grand_exchange,
    cash = account.cash,
    combat = account.combat,
  })
end

return {
  actions = {
    { id = "refresh", label = "Refresh" },
  },

  run = function(input)
    gc.await { event = "game.tick" }
    audit()

    while true do
      gc.await { event = "game.tick" }
      if gc.next_action() == "refresh" then
        audit()
      end
    end
  end,
}
