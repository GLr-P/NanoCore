package io.github.glrp.nanocore.event;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartsTest {

    static final Set<String> MOBS = Set.of("zombie", "skeleton", "piglin", "magma_cube", "blaze", "wither_skeleton", "ghast", "piglin_brute",
            "enderman", "vex", "evoker", "phantom", "ravager", "pillager", "vindicator", "endermite", "ender_dragon");

    static Map<String, EventDef> bundled(List<String> warnings) {
        return EventDefs.parse(YamlConfiguration.loadConfiguration(new File("src/main/resources/events.yml")), EventDefsTest.DEFAULTS,
                EventDefsTest.MILESTONES::contains, id -> !id.startsWith("not_"), MOBS::contains, warnings::add);
    }

    @Test
    void bundledPartsRead() {
        List<String> warnings = new ArrayList<>();
        Map<String, EventDef> all = bundled(warnings);
        assertEquals(List.of(), warnings);

        PartsDef breach = all.get("first_flame").phase(2).parts();
        assertEquals(5, breach.waves().size());
        assertEquals("piglin", breach.waves().get(0).mobs().get(0).type());
        assertEquals(6, breach.waves().get(0).mobs().get(0).count());
        assertEquals(400, breach.boss().health());
        assertEquals(List.of("minions", "slam", "leap", "enrage"), breach.boss().abilities());
        assertTrue(breach.lose().timer() && breach.lose().allDown());
        assertTrue(all.get("first_flame").phase(2).end().cleared());
        assertEquals("nether", all.get("first_flame").unlocks());

        assertTrue(all.get("fall_of_the_dragon").phase(0).parts().boss().existing());
        assertEquals(1.5, all.get("end_times").phase(2).parts().waves().get(5).health());

        PartsDef market = all.get("grand_market").phase(0).parts();
        assertEquals(2000L, market.deliver().need().get("#planks"));
        assertTrue(market.deliver().atBoard());
        assertEquals(500, all.get("grand_market").prizes().share());

        assertEquals(EventDef.Winner.TIME, all.get("race_day").winner());
        assertEquals("race_finish", all.get("race_day").phase(1).parts().race().finish());
        assertEquals(3, all.get("scavenger_hunt").phase(1).end().finishers());
        assertEquals(15, all.get("scavenger_hunt").phase(1).parts().collect().count());
        assertEquals("hill", all.get("king_of_the_hill").phase(1).parts().holdArea());
        assertEquals(List.of("snow_block"), all.get("spleef").phase(0).parts().breakable());
        assertTrue(all.get("spleef").phase(1).parts().revive());
        assertEquals(2, all.get("hide_and_seek").phase(0).parts().seekers());
        assertEquals(30_000, all.get("purge").phase(0).parts().respawnProtectionMs());
        assertEquals(3, all.get("purge").phase(0).parts().streak().at());
        assertEquals(0.0, all.get("market_day").phase(0).parts().shopTax());
        assertEquals("player_shops", all.get("market_day").requires());
        assertEquals(3, all.get("nether_day").phase(0).parts().checklist().size());
        assertEquals("plot", all.get("build_battle").phase(0).parts().teleport());
        assertEquals(12, all.get("build_battle").phase(0).parts().themes().size());
        assertEquals(5, all.get("treasure_hunt").phase(1).parts().treasure().chests());
        assertEquals(7 * 86_400_000L, all.get("grand_market").phase(0).end().timerMs(), "days in timers");
        assertFalse(all.get("carnival").auto(), "events that need an area start out of the daily pool");
        assertTrue(all.get("purge").auto());
    }

    @Test
    void badPartsWarnAndSkip() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("x.name", "X");
        y.set("x.phases", List.of(Map.of("name", "P", "waves", List.of(Map.of("mobs", List.of("dragonfly 3"))),
                "boss", Map.of("type", "zombie", "abilities", List.of("fly")), "teleport", "moon")));
        List<String> warnings = new ArrayList<>();
        Map<String, EventDef> out = EventDefs.parse(y, EventDefsTest.DEFAULTS, m -> true, i -> true, MOBS::contains, warnings::add);
        PartsDef p = out.get("x").phase(0).parts();
        assertTrue(p.waves().isEmpty());
        assertNotNull(p.boss());
        assertTrue(p.boss().abilities().isEmpty());
        assertNull(p.teleport());
        assertEquals(4, warnings.size(), warnings.toString());
    }

    @Test
    void mobLines() {
        assertEquals(new PartsDef.MobSpec("skeleton", 5, "Bone Archer"), PartsDefs.mob("skeleton 5 Bone Archer"));
        assertEquals(new PartsDef.MobSpec("zombie", 1, null), PartsDefs.mob("minecraft:zombie"));
        assertNull(PartsDefs.mob("zombie lots"));
    }

    @Test
    void rules() {
        assertEquals(10, PartsRules.scaled(10, 2, true));
        assertEquals(20, PartsRules.scaled(10, 4, true));
        assertEquals(30, PartsRules.scaled(10, 5, true));
        assertEquals(10, PartsRules.scaled(10, 8, false));

        assertArrayEquals(new int[]{0, 0}, PartsRules.plotCorner(0, 0, 0, 40, 16, 3));
        assertArrayEquals(new int[]{19, 0}, PartsRules.plotCorner(1, 0, 0, 40, 16, 3));
        assertArrayEquals(new int[]{0, 19}, PartsRules.plotCorner(2, 0, 0, 40, 16, 3));
        assertEquals(4, PartsRules.plotCapacity(40, 40, 16, 3));
        assertEquals(1, PartsRules.plotCapacity(16, 16, 16, 3));

        assertEquals("1:23.4", PartsRules.raceTime(83_456));
        assertEquals("9.9", PartsRules.raceTime(9_950));

        assertEquals("hot", PartsRules.hint(40, 10));
        assertEquals("warmer", PartsRules.hint(100, 80));
        assertEquals("colder", PartsRules.hint(80, 100));
        assertEquals("same", PartsRules.hint(-1, 100));

        assertTrue(PartsRules.holderScores(java.util.Collections.singletonList(null)));
        assertFalse(PartsRules.holderScores(java.util.Arrays.asList(null, null)), "two solo players: contested");
        assertTrue(PartsRules.holderScores(List.of(1, 1)), "two of one team: they score");
        assertFalse(PartsRules.holderScores(List.of(1, 2)));
        assertFalse(PartsRules.holderScores(List.of()));

        List<String> pick = PartsRules.pickList(List.of("a", "b", "c", "d"), 3, new Random(1));
        assertEquals(3, Set.copyOf(pick).size());
        assertTrue(PartsRules.finishScore(1) > PartsRules.finishScore(2));
        assertTrue(PartsRules.needsMet(Map.of("a", 2L), Map.of("a", 3L)));
        assertFalse(PartsRules.needsMet(Map.of("a", 2L, "b", 1L), Map.of("a", 3L)));
    }

    @Test
    void editorTimers() {
        List<Map<?, ?>> phases = List.of(Map.of("name", "A", "end", Map.of("timer", "30m")), Map.of("name", "B"));
        var out = EventEditor.changeTimer(phases, 0, 5);
        assertEquals("35m", ((Map<?, ?>) out.get(0).get("end")).get("timer"));
        out = EventEditor.changeTimer(phases, 1, 5);
        assertEquals("5m", ((Map<?, ?>) out.get(1).get("end")).get("timer"), "a phase without a timer gets one");
        assertEquals("1h", ((Map<?, ?>) EventEditor.changeTimer(List.of(Map.of("end", Map.of("timer", "55m"))), 0, 5).get(0).get("end")).get("timer"));
        assertEquals("1m", ((Map<?, ?>) EventEditor.changeTimer(phases, 0, -100).get(0).get("end")).get("timer"));
    }
}
