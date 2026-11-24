package io.github.glrp.nanocore.event;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Reads events from events.yml (and the files in events/). Works on plain YAML sections, so the bundled
 * events are unit-tested. A bad event is skipped with a warning; a bad value inside one falls back to its default.
 */
public final class EventDefs {

    private EventDefs() {
    }

    public static Map<String, EventDef> parse(ConfigurationSection root, EventDef.Prizes defaults, Predicate<String> validMilestone,
                                              Predicate<String> validItem, Consumer<String> warn) {
        return parse(root, defaults, validMilestone, validItem, mob -> true, warn);
    }

    public static Map<String, EventDef> parse(ConfigurationSection root, EventDef.Prizes defaults, Predicate<String> validMilestone,
                                              Predicate<String> validItem, Predicate<String> validMob, Consumer<String> warn) {
        Map<String, EventDef> out = new LinkedHashMap<>();
        if (root == null) return out;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            try {
                out.put(id, parseOne(id, s, defaults, validMilestone, validItem, validMob, warn));
            } catch (IllegalArgumentException e) {
                warn.accept("events: " + id + ": " + e.getMessage() + ", skipped");
            }
        }
        return out;
    }

    private static EventDef parseOne(String id, ConfigurationSection s, EventDef.Prizes defaults, Predicate<String> validMilestone,
                                     Predicate<String> validItem, Predicate<String> validMob, Consumer<String> warn) {
        String join = s.getString("join", "opt-in").toLowerCase(Locale.ROOT);
        if (!join.equals("opt-in") && !join.equals("auto")) throw new IllegalArgumentException("join must be opt-in or auto");

        List<EventDef.Phase> phases = new ArrayList<>();
        int n = 0;
        for (Map<?, ?> map : s.getMapList("phases")) {
            n++;
            ConfigurationSection p = new MemoryConfiguration().createSection("p", map);
            phases.add(phase(id, n, p, validItem, validMob, warn));
        }
        if (phases.isEmpty()) throw new IllegalArgumentException("no phases");

        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String d : s.getStringList("weekdays")) {
            DayOfWeek day = day(d);
            if (day == null) warn.accept("events: " + id + ": unknown weekday '" + d + "', ignored");
            else days.add(day);
        }

        EventDef.Winner winner;
        try {
            winner = EventDef.Winner.valueOf(s.getString("winner", "score").trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("winner must be score, votes, last-standing, time or none");
        }

        String unlocks = blank(s.getString("unlocks"));
        if (unlocks != null && !validMilestone.test(unlocks)) throw new IllegalArgumentException("unknown milestone '" + unlocks + "'");
        String requires = blank(s.getString("requires"));
        if (requires != null && !validMilestone.test(requires)) {
            warn.accept("events: " + id + ": unknown milestone in requires '" + requires + "', ignored");
            requires = null;
        }

        ConfigurationSection pz = s.getConfigurationSection("prizes");
        EventDef.Prizes prizes = defaults;
        if (pz != null) {
            List<Long> places = pz.contains("places") ? pz.getLongList("places") : defaults.places();
            String trophyItem = pz.getString("trophy-item", defaults.trophyItem());
            if (!validItem.test(trophyItem)) {
                warn.accept("events: " + id + ": unknown trophy-item '" + trophyItem + "', using " + defaults.trophyItem());
                trophyItem = defaults.trophyItem();
            }
            prizes = new EventDef.Prizes(List.copyOf(places), pz.getLong("participation", defaults.participation()),
                    pz.getBoolean("trophy", defaults.trophy()), trophyItem, pz.getLong("goal", 0), pz.getLong("goal-bonus", 0),
                    pz.getLong("active-actions", defaults.activeActions()), Math.max(0, pz.getLong("share", 0)), Math.max(0, pz.getLong("share-min", 0)));
        }

        return new EventDef(id, s.getString("name", id.replace('_', ' ')), s.getString("icon", "clock"),
                List.copyOf(s.getStringList("rules")), join.equals("auto"), s.getBoolean("late-join", true),
                Math.max(0, s.getInt("max-players", 0)), Math.max(0, Math.min(8, s.getInt("teams", 0))),
                blank(s.getString("area")), List.copyOf(s.getStringList("safe-zones")),
                s.getBoolean("auto", true), Math.max(1, s.getInt("weight", 1)), Math.max(0, s.getInt("min-players", 2)),
                days, List.copyOf(phases), prizes, unlocks, winner, requires);
    }

    private static EventDef.Phase phase(String event, int n, ConfigurationSection p, Predicate<String> validItem, Predicate<String> validMob,
                                        Consumer<String> warn) {
        String where = "events: " + event + ": phase " + n;
        ConfigurationSection e = p.getConfigurationSection("end");
        EventDef.End end;
        if (e == null) {
            end = new EventDef.End(0, 0, -1, null, false, false, false, false, 0);
        } else {
            long timer = 0;
            if (e.contains("timer")) {
                try {
                    timer = Durations.parse(e.getString("timer"));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("phase " + n + ": " + ex.getMessage());
                }
            }
            long ticks = Math.round(e.getDouble("mc-days", 0) * 24000);
            long time = e.contains("mc-time") ? Math.floorMod(e.getLong("mc-time"), 24000L) : -1;
            String ready = blank(e.getString("ready"));
            if (ready != null && !ReadyRule.valid(ready)) {
                warn.accept(where + ": ready must be all, online or a share like 75%; using all");
                ready = "all";
            }
            end = new EventDef.End(timer, ticks, time, ready, e.getBoolean("goal", false), e.getBoolean("last-standing", false), e.getBoolean("skip-if-past", false),
                    e.getBoolean("cleared", false), Math.max(0, e.getInt("finishers", 0)));
        }

        Map<String, Long> points = new LinkedHashMap<>();
        ConfigurationSection ps = p.getConfigurationSection("points");
        // Stat names contain dots, which YAML sections read as nesting ("mine.coal_ore" -> mine: coal_ore:), so walk the leaves.
        if (ps != null) for (String k : ps.getKeys(true)) if (!ps.isConfigurationSection(k)) points.put(k.toLowerCase(Locale.ROOT), ps.getLong(k));

        EventDef.Rules rules = EventDef.Rules.NONE;
        ConfigurationSection r = p.getConfigurationSection("rules");
        if (r != null) {
            Integer drop = r.contains("drop-chance") ? Math.max(0, Math.min(100, r.getInt("drop-chance"))) : null;
            String weather = blank(r.getString("weather"));
            if (weather != null && !weather.equalsIgnoreCase("clear") && !weather.equalsIgnoreCase("rain")) {
                warn.accept(where + ": weather must be clear or rain, ignored");
                weather = null;
            }
            String difficulty = blank(r.getString("difficulty"));
            if (difficulty != null && !List.of("peaceful", "easy", "normal", "hard").contains(difficulty.toLowerCase(Locale.ROOT))) {
                warn.accept(where + ": difficulty must be peaceful, easy, normal or hard, ignored");
                difficulty = null;
            }
            rules = new EventDef.Rules(bool(r, "pvp"), bool(r, "keep-inventory"), drop, bool(r, "mob-griefing"), bool(r, "building"),
                    bool(r, "fall-damage"), bool(r, "hunger"), bool(r, "natural-regen"), bool(r, "ender-pearls"), bool(r, "elytra"),
                    r.contains("time-lock") ? Math.floorMod(r.getLong("time-lock"), 24000L) : null,
                    weather == null ? null : weather.toLowerCase(Locale.ROOT),
                    difficulty == null ? null : difficulty.toLowerCase(Locale.ROOT),
                    r.contains("quest-multiplier") ? Math.max(0, r.getDouble("quest-multiplier")) : null,
                    r.getBoolean("in-area-only", false));
        }

        EventDef.Kit kit = null;
        ConfigurationSection k = p.getConfigurationSection("kit");
        if (k != null) {
            List<String> items = new ArrayList<>();
            for (String it : k.getStringList("items")) {
                String item = it.trim().split("\\s+")[0].toLowerCase(Locale.ROOT).replace("minecraft:", "");
                if (validItem.test(item)) items.add(it.trim());
                else warn.accept(where + ": unknown kit item '" + it + "', skipped");
            }
            String gm = blank(k.getString("gamemode"));
            if (gm != null && !List.of("survival", "creative", "adventure").contains(gm.toLowerCase(Locale.ROOT))) {
                warn.accept(where + ": kit gamemode must be survival, creative or adventure, ignored");
                gm = null;
            }
            kit = new EventDef.Kit(List.copyOf(items), gm == null ? null : gm.toLowerCase(Locale.ROOT));
        }

        return new EventDef.Phase(p.getString("id", "phase-" + n), p.getString("name", "Phase " + n), end, points, rules, kit,
                p.getBoolean("vote", false), Math.max(0, p.getInt("lives", 0)), Math.max(0, p.getLong("goal", 0)),
                PartsDefs.parse(p, where, validItem, validMob, warn));
    }

    private static Boolean bool(ConfigurationSection s, String key) {
        return s.contains(key) ? s.getBoolean(key) : null;
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    static DayOfWeek day(String d) {
        String s = d.trim().toUpperCase(Locale.ROOT);
        for (DayOfWeek w : DayOfWeek.values()) if (w.name().startsWith(s) && s.length() >= 3) return w;
        return null;
    }
}
