package io.github.glrp.nanocore.event;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Votes: each voter picks a top 3 (3, 2 and 1 points). Votes for yourself and repeats on one ballot don't count.
 * Ties on points go to whoever got more first-place votes. Pure logic.
 */
public final class VoteTally {

    public static final long[] WEIGHTS = {3, 2, 1};
    /** Scores combine points and first-place votes so Ranking can sort them; divide by this to show points. */
    public static final long SCALE = 10_000;

    private VoteTally() {
    }

    /** Candidate to (points * SCALE + first-place votes). Only candidates in {@code allowed} count. */
    public static <K> Map<K, Long> tally(Map<K, List<K>> ballots, Set<K> allowed) {
        Map<K, Long> points = new HashMap<>();
        Map<K, Long> firsts = new HashMap<>();
        for (var b : ballots.entrySet()) {
            Set<K> seen = new HashSet<>();
            int slot = 0;
            for (K pick : b.getValue()) {
                if (slot >= WEIGHTS.length) break;
                if (pick == null || pick.equals(b.getKey()) || !allowed.contains(pick) || !seen.add(pick)) continue;
                points.merge(pick, WEIGHTS[slot], Long::sum);
                if (slot == 0) firsts.merge(pick, 1L, Long::sum);
                slot++;
            }
        }
        Map<K, Long> out = new HashMap<>();
        for (var e : points.entrySet()) out.put(e.getKey(), e.getValue() * SCALE + Math.min(SCALE - 1, firsts.getOrDefault(e.getKey(), 0L)));
        return out;
    }

    public static long shownPoints(long tallied) {
        return tallied / SCALE;
    }
}
