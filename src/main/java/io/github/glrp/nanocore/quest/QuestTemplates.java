package io.github.glrp.nanocore.quest;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Reads quest kinds from quests.yml. Works on a plain YAML section (no server needed), so the bundled
 * file is unit-tested. Bad entries are skipped with a warning.
 *
 * <pre>
 * mine_stone:
 *   category: mining
 *   name: "Mine natural stone"
 *   icon: stone
 *   count: [mine.stone, mine.deepslate]   # or deliver: cobblestone / biome: [...] / structure: [...]
 *   easy: 400-600
 *   medium: 1200-1800
 *   community: 40000
 *   requires: nether      # optional milestone
 *   scale: 100            # optional: raw units per shown unit (distances are counted in cm)
 *   weight: 1             # optional: how often it's picked
 *   variants:             # optional: one is picked per quest; "<variant>" in the name becomes its label
 *     oak: { label: "oak", icon: oak_log, count: [mine.oak_log] }
 * </pre>
 */
public final class QuestTemplates {

    private QuestTemplates() {
    }

    public static Map<String, QuestTemplate> parse(ConfigurationSection root, Predicate<String> validItem,
                                                   Predicate<String> validMilestone, Consumer<String> warn) {
        Map<String, QuestTemplate> out = new LinkedHashMap<>();
        if (root == null) return out;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            try {
                out.put(id, parseOne(id, s, validItem, validMilestone));
            } catch (IllegalArgumentException e) {
                warn.accept("quests.yml: " + id + ": " + e.getMessage() + ", skipped");
            }
        }
        return out;
    }

    private static QuestTemplate parseOne(String id, ConfigurationSection s, Predicate<String> validItem, Predicate<String> validMilestone) {
        QuestTemplate.Objective objective;
        List<String> targets;
        if (has(s, "deliver") || s.getBoolean("delivery")) {
            objective = QuestTemplate.Objective.DELIVER;
            String item = clean(s.getString("deliver"));
            if (item != null && !validItem.test(item)) throw new IllegalArgumentException("unknown item '" + item + "'");
            targets = item == null ? List.of() : List.of(item);
        } else if (has(s, "biome")) {
            objective = QuestTemplate.Objective.BIOME;
            targets = list(s, "biome");
        } else if (has(s, "structure")) {
            objective = QuestTemplate.Objective.STRUCTURE;
            targets = list(s, "structure");
        } else {
            objective = QuestTemplate.Objective.COUNT;
            targets = list(s, "count");
        }

        List<QuestTemplate.Variant> variants = new ArrayList<>();
        ConfigurationSection vs = s.getConfigurationSection("variants");
        if (vs != null) {
            String key = switch (objective) {
                case BIOME -> "biome";
                case STRUCTURE -> "structure";
                case DELIVER -> "deliver";
                default -> "count";
            };
            for (String vid : vs.getKeys(false)) {
                ConfigurationSection v = vs.getConfigurationSection(vid);
                if (v == null) continue;
                String icon = v.getString("icon");
                if (icon != null && !validItem.test(clean(icon))) icon = null;
                List<String> vt = list(v, key);
                if (objective == QuestTemplate.Objective.DELIVER) for (String item : vt) if (!validItem.test(item)) throw new IllegalArgumentException("unknown item '" + item + "' in variant " + vid);
                variants.add(new QuestTemplate.Variant(vid, v.getString("label", vid.replace('_', ' ')),
                        icon == null ? null : clean(icon), vt));
            }
        }
        boolean anyTargets = !targets.isEmpty() || (!variants.isEmpty() && variants.stream().allMatch(v -> !v.targets().isEmpty()));
        if (!anyTargets) throw new IllegalArgumentException("nothing to count (add count:, deliver:, biome: or structure:)");

        Map<Tier, Range> amounts = new EnumMap<>(Tier.class);
        for (Tier t : Tier.values()) {
            if (!s.contains(t.id())) continue;
            Range r = Range.parse(s.getString(t.id()));
            if (r.min() <= 0) throw new IllegalArgumentException(t.id() + " amount must be above 0");
            amounts.put(t, r);
        }
        if (amounts.isEmpty()) throw new IllegalArgumentException("no amounts (add easy:, medium:, hard: or community:)");

        String requires = s.getString("requires");
        if (requires != null && requires.isBlank()) requires = null;
        if (requires != null && !validMilestone.test(requires)) throw new IllegalArgumentException("unknown milestone '" + requires + "'");

        String icon = clean(s.getString("icon", "paper"));
        if (!validItem.test(icon)) icon = objective == QuestTemplate.Objective.DELIVER && !targets.isEmpty() ? targets.getFirst() : "paper";

        String name = s.getString("name", id.replace('_', ' '));
        return new QuestTemplate(id, s.getString("category", id), name, icon, objective, targets,
                Math.max(1, s.getLong("scale", 1)), amounts, requires, s.getInt("weight", 1), variants);
    }

    /** True if the quest or any of its variants has this key (variants may carry the targets alone). */
    private static boolean has(ConfigurationSection s, String key) {
        if (s.contains(key)) return true;
        ConfigurationSection vs = s.getConfigurationSection("variants");
        if (vs == null) return false;
        for (String v : vs.getKeys(false)) if (vs.contains(v + "." + key)) return true;
        return false;
    }

    private static List<String> list(ConfigurationSection s, String key) {
        List<String> out = new ArrayList<>();
        if (s.isList(key)) {
            for (String v : s.getStringList(key)) out.add(clean(v));
        } else if (s.getString(key) != null) {
            out.add(clean(s.getString(key)));
        }
        return out;
    }

    private static String clean(String v) {
        return v == null ? null : v.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
    }
}
