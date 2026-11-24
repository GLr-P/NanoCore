package io.github.glrp.nanocore.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Works out what the admin shop pays for a set of item stacks. Pure logic, no server types.
 * Stacks are handled in slot order; a stack that would go over the daily limit sells only the items that still fit.
 */
public final class SellQuote {

    /** Why some (or all) of a stack isn't bought. */
    public enum Reason { NONE, NOT_BOUGHT, LOCKED, DAMAGED, LIMIT }

    /** One stack offered for sale. {@code intact} is false for damaged tools and armour. */
    public record Offer(int slot, String item, int amount, boolean intact) {
    }

    /** What happens to one stack: {@code sold} items are bought for {@code coins}; the rest go back for {@code reason}. */
    public record Line(Offer offer, int sold, long coins, Reason reason) {
        public int kept() {
            return offer.amount() - sold;
        }
    }

    public record Result(List<Line> lines, long total, int itemsSold) {
        public boolean empty() {
            return total <= 0;
        }
    }

    private SellQuote() {
    }

    public static Result quote(List<Offer> offers, Map<String, SellPrice> prices, Predicate<String> milestoneUnlocked, long remaining) {
        List<Line> lines = new ArrayList<>(offers.size());
        long left = Math.max(0, remaining);
        long total = 0;
        int count = 0;
        for (Offer o : offers) {
            if (o.amount() <= 0) continue;
            SellPrice p = prices.get(o.item());
            if (p == null || p.price() <= 0) {
                lines.add(new Line(o, 0, 0, Reason.NOT_BOUGHT));
                continue;
            }
            if (p.requires() != null && !milestoneUnlocked.test(p.requires())) {
                lines.add(new Line(o, 0, 0, Reason.LOCKED));
                continue;
            }
            if (!o.intact()) {
                lines.add(new Line(o, 0, 0, Reason.DAMAGED));
                continue;
            }
            int sold = (int) Math.min(o.amount(), left / p.price());
            long coins = sold * p.price();
            left -= coins;
            total += coins;
            count += sold;
            lines.add(new Line(o, sold, coins, sold < o.amount() ? Reason.LIMIT : Reason.NONE));
        }
        return new Result(List.copyOf(lines), total, count);
    }
}
