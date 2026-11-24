package io.github.glrp.nanocore.economy;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountsTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    private Accounts twoPlayers() {
        Accounts acc = new Accounts();
        acc.ensure(a, "Alex", 100);
        acc.ensure(b, "Sam", 100);
        return acc;
    }

    @Test
    void newAccountGetsStartingMoneyOnce() {
        Accounts acc = new Accounts();
        assertTrue(acc.ensure(a, "Alex", 100));
        acc.deposit(a, 50);
        assertFalse(acc.ensure(a, "Alex2", 100));
        assertEquals(150, acc.balance(a));
        assertEquals("Alex2", acc.name(a));
    }

    @Test
    void cannotGoBelowZero() {
        Accounts acc = twoPlayers();
        assertEquals(Accounts.Result.NOT_ENOUGH, acc.withdraw(a, 101));
        assertEquals(100, acc.balance(a));
        assertEquals(Accounts.Result.OK, acc.withdraw(a, 100));
        assertEquals(0, acc.balance(a));
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        Accounts acc = twoPlayers();
        assertEquals(Accounts.Result.INVALID_AMOUNT, acc.deposit(a, 0));
        assertEquals(Accounts.Result.INVALID_AMOUNT, acc.deposit(a, -5));
        assertEquals(Accounts.Result.INVALID_AMOUNT, acc.withdraw(a, -5));
        assertEquals(Accounts.Result.INVALID_AMOUNT, acc.transfer(a, b, 0));
        assertEquals(Accounts.Result.INVALID_AMOUNT, acc.set(a, -1));
    }

    @Test
    void transferMovesMoneyBothWays() {
        Accounts acc = twoPlayers();
        assertEquals(Accounts.Result.OK, acc.transfer(a, b, 30));
        assertEquals(70, acc.balance(a));
        assertEquals(130, acc.balance(b));
        assertEquals(Accounts.Result.NOT_ENOUGH, acc.transfer(a, b, 71));
        assertEquals(70, acc.balance(a));
    }

    @Test
    void cannotPaySelfOrUnknown() {
        Accounts acc = twoPlayers();
        assertEquals(Accounts.Result.SAME_ACCOUNT, acc.transfer(a, a, 10));
        assertEquals(Accounts.Result.NO_ACCOUNT, acc.transfer(a, UUID.randomUUID(), 10));
        assertEquals(Accounts.Result.NO_ACCOUNT, acc.deposit(UUID.randomUUID(), 10));
    }

    @Test
    void overflowIsRefused() {
        Accounts acc = twoPlayers();
        acc.set(b, Long.MAX_VALUE);
        assertEquals(Accounts.Result.INVALID_AMOUNT, acc.deposit(b, 1));
        assertEquals(Accounts.Result.INVALID_AMOUNT, acc.transfer(a, b, 1));
        assertEquals(100, acc.balance(a));
    }

    @Test
    void findsByNameIgnoringCase() {
        Accounts acc = twoPlayers();
        assertEquals(a, acc.findByName("alex").orElseThrow());
        assertTrue(acc.findByName("nobody").isEmpty());
    }

    @Test
    void topIsRichestFirst() {
        Accounts acc = twoPlayers();
        acc.deposit(b, 5);
        assertEquals("Sam", acc.top().get(0).name());
        assertEquals(105, acc.top().get(0).balance());
    }
}
