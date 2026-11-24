package io.github.glrp.nanocore.quest;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.db.Database;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The physical side of quests: board blocks players right-click, their floating labels, and the town storehouse
 * chest that delivered items go into (anything that doesn't fit waits in a backlog until there's room).
 */
public final class QuestBoards implements Listener {

    /** A block position. */
    public record Place(String world, int x, int y, int z) {
        static Place of(Block b) {
            return new Place(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        }

        Block block(NanoCorePlugin plugin) {
            World w = plugin.getServer().getWorld(world);
            return w == null ? null : w.getBlockAt(x, y, z);
        }

        boolean loaded(NanoCorePlugin plugin) {
            World w = plugin.getServer().getWorld(world);
            return w != null && w.isChunkLoaded(x >> 4, z >> 4);
        }

        @Override
        public String toString() {
            return world + " " + x + " " + y + " " + z;
        }
    }

    private final NanoCorePlugin plugin;
    private final Database db;
    private final List<Place> boards = new ArrayList<>();
    private Place storehouse;
    private final List<ItemStack> backlog = new ArrayList<>();
    private final Map<Place, UUID> labels = new HashMap<>();

    public QuestBoards(NanoCorePlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT kind, world, x, y, z FROM quest_places");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Place p = new Place(rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5));
                    if (rs.getString(1).equals("board")) boards.add(p);
                    else storehouse = p;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT item FROM quest_backlog ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        backlog.add(ItemStack.deserializeBytes(rs.getBytes(1)));
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("A storehouse backlog item couldn't be read: " + e);
                    }
                }
            }
            return null;
        });
    }

    public List<Place> boards() {
        return List.copyOf(boards);
    }

    public Place storehouse() {
        return storehouse;
    }

    public int backlogSize() {
        int n = 0;
        for (ItemStack s : backlog) n += s.getAmount();
        return n;
    }

    public boolean isBoard(Block b) {
        return boards.contains(Place.of(b));
    }

    /** True if the player is within reach of any board. */
    public boolean nearBoard(Player p, double reach) {
        Location at = p.getLocation();
        for (Place b : boards) {
            if (!b.world().equals(at.getWorld().getName())) continue;
            double dx = b.x() + 0.5 - at.getX(), dy = b.y() + 0.5 - at.getY(), dz = b.z() + 0.5 - at.getZ();
            if (dx * dx + dy * dy + dz * dz <= reach * reach) return true;
        }
        return false;
    }

    public boolean addBoard(Block b) {
        Place p = Place.of(b);
        if (boards.contains(p)) return false;
        boards.add(p);
        db.run(c -> Database.update(c, "INSERT OR IGNORE INTO quest_places (kind, world, x, y, z) VALUES ('board', ?, ?, ?, ?)",
                p.world(), p.x(), p.y(), p.z()));
        return true;
    }

    public boolean removeBoard(Block b) {
        Place p = Place.of(b);
        if (!boards.remove(p)) return false;
        removeLabel(p);
        db.run(c -> Database.update(c, "DELETE FROM quest_places WHERE kind = 'board' AND world = ? AND x = ? AND y = ? AND z = ?",
                p.world(), p.x(), p.y(), p.z()));
        return true;
    }

    public void setStorehouse(Block b) {
        Place p = Place.of(b);
        storehouse = p;
        db.run(c -> {
            Database.update(c, "DELETE FROM quest_places WHERE kind = 'storehouse'");
            Database.update(c, "INSERT INTO quest_places (kind, world, x, y, z) VALUES ('storehouse', ?, ?, ?, ?)", p.world(), p.x(), p.y(), p.z());
        });
        flushBacklog();
    }

    // --- storehouse ---

    /** Puts delivered items into the storehouse; what doesn't fit waits in the backlog. */
    public void store(Material m, int amount) {
        int max = m.getMaxStackSize();
        while (amount > 0) {
            int n = Math.min(max, amount);
            backlog.add(ItemStack.of(m, n));
            amount -= n;
        }
        flushBacklog();
    }

    /** Moves as much of the backlog as fits into the storehouse chest, then saves what's left. */
    public void flushBacklog() {
        if (!backlog.isEmpty() && storehouse != null && storehouse.loaded(plugin)) {
            Block b = storehouse.block(plugin);
            if (b != null && b.getState(false) instanceof Container chest) {
                List<ItemStack> left = new ArrayList<>();
                for (ItemStack s : backlog) left.addAll(chest.getInventory().addItem(s.clone()).values());
                backlog.clear();
                backlog.addAll(left);
            }
        }
        List<byte[]> rows = new ArrayList<>();
        for (ItemStack s : backlog) rows.add(s.serializeAsBytes());
        db.run(c -> {
            Database.update(c, "DELETE FROM quest_backlog");
            for (byte[] r : rows) Database.update(c, "INSERT INTO quest_backlog (item) VALUES (?)", (Object) r);
        });
    }

    // --- labels ---

    /** Spawns missing labels (for loaded boards) and sets their text. Labels aren't saved with the world. */
    public void updateLabels(Component text, double height) {
        for (Place p : boards) {
            if (!p.loaded(plugin)) {
                labels.remove(p);
                continue;
            }
            UUID id = labels.get(p);
            Entity e = id == null ? null : plugin.getServer().getEntity(id);
            if (e instanceof TextDisplay td && td.isValid()) {
                td.text(text);
                continue;
            }
            World w = plugin.getServer().getWorld(p.world());
            Location at = new Location(w, p.x() + 0.5, p.y() + height, p.z() + 0.5);
            TextDisplay td = w.spawn(at, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setDefaultBackground(false);
                d.setShadowed(true);
                d.text(text);
            });
            labels.put(p, td.getUniqueId());
        }
    }

    private void removeLabel(Place p) {
        UUID id = labels.remove(p);
        Entity e = id == null ? null : plugin.getServer().getEntity(id);
        if (e != null) e.remove();
    }

    public void removeLabels() {
        for (Place p : new ArrayList<>(labels.keySet())) removeLabel(p);
    }

    // --- events ---

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        if (!isBoard(e.getClickedBlock())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> QuestMenu.open(plugin, p, true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isBoard(e.getBlock())) {
            if (!e.getPlayer().hasPermission("nanocore.admin")) {
                e.setCancelled(true);
                plugin.messages().send(e.getPlayer(), "quests.board-protected");
                return;
            }
            removeBoard(e.getBlock());
            plugin.messages().send(e.getPlayer(), "quests.board-removed");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isBoard);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isBoard);
    }
}
