package io.github.glrp.nanocore.quest;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** The quests section of config.yml. */
public record QuestSettings(
        Map<Tier, Integer> counts,
        Map<Tier, Range> rewards,
        int maxActive,
        long rerollCost,
        LocalTime refreshTime,
        ZoneId zone,
        long communityPot,
        long communityMinShare,
        DayOfWeek communityDay,
        double labelHeight,
        double boardReach) {

    public static QuestSettings from(ConfigurationSection s, Consumer<String> warn) {
        if (s == null) s = new MemoryConfiguration();
        Map<Tier, Integer> counts = new EnumMap<>(Tier.class);
        Map<Tier, Range> rewards = new EnumMap<>(Tier.class);
        Map<Tier, Integer> defCounts = Map.of(Tier.EASY, 5, Tier.MEDIUM, 4, Tier.HARD, 2);
        Map<Tier, String> defRewards = Map.of(Tier.EASY, "40-80", Tier.MEDIUM, "120-200", Tier.HARD, "300-500");
        for (Tier t : QuestGenerator.DAILY) {
            counts.put(t, Math.max(0, Math.min(7, s.getInt("board." + t.id(), defCounts.get(t)))));
            try {
                rewards.put(t, Range.parse(s.getString("rewards." + t.id(), defRewards.get(t))));
            } catch (IllegalArgumentException e) {
                warn.accept("quests.rewards." + t.id() + ": " + e.getMessage() + "; using " + defRewards.get(t));
                rewards.put(t, Range.parse(defRewards.get(t)));
            }
        }
        LocalTime refresh;
        try {
            refresh = LocalTime.parse(s.getString("refresh-time", "06:00"));
        } catch (RuntimeException e) {
            warn.accept("quests.refresh-time must look like 06:00; using 06:00");
            refresh = LocalTime.of(6, 0);
        }
        ZoneId zone;
        String tz = s.getString("timezone", "");
        try {
            zone = tz == null || tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz);
        } catch (RuntimeException e) {
            warn.accept("quests.timezone '" + tz + "' isn't a known time zone; using the server's");
            zone = ZoneId.systemDefault();
        }
        DayOfWeek day;
        try {
            day = DayOfWeek.valueOf(s.getString("community.day", "monday").trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            warn.accept("quests.community.day must be a weekday like monday; using monday");
            day = DayOfWeek.MONDAY;
        }
        return new QuestSettings(counts, rewards,
                Math.max(1, Math.min(9, s.getInt("max-active", 3))),
                Math.max(0, s.getLong("reroll-cost", 25)),
                refresh, zone,
                Math.max(0, s.getLong("community.pot", 1500)),
                Math.max(0, s.getLong("community.min-share", 25)),
                day,
                s.getDouble("label-height", 1.8),
                Math.max(1, s.getDouble("board-reach", 6)));
    }
}
