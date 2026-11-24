package io.github.glrp.nanocore.quest;

import io.github.glrp.nanocore.shop.DailyLimit;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/** Board periods: the daily board from the refresh time, the community week from a weekday at that time. Pure logic. */
public final class QuestClock {

    private QuestClock() {
    }

    /** The day whose board is up at {@code now}. Before the refresh time it's still yesterday's board. */
    public static LocalDate day(ZonedDateTime now, LocalTime refresh) {
        return DailyLimit.day(now, refresh);
    }

    public static ZonedDateTime nextDay(ZonedDateTime now, LocalTime refresh) {
        return DailyLimit.nextReset(now, refresh);
    }

    /** The start date of the community week that {@code now} falls in. */
    public static LocalDate weekStart(ZonedDateTime now, DayOfWeek startDay, LocalTime refresh) {
        return day(now, refresh).with(TemporalAdjusters.previousOrSame(startDay));
    }

    public static ZonedDateTime nextWeek(ZonedDateTime now, DayOfWeek startDay, LocalTime refresh) {
        return weekStart(now, startDay, refresh).plusWeeks(1).atTime(refresh).atZone(now.getZone());
    }
}
