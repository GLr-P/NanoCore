package io.github.glrp.nanocore.pshop;

/**
 * Pure rules for one trade at a player shop: how many items change hands, what it costs and the sales tax.
 * The shop's owner sells to customers ("buy" from the customer's side) and/or buys from them ("sell").
 */
public final class ShopTrade {

    /** What customers can do at a shop. */
    public enum Mode {
        /** Customers buy from the owner. */
        SELL,
        /** Customers sell to the owner. */
        BUY,
        /** Both. */
        BOTH;

        public boolean ownerSells() {
            return this != BUY;
        }

        public boolean ownerBuys() {
            return this != SELL;
        }

        public static Mode parse(String s) {
            try {
                return Mode.valueOf(s.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return SELL;
            }
        }
    }

    public enum Status { OK, NOT_OFFERED, OUT_OF_STOCK, CANT_AFFORD, NO_ROOM, NOTHING_TO_SELL, SHOP_FULL, OWNER_CANT_AFFORD }

    /**
     * @param amount items that change hands
     * @param total  coins the paying side pays
     * @param tax    coins that disappear
     * @param paid   coins the receiving side gets (total - tax)
     */
    public record Result(Status status, int amount, long total, long tax, long paid) {
        static Result fail(Status s) {
            return new Result(s, 0, 0, 0, 0);
        }

        public boolean ok() {
            return status == Status.OK;
        }
    }

    private ShopTrade() {
    }

    /** Tax on a sale, rounded down, so small sales are tax free. */
    public static long tax(long total, double percent) {
        if (percent <= 0 || total <= 0) return 0;
        return Math.min(total, (long) Math.floor(total * Math.min(100, percent) / 100.0));
    }

    /**
     * A customer buys up to {@code wanted} items.
     *
     * @param stock  matching items in the shop's chest
     * @param room   how many fit in the customer's inventory
     * @param wallet the customer's balance
     */
    public static Result customerBuys(Mode mode, long price, int wanted, int stock, int room, long wallet, double taxPercent) {
        if (!mode.ownerSells() || price <= 0 || wanted <= 0) return Result.fail(Status.NOT_OFFERED);
        if (stock <= 0) return Result.fail(Status.OUT_OF_STOCK);
        if (room <= 0) return Result.fail(Status.NO_ROOM);
        long affordable = wallet / price;
        if (affordable <= 0) return Result.fail(Status.CANT_AFFORD);
        int n = (int) Math.min(Math.min(wanted, stock), Math.min(room, affordable));
        long total = price * n;
        long tax = tax(total, taxPercent);
        return new Result(Status.OK, n, total, tax, total - tax);
    }

    /**
     * A customer sells up to {@code wanted} items to the owner.
     *
     * @param has         matching items the customer carries
     * @param space       how many more fit in the shop's chest
     * @param ownerWallet the owner's balance
     */
    public static Result customerSells(Mode mode, long price, int wanted, int has, int space, long ownerWallet, double taxPercent) {
        if (!mode.ownerBuys() || price <= 0 || wanted <= 0) return Result.fail(Status.NOT_OFFERED);
        if (has <= 0) return Result.fail(Status.NOTHING_TO_SELL);
        if (space <= 0) return Result.fail(Status.SHOP_FULL);
        long affordable = ownerWallet / price;
        if (affordable <= 0) return Result.fail(Status.OWNER_CANT_AFFORD);
        int n = (int) Math.min(Math.min(wanted, has), Math.min(space, affordable));
        long total = price * n;
        long tax = tax(total, taxPercent);
        return new Result(Status.OK, n, total, tax, total - tax);
    }

    /** The click amounts for an item: 1, 8 and a full stack (1, 4, 16 for items that stack to 16; just 1 for tools). */
    public static int[] clickAmounts(int maxStack) {
        if (maxStack <= 1) return new int[]{1};
        if (maxStack <= 16) return new int[]{1, Math.max(2, maxStack / 4), maxStack};
        return new int[]{1, 8, maxStack};
    }

    /** Keeps a price inside 0..max. */
    public static long clampPrice(long price, long max) {
        return Math.max(0, Math.min(max, price));
    }
}
