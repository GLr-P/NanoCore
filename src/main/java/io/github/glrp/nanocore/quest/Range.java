package io.github.glrp.nanocore.quest;

/** A whole-number range like "400-600", or a single value ("500"). */
public record Range(long min, long max) {

    public Range {
        if (min < 0 || max < min) throw new IllegalArgumentException("bad range " + min + "-" + max);
    }

    /** Parses "400-600", "400 - 600" or "500". Throws IllegalArgumentException on anything else. */
    public static Range parse(String text) {
        if (text == null) throw new IllegalArgumentException("missing range");
        String s = text.replace(",", "").replace("_", "").trim();
        int dash = s.indexOf('-', 1);
        try {
            if (dash < 0) {
                long v = Long.parseLong(s);
                return new Range(v, v);
            }
            return new Range(Long.parseLong(s.substring(0, dash).trim()), Long.parseLong(s.substring(dash + 1).trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' isn't a number or a range like 400-600");
        }
    }

    public boolean fixed() {
        return min == max;
    }

    public boolean contains(long v) {
        return v >= min && v <= max;
    }
}
