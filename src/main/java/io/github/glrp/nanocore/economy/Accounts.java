package io.github.glrp.nanocore.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory balances with the money rules. No Bukkit or database here, so it can be unit tested. */
public final class Accounts {

    public enum Result { OK, NO_ACCOUNT, NOT_ENOUGH, INVALID_AMOUNT, SAME_ACCOUNT }

    public record Entry(UUID id, String name, long balance) {
    }

    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    public synchronized boolean exists(UUID id) {
        return balances.containsKey(id);
    }

    /** Adds the account if new; otherwise only refreshes the name. Returns true if it was created. */
    public synchronized boolean ensure(UUID id, String name, long startingBalance) {
        if (name != null) names.put(id, name);
        if (balances.containsKey(id)) return false;
        balances.put(id, Math.max(0, startingBalance));
        return true;
    }

    public synchronized void load(UUID id, String name, long balance) {
        balances.put(id, balance);
        names.put(id, name);
    }

    public synchronized long balance(UUID id) {
        return balances.getOrDefault(id, 0L);
    }

    public synchronized String name(UUID id) {
        return names.get(id);
    }

    public synchronized Result deposit(UUID id, long amount) {
        if (amount <= 0) return Result.INVALID_AMOUNT;
        Long b = balances.get(id);
        if (b == null) return Result.NO_ACCOUNT;
        try {
            balances.put(id, Math.addExact(b, amount));
        } catch (ArithmeticException e) {
            return Result.INVALID_AMOUNT;
        }
        return Result.OK;
    }

    public synchronized Result withdraw(UUID id, long amount) {
        if (amount <= 0) return Result.INVALID_AMOUNT;
        Long b = balances.get(id);
        if (b == null) return Result.NO_ACCOUNT;
        if (b < amount) return Result.NOT_ENOUGH;
        balances.put(id, b - amount);
        return Result.OK;
    }

    public synchronized Result transfer(UUID from, UUID to, long amount) {
        if (from.equals(to)) return Result.SAME_ACCOUNT;
        if (amount <= 0) return Result.INVALID_AMOUNT;
        Long a = balances.get(from);
        Long b = balances.get(to);
        if (a == null || b == null) return Result.NO_ACCOUNT;
        if (a < amount) return Result.NOT_ENOUGH;
        long nb;
        try {
            nb = Math.addExact(b, amount);
        } catch (ArithmeticException e) {
            return Result.INVALID_AMOUNT;
        }
        balances.put(from, a - amount);
        balances.put(to, nb);
        return Result.OK;
    }

    /** Sets a balance (admin). Negative is not allowed. */
    public synchronized Result set(UUID id, long amount) {
        if (amount < 0) return Result.INVALID_AMOUNT;
        if (!balances.containsKey(id)) return Result.NO_ACCOUNT;
        balances.put(id, amount);
        return Result.OK;
    }

    public synchronized Optional<UUID> findByName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return names.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().toLowerCase(Locale.ROOT).equals(n))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** Richest first; ties by name. */
    public synchronized List<Entry> top() {
        List<Entry> list = new ArrayList<>();
        balances.forEach((id, b) -> list.add(new Entry(id, names.getOrDefault(id, "?"), b)));
        list.sort(Comparator.comparingLong(Entry::balance).reversed().thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }
}
