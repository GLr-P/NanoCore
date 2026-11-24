package io.github.glrp.nanocore.shop;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyLimitTest {

    private static final ZoneId ZONE = ZoneId.of("America/Vancouver");

    private static ZonedDateTime at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 12, day, hour, minute, 0, 0, ZONE);
    }

    @Test
    void midnightResetUsesTheCalendarDay() {
        assertEquals(LocalDate.of(2026, 12, 5), DailyLimit.day(at(5, 0, 0), LocalTime.MIDNIGHT));
        assertEquals(LocalDate.of(2026, 12, 5), DailyLimit.day(at(5, 23, 59), LocalTime.MIDNIGHT));
    }

    @Test
    void beforeALaterResetItIsStillYesterday() {
        LocalTime six = LocalTime.of(6, 0);
        assertEquals(LocalDate.of(2026, 12, 4), DailyLimit.day(at(5, 5, 59), six));
        assertEquals(LocalDate.of(2026, 12, 5), DailyLimit.day(at(5, 6, 0), six));
    }

    @Test
    void nextResetIsLaterTodayOrTomorrow() {
        LocalTime six = LocalTime.of(6, 0);
        assertEquals(at(5, 6, 0), DailyLimit.nextReset(at(5, 5, 0), six));
        assertEquals(at(6, 6, 0), DailyLimit.nextReset(at(5, 6, 0), six));
        assertEquals(at(6, 0, 0), DailyLimit.nextReset(at(5, 18, 30), LocalTime.MIDNIGHT));
    }

    @Test
    void formatsWaitTimes() {
        assertEquals("5h 30m", DailyLimit.format(Duration.ofMinutes(330)));
        assertEquals("2h", DailyLimit.format(Duration.ofHours(2)));
        assertEquals("45m", DailyLimit.format(Duration.ofMinutes(45)));
        assertEquals("1m", DailyLimit.format(Duration.ofSeconds(3)));
        assertEquals("1m", DailyLimit.format(Duration.ZERO));
    }
}
