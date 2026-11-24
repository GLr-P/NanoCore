package io.github.glrp.nanocore.milestone;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/** Server progress that stays locked until the server unlocks it together (usually at a big event). */
public enum Milestone {
    PLAYER_SHOPS("player_shops", "CHEST"),
    NETHER("nether", "NETHERRACK"),
    NETHER_ROOF("nether_roof", "BEDROCK"),
    NETHERITE("netherite", "NETHERITE_INGOT"),
    END("end", "END_PORTAL_FRAME"),
    ELYTRA("elytra", "ELYTRA");

    private final String id;
    private final String icon;

    Milestone(String id, String icon) {
        this.id = id;
        this.icon = icon;
    }

    public String id() {
        return id;
    }

    /** Material name for the menu icon. */
    public String icon() {
        return icon;
    }

    public static Optional<Milestone> byId(String id) {
        String s = id.toLowerCase(Locale.ROOT).trim();
        return Arrays.stream(values()).filter(m -> m.id.equals(s)).findFirst();
    }

    public static String ids() {
        return Arrays.stream(values()).map(Milestone::id).collect(Collectors.joining(", "));
    }
}
