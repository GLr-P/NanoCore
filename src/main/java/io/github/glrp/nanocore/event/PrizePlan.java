package io.github.glrp.nanocore.event;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Who gets what at the end of an event. Pure logic. */
public final class PrizePlan {

    private PrizePlan() {
    }

    /** @param place 0 when not placed */
    public record Payout(long coins, int place, boolean trophy) {
    }

    /**
     * @param places       placed players (ties share a place and each gets that place's prize)
     * @param participants everyone in the event
     * @param active       who counts as having taken part (AFK check passed)
     * @param goalReached  the shared goal was reached: every active participant gets the bonus
     */
    public static <K> Map<K, Payout> plan(List<Ranking.Place<K>> places, Collection<K> participants, Collection<K> active,
                                          EventDef.Prizes prizes, boolean goalReached) {
        Map<K, Payout> out = new LinkedHashMap<>();
        for (Ranking.Place<K> p : places) {
            if (p.place() > prizes.places().size()) continue;
            out.put(p.who(), new Payout(prizes.places().get(p.place() - 1), p.place(), prizes.trophy()));
        }
        for (K k : participants) {
            if (!active.contains(k)) continue;
            Payout got = out.get(k);
            long bonus = goalReached ? prizes.goalBonus() : 0;
            if (got == null) {
                long coins = prizes.participation() + bonus;
                if (coins > 0) out.put(k, new Payout(coins, 0, false));
            } else if (bonus > 0) {
                out.put(k, new Payout(got.coins() + bonus, got.place(), got.trophy()));
            }
        }
        return out;
    }
}
