package io.github.glrp.nanocore.event;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Which areas the events use, so the setup page can list the missing ones. Pure, so it is unit-tested. */
public final class EventAreas {

    /**
     * An area name and the events using it.
     *
     * @param box      it has to be a box (plots and fills need corners)
     * @param optional the events work without it (race checkpoints, where wave mobs appear, where treasure is hidden)
     */
    public record Need(String area, List<String> events, boolean box, boolean optional) {
    }

    private static final class Use {
        final List<String> events = new ArrayList<>();
        boolean box;
        boolean optional = true;
    }

    private EventAreas() {
    }

    public static List<Need> needed(Collection<EventDef> defs) {
        Map<String, Use> uses = new LinkedHashMap<>();
        for (EventDef d : defs) {
            add(uses, d.area(), d.name(), false, false);
            for (String s : d.safeZones()) add(uses, s, d.name(), false, false);
            for (EventDef.Phase ph : d.phases()) {
                PartsDef p = ph.parts();
                if (p == null) continue;
                add(uses, p.spawnArea(), d.name(), false, true);
                if (p.plots() != null) add(uses, p.plots().area(), d.name(), true, false);
                if (p.treasure() != null) add(uses, p.treasure().around(), d.name(), false, true);
                if (p.race() != null) {
                    add(uses, p.race().start(), d.name(), false, false);
                    for (String c : p.race().checkpoints()) add(uses, c, d.name(), false, true);
                    add(uses, p.race().finish(), d.name(), false, false);
                }
                add(uses, p.holdArea(), d.name(), false, false);
                if (p.fill() != null) add(uses, p.fill().area(), d.name(), true, false);
            }
        }
        List<Need> out = new ArrayList<>();
        uses.forEach((area, u) -> out.add(new Need(area, List.copyOf(u.events), u.box, u.optional)));
        return out;
    }

    private static void add(Map<String, Use> uses, String area, String event, boolean box, boolean optional) {
        if (area == null || area.isBlank()) return;
        Use u = uses.computeIfAbsent(area.toLowerCase(Locale.ROOT), k -> new Use());
        if (!u.events.contains(event)) u.events.add(event);
        u.box |= box;
        u.optional &= optional;
    }
}
