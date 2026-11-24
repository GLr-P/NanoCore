package io.github.glrp.nanocore.milestone;

import io.github.glrp.nanocore.db.Database;
import io.github.glrp.nanocore.text.Messages;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/** Which milestones are unlocked, saved in the database. Everything starts locked. */
public final class MilestoneService {

    public record State(boolean unlocked, long changedAt, String changedBy) {
    }

    private final Database db;
    private final Messages messages;
    private final Map<Milestone, State> states = new EnumMap<>(Milestone.class);
    private final List<BiConsumer<Milestone, Boolean>> listeners = new CopyOnWriteArrayList<>();

    public MilestoneService(Database db, Messages messages) {
        this.db = db;
        this.messages = messages;
        for (Milestone m : Milestone.values()) states.put(m, new State(false, 0, null));
        db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, unlocked, changed_at, changed_by FROM milestones");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    State s = new State(rs.getInt(2) == 1, rs.getLong(3), rs.getString(4));
                    Milestone.byId(id).ifPresent(m -> states.put(m, s));
                }
            }
            return null;
        });
    }

    /** Called with (milestone, unlocked) after every change, on the server thread. */
    public void addListener(BiConsumer<Milestone, Boolean> l) {
        listeners.add(l);
    }

    public synchronized boolean isUnlocked(Milestone m) {
        return states.get(m).unlocked();
    }

    public synchronized State state(Milestone m) {
        return states.get(m);
    }

    /** Unlocks (with a server-wide celebration) or locks again. Returns false if nothing changed. */
    public boolean set(Milestone m, boolean unlocked, String by) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (states.get(m).unlocked() == unlocked) return false;
            states.put(m, new State(unlocked, now, by));
        }
        db.run(c -> Database.update(c,
                "INSERT INTO milestones (id, unlocked, changed_at, changed_by) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET unlocked = excluded.unlocked, changed_at = excluded.changed_at, changed_by = excluded.changed_by",
                m.id(), unlocked ? 1 : 0, now, by));
        if (unlocked) celebrate(m);
        listeners.forEach(l -> l.accept(m, unlocked));
        return true;
    }

    public String displayName(Milestone m) {
        return messages.raw("milestones.names." + m.id());
    }

    private void celebrate(Milestone m) {
        String name = displayName(m);
        Title title = Title.title(
                messages.msg("milestones.unlocked-broadcast-title", "milestone", name),
                messages.msg("milestones.unlocked-broadcast-subtitle", "milestone", name),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        Bukkit.getServer().sendMessage(messages.msg("milestones.unlocked-broadcast-chat", "milestone", name));
    }
}
