package io.github.glrp.nanocore.quest;

import java.util.List;
import java.util.Map;

/**
 * One kind of quest from quests.yml, for example "mine natural stone" or "chop logs of one kind".
 * Pure data (no Bukkit types) so generation can be unit-tested.
 *
 * @param targets  stat keys that count (COUNT), the item id to hand in (DELIVER), or biome/structure ids (BIOME/STRUCTURE)
 * @param scale    raw stat units per shown unit (100 for distances: stats count centimetres, quests show blocks)
 * @param amounts  how many are asked for, per tier; a tier missing here never gets this quest
 * @param requires milestone id that must be unlocked before the quest appears, or null
 * @param variants optional kinds (oak, birch...): each replaces name placeholder, icon and targets; one is picked per quest
 */
public record QuestTemplate(
        String id,
        String category,
        String name,
        String icon,
        Objective objective,
        List<String> targets,
        long scale,
        Map<Tier, Range> amounts,
        String requires,
        int weight,
        List<Variant> variants) {

    public enum Objective { COUNT, DELIVER, BIOME, STRUCTURE }

    public record Variant(String id, String label, String icon, List<String> targets) {
    }

    /** What a quest built from this template (and variant) asks for. */
    public record Resolved(String name, String icon, Objective objective, List<String> targets, long scale, String category) {
    }

    public QuestTemplate {
        targets = List.copyOf(targets);
        amounts = Map.copyOf(amounts);
        variants = variants == null ? List.of() : List.copyOf(variants);
        if (scale <= 0) scale = 1;
        if (weight <= 0) weight = 1;
    }

    public Variant variant(String variantId) {
        if (variantId == null) return null;
        for (Variant v : variants) if (v.id().equals(variantId)) return v;
        return null;
    }

    public Resolved resolve(String variantId) {
        Variant v = variant(variantId);
        if (v == null) return new Resolved(name.replace("<variant>", ""), icon, objective, targets, scale, category);
        return new Resolved(name.replace("<variant>", v.label()), v.icon() == null ? icon : v.icon(), objective,
                v.targets().isEmpty() ? targets : v.targets(), scale, category);
    }
}
