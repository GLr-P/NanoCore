package io.github.glrp.nanocore.event;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Reads a phase's gameplay parts (see {@link PartsDef}). Bad values are skipped with a warning. */
final class PartsDefs {

    static final Set<String> ABILITIES = Set.of("minions", "slam", "leap", "shield", "enrage", "teleport", "pull");
    static final Set<String> ARMOR = Set.of("leather", "chainmail", "iron", "gold", "golden", "diamond", "netherite");

    private PartsDefs() {
    }

    static PartsDef parse(ConfigurationSection p, String where, Predicate<String> validItem, Predicate<String> validMob, Consumer<String> warn) {
        List<PartsDef.Wave> waves = new ArrayList<>();
        int wn = 0;
        for (Map<?, ?> map : p.getMapList("waves")) {
            wn++;
            ConfigurationSection w = new MemoryConfiguration().createSection("w", map);
            List<PartsDef.MobSpec> mobs = new ArrayList<>();
            for (String line : w.getStringList("mobs")) {
                PartsDef.MobSpec m = mob(line);
                if (m == null || !validMob.test(m.type())) warn.accept(where + ": wave " + wn + ": unknown mob '" + line + "', skipped");
                else mobs.add(m);
            }
            if (mobs.isEmpty()) {
                warn.accept(where + ": wave " + wn + " has no mobs, skipped");
                continue;
            }
            String armor = lower(w.getString("armor"));
            if (armor != null && !ARMOR.contains(armor)) {
                warn.accept(where + ": wave " + wn + ": unknown armor '" + armor + "', ignored");
                armor = null;
            }
            String weapon = lower(w.getString("weapon"));
            if (weapon != null && !validItem.test(weapon)) {
                warn.accept(where + ": wave " + wn + ": unknown weapon '" + weapon + "', ignored");
                weapon = null;
            }
            long next = 0;
            if (w.contains("next")) {
                String n = w.getString("next", "cleared");
                if (!n.equalsIgnoreCase("cleared")) {
                    try {
                        next = Durations.parse(n);
                    } catch (IllegalArgumentException e) {
                        warn.accept(where + ": wave " + wn + ": next must be 'cleared' or a time like 2m, using cleared");
                    }
                }
            }
            waves.add(new PartsDef.Wave(List.copyOf(mobs), Math.max(0.1, w.getDouble("health", 1)), Math.max(0, w.getDouble("damage", 1)),
                    armor, weapon, List.copyOf(w.getStringList("effects")), next));
        }

        PartsDef.Boss boss = null;
        ConfigurationSection b = p.getConfigurationSection("boss");
        if (b != null) {
            String type = lower(b.getString("type", "zombie"));
            if (!validMob.test(type)) {
                warn.accept(where + ": unknown boss type '" + type + "', no boss");
            } else {
                List<String> abilities = new ArrayList<>();
                for (String a : b.getStringList("abilities")) {
                    String al = a.toLowerCase(Locale.ROOT).trim();
                    if (ABILITIES.contains(al)) abilities.add(al);
                    else warn.accept(where + ": unknown boss ability '" + a + "' (" + String.join(", ", ABILITIES) + ")");
                }
                String minion = lower(b.getString("minion", "zombie"));
                if (!validMob.test(minion)) minion = "zombie";
                boss = new PartsDef.Boss(type, b.getString("name", "Boss"), Math.max(1, b.getDouble("health", 300)), List.copyOf(abilities),
                        minion, b.getBoolean("existing", false));
            }
        }

        PartsDef.Plots plots = null;
        ConfigurationSection pl = p.getConfigurationSection("plots");
        if (pl != null && blank(pl.getString("area")) != null) {
            plots = new PartsDef.Plots(pl.getString("area").trim(), Math.max(3, Math.min(64, pl.getInt("size", 16))), Math.max(0, Math.min(32, pl.getInt("gap", 3))));
        }

        PartsDef.Collect collect = null;
        ConfigurationSection c = p.getConfigurationSection("collect");
        if (c != null) {
            List<String> pool = new ArrayList<>();
            for (String it : c.getStringList("pool")) {
                String id = it.toLowerCase(Locale.ROOT).trim().replace("minecraft:", "");
                if (validItem.test(id)) pool.add(id);
                else warn.accept(where + ": unknown collect item '" + it + "', skipped");
            }
            if (pool.isEmpty()) warn.accept(where + ": collect has an empty pool, ignored");
            else collect = new PartsDef.Collect(Math.max(1, Math.min(pool.size(), c.getInt("count", 15))), List.copyOf(pool), c.getBoolean("at-board", true));
        }

        PartsDef.Treasure treasure = null;
        ConfigurationSection t = p.getConfigurationSection("treasure");
        if (t != null) {
            treasure = new PartsDef.Treasure(Math.max(1, Math.min(50, t.getInt("chests", 5))), Math.max(10, t.getInt("radius", 500)),
                    blank(t.getString("around")), Math.max(0, t.getLong("coins", 100)), t.getBoolean("trophy", true));
        }

        PartsDef.Race race = null;
        ConfigurationSection r = p.getConfigurationSection("race");
        if (r != null) {
            String start = blank(r.getString("start")), finish = blank(r.getString("finish"));
            if (start == null || finish == null) warn.accept(where + ": race needs a start and a finish area, ignored");
            else race = new PartsDef.Race(start, List.copyOf(r.getStringList("checkpoints")), finish);
        }

        PartsDef.Fill fill = null;
        ConfigurationSection f = p.getConfigurationSection("fill");
        if (f != null && blank(f.getString("area")) != null) {
            String block = lower(f.getString("block", "snow_block"));
            fill = new PartsDef.Fill(f.getString("area").trim(), block);
        }

        PartsDef.Streak streak = null;
        ConfigurationSection s = p.getConfigurationSection("streak");
        if (s != null) streak = new PartsDef.Streak(Math.max(2, s.getInt("at", 3)), Math.max(0, s.getLong("points", 1)));

        long protect = 0;
        if (p.contains("respawn-protection")) {
            try {
                protect = Durations.parse(p.getString("respawn-protection"));
            } catch (IllegalArgumentException e) {
                warn.accept(where + ": respawn-protection must be a time like 30s, ignored");
            }
        }

        PartsDef.Deliver deliver = null;
        ConfigurationSection d = p.getConfigurationSection("deliver");
        if (d != null) {
            Map<String, Long> pts = items(d.getConfigurationSection("items"), where, validItem, warn);
            Map<String, Long> need = items(d.getConfigurationSection("need"), where, validItem, warn);
            if (pts.isEmpty() && need.isEmpty()) warn.accept(where + ": deliver lists no items, ignored");
            else deliver = new PartsDef.Deliver(pts, need, d.getBoolean("at-board", false), d.getBoolean("storehouse", true));
        }

        String teleport = lower(p.getString("teleport"));
        if (teleport != null && !teleport.equals("area") && !teleport.equals("plot")) {
            warn.accept(where + ": teleport must be area or plot, ignored");
            teleport = null;
        }

        PartsDef.Lose lose = null;
        ConfigurationSection l = p.getConfigurationSection("lose");
        if (l != null) lose = new PartsDef.Lose(l.getBoolean("timer", false), l.getBoolean("all-down", false));

        List<String> breakable = new ArrayList<>();
        for (String it : p.getStringList("breakable")) breakable.add(it.toLowerCase(Locale.ROOT).trim().replace("minecraft:", ""));

        return new PartsDef(List.copyOf(waves), boss, blank(p.getString("spawn-area")), p.getBoolean("scale", true), plots,
                List.copyOf(p.getStringList("themes")), collect, treasure, race, blank(p.getString("hold")), fill,
                p.contains("out-below") ? p.getInt("out-below") : null, p.getBoolean("revive", false), p.getBoolean("survivor-points", false),
                Math.max(0, p.getInt("seekers", 0)), p.getBoolean("blind-seekers", false), streak, protect, deliver,
                List.copyOf(p.getStringList("checklist")), p.contains("shop-tax") ? Math.max(0, Math.min(100, p.getDouble("shop-tax"))) : null,
                List.copyOf(breakable), teleport, lose);
    }

    /** "zombie 10" or "skeleton 5 Bone Archer". */
    static PartsDef.MobSpec mob(String line) {
        if (line == null || line.isBlank()) return null;
        String[] f = line.trim().split("\\s+", 3);
        String type = f[0].toLowerCase(Locale.ROOT).replace("minecraft:", "");
        int count = 1;
        if (f.length > 1) {
            try {
                count = Integer.parseInt(f[1]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new PartsDef.MobSpec(type, Math.max(1, Math.min(100, count)), f.length > 2 ? f[2] : null);
    }

    private static Map<String, Long> items(ConfigurationSection s, String where, Predicate<String> validItem, Consumer<String> warn) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            String key = k.toLowerCase(Locale.ROOT).trim().replace("minecraft:", "");
            if (!key.startsWith("#") && !validItem.test(key)) {
                warn.accept(where + ": unknown deliver item '" + k + "', skipped");
                continue;
            }
            out.put(key, Math.max(0, s.getLong(k)));
        }
        return out;
    }

    private static String lower(String v) {
        String b = blank(v);
        return b == null ? null : b.toLowerCase(Locale.ROOT).replace("minecraft:", "");
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
