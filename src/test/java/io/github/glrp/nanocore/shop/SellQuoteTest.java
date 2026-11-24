package io.github.glrp.nanocore.shop;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SellQuoteTest {

    private static final Map<String, SellPrice> PRICES = Map.of(
            "diamond", new SellPrice("diamond", 6, null),
            "disc_fragment_5", new SellPrice("disc_fragment_5", 4, null),
            "elytra", new SellPrice("elytra", 150, "elytra"));

    private static SellQuote.Offer offer(int slot, String item, int amount) {
        return new SellQuote.Offer(slot, item, amount, true);
    }

    @Test
    void paysPerItem() {
        var r = SellQuote.quote(List.of(offer(0, "diamond", 10)), PRICES, m -> false, 150);
        assertEquals(60, r.total());
        assertEquals(10, r.itemsSold());
        assertEquals(SellQuote.Reason.NONE, r.lines().getFirst().reason());
    }

    @Test
    void stopsAtTheDailyLimitWithWholeItems() {
        var r = SellQuote.quote(List.of(offer(0, "diamond", 30)), PRICES, m -> false, 150);
        assertEquals(150, r.total());
        assertEquals(25, r.lines().getFirst().sold());
        assertEquals(5, r.lines().getFirst().kept());
        assertEquals(SellQuote.Reason.LIMIT, r.lines().getFirst().reason());
    }

    @Test
    void neverPaysMoreThanWhatIsLeft() {
        // 10 left: one diamond (6) fits, then a fragment (4) still fits, a second diamond doesn't.
        var r = SellQuote.quote(List.of(offer(0, "diamond", 3), offer(1, "disc_fragment_5", 2)), PRICES, m -> false, 10);
        assertEquals(10, r.total());
        assertEquals(1, r.lines().get(0).sold());
        assertEquals(1, r.lines().get(1).sold());
    }

    @Test
    void nothingLeftSellsNothing() {
        var r = SellQuote.quote(List.of(offer(0, "diamond", 3)), PRICES, m -> false, 0);
        assertTrue(r.empty());
        assertEquals(SellQuote.Reason.LIMIT, r.lines().getFirst().reason());
    }

    @Test
    void unlistedItemsAreKept() {
        var r = SellQuote.quote(List.of(offer(0, "dirt", 64), offer(1, "diamond", 1)), PRICES, m -> false, 150);
        assertEquals(6, r.total());
        assertEquals(SellQuote.Reason.NOT_BOUGHT, r.lines().getFirst().reason());
        assertEquals(64, r.lines().getFirst().kept());
    }

    @Test
    void milestoneItemsNeedTheMilestone() {
        var locked = SellQuote.quote(List.of(offer(0, "elytra", 1)), PRICES, m -> false, 150);
        assertTrue(locked.empty());
        assertEquals(SellQuote.Reason.LOCKED, locked.lines().getFirst().reason());
        var unlocked = SellQuote.quote(List.of(offer(0, "elytra", 1)), PRICES, Set.of("elytra")::contains, 150);
        assertEquals(150, unlocked.total());
    }

    @Test
    void damagedItemsAreKept() {
        var r = SellQuote.quote(List.of(new SellQuote.Offer(0, "elytra", 1, false)), PRICES, m -> true, 150);
        assertTrue(r.empty());
        assertEquals(SellQuote.Reason.DAMAGED, r.lines().getFirst().reason());
    }

    @Test
    void negativeRemainingCountsAsZero() {
        assertTrue(SellQuote.quote(List.of(offer(0, "diamond", 1)), PRICES, m -> false, -20).empty());
    }
}
