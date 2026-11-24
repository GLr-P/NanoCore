package io.github.glrp.nanocore.stats;

import java.util.Set;

/**
 * Names of the counters NanoCore keeps, e.g. "mine.diamond_ore", "kill.zombie", "move.walk".
 * Plain strings so quests and events can name any of them in config.
 */
public final class StatKeys {

    private StatKeys() {
    }

    public static final String MINE = "mine.";          // + block id, natural blocks only
    public static final String MINE_TOTAL = "mine.total";
    public static final String PLACE = "place.";        // + block id
    public static final String PLACE_TOTAL = "place.total";
    public static final String HARVEST = "harvest.";    // + crop id
    public static final String HARVEST_TOTAL = "harvest.total";
    public static final String KILL = "kill.";          // + entity id, natural spawns only
    public static final String KILL_TOTAL = "kill.total";
    public static final String KILL_PLAYER = "kill.player";
    public static final String DEATH_TOTAL = "death.total";
    public static final String DEATH = "death.";        // + damage type
    public static final String FISH = "fish.";          // fish / treasure / junk / + item id
    public static final String BREED = "breed.";        // + entity id
    public static final String BREED_TOTAL = "breed.total";
    public static final String TAME = "tame.";
    public static final String SHEAR = "shear.";
    public static final String CRAFT = "craft.";        // + item id
    public static final String CRAFT_TOTAL = "craft.total";
    public static final String SMELT = "smelt.";        // + item id
    public static final String SMELT_TOTAL = "smelt.total";
    public static final String BREW_TOTAL = "brew.total";
    public static final String ENCHANT_TOTAL = "enchant.total";
    public static final String TRADE_TOTAL = "trade.total";
    public static final String EXPLORE_CHUNKS = "explore.chunks";
    public static final String BIOME = "biome.";        // + biome id (1 once visited)
    public static final String STRUCTURE = "structure."; // + structure id (1 once visited)
    public static final String MOVE = "move.";          // walk / swim / boat / horse / minecart / elytra, in cm
    public static final String TIME_PLAYED = "time.played"; // seconds
    public static final String MONEY_EARNED = "money.earned";
    public static final String MONEY_SPENT = "money.spent";
    public static final String QUESTS_DONE = "quest.done";

    /** Spawn reasons that don't count as natural (farms built from spawners, eggs, breeding, commands). */
    private static final Set<String> UNNATURAL_SPAWNS = Set.of(
            "SPAWNER", "TRIAL_SPAWNER", "SPAWNER_EGG", "DISPENSE_EGG", "EGG", "BREEDING", "CUSTOM", "COMMAND",
            "BUILD_IRONGOLEM", "BUILD_SNOWMAN", "BUILD_WITHER", "BUCKET", "OMINOUS_ITEM_SPAWNER", "DUPLICATION",
            "SHEARED", "ENDER_PEARL", "NETHER_PORTAL");

    /** True if a mob that spawned for this reason should count for kill quests and events. */
    public static boolean isNaturalSpawn(String spawnReason) {
        return spawnReason != null && !UNNATURAL_SPAWNS.contains(spawnReason);
    }

    private static final Set<String> FISH_ITEMS = Set.of("cod", "salmon", "tropical_fish", "pufferfish");
    private static final Set<String> TREASURE_ITEMS = Set.of("bow", "enchanted_book", "name_tag", "nautilus_shell", "saddle");

    /** "fish", "treasure" or "junk" for an item id caught by fishing. A fishing rod is treasure only when enchanted. */
    public static String fishCategory(String itemId, boolean enchanted) {
        if (FISH_ITEMS.contains(itemId)) return "fish";
        if (TREASURE_ITEMS.contains(itemId)) return "treasure";
        if (itemId.equals("fishing_rod") && enchanted) return "treasure";
        return "junk";
    }
}
