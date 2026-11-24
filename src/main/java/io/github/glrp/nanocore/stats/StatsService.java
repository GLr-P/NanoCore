package io.github.glrp.nanocore.stats;

import io.github.glrp.nanocore.db.Database;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-player counters. Totals for online players live in memory; increments are queued and
 * saved in batches. Quests and events subscribe to every increment (with where it happened).
 */
public final class StatsService {

    /** Called on the server thread for every counted action. */
    public interface StatListener {
        void onStat(Player player, String stat, long amount, Location where);
    }

    private final Database db;
    private final Map<UUID, Map<String, Long>> totals = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> explored = new ConcurrentHashMap<>();
    private final Object pendingLock = new Object();
    private Map<UUID, Map<String, Long>> pending = new HashMap<>();
    private List<Object[]> pendingChunks = new ArrayList<>();
    private final List<StatListener> listeners = new CopyOnWriteArrayList<>();

    public StatsService(Database db) {
        this.db = db;
    }

    public void subscribe(StatListener l) {
        listeners.add(l);
    }

    /** Loads a player's counters. Call from an async login event (it waits on the database). */
    public void load(UUID id) {
        Map<String, Long> loaded = new ConcurrentHashMap<>();
        Set<String> chunks = ConcurrentHashMap.newKeySet();
        db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT stat, value FROM stats WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) loaded.put(rs.getString(1), rs.getLong(2));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT world, cx, cz FROM explored_chunks WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) chunks.add(chunkId(rs.getString(1), rs.getInt(2), rs.getInt(3)));
                }
            }
            return null;
        });
        synchronized (pendingLock) {
            // Anything counted but not yet saved (e.g. a quick rejoin) is added on top.
            Map<String, Long> p = pending.get(id);
            if (p != null) p.forEach((k, v) -> loaded.merge(k, v, Long::sum));
            for (Object[] row : pendingChunks) if (row[0].equals(id.toString())) chunks.add(chunkId((String) row[1], (int) row[2], (int) row[3]));
        }
        totals.put(id, loaded);
        explored.put(id, chunks);
    }

    public void unload(UUID id) {
        totals.remove(id);
        explored.remove(id);
    }

    public long get(UUID id, String stat) {
        Map<String, Long> m = totals.get(id);
        return m == null ? 0 : m.getOrDefault(stat, 0L);
    }

    /** Sum of every counter starting with the prefix, e.g. "mine." for all natural blocks mined. */
    public long sumPrefix(UUID id, String prefix) {
        Map<String, Long> m = totals.get(id);
        if (m == null) return 0;
        long s = 0;
        for (var e : m.entrySet()) if (e.getKey().startsWith(prefix) && !e.getKey().endsWith(".total")) s += e.getValue();
        return s;
    }

    public Map<String, Long> all(UUID id) {
        Map<String, Long> m = totals.get(id);
        return m == null ? Map.of() : Map.copyOf(m);
    }

    public void add(Player p, String stat, long amount, Location where) {
        if (amount <= 0) return;
        UUID id = p.getUniqueId();
        Map<String, Long> m = totals.get(id);
        if (m == null) return; // not loaded (shouldn't happen for an online player)
        m.merge(stat, amount, Long::sum);
        synchronized (pendingLock) {
            pending.computeIfAbsent(id, k -> new HashMap<>()).merge(stat, amount, Long::sum);
        }
        for (StatListener l : listeners) l.onStat(p, stat, amount, where);
    }

    /** Counts a stat once per player (biomes, structures). Returns true the first time. */
    public boolean addOnce(Player p, String stat, Location where) {
        if (get(p.getUniqueId(), stat) > 0) return false;
        add(p, stat, 1, where);
        return true;
    }

    /** Records a chunk visit. Returns true if the player had never been in it. */
    public boolean explore(Player p, String world, int cx, int cz) {
        Set<String> set = explored.get(p.getUniqueId());
        if (set == null || !set.add(chunkId(world, cx, cz))) return false;
        synchronized (pendingLock) {
            pendingChunks.add(new Object[]{p.getUniqueId().toString(), world, cx, cz});
        }
        return true;
    }

    private static String chunkId(String world, int cx, int cz) {
        return world + ":" + cx + ":" + cz;
    }

    /** Queues all unsaved counters for the database. */
    public void flush() {
        Map<UUID, Map<String, Long>> batch;
        List<Object[]> chunks;
        synchronized (pendingLock) {
            if (pending.isEmpty() && pendingChunks.isEmpty()) return;
            batch = pending;
            chunks = pendingChunks;
            pending = new HashMap<>();
            pendingChunks = new ArrayList<>();
        }
        db.run(c -> {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO stats (uuid, stat, value) VALUES (?, ?, ?) ON CONFLICT (uuid, stat) DO UPDATE SET value = value + excluded.value")) {
                    for (var player : batch.entrySet()) {
                        for (var stat : player.getValue().entrySet()) {
                            ps.setString(1, player.getKey().toString());
                            ps.setString(2, stat.getKey());
                            ps.setLong(3, stat.getValue());
                            ps.addBatch();
                        }
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO explored_chunks (uuid, world, cx, cz) VALUES (?, ?, ?, ?)")) {
                    for (Object[] row : chunks) {
                        Database.bind(ps, row);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        });
    }

    /** For tests and admin tools: everything a player has, including stats of offline players. */
    public Map<String, Long> loadOffline(UUID id) {
        Map<String, Long> m = totals.get(id);
        if (m != null) return Map.copyOf(m);
        Map<String, Long> out = new HashMap<>();
        db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT stat, value FROM stats WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
                }
            }
            return null;
        });
        return out;
    }

    public Set<UUID> loadedPlayers() {
        return new HashSet<>(totals.keySet());
    }
}
