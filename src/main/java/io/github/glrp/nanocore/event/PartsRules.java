package io.github.glrp.nanocore.event;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Pure rules behind the gameplay parts, kept apart so they're unit-tested. */
public final class PartsRules {

    private PartsRules() {
    }

    /** Mobs in a wave for this many players: the count, times half the players (rounded up) when scaling. */
    public static int scaled(int count, int players, boolean scale) {
        if (!scale || players <= 2) return count;
        return count * (int) Math.ceil(players / 2.0);
    }

    /** Corner (x, z) of plot number {@code i} (0-based) in a grid starting at (x0, z0) that fits {@code width} blocks across. */
    public static int[] plotCorner(int i, int x0, int z0, int width, int size, int gap) {
        int step = size + gap;
        int perRow = Math.max(1, (width + gap) / step);
        return new int[]{x0 + (i % perRow) * step, z0 + (i / perRow) * step};
    }

    /** How many plots fit in a box of this width and depth. */
    public static int plotCapacity(int width, int depth, int size, int gap) {
        int step = size + gap;
        return Math.max(0, (width + gap) / step) * Math.max(0, (depth + gap) / step);
    }

    /** Race time like 1:23.4 (or 12.3 under a minute). */
    public static String raceTime(long ms) {
        long tenths = Math.max(0, ms) / 100;
        long m = tenths / 600, s = (tenths / 10) % 60, t = tenths % 10;
        return m > 0 ? String.format(Locale.ROOT, "%d:%02d.%d", m, s, t) : String.format(Locale.ROOT, "%d.%d", s, t);
    }

    /** Distance hint for treasure: "hot" under 15 blocks, otherwise warmer/colder than last time (or "same"). */
    public static String hint(double before, double now) {
        if (now < 15) return "hot";
        if (before < 0 || Math.abs(now - before) < 1.5) return "same";
        return now < before ? "warmer" : "colder";
    }

    /** King of the hill: who scores this second? Only when exactly one player (or one team) holds the zone. */
    public static boolean holderScores(Collection<Integer> teamsInZone) {
        if (teamsInZone.isEmpty()) return false;
        if (teamsInZone.size() == 1) return true;
        return !teamsInZone.contains(null) && teamsInZone.stream().distinct().count() == 1;
    }

    /** {@code count} different items picked at random from the pool. */
    public static List<String> pickList(List<String> pool, int count, Random rng) {
        List<String> copy = new ArrayList<>(pool);
        java.util.Collections.shuffle(copy, rng);
        return List.copyOf(copy.subList(0, Math.min(count, copy.size())));
    }

    /** Score for the n-th player (1-based) to hand in a full collection list: earlier is higher. */
    public static long finishScore(int order) {
        return Math.max(1, 1000 - order);
    }

    /** True when every need has been delivered. */
    public static boolean needsMet(java.util.Map<String, Long> need, java.util.Map<String, Long> delivered) {
        if (need.isEmpty()) return false;
        for (var e : need.entrySet()) if (delivered.getOrDefault(e.getKey(), 0L) < e.getValue()) return false;
        return true;
    }
}
