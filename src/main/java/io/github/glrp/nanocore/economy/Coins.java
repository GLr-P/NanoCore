package io.github.glrp.nanocore.economy;

import java.text.NumberFormat;
import java.util.Locale;

/** Formats coin amounts for players: "1 coin", "1,250 coins". Amounts are whole coins. */
public final class Coins {

    private Coins() {
    }

    public static String format(long amount, String singular, String plural) {
        String number = NumberFormat.getIntegerInstance(Locale.US).format(amount);
        return number + " " + (Math.abs(amount) == 1 ? singular : plural);
    }

    public static String format(long amount) {
        return format(amount, "coin", "coins");
    }
}
