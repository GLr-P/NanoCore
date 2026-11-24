package io.github.glrp.nanocore.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventAreasTest {

    @Test
    void bundledEventsListTheirAreas() {
        Map<String, EventDef> defs = EventDefsTest.load("src/main/resources/events.yml", new ArrayList<>());
        Map<String, EventAreas.Need> needs = EventAreas.needed(defs.values()).stream()
                .collect(Collectors.toMap(EventAreas.Need::area, Function.identity()));

        EventAreas.Need town = needs.get("town");
        assertFalse(town.optional(), "town is a safe zone and an event area");
        assertTrue(town.events().size() >= 2);
        assertTrue(needs.get("carnival").box(), "plots need a box");
        assertTrue(needs.get("spleef_floor").box(), "a fill needs a box");
        assertFalse(needs.get("hill").box());
        assertFalse(needs.get("race_start").optional());
        assertTrue(needs.get("race_cp1").optional(), "missing checkpoints are skipped");
        assertTrue(needs.get("portal").optional(), "wave mobs appear around the players without it");
        assertEquals(1, needs.get("carnival").events().size());
    }
}
