package io.github.glrp.nanocore.event;

import io.github.glrp.nanocore.db.Database;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Kit mode: saves a player's inventory, XP, position and game mode before an event kit (or spectating), and gives it
 * all back afterwards. Saved to the database right away, so a crash mid-event loses nothing: the player gets their
 * things back the next time they join.
 */
public final class KitService {

    private final Plugin plugin;
    private final Database db;
    private final Map<UUID, String> saved = new HashMap<>();

    public KitService(Plugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, data FROM event_kits"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) saved.put(UUID.fromString(rs.getString(1)), rs.getString(2));
            }
            return null;
        });
    }

    public boolean has(UUID id) {
        return saved.containsKey(id);
    }

    /** Saves the player's things once (a second call keeps the first snapshot). */
    public void save(Player p) {
        UUID id = p.getUniqueId();
        if (saved.containsKey(id)) return;
        YamlConfiguration y = new YamlConfiguration();
        List<String> items = new ArrayList<>();
        for (ItemStack s : p.getInventory().getContents()) {
            items.add(s == null || s.getType().isAir() ? "" : Base64.getEncoder().encodeToString(s.serializeAsBytes()));
        }
        y.set("items", items);
        y.set("level", p.getLevel());
        y.set("exp", (double) p.getExp());
        y.set("gamemode", p.getGameMode().name());
        y.set("health", p.getHealth());
        y.set("food", p.getFoodLevel());
        y.set("saturation", (double) p.getSaturation());
        Location l = p.getLocation();
        y.set("world", l.getWorld().getName());
        y.set("x", l.getX());
        y.set("y", l.getY());
        y.set("z", l.getZ());
        y.set("yaw", (double) l.getYaw());
        y.set("pitch", (double) l.getPitch());
        String data = y.saveToString();
        saved.put(id, data);
        db.run(c -> Database.update(c, "INSERT OR REPLACE INTO event_kits (uuid, data) VALUES (?, ?)", id.toString(), data));
    }

    /** Clears the inventory and hands out the kit. Items look like "stone_sword" or "cooked_beef 16". */
    public void give(Player p, EventDef.Kit kit) {
        p.getInventory().clear();
        for (String line : kit.items()) {
            String[] parts = line.trim().split("\\s+");
            Material m = Material.matchMaterial(parts[0].toLowerCase(Locale.ROOT).replace("minecraft:", ""));
            if (m == null || !m.isItem()) continue;
            int amount = 1;
            if (parts.length > 1) {
                try {
                    amount = Math.max(1, Integer.parseInt(parts[1]));
                } catch (NumberFormatException ignored) {
                    // keep 1
                }
            }
            while (amount > 0) {
                int n = Math.min(amount, m.getMaxStackSize());
                p.getInventory().addItem(ItemStack.of(m, n));
                amount -= n;
            }
        }
        if (kit.gamemode() != null) p.setGameMode(GameMode.valueOf(kit.gamemode().toUpperCase(Locale.ROOT)));
    }

    /** Gives back everything saved for the player. Returns false if nothing was saved. */
    public boolean restore(Player p) {
        UUID id = p.getUniqueId();
        String data = saved.remove(id);
        if (data == null) return false;
        db.run(c -> Database.update(c, "DELETE FROM event_kits WHERE uuid = ?", id.toString()));
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(data);
        } catch (InvalidConfigurationException e) {
            plugin.getLogger().warning("Saved event inventory of " + p.getName() + " couldn't be read: " + e.getMessage());
            return false;
        }
        List<String> items = y.getStringList("items");
        ItemStack[] contents = new ItemStack[p.getInventory().getSize()];
        for (int i = 0; i < items.size() && i < contents.length; i++) {
            String b = items.get(i);
            if (b.isEmpty()) continue;
            try {
                contents[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(b));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("An item saved for " + p.getName() + " couldn't be read: " + e.getMessage());
            }
        }
        p.getInventory().setContents(contents);
        p.setLevel(y.getInt("level"));
        p.setExp((float) Math.max(0, Math.min(0.9999, y.getDouble("exp"))));
        try {
            p.setGameMode(GameMode.valueOf(y.getString("gamemode", "SURVIVAL")));
        } catch (IllegalArgumentException e) {
            p.setGameMode(GameMode.SURVIVAL);
        }
        var maxAttr = p.getAttribute(Attribute.MAX_HEALTH);
        double max = maxAttr == null ? 20 : maxAttr.getValue();
        p.setHealth(Math.max(1, Math.min(max, y.getDouble("health", max))));
        p.setFoodLevel(y.getInt("food", 20));
        p.setSaturation((float) y.getDouble("saturation", 5));
        World w = plugin.getServer().getWorld(y.getString("world", ""));
        if (w != null) {
            p.teleportAsync(new Location(w, y.getDouble("x"), y.getDouble("y"), y.getDouble("z"), (float) y.getDouble("yaw"), (float) y.getDouble("pitch")));
        }
        return true;
    }
}
