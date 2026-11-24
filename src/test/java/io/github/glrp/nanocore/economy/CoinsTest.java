package io.github.glrp.nanocore.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoinsTest {

    @Test
    void singularAndPlural() {
        assertEquals("1 coin", Coins.format(1));
        assertEquals("0 coins", Coins.format(0));
        assertEquals("-1 coin", Coins.format(-1));
    }

    @Test
    void thousandsSeparator() {
        assertEquals("1,250 coins", Coins.format(1250));
        assertEquals("1,000,000 coins", Coins.format(1_000_000));
    }

    @Test
    void customName() {
        assertEquals("5 gems", Coins.format(5, "gem", "gems"));
    }
}
