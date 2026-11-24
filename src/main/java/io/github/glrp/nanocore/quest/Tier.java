package io.github.glrp.nanocore.quest;

import java.util.Locale;
import java.util.Optional;

/** Quest difficulty. Community quests are shared by the whole server for a week. */
public enum Tier {
    EASY, MEDIUM, HARD, COMMUNITY;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Tier> byId(String id) {
        for (Tier t : values()) if (t.id().equalsIgnoreCase(id)) return Optional.of(t);
        return Optional.empty();
    }
}
