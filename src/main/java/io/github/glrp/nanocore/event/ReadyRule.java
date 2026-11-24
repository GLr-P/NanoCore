package io.github.glrp.nanocore.event;

import java.util.Locale;

/** "Everyone says they're done" checks: "all", "online" (everyone still online) or a share like "75%". Pure logic. */
public final class ReadyRule {

    private ReadyRule() {
    }

    public static boolean valid(String rule) {
        String r = rule.trim().toLowerCase(Locale.ROOT);
        if (r.equals("all") || r.equals("online")) return true;
        return percent(r) > 0;
    }

    private static int percent(String r) {
        if (!r.endsWith("%")) return -1;
        try {
            int p = Integer.parseInt(r.substring(0, r.length() - 1).trim());
            return p >= 1 && p <= 100 ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * @param ready         participants who typed /event done (online or not)
     * @param readyOnline   of those, how many are online
     * @param participants  everyone in the event
     * @param online        participants online right now
     */
    public static boolean met(String rule, int ready, int readyOnline, int participants, int online) {
        if (participants <= 0) return false;
        String r = rule.trim().toLowerCase(Locale.ROOT);
        if (r.equals("online")) return online > 0 && readyOnline >= online;
        if (r.equals("all")) return ready >= participants;
        int p = percent(r);
        return p > 0 && ready * 100L >= (long) p * participants;
    }

    /** How many still need to be ready, for the "3 of 5 are done" line. */
    public static int needed(String rule, int participants, int online) {
        String r = rule.trim().toLowerCase(Locale.ROOT);
        if (r.equals("online")) return online;
        if (r.equals("all")) return participants;
        int p = Math.max(1, percent(r));
        return (int) Math.ceil(participants * p / 100.0);
    }
}
