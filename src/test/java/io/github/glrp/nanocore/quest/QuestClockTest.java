package io.github.glrp.nanocore.quest;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuestClockTest {

    private static final ZoneId ZONE = ZoneId.of("America/Vancouver");
    private static final LocalTime SIX = LocalTime.of(6, 0);

    private static ZonedDateTime at(int day, int hour) {
        return ZonedDateTime.of(2026, 12, day, hour, 0, 0, 0, ZONE);
    }

    @Test
    void boardChangesAtTheRefreshTime() {
        assertEquals(LocalDate.of(2026, 12, 4), QuestClock.day(at(5, 5), SIX));
        assertEquals(LocalDate.of(2026, 12, 5), QuestClock.day(at(5, 6), SIX));
        assertEquals(at(5, 6), QuestClock.nextDay(at(5, 5), SIX));
        assertEquals(at(6, 6), QuestClock.nextDay(at(5, 6), SIX));
    }

    @Test
    void communityWeekStartsOnTheChosenDay() {
        // 2026-12-07 is a Monday.
        assertEquals(LocalDate.of(2026, 11, 30), QuestClock.weekStart(at(7, 5), DayOfWeek.MONDAY, SIX));
        assertEquals(LocalDate.of(2026, 12, 7), QuestClock.weekStart(at(7, 6), DayOfWeek.MONDAY, SIX));
        assertEquals(LocalDate.of(2026, 12, 7), QuestClock.weekStart(at(13, 23), DayOfWeek.MONDAY, SIX));
        assertEquals(at(14, 6), QuestClock.nextWeek(at(10, 12), DayOfWeek.MONDAY, SIX));
    }
}
