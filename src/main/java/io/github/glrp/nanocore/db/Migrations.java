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
            },
            // 3: quests
            new String[]{
                    "CREATE TABLE quest_board (id TEXT PRIMARY KEY, period TEXT NOT NULL, slot INTEGER NOT NULL, tier TEXT NOT NULL, template TEXT NOT NULL, variant TEXT, amount INTEGER NOT NULL, reward INTEGER NOT NULL, owner TEXT, replaces TEXT)",
                    "CREATE TABLE quest_progress (uuid TEXT NOT NULL, quest TEXT NOT NULL, state TEXT NOT NULL, progress INTEGER NOT NULL, PRIMARY KEY (uuid, quest))",
                    "CREATE TABLE quest_places (kind TEXT NOT NULL, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, PRIMARY KEY (kind, world, x, y, z))",
                    "CREATE TABLE quest_backlog (id INTEGER PRIMARY KEY AUTOINCREMENT, item BLOB NOT NULL)"
            },
            // 4: events
            new String[]{
                    "CREATE TABLE event_active (id INTEGER PRIMARY KEY, data TEXT NOT NULL)",
                    "CREATE TABLE event_history (id INTEGER PRIMARY KEY AUTOINCREMENT, event TEXT NOT NULL, name TEXT NOT NULL, started INTEGER NOT NULL, ended INTEGER NOT NULL, results TEXT NOT NULL)",
                    "CREATE TABLE event_kits (uuid TEXT PRIMARY KEY, data TEXT NOT NULL)",
                    "CREATE TABLE event_claims (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, item BLOB NOT NULL)"
            },
            // 5: player shops
            new String[]{
                    "CREATE TABLE pshops (id INTEGER PRIMARY KEY, owner TEXT NOT NULL, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, item BLOB NOT NULL, mode TEXT NOT NULL, sell_price INTEGER NOT NULL, buy_price INTEGER NOT NULL, created INTEGER NOT NULL, UNIQUE (world, x, y, z))",
                    "CREATE TABLE pshop_sales (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, shop INTEGER NOT NULL, owner TEXT NOT NULL, customer TEXT NOT NULL, kind TEXT NOT NULL, item TEXT NOT NULL, amount INTEGER NOT NULL, coins INTEGER NOT NULL, seen INTEGER NOT NULL)",
                    "CREATE INDEX pshop_sales_owner ON pshop_sales (owner, seen)"
            },
            // 6: player shop amount per click
            new String[]{
                    "ALTER TABLE pshops ADD COLUMN click_amount INTEGER NOT NULL DEFAULT 0"
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
