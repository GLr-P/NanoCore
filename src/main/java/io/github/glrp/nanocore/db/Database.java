package io.github.glrp.nanocore.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One SQLite connection owned by one thread. Every query runs on that thread, in order,
 * so the server thread never waits on disk and writes never race.
 */
public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection c) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlAction {
        void run(Connection c) throws SQLException;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NanoCore-DB");
        t.setDaemon(true);
        return t;
    });
    private final Logger log;
    private Connection connection;

    public Database(Path file, Logger log) {
        this.log = log;
        try {
            executor.submit(() -> {
                connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                try (Statement s = connection.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.execute("PRAGMA synchronous=NORMAL");
                    s.execute("PRAGMA foreign_keys=ON");
                }
                return null;
            }).get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not open the database " + file, e);
        }
        Migrations.apply(this);
    }

    /** The schema version after all migrations. */
    public static int schemaVersion() {
        return Migrations.STEPS.size();
    }

    /** Runs on the DB thread and returns a future. */
    public <T> CompletableFuture<T> submit(SqlFunction<T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                f.complete(fn.apply(connection));
            } catch (Throwable e) {
                log.log(Level.SEVERE, "Database error", e);
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    /** Fire-and-forget write on the DB thread. */
    public void run(SqlAction action) {
        submit(c -> {
            action.run(c);
            return null;
        });
    }

    /** Runs on the DB thread and waits. Only for startup, login (async thread) and shutdown. */
    public <T> T sync(SqlFunction<T> fn) {
        if (executor.isShutdown()) throw new IllegalStateException("Database closed");
        return submit(fn).join();
    }

    public static void update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        }
    }

    public static long queryLong(Connection c, String sql, long fallback, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : fallback;
            }
        }
    }

    public static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
    }

    @Override
    public void close() {
        executor.execute(() -> {
            try {
                if (connection != null) connection.close();
            } catch (SQLException e) {
                log.log(Level.WARNING, "Closing the database", e);
            }
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) log.warning("Database writes did not finish within 30 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
