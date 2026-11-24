package io.github.glrp.nanocore.shop;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/** Which "shop day" it is, given the reset time, and how long until the next one. Pure logic. */
public final class DailyLimit {

    private DailyLimit() {
    }

    /** The day a sale at {@code now} counts toward. Before the reset time it still counts toward yesterday. */
    public static LocalDate day(ZonedDateTime now, LocalTime reset) {
        return now.toLocalTime().isBefore(reset) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
    }

    public static ZonedDateTime nextReset(ZonedDateTime now, LocalTime reset) {
        ZonedDateTime candidate = now.toLocalDate().atTime(reset).atZone(now.getZone());
        return candidate.isAfter(now) ? candidate : candidate.plusDays(1);
    }

    /** "3h 12m", "45m"; never "0m". */
    public static String format(Duration d) {
        long minutes = Math.max(1, (d.toSeconds() + 59) / 60);
        long h = minutes / 60;
        long m = minutes % 60;
        if (h == 0) return m + "m";
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }
}
