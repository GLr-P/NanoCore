package io.github.glrp.nanocore.economy;

import io.github.glrp.nanocore.db.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * Money for the whole server. Balances live in memory (all accounts; a friends server is small)
 * and every change is written to the database in order, with a transaction log.
 * Safe to call from any thread (Vault callers may be async).
 */
public final class EconomyService {

    /** Told about every successful balance change (on the calling thread). */
    public interface ChangeListener {
        void onChange(UUID id, long delta, String reason);
    }

    private final Accounts accounts = new Accounts();
    private final Database db;
    private final LongSupplier startingBalance;
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();
    private volatile String singular;
    private volatile String plural;

    public EconomyService(Database db, LongSupplier startingBalance, String singular, String plural) {
        this.db = db;
        this.startingBalance = startingBalance;
        setCurrencyNames(singular, plural);
        db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, balance FROM accounts");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) accounts.load(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3));
            }
            return null;
        });
    }

    public void setCurrencyNames(String singular, String plural) {
        this.singular = singular;
        this.plural = plural;
    }

    public void addListener(ChangeListener l) {
        listeners.add(l);
    }

    public String format(long amount) {
        return Coins.format(amount, singular, plural);
    }

    public String singular() {
        return singular;
    }

    public String plural() {
        return plural;
    }

    /** Creates the account on first join (with the starting money) and keeps the name current. */
    public void ensureAccount(UUID id, String name) {
        long start = startingBalance.getAsLong();
        boolean created = accounts.ensure(id, name, start);
        long now = System.currentTimeMillis();
        long balance = accounts.balance(id);
        db.run(c -> Database.update(c,
                "INSERT INTO accounts (uuid, name, balance, updated) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (uuid) DO UPDATE SET name = excluded.name",
                id.toString(), name, balance, now));
        if (created && start > 0) logTransaction(null, id, start, "starting money");
    }

    public boolean hasAccount(UUID id) {
        return accounts.exists(id);
    }

    public long balance(UUID id) {
        return accounts.balance(id);
    }

    public String name(UUID id) {
        return accounts.name(id);
    }

    public Optional<UUID> findByName(String name) {
        return accounts.findByName(name);
    }

    public List<Accounts.Entry> top() {
        return accounts.top();
    }

    public Accounts.Result deposit(UUID id, long amount, String reason) {
        Accounts.Result r = accounts.deposit(id, amount);
        if (r == Accounts.Result.OK) {
            save(id);
            logTransaction(null, id, amount, reason);
            listeners.forEach(l -> l.onChange(id, amount, reason));
        }
        return r;
    }

    public Accounts.Result withdraw(UUID id, long amount, String reason) {
        Accounts.Result r = accounts.withdraw(id, amount);
        if (r == Accounts.Result.OK) {
            save(id);
            logTransaction(id, null, amount, reason);
            listeners.forEach(l -> l.onChange(id, -amount, reason));
        }
        return r;
    }

    public Accounts.Result transfer(UUID from, UUID to, long amount, String reason) {
        Accounts.Result r = accounts.transfer(from, to, amount);
        if (r == Accounts.Result.OK) {
            save(from);
            save(to);
            logTransaction(from, to, amount, reason);
            listeners.forEach(l -> {
                l.onChange(from, -amount, reason);
                l.onChange(to, amount, reason);
            });
        }
        return r;
    }

    public Accounts.Result set(UUID id, long amount, String reason) {
        long before = accounts.balance(id);
        Accounts.Result r = accounts.set(id, amount);
        if (r == Accounts.Result.OK) {
            save(id);
            long delta = amount - before;
            if (delta > 0) logTransaction(null, id, delta, reason);
            else if (delta < 0) logTransaction(id, null, -delta, reason);
        }
        return r;
    }

    private void save(UUID id) {
        // The balance is read when the write runs, so the database always ends with the latest value.
        db.run(c -> Database.update(c, "UPDATE accounts SET balance = ?, updated = ? WHERE uuid = ?",
                accounts.balance(id), System.currentTimeMillis(), id.toString()));
    }

    private void logTransaction(UUID from, UUID to, long amount, String reason) {
        long now = System.currentTimeMillis();
        db.run(c -> Database.update(c, "INSERT INTO transactions (time, from_uuid, to_uuid, amount, reason) VALUES (?, ?, ?, ?, ?)",
                now, from == null ? null : from.toString(), to == null ? null : to.toString(), amount, reason));
    }
}
