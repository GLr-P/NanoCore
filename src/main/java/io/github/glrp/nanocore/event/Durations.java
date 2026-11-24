package io.github.glrp.nanocore.event;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "30m", "2h", "90s", "1h30m", "45" (minutes) to milliseconds, and back to "12m 30s". Pure logic. */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*([dhms]?)");

    private Durations() {
    }

    public static long parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("missing time");
        String s = text.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher m = PART.matcher(s);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) throw new IllegalArgumentException("'" + text + "' isn't a time like 30m, 2h or 1h30m");
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "d" -> n * 86_400_000L;
                case "h" -> n * 3_600_000L;
                case "s" -> n * 1_000L;
                default -> n * 60_000L;
            };
            end = m.end();
        }
        if (end != s.length() || end == 0) throw new IllegalArgumentException("'" + text + "' isn't a time like 30m, 2h or 1h30m");
        return total;
    }

    /** "1h 5m", "12m 30s", "45s"; never negative. */
    public static String format(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return m == 0 ? h + "h" : h + "h " + m + "m";
        if (m > 0) return sec == 0 || m >= 10 ? m + "m" : m + "m " + sec + "s";
        return sec + "s";
    }
}
