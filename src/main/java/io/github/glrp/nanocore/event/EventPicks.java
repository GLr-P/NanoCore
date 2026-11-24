package io.github.glrp.nanocore.event;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** The automatic schedule's pick, and team splits. Pure logic. */
public final class EventPicks {

    private EventPicks() {
    }

    /**
     * A weighted random event for today: in the automatic pool, allowed on today's weekday, and not run in the
     * last {@code noRepeatDays} days. Null if none fits.
     */
    public static EventDef pick(Collection<EventDef> defs, Map<String, LocalDate> lastRun, LocalDate today, int noRepeatDays, Random rng) {
        List<EventDef> pool = new ArrayList<>();
        for (EventDef d : defs) {
            if (!d.auto()) continue;
            if (!d.weekdays().isEmpty() && !d.weekdays().contains(today.getDayOfWeek())) continue;
            LocalDate last = lastRun.get(d.id());
            if (last != null && ChronoUnit.DAYS.between(last, today) < noRepeatDays) continue;
            pool.add(d);
        }
        if (pool.isEmpty()) return null;
        int total = 0;
        for (EventDef d : pool) total += d.weight();
        int roll = rng.nextInt(total);
        for (EventDef d : pool) {
            roll -= d.weight();
            if (roll < 0) return d;
        }
        return pool.getLast();
    }

    /** Splits players into {@code teams} teams of equal size (numbered from 1), in the order given. */
    public static <K> Map<K, Integer> teams(List<K> players, int teams) {
        Map<K, Integer> out = new HashMap<>();
        for (int i = 0; i < players.size(); i++) out.put(players.get(i), i % teams + 1);
        return out;
    }

    /** The smallest team (lowest number on a tie), for someone joining late. */
    public static <K> int smallestTeam(Map<K, Integer> current, int teams) {
        int[] size = new int[teams + 1];
        for (int t : current.values()) if (t >= 1 && t <= teams) size[t]++;
        int best = 1;
        for (int t = 2; t <= teams; t++) if (size[t] < size[best]) best = t;
        return best;
    }
}
