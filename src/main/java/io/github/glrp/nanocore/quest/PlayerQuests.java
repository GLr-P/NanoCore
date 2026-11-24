package io.github.glrp.nanocore.quest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One player's quests for the current board: taken, done, claimed, and progress. Pure rules. */
public final class PlayerQuests {

    public enum State { ACTIVE, DONE, CLAIMED }

    public enum TakeResult { OK, ALREADY, FULL }

    /** Progress is in raw stat units (centimetres for distances). */
    public record Entry(State state, long progress) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public Map<String, Entry> entries() {
        return Collections.unmodifiableMap(entries);
    }

    /** For loading saved rows. */
    public void put(String questId, State state, long progress) {
        entries.put(questId, new Entry(state, progress));
    }

    public Entry get(String questId) {
        return entries.get(questId);
    }

    public State state(String questId) {
        Entry e = entries.get(questId);
        return e == null ? null : e.state();
    }

    public long progress(String questId) {
        Entry e = entries.get(questId);
        return e == null ? 0 : e.progress();
    }

    public int activeCount() {
        int n = 0;
        for (Entry e : entries.values()) if (e.state() == State.ACTIVE) n++;
        return n;
    }

    /** Takes a quest. Each quest can be taken once per board (unless abandoned); at most {@code maxActive} at a time. */
    public TakeResult take(String questId, int maxActive) {
        if (entries.containsKey(questId)) return TakeResult.ALREADY;
        if (activeCount() >= maxActive) return TakeResult.FULL;
        entries.put(questId, new Entry(State.ACTIVE, 0));
        return TakeResult.OK;
    }

    /** Drops an active quest and its progress. It can be taken again later. */
    public boolean abandon(String questId) {
        Entry e = entries.get(questId);
        if (e == null || e.state() != State.ACTIVE) return false;
        entries.remove(questId);
        return true;
    }

    /** Adds progress to an active quest. Returns true if this finished it. Progress never goes past the target. */
    public boolean add(String questId, long raw, long target) {
        Entry e = entries.get(questId);
        if (e == null || e.state() != State.ACTIVE || raw <= 0) return false;
        long now = Math.min(target, e.progress() + raw);
        boolean done = now >= target;
        entries.put(questId, new Entry(done ? State.DONE : State.ACTIVE, now));
        return done;
    }

    /** How many more raw units an active quest still needs (0 if it isn't active). */
    public long missing(String questId, long target) {
        Entry e = entries.get(questId);
        return e == null || e.state() != State.ACTIVE ? 0 : Math.max(0, target - e.progress());
    }

    public boolean claim(String questId) {
        Entry e = entries.get(questId);
        if (e == null || e.state() != State.DONE) return false;
        entries.put(questId, new Entry(State.CLAIMED, e.progress()));
        return true;
    }

    /** Drops everything about a quest (when the board refreshes or a reroll replaces it). */
    public void forget(String questId) {
        entries.remove(questId);
    }
}
