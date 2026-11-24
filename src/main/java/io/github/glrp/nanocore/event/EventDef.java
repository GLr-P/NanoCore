package io.github.glrp.nanocore.event;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One event from events.yml: how players join, its phases, what scores, the rules and the prizes.
 * Pure data, so the bundled events are unit-tested.
 */
public record EventDef(
        String id,
        String name,
        String icon,
        List<String> rules,
        boolean autoJoin,
        boolean lateJoin,
        int maxPlayers,
        int teams,
        String area,
        List<String> safeZones,
        boolean auto,
        int weight,
        int minPlayers,
        Set<DayOfWeek> weekdays,
        List<Phase> phases,
        Prizes prizes,
        String unlocks,
        Winner winner) {

    public enum Winner { SCORE, VOTES, LAST_STANDING, NONE }

    /**
     * @param points stat counter (or "prefix*") to points per unit counted
     * @param goal   a shared target for all participants' points together (0 = none)
     * @param lives  deaths allowed before a player is out (0 = unlimited)
     */
    public record Phase(String id, String name, End end, Map<String, Long> points, Rules rules, Kit kit,
                        boolean vote, int lives, long goal) {

        /** Points for one unit of this stat: an exact key first, then the longest "prefix*" match. Totals never count. */
        public long pointsFor(String stat) {
            if (points.isEmpty() || stat.endsWith(".total")) return 0;
            Long exact = points.get(stat);
            if (exact != null) return exact;
            long best = 0;
            int bestLen = -1;
            for (var e : points.entrySet()) {
                String k = e.getKey();
                if (k.endsWith("*") && stat.startsWith(k.substring(0, k.length() - 1)) && k.length() > bestLen) {
                    best = e.getValue();
                    bestLen = k.length();
                }
            }
            return best;
        }

        public boolean scores() {
            return !points.isEmpty();
        }
    }

    /**
     * When a phase ends (whichever comes first; an admin can always skip it).
     *
     * @param timerMs real time (0 = none)
     * @param mcTicks Minecraft time (0 = none; 24,000 per Minecraft day)
     * @param mcTime  end when the time of day reaches this tick (-1 = none; 0 = sunrise, 13000 = night)
     * @param ready   "all", "online" or a share like "75%": when that many participants typed /event done (null = no ready check)
     */
    public record End(long timerMs, long mcTicks, long mcTime, String ready, boolean goal, boolean lastStanding) {
        public boolean any() {
            return timerMs > 0 || mcTicks > 0 || mcTime >= 0 || ready != null || goal || lastStanding;
        }
    }

    /** Rule switches for a phase. A null value leaves the server's normal behaviour. */
    public record Rules(Boolean pvp, Boolean keepInventory, Integer dropChance, Boolean mobGriefing, Boolean building,
                        Boolean fallDamage, Boolean hunger, Boolean naturalRegen, Boolean enderPearls, Boolean elytra,
                        Long timeLock, String weather, String difficulty, Double questMultiplier, boolean inAreaOnly) {
        public static final Rules NONE = new Rules(null, null, null, null, null, null, null, null, null, null, null, null, null, null, false);

        public boolean empty() {
            return pvp == null && keepInventory == null && dropChance == null && mobGriefing == null && building == null && fallDamage == null
                    && hunger == null && naturalRegen == null && enderPearls == null && elytra == null && timeLock == null && weather == null
                    && difficulty == null && questMultiplier == null;
        }
    }

    /** Items (like "stone_sword" or "cooked_beef 16") and an optional game mode. Own inventories come back after the event. */
    public record Kit(List<String> items, String gamemode) {
    }

    /**
     * @param places        coins for 1st, 2nd, 3rd...
     * @param participation coins for everyone who took part (and wasn't placed)
     * @param goal          a total for all participants together; when reached everyone who took part gets goalBonus
     * @param activeActions counted actions needed to count as taking part (AFK check)
     */
    public record Prizes(List<Long> places, long participation, boolean trophy, String trophyItem, long goal, long goalBonus, long activeActions) {
    }

    public Phase phase(int i) {
        return i >= 0 && i < phases.size() ? phases.get(i) : null;
    }
}
