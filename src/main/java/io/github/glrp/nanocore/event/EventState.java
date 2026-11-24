package io.github.glrp.nanocore.event;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The running event: phase, timers, who's in, scores, votes. Saved to the database as YAML text so a restart
 * (or a crash) picks it up where it was. Plain data; the rules live in EventService.
 */
public final class EventState {

    public String eventId;
    /** -1 while announced (before the first phase). */
    public int phase = -1;
    public boolean autoStarted;
    public long startedAt;
    public long phaseStartedAt;
    /** Real-time end of the phase (or of the announcement), 0 = none. */
    public long endsAt;
    /** While paused: time that was left; -1 = running. */
    public long pausedRemaining = -1;
    /** World full time at which the phase ends, -1 = none. */
    public long mcEnd = -1;
    public final Set<Integer> remindersSent = new HashSet<>();
    public final LinkedHashSet<UUID> participants = new LinkedHashSet<>();
    public final Map<UUID, Long> scores = new LinkedHashMap<>();
    public final Map<UUID, Long> actions = new HashMap<>();
    public final Map<UUID, Integer> teams = new HashMap<>();
    public final Map<UUID, Integer> lives = new HashMap<>();
    public final Set<UUID> ready = new HashSet<>();
    public final Set<UUID> eliminated = new HashSet<>();
    public final Set<UUID> kitted = new HashSet<>();
    public final Map<UUID, List<UUID>> votes = new HashMap<>();
    /** World settings to put back when the event ends, like "difficulty.world" -> "normal". */
    public final Map<String, String> restore = new HashMap<>();
    /** State of the gameplay parts (current wave, plots, treasure spots, race bests, roles...), as text. */
    public final Map<String, String> data = new LinkedHashMap<>();

    public String get(String key) {
        return data.get(key);
    }

    public long getLong(String key, long fallback) {
        String v = data.get(key);
        if (v == null) return fallback;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean paused() {
        return pausedRemaining >= 0;
    }

    public long score(UUID id) {
        return scores.getOrDefault(id, 0L);
    }

    public long totalPoints() {
        long s = 0;
        for (UUID id : participants) s += score(id);
        return s;
    }

    /** Points per team (1..n). */
    public Map<Integer, Long> teamScores() {
        Map<Integer, Long> out = new LinkedHashMap<>();
        for (UUID id : participants) {
            Integer t = teams.get(id);
            if (t != null) out.merge(t, score(id), Long::sum);
        }
        return out;
    }

    // --- saving ---

    public String toYaml() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("event", eventId);
        y.set("phase", phase);
        y.set("auto", autoStarted);
        y.set("started", startedAt);
        y.set("phase-started", phaseStartedAt);
        y.set("ends", endsAt);
        y.set("paused", pausedRemaining);
        y.set("mc-end", mcEnd);
        y.set("reminders", new ArrayList<>(remindersSent));
        y.set("participants", ids(participants));
        scores.forEach((k, v) -> y.set("scores." + k, v));
        actions.forEach((k, v) -> y.set("actions." + k, v));
        teams.forEach((k, v) -> y.set("teams." + k, v));
        lives.forEach((k, v) -> y.set("lives." + k, v));
        y.set("ready", ids(ready));
        y.set("eliminated", ids(eliminated));
        y.set("kitted", ids(kitted));
        votes.forEach((k, v) -> y.set("votes." + k, ids(v)));
        // Restore keys contain dots ("difficulty.world"), so they're stored as a list of "key=value".
        List<String> r = new ArrayList<>();
        restore.forEach((k, v) -> r.add(k + "=" + v));
        y.set("restore", r);
        List<String> d = new ArrayList<>();
        data.forEach((k, v) -> d.add(k + "=" + v));
        y.set("data", d);
        return y.saveToString();
    }

    public static EventState fromYaml(String text) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new IllegalArgumentException("saved event can't be read", e);
        }
        EventState s = new EventState();
        s.eventId = y.getString("event");
        s.phase = y.getInt("phase", -1);
        s.autoStarted = y.getBoolean("auto");
        s.startedAt = y.getLong("started");
        s.phaseStartedAt = y.getLong("phase-started");
        s.endsAt = y.getLong("ends");
        s.pausedRemaining = y.getLong("paused", -1);
        s.mcEnd = y.getLong("mc-end", -1);
        s.remindersSent.addAll(y.getIntegerList("reminders"));
        for (String id : y.getStringList("participants")) s.participants.add(UUID.fromString(id));
        longs(y.getConfigurationSection("scores"), s.scores);
        longs(y.getConfigurationSection("actions"), s.actions);
        ints(y.getConfigurationSection("teams"), s.teams);
        ints(y.getConfigurationSection("lives"), s.lives);
        for (String id : y.getStringList("ready")) s.ready.add(UUID.fromString(id));
        for (String id : y.getStringList("eliminated")) s.eliminated.add(UUID.fromString(id));
        for (String id : y.getStringList("kitted")) s.kitted.add(UUID.fromString(id));
        ConfigurationSection v = y.getConfigurationSection("votes");
        if (v != null) {
            for (String k : v.getKeys(false)) {
                List<UUID> picks = new ArrayList<>();
                for (String id : v.getStringList(k)) picks.add(UUID.fromString(id));
                s.votes.put(UUID.fromString(k), picks);
            }
        }
        for (String line : y.getStringList("restore")) {
            int eq = line.indexOf('=');
            if (eq > 0) s.restore.put(line.substring(0, eq), line.substring(eq + 1));
        }
        for (String line : y.getStringList("data")) {
            int eq = line.indexOf('=');
            if (eq > 0) s.data.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return s;
    }

    private static List<String> ids(Iterable<UUID> set) {
        List<String> out = new ArrayList<>();
        for (UUID id : set) out.add(id.toString());
        return out;
    }

    private static void longs(ConfigurationSection s, Map<UUID, Long> into) {
        if (s != null) for (String k : s.getKeys(false)) into.put(UUID.fromString(k), s.getLong(k));
    }

    private static void ints(ConfigurationSection s, Map<UUID, Integer> into) {
        if (s != null) for (String k : s.getKeys(false)) into.put(UUID.fromString(k), s.getInt(k));
    }
}
