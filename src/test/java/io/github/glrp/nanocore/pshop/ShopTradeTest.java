package io.github.glrp.nanocore.pshop;

import io.github.glrp.nanocore.pshop.ShopTrade.Mode;
import io.github.glrp.nanocore.pshop.ShopTrade.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopTradeTest {

    @Test
    void taxRoundsDownAndCaps() {
        assertEquals(0, ShopTrade.tax(11, 2));
        assertEquals(1, ShopTrade.tax(88, 2));
        assertEquals(2, ShopTrade.tax(100, 2));
        assertEquals(0, ShopTrade.tax(100, 0));
        assertEquals(100, ShopTrade.tax(100, 250));
    }

    @Test
    void buyLimitedByStockRoomAndWallet() {
        var r = ShopTrade.customerBuys(Mode.SELL, 11, 8, 10, 100, 100, 2);
        assertEquals(Status.OK, r.status());
        assertEquals(8, r.amount());
        assertEquals(88, r.total());
        assertEquals(1, r.tax());
        assertEquals(87, r.paid());

        assertEquals(3, ShopTrade.customerBuys(Mode.BOTH, 11, 64, 3, 100, 1000, 0).amount());
        assertEquals(5, ShopTrade.customerBuys(Mode.SELL, 11, 64, 30, 5, 1000, 0).amount());
        assertEquals(9, ShopTrade.customerBuys(Mode.SELL, 11, 64, 30, 64, 100, 0).amount());
    }

    @Test
    void buyFailures() {
        assertEquals(Status.NOT_OFFERED, ShopTrade.customerBuys(Mode.BUY, 11, 1, 10, 10, 100, 0).status());
        assertEquals(Status.NOT_OFFERED, ShopTrade.customerBuys(Mode.SELL, 0, 1, 10, 10, 100, 0).status());
        assertEquals(Status.OUT_OF_STOCK, ShopTrade.customerBuys(Mode.SELL, 5, 1, 0, 10, 100, 0).status());
        assertEquals(Status.NO_ROOM, ShopTrade.customerBuys(Mode.SELL, 5, 1, 10, 0, 100, 0).status());
        assertEquals(Status.CANT_AFFORD, ShopTrade.customerBuys(Mode.SELL, 5, 1, 10, 10, 4, 0).status());
    }

    @Test
    void sellLimitedByItemsSpaceAndOwnerMoney() {
        var r = ShopTrade.customerSells(Mode.BOTH, 1, 1, 7, 100, 50, 2);
        assertEquals(Status.OK, r.status());
        assertEquals(1, r.amount());
        assertEquals(1, r.paid());
        assertEquals(4, ShopTrade.customerSells(Mode.BUY, 3, 64, 4, 100, 1000, 0).amount());
        assertEquals(2, ShopTrade.customerSells(Mode.BUY, 3, 64, 40, 2, 1000, 0).amount());
        assertEquals(10, ShopTrade.customerSells(Mode.BUY, 3, 64, 40, 64, 31, 0).amount());
    }

    @Test
    void sellFailures() {
        assertEquals(Status.NOT_OFFERED, ShopTrade.customerSells(Mode.SELL, 3, 1, 4, 10, 100, 0).status());
        assertEquals(Status.NOTHING_TO_SELL, ShopTrade.customerSells(Mode.BUY, 3, 1, 0, 10, 100, 0).status());
        assertEquals(Status.SHOP_FULL, ShopTrade.customerSells(Mode.BUY, 3, 1, 4, 0, 100, 0).status());
        assertEquals(Status.OWNER_CANT_AFFORD, ShopTrade.customerSells(Mode.BUY, 3, 1, 4, 10, 2, 0).status());
    }

    @Test
    void clickAmountsFollowStackSize() {
        assertArrayEquals(new int[]{1, 8, 64}, ShopTrade.clickAmounts(64));
        assertArrayEquals(new int[]{1, 4, 16}, ShopTrade.clickAmounts(16));
        assertArrayEquals(new int[]{1}, ShopTrade.clickAmounts(1));
    }

    @Test
    void modes() {
        assertEquals(Mode.BOTH, Mode.parse("both"));
        assertEquals(Mode.SELL, Mode.parse("nonsense"));
        assertEquals(true, Mode.BOTH.ownerBuys() && Mode.BOTH.ownerSells());
        assertEquals(false, Mode.SELL.ownerBuys());
        assertEquals(false, Mode.BUY.ownerSells());
    }
}
