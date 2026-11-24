package io.github.glrp.nanocore.db;

import java.sql.Statement;
import java.util.List;

/** Schema versions. Append new entries; never edit old ones. */
final class Migrations {

    private Migrations() {
    }

    static final List<String[]> STEPS = List.<String[]>of(
            // 1: core
            new String[]{
                    "CREATE TABLE accounts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, balance INTEGER NOT NULL, updated INTEGER NOT NULL)",
                    "CREATE INDEX accounts_name ON accounts (name COLLATE NOCASE)",
                    "CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, from_uuid TEXT, to_uuid TEXT, amount INTEGER NOT NULL, reason TEXT NOT NULL)",
                    "CREATE TABLE stats (uuid TEXT NOT NULL, stat TEXT NOT NULL, value INTEGER NOT NULL, PRIMARY KEY (uuid, stat))",
                    "CREATE TABLE explored_chunks (uuid TEXT NOT NULL, world TEXT NOT NULL, cx INTEGER NOT NULL, cz INTEGER NOT NULL, PRIMARY KEY (uuid, world, cx, cz))",
                    "CREATE TABLE milestones (id TEXT PRIMARY KEY, unlocked INTEGER NOT NULL, changed_at INTEGER NOT NULL, changed_by TEXT)",
                    "CREATE TABLE areas (name TEXT PRIMARY KEY, world TEXT NOT NULL, kind TEXT NOT NULL, x1 REAL, y1 REAL, z1 REAL, x2 REAL, y2 REAL, z2 REAL, radius REAL)"
            },
            // 2: admin shop
            new String[]{
                    "CREATE TABLE shop_daily (uuid TEXT NOT NULL, day TEXT NOT NULL, earned INTEGER NOT NULL, PRIMARY KEY (uuid, day))",
                    "CREATE TABLE shop_sales (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, uuid TEXT NOT NULL, item TEXT NOT NULL, amount INTEGER NOT NULL, coins INTEGER NOT NULL)"
            }
    );

    static void apply(Database db) {
        db.sync(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            }
            int version = (int) Database.queryLong(c, "SELECT CAST(value AS INTEGER) FROM meta WHERE key = 'schema'", 0);
            for (int v = version; v < STEPS.size(); v++) {
                c.setAutoCommit(false);
                try (Statement s = c.createStatement()) {
                    for (String sql : STEPS.get(v)) s.execute(sql);
                    Database.update(c, "INSERT INTO meta (key, value) VALUES ('schema', ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                            String.valueOf(v + 1));
                    c.commit();
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
            return null;
        });
    }
}
