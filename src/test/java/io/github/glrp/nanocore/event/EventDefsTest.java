package io.github.glrp.nanocore.event;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventDefsTest {

    static final EventDef.Prizes DEFAULTS = new EventDef.Prizes(List.of(250L, 150L, 100L), 25, true, "gold_ingot", 0, 0, 20);
    static final Set<String> MILESTONES = Set.of("nether", "end", "elytra", "netherite", "nether_roof", "player_shops");

    static Map<String, EventDef> load(String path, List<String> warnings) {
        return EventDefs.parse(YamlConfiguration.loadConfiguration(new File(path)), DEFAULTS, MILESTONES::contains,
                id -> !id.startsWith("not_"), warnings::add);
    }

    @Test
    void bundledEventsLoad() {
        List<String> warnings = new ArrayList<>();
        Map<String, EventDef> all = load("src/main/resources/events.yml", warnings);
        assertEquals(List.of(), warnings);
        assertEquals(Set.of("mining_rush", "fishing_derby", "monster_hunt"), all.keySet());

        EventDef rush = all.get("mining_rush");
        assertEquals("Mining Rush", rush.name());
        assertFalse(rush.autoJoin());
        assertEquals(2, rush.phases().size());
        assertEquals(2 * 60_000L, rush.phase(0).end().timerMs());
        EventDef.Phase mine = rush.phase(1);
        assertEquals(10, mine.pointsFor("mine.diamond_ore"), "dotted stat names survive YAML nesting");
        assertEquals(15, mine.pointsFor("mine.ancient_debris"));
        assertEquals(0, mine.pointsFor("mine.stone"));
        assertEquals(0, mine.pointsFor("mine.total"));
        assertEquals(List.of(250L, 150L, 100L), rush.prizes().places(), "standard prizes when the event sets none");

        EventDef hunt = all.get("monster_hunt");
        assertEquals(13000, hunt.phase(0).end().mcTime());
        assertTrue(hunt.phase(0).end().skipIfPast());
        assertFalse(hunt.phase(1).end().skipIfPast(), "the hunt itself must not end at once when started at night");
        assertEquals(400, hunt.phase(1).goal());
        assertEquals(50, hunt.prizes().goalBonus());
        assertEquals(400, hunt.prizes().goal());
        assertEquals(List.of(250L, 150L, 100L), hunt.prizes().places());
    }

    @Test
    void everyPartOfTheFormatReads() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("purge.name", "Purge");
        y.set("purge.join", "auto");
        y.set("purge.late-join", false);
        y.set("purge.teams", 2);
        y.set("purge.safe-zones", List.of("town"));
        y.set("purge.weekdays", List.of("fri", "saturday", "blursday"));
        y.set("purge.winner", "last-standing");
        y.set("purge.unlocks", "nether");
        y.set("purge.phases", List.of(
                Map.of("name", "Countdown", "end", Map.of("mc-days", 5)),
                Map.of("name", "Fight", "lives", 3,
                        "end", Map.of("timer", "20m", "last-standing", true, "ready", "75%"),
                        "points", Map.of("kill.player", 1, "kill.*", 2),
                        "rules", Map.of("pvp", true, "drop-chance", 25, "hunger", false, "difficulty", "hard", "quest-multiplier", 1.5, "in-area-only", true),
                        "kit", Map.of("items", List.of("stone_sword", "cooked_beef 16", "not_real"), "gamemode", "adventure"))));
        y.createSection("purge.prizes", Map.of("places", List.of(100), "participation", 0));
        List<String> warnings = new ArrayList<>();
        EventDef d = EventDefs.parse(y, DEFAULTS, MILESTONES::contains, id -> !id.startsWith("not_"), warnings::add).get("purge");
        assertNotNull(d, warnings.toString());
        assertTrue(d.autoJoin());
        assertFalse(d.lateJoin());
        assertEquals(2, d.teams());
        assertEquals(List.of("town"), d.safeZones());
        assertEquals(2, d.weekdays().size());
        assertEquals(EventDef.Winner.LAST_STANDING, d.winner());
        assertEquals("nether", d.unlocks());
        assertEquals(5 * 24000, d.phase(0).end().mcTicks());
        EventDef.Phase fight = d.phase(1);
        assertEquals(3, fight.lives());
        assertTrue(fight.end().lastStanding());
        assertEquals("75%", fight.end().ready());
        assertEquals(1, fight.pointsFor("kill.player"), "an exact key beats a prefix");
        assertEquals(2, fight.pointsFor("kill.zombie"));
        assertEquals(0, fight.pointsFor("kill.total"));
        assertEquals(Boolean.TRUE, fight.rules().pvp());
        assertEquals(25, fight.rules().dropChance());
        assertEquals(Boolean.FALSE, fight.rules().hunger());
        assertEquals("hard", fight.rules().difficulty());
        assertEquals(1.5, fight.rules().questMultiplier());
        assertTrue(fight.rules().inAreaOnly());
        assertFalse(fight.rules().empty());
        assertTrue(d.phase(0).rules().empty());
        assertEquals(List.of("stone_sword", "cooked_beef 16"), fight.kit().items());
        assertEquals("adventure", fight.kit().gamemode());
        assertEquals(List.of(100L), d.prizes().places());
        assertEquals(0, d.prizes().participation());
        assertEquals(2, warnings.size(), "unknown weekday and kit item: " + warnings);
    }

    @Test
    void badEventsAreSkipped() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("no_phases.name", "x");
        y.set("bad_join.join", "maybe");
        y.set("bad_join.phases", List.of(Map.of("name", "a")));
        y.set("bad_timer.phases", List.of(Map.of("end", Map.of("timer", "soon"))));
        y.set("bad_milestone.unlocks", "moon");
        y.set("bad_milestone.phases", List.of(Map.of("name", "a")));
        y.set("good.phases", List.of(Map.of("name", "a", "end", Map.of("ready", "most"))));
        List<String> warnings = new ArrayList<>();
        Map<String, EventDef> all = EventDefs.parse(y, DEFAULTS, MILESTONES::contains, id -> true, warnings::add);
        assertEquals(Set.of("good"), all.keySet());
        assertEquals("all", all.get("good").phase(0).end().ready(), "a bad ready rule falls back to all");
        assertEquals(5, warnings.size(), warnings.toString());
        assertNull(all.get("good").unlocks());
    }

    @Test
    void weekdayNames() {
        assertEquals(java.time.DayOfWeek.FRIDAY, EventDefs.day("fri"));
        assertEquals(java.time.DayOfWeek.SUNDAY, EventDefs.day("Sunday"));
        assertNull(EventDefs.day("su"));
    }
}
