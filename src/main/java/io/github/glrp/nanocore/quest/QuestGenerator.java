package io.github.glrp.nanocore.quest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/** Builds boards from templates: amounts, rewards and variety. Pure logic. */
public final class QuestGenerator {

    /** Tiers filled on the daily board, in board order. */
    public static final List<Tier> DAILY = List.of(Tier.EASY, Tier.MEDIUM, Tier.HARD);

    private QuestGenerator() {
    }

    /** Round numbers that read well: 7, 45, 380, 1,250, 4,000. */
    static long step(long v) {
        if (v < 20) return 1;
        if (v < 100) return 5;
        if (v < 500) return 10;
        if (v < 2000) return 50;
        if (v < 10000) return 100;
        return 500;
    }

    /** A random amount inside the range, rounded to a readable number (and kept inside the range). */
    public static long pickAmount(Range r, Random rng) {
        if (r.fixed()) return r.min();
        long v = r.min() + (long) (rng.nextDouble() * (r.max() - r.min() + 1));
        long s = step(v);
        long rounded = Math.round((double) v / s) * s;
        if (rounded < r.min()) rounded += s;
        if (rounded > r.max()) rounded -= s;
        return Math.max(r.min(), Math.min(r.max(), rounded));
    }

    /** Coins for a quest: scales inside the tier's range with the amount asked, rounded to 5. */
    public static long reward(long amount, Range amounts, Range rewards) {
        double frac = amounts.fixed() ? 0.5 : (double) (amount - amounts.min()) / (amounts.max() - amounts.min());
        frac = Math.max(0, Math.min(1, frac));
        long raw = Math.round(rewards.min() + frac * (rewards.max() - rewards.min()));
        long rounded = Math.round(raw / 5.0) * 5;
        return Math.max(rewards.min(), Math.min(rewards.max(), rounded));
    }

    /** A template (and variant) chosen for a slot. */
    public record Choice(QuestTemplate template, String variant) {
    }

    /**
     * Picks one template for the tier. Prefers a category not on the board yet, then one not used in this tier,
     * then anything left. Never repeats a template. Weighted by {@code weight}. Null if nothing fits.
     */
    public static Choice pick(Collection<QuestTemplate> templates, Tier tier, Predicate<String> unlocked, Random rng,
                              Set<String> usedTemplates, Set<String> boardCategories, Set<String> tierCategories) {
        List<QuestTemplate> candidates = new ArrayList<>();
        for (QuestTemplate t : templates) {
            if (!t.amounts().containsKey(tier) || usedTemplates.contains(t.id())) continue;
            if (t.requires() != null && !unlocked.test(t.requires())) continue;
            if (tier == Tier.COMMUNITY && (t.objective() == QuestTemplate.Objective.BIOME || t.objective() == QuestTemplate.Objective.STRUCTURE)) continue;
            candidates.add(t);
        }
        for (int level = 0; level < 3; level++) {
            List<QuestTemplate> pool = new ArrayList<>();
            for (QuestTemplate t : candidates) {
                if (level == 0 && boardCategories.contains(t.category())) continue;
                if (level == 1 && tierCategories.contains(t.category())) continue;
                pool.add(t);
            }
            if (pool.isEmpty()) continue;
            QuestTemplate t = weighted(pool, rng);
            String variant = t.variants().isEmpty() ? null : t.variants().get(rng.nextInt(t.variants().size())).id();
            return new Choice(t, variant);
        }
        return null;
    }

    private static QuestTemplate weighted(List<QuestTemplate> pool, Random rng) {
        int total = 0;
        for (QuestTemplate t : pool) total += t.weight();
        int roll = rng.nextInt(total);
        for (QuestTemplate t : pool) {
            roll -= t.weight();
            if (roll < 0) return t;
        }
        return pool.getLast();
    }

    /** Builds a quest for a choice: random amount, reward from the tier's range (community: the pot). */
    public static Quest build(String id, Tier tier, Choice c, Range tierRewards, long communityPot, Random rng) {
        Range amounts = c.template().amounts().get(tier);
        long amount = pickAmount(amounts, rng);
        long reward = tier == Tier.COMMUNITY ? communityPot : reward(amount, amounts, tierRewards);
        return new Quest(id, tier, c.template().id(), c.variant(), amount, reward, null, null);
    }

    /** The daily board: counts per tier, ids like "2026-12-05/easy-1". Tiers with too few templates get fewer quests. */
    public static List<Quest> dailyBoard(Collection<QuestTemplate> templates, Map<Tier, Integer> counts, Map<Tier, Range> rewards,
                                         Predicate<String> unlocked, Random rng, String period) {
        List<Quest> board = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Set<String> boardCats = new HashSet<>();
        for (Tier tier : DAILY) {
            Set<String> tierCats = new HashSet<>();
            int n = counts.getOrDefault(tier, 0);
            Range r = rewards.get(tier);
            if (r == null) continue;
            for (int i = 0; i < n; i++) {
                Choice c = pick(templates, tier, unlocked, rng, used, boardCats, tierCats);
                if (c == null) break;
                used.add(c.template().id());
                boardCats.add(c.template().category());
                tierCats.add(c.template().category());
                board.add(build(period + "/" + tier.id() + "-" + (i + 1), tier, c, r, 0, rng));
            }
        }
        return board;
    }

    /** The week's community quest, or null if no template has a community amount. */
    public static Quest communityQuest(Collection<QuestTemplate> templates, Predicate<String> unlocked, Random rng, String period, long pot) {
        Choice c = pick(templates, Tier.COMMUNITY, unlocked, rng, Set.of(), Set.of(), Set.of());
        return c == null ? null : build(period + "/community", Tier.COMMUNITY, c, new Range(pot, pot), pot, rng);
    }

    /** A personal replacement for a quest of the given tier that isn't any of {@code exclude} (template ids). */
    public static Quest replacement(Collection<QuestTemplate> templates, Tier tier, Range tierRewards, Predicate<String> unlocked,
                                    Random rng, Set<String> exclude, String id) {
        Choice c = pick(templates, tier, unlocked, rng, exclude, Set.of(), Set.of());
        return c == null ? null : build(id, tier, c, tierRewards, 0, rng);
    }
}
