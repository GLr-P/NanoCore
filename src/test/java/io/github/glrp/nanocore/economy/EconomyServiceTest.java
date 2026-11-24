package io.github.glrp.nanocore.economy;

import io.github.glrp.nanocore.db.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Real SQLite file: balances and the transaction log survive a restart. */
class EconomyServiceTest {

    @TempDir
    Path dir;

    @Test
    void balancesSurviveRestart() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Path file = dir.resolve("test.db");

        try (Database db = new Database(file, Logger.getLogger("test"))) {
            EconomyService eco = new EconomyService(db, () -> 100, "coin", "coins");
            eco.ensureAccount(a, "Alex");
            eco.ensureAccount(b, "Sam");
            eco.deposit(a, 50, "test");
            eco.transfer(a, b, 20, "pay");
            eco.withdraw(b, 5, "test");
            assertEquals(130, eco.balance(a));
            assertEquals(115, eco.balance(b));
        }

        try (Database db = new Database(file, Logger.getLogger("test"))) {
            EconomyService eco = new EconomyService(db, () -> 100, "coin", "coins");
            assertEquals(130, eco.balance(a));
            assertEquals(115, eco.balance(b));
            assertEquals("Sam", eco.name(b));
            // starting money x2, deposit, pay, withdraw
            long logged = db.sync(c -> Database.queryLong(c, "SELECT COUNT(*) FROM transactions", -1));
            assertEquals(5, logged);
            // migrations ran once
            long schema = db.sync(c -> Database.queryLong(c, "SELECT CAST(value AS INTEGER) FROM meta WHERE key = 'schema'", -1));
            assertEquals(Database.schemaVersion(), schema);
        }
    }

    @Test
    void formatUsesCurrencyNames() {
        try (Database db = new Database(dir.resolve("f.db"), Logger.getLogger("test"))) {
            EconomyService eco = new EconomyService(db, () -> 0, "gem", "gems");
            assertEquals("1 gem", eco.format(1));
            assertEquals("2,500 gems", eco.format(2500));
        }
    }
}
