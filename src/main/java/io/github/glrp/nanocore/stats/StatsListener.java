package io.github.glrp.nanocore.stats;

import io.github.glrp.nanocore.blocks.PlacedBlockTracker;
import io.github.glrp.nanocore.config.Settings;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Turns gameplay into counters. Register BEFORE PlacedBlockTracker (both MONITOR; this one reads the marks first). */
public final class StatsListener implements Listener {

    private static final Set<Material> CROPS = Set.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.NETHER_WART, Material.COCOA);
    private static final Set<Material> GROWN_BLOCKS = Set.of(
            Material.MELON, Material.PUMPKIN, Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO, Material.KELP_PLANT);

    /** Vanilla distance statistics folded into our move.* counters. */
    private static final Map<Statistic, String> MOVE_STATS = new EnumMap<>(Statistic.class);

    static {
        MOVE_STATS.put(Statistic.WALK_ONE_CM, "walk");
        MOVE_STATS.put(Statistic.SPRINT_ONE_CM, "walk");
        MOVE_STATS.put(Statistic.CROUCH_ONE_CM, "walk");
        MOVE_STATS.put(Statistic.SWIM_ONE_CM, "swim");
        MOVE_STATS.put(Statistic.WALK_UNDER_WATER_ONE_CM, "swim");
        MOVE_STATS.put(Statistic.WALK_ON_WATER_ONE_CM, "swim");
        MOVE_STATS.put(Statistic.BOAT_ONE_CM, "boat");
        MOVE_STATS.put(Statistic.HORSE_ONE_CM, "horse");
        MOVE_STATS.put(Statistic.MINECART_ONE_CM, "minecart");
        MOVE_STATS.put(Statistic.AVIATE_ONE_CM, "elytra");
    }

    private final Plugin plugin;
    private final StatsService stats;
    private final PlacedBlockTracker placed;
    private final Supplier<Settings> settings;
    private final Map<UUID, Map<Statistic, Integer>> lastVanilla = new HashMap<>();
    private final Map<UUID, Integer> lastPlayTicks = new HashMap<>();
    private final Map<Location, UUID> brewers = new HashMap<>();

    public StatsListener(Plugin plugin, StatsService stats, PlacedBlockTracker placed, Supplier<Settings> settings) {
        this.plugin = plugin;
        this.stats = stats;
        this.placed = placed;
        this.settings = settings;
    }

    /** Starts the repeating checks: vanilla distances and play time (10 s), biome and structure visits (5 s). */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::pollVanilla, 200L, 200L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::pollPlaces, 100L, 100L);
    }

    private boolean counts(Player p) {
        GameMode g = p.getGameMode();
        return settings.get().countCreative() || g == GameMode.SURVIVAL || g == GameMode.ADVENTURE;
    }

    private static String id(Material m) {
        return m.getKey().getKey();
    }

    private static String id(Entity e) {
        return e.getType().getKey().getKey();
    }

    // --- login / quit ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) stats.load(e.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (stats.loadedPlayers().contains(p.getUniqueId())) {
            snapshotVanilla(p);
        } else {
            // Safety net if the async login hook didn't run (e.g. after /reload).
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                stats.load(p.getUniqueId());
                plugin.getServer().getScheduler().runTask(plugin, () -> snapshotVanilla(p));
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        collectVanilla(p);
        lastVanilla.remove(p.getUniqueId());
        lastPlayTicks.remove(p.getUniqueId());
        stats.unload(p.getUniqueId());
    }

    // --- blocks ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!counts(p)) return;
        Block b = e.getBlock();
        if (placed.isPlaced(b)) return; // player-placed blocks never count
        Location at = b.getLocation();
        Material type = b.getType();
        if (CROPS.contains(type)) {
            if (b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge()) {
                stats.add(p, StatKeys.HARVEST + id(type), 1, at);
                stats.add(p, StatKeys.HARVEST_TOTAL, 1, at);
            }
            return; // crops aren't "mined"
        }
        if (GROWN_BLOCKS.contains(type)) {
            stats.add(p, StatKeys.HARVEST + id(type), 1, at);
            stats.add(p, StatKeys.HARVEST_TOTAL, 1, at);
        }
        stats.add(p, StatKeys.MINE + id(type), 1, at);
        stats.add(p, StatKeys.MINE_TOTAL, 1, at);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (!counts(p)) return;
        Location at = e.getBlockPlaced().getLocation();
        stats.add(p, StatKeys.PLACE + id(e.getBlockPlaced().getType()), 1, at);
        stats.add(p, StatKeys.PLACE_TOTAL, 1, at);
    }

    // --- combat ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null || !counts(killer)) return;
        Location at = e.getEntity().getLocation();
        if (e.getEntity() instanceof Player victim) {
            if (!victim.equals(killer)) stats.add(killer, StatKeys.KILL_PLAYER, 1, at);
            return;
        }
        if (!StatKeys.isNaturalSpawn(e.getEntity().getEntitySpawnReason().name())) return;
        stats.add(killer, StatKeys.KILL + id(e.getEntity()), 1, at);
        stats.add(killer, StatKeys.KILL_TOTAL, 1, at);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Location at = p.getLocation();
        stats.add(p, StatKeys.DEATH_TOTAL, 1, at);
        try {
            stats.add(p, StatKeys.DEATH + e.getDamageSource().getDamageType().getKey().getKey(), 1, at);
        } catch (RuntimeException ignored) {
            // damage type unknown: the total is enough
        }
    }

    // --- skills ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof Item item)) return;
        Player p = e.getPlayer();
        if (!counts(p)) return;
        ItemStack stack = item.getItemStack();
        String itemId = id(stack.getType());
        String category = StatKeys.fishCategory(itemId, !stack.getEnchantments().isEmpty());
        Location at = p.getLocation();
        stats.add(p, StatKeys.FISH + category, 1, at);
        stats.add(p, StatKeys.FISH + itemId, 1, at);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (!(e.getBreeder() instanceof Player p) || !counts(p)) return;
        stats.add(p, StatKeys.BREED + id(e.getEntity()), 1, e.getEntity().getLocation());
        stats.add(p, StatKeys.BREED_TOTAL, 1, e.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent e) {
        if (!(e.getOwner() instanceof Player p) || !counts(p)) return;
        stats.add(p, StatKeys.TAME + id(e.getEntity()), 1, e.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent e) {
        if (!counts(e.getPlayer())) return;
        stats.add(e.getPlayer(), StatKeys.SHEAR + id(e.getEntity()), 1, e.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !counts(p)) return;
        ItemStack result = e.getRecipe().getResult();
        int crafts = 1;
        if (e.isShiftClick()) {
            // Shift-click crafts as many as the smallest ingredient stack allows.
            int min = Integer.MAX_VALUE;
            for (ItemStack s : e.getInventory().getMatrix()) if (s != null && !s.getType().isAir()) min = Math.min(min, s.getAmount());
            crafts = min == Integer.MAX_VALUE ? 1 : min;
        }
        long amount = (long) result.getAmount() * crafts;
        stats.add(p, StatKeys.CRAFT + id(result.getType()), amount, p.getLocation());
        stats.add(p, StatKeys.CRAFT_TOTAL, amount, p.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSmelt(FurnaceExtractEvent e) {
        Player p = e.getPlayer();
        if (!counts(p) || e.getItemAmount() <= 0) return;
        stats.add(p, StatKeys.SMELT + id(e.getItemType()), e.getItemAmount(), e.getBlock().getLocation());
        stats.add(p, StatKeys.SMELT_TOTAL, e.getItemAmount(), e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getInventory().getType() == InventoryType.BREWING && e.getInventory().getLocation() != null) {
            brewers.put(e.getInventory().getLocation().toBlockLocation(), e.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent e) {
        UUID who = brewers.get(e.getBlock().getLocation().toBlockLocation());
        Player p = who == null ? null : plugin.getServer().getPlayer(who);
        if (p == null || !counts(p)) return;
        long potions = 0;
        List<ItemStack> results = e.getResults();
        for (ItemStack s : results) if (s != null && !s.getType().isAir()) potions++;
        stats.add(p, StatKeys.BREW_TOTAL, potions, e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        if (!counts(e.getEnchanter())) return;
        stats.add(e.getEnchanter(), StatKeys.ENCHANT_TOTAL, 1, e.getEnchantBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent e) {
        if (!counts(e.getPlayer())) return;
        stats.add(e.getPlayer(), StatKeys.TRADE_TOTAL, 1, e.getPlayer().getLocation());
    }

    // --- exploring ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        int cx = to.getBlockX() >> 4;
        int cz = to.getBlockZ() >> 4;
        if (cx == (from.getBlockX() >> 4) && cz == (from.getBlockZ() >> 4) && to.getWorld().equals(from.getWorld())) return;
        Player p = e.getPlayer();
        if (!counts(p)) return;
        if (stats.explore(p, to.getWorld().getName(), cx, cz)) stats.add(p, StatKeys.EXPLORE_CHUNKS, 1, to);
    }

    private void pollPlaces() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (!counts(p) || !stats.loadedPlayers().contains(p.getUniqueId())) continue;
            Location at = p.getLocation();
            try {
                stats.addOnce(p, StatKeys.BIOME + at.getBlock().getBiome().getKey().getKey(), at);
                for (GeneratedStructure s : at.getChunk().getStructures()) {
                    BoundingBox box = s.getBoundingBox();
                    if (box.contains(at.toVector())) stats.addOnce(p, StatKeys.STRUCTURE + Places.structureId(s.getStructure()), at);
                }
            } catch (RuntimeException ignored) {
                // unknown biome/structure in a custom world: skip
            }
        }
    }

    // --- vanilla distances and play time ---

    private void snapshotVanilla(Player p) {
        Map<Statistic, Integer> snap = new EnumMap<>(Statistic.class);
        for (Statistic s : MOVE_STATS.keySet()) snap.put(s, p.getStatistic(s));
        lastVanilla.put(p.getUniqueId(), snap);
        lastPlayTicks.put(p.getUniqueId(), p.getStatistic(Statistic.PLAY_ONE_MINUTE));
    }

    private void pollVanilla() {
        for (Player p : plugin.getServer().getOnlinePlayers()) collectVanilla(p);
    }

    private void collectVanilla(Player p) {
        Map<Statistic, Integer> snap = lastVanilla.get(p.getUniqueId());
        if (snap == null || !stats.loadedPlayers().contains(p.getUniqueId())) return;
        Location at = p.getLocation();
        for (var entry : MOVE_STATS.entrySet()) {
            int now = p.getStatistic(entry.getKey());
            int delta = now - snap.getOrDefault(entry.getKey(), now);
            snap.put(entry.getKey(), now);
            if (delta > 0 && counts(p)) stats.add(p, StatKeys.MOVE + entry.getValue(), delta, at);
        }
        int ticks = p.getStatistic(Statistic.PLAY_ONE_MINUTE);
        int last = lastPlayTicks.getOrDefault(p.getUniqueId(), ticks);
        int seconds = (ticks - last) / 20;
        if (seconds > 0) {
            lastPlayTicks.put(p.getUniqueId(), last + seconds * 20);
            stats.add(p, StatKeys.TIME_PLAYED, seconds, at);
        }
    }
}
