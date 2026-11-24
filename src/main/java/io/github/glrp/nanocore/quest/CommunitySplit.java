package io.github.glrp.nanocore.quest;

import java.util.LinkedHashMap;
import java.util.Map;

/** How the community quest's pot is shared. Pure logic. */
public final class CommunitySplit {

    private CommunitySplit() {
    }

    /** The pot paid out: all of it when the goal was reached, otherwise the share of the goal the server got done. */
    public static long potFor(long progress, long goal, long pot) {
        if (goal <= 0 || progress >= goal) return pot;
        return Math.max(0, pot * Math.max(0, progress) / goal);
    }

    /** Shares by contribution (rounded down), at least {@code minShare} for everyone who helped. */
    public static <K> Map<K, Long> split(Map<K, Long> contributions, long pot, long minShare) {
        long total = 0;
        for (long c : contributions.values()) if (c > 0) total += c;
        Map<K, Long> out = new LinkedHashMap<>();
        if (total == 0) return out;
        for (var e : contributions.entrySet()) {
            if (e.getValue() <= 0) continue;
            long share = (long) Math.floor((double) pot * e.getValue() / total);
            out.put(e.getKey(), Math.max(minShare, share));
        }
        return out;
    }
}
