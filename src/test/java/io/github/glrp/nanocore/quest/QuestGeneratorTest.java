package io.github.glrp.nanocore.quest;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestGeneratorTest {

    static final Map<Tier, Integer> COUNTS = Map.of(Tier.EASY, 5, Tier.MEDIUM, 4, Tier.HARD, 2);
    static final Map<Tier, Range> REWARDS = Map.of(Tier.EASY, new Range(40, 80), Tier.MEDIUM, new Range(120, 200), Tier.HARD, new Range(300, 500));

    /** The quests.yml that ships with the plugin. Every item counts as valid here. */
    static Map<String, QuestTemplate> bundled(List<String> warnings) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(new File("src/main/resources/quests.yml"));
        return QuestTemplates.parse(yml, id -> !id.isBlank(),
                id -> Set.of("nether", "end", "elytra", "netherite", "nether_roof", "player_shops").contains(id), warnings::add);
    }

    static QuestTemplate template(String id, String category, Map<Tier, Range> amounts, String requires) {
        return new QuestTemplate(id, category, id, "paper", QuestTemplate.Objective.COUNT, List.of("mine." + id), 1, amounts, requires, 1, List.of());
    }

    @Test
    void switchedOffKindsAreNeverPicked() {
        QuestTemplate on = template("on", "a", Map.of(Tier.EASY, new Range(1, 2)), null);
        QuestTemplate off = new QuestTemplate("off", "b", "off", "paper", QuestTemplate.Objective.COUNT, List.of("mine.off"), 1,
                Map.of(Tier.EASY, new Range(1, 2)), null, 0, List.of());
        Random rng = new Random(3);
        for (int i = 0; i < 100; i++) {
            var c = QuestGenerator.pick(List.of(on, off), Tier.EASY, m -> true, rng, Set.of(), Set.of(), Set.of());
            assertEquals("on", c.template().id());
        }
        assertEquals(null, QuestGenerator.pick(List.of(off), Tier.EASY, m -> true, rng, Set.of(), Set.of(), Set.of()), "only off kinds: nothing, no crash");
    }

    @Test
    void rangesParse() {
        assertEquals(new Range(400, 600), Range.parse("400-600"));
        assertEquals(new Range(500, 500), Range.parse("500"));
        assertEquals(new Range(1000, 1500), Range.parse("1,000 - 1,500"));
        assertThrows(IllegalArgumentException.class, () -> Range.parse("lots"));
        assertThrows(IllegalArgumentException.class, () -> Range.parse("600-400"));
    }

    @Test
    void amountsStayInRangeAndAreRound() {
        Random rng = new Random(1);
        for (int i = 0; i < 500; i++) {
            long a = QuestGenerator.pickAmount(new Range(1200, 1800), rng);
            assertTrue(a >= 1200 && a <= 1800, "amount " + a);
            assertEquals(0, a % 50, "1,200-1,800 rounds to 50s: " + a);
            long b = QuestGenerator.pickAmount(new Range(4, 5), rng);
            assertTrue(b == 4 || b == 5);
        }
        assertEquals(256, QuestGenerator.pickAmount(new Range(256, 256), rng));
    }

    @Test
    void rewardScalesWithAmount() {
        Range amounts = new Range(400, 600);
        Range rewards = new Range(40, 80);
        assertEquals(40, QuestGenerator.reward(400, amounts, rewards));
        assertEquals(80, QuestGenerator.reward(600, amounts, rewards));
        assertEquals(60, QuestGenerator.reward(500, amounts, rewards));
        assertTrue(QuestGenerator.reward(550, amounts, rewards) > QuestGenerator.reward(450, amounts, rewards));
        // A fixed amount pays the middle; anything outside the range is clamped.
        assertEquals(60, QuestGenerator.reward(256, new Range(256, 256), rewards));
        assertEquals(40, QuestGenerator.reward(3, amounts, rewards));
        assertEquals(0, QuestGenerator.reward(437, amounts, rewards) % 5);
    }

    @Test
    void bundledQuestsAllLoad() {
        List<String> warnings = new ArrayList<>();
        Map<String, QuestTemplate> all = bundled(warnings);
        assertEquals(List.of(), warnings);
        assertTrue(all.size() >= 40, "only " + all.size() + " quest kinds");
        assertEquals(QuestTemplate.Objective.DELIVER, all.get("deliver_cobblestone").objective());
        assertEquals(List.of("cobblestone"), all.get("deliver_cobblestone").targets());
        assertEquals("Chop dark oak logs", all.get("chop_logs").resolve("dark_oak").name());
        assertEquals(List.of("mine.dark_oak_log", "mine.dark_oak_wood"), all.get("chop_logs").resolve("dark_oak").targets());
        assertEquals(QuestTemplate.Objective.DELIVER, all.get("deliver_logs").objective());
        assertEquals("spruce_log", all.get("deliver_logs").resolve("spruce").targets().getFirst());
        assertEquals(100, all.get("swim_far").scale());
        assertEquals("nether", all.get("kill_blazes").requires());
        assertEquals(QuestTemplate.Objective.STRUCTURE, all.get("find_structure").objective());
        assertTrue(all.get("dig_dirt").targets().contains("mine.grass_block"));
    }

    @Test
    void badEntriesAreSkippedWithAWarning() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("good.count", List.of("mine.stone"));
        yml.set("good.easy", "10-20");
        yml.set("no_amounts.count", List.of("mine.stone"));
        yml.set("bad_item.deliver", "not_an_item");
        yml.set("bad_item.easy", 5);
        yml.set("bad_milestone.count", List.of("mine.stone"));
        yml.set("bad_milestone.easy", 5);
        yml.set("bad_milestone.requires", "moon");
        List<String> warnings = new ArrayList<>();
        Map<String, QuestTemplate> t = QuestTemplates.parse(yml, id -> !id.startsWith("not_"), id -> id.equals("nether"), warnings::add);
        assertEquals(Set.of("good"), t.keySet());
        assertEquals(3, warnings.size(), warnings.toString());
    }

    @Test
    void dailyBoardFollowsTheRules() {
        Map<String, QuestTemplate> all = bundled(new ArrayList<>());
        for (int seed = 0; seed < 200; seed++) {
            List<Quest> board = QuestGenerator.dailyBoard(all.values(), COUNTS, REWARDS, m -> false, new Random(seed), "2026-12-05");
            assertEquals(11, board.size(), "seed " + seed);
            Set<String> ids = new HashSet<>();
            Set<String> templates = new HashSet<>();
            Map<Tier, Set<String>> tierCats = new EnumMap<>(Tier.class);
            for (Quest q : board) {
                assertTrue(ids.add(q.id()), "duplicate id " + q.id());
                assertTrue(templates.add(q.template()), "template twice on one board: " + q.template());
                QuestTemplate t = all.get(q.template());
                assertNull(t.requires(), "locked quest on the board: " + q.template());
                assertTrue(t.amounts().get(q.tier()).contains(q.amount()), q.toString());
                assertTrue(REWARDS.get(q.tier()).contains(q.reward()), q.toString());
                assertTrue(tierCats.computeIfAbsent(q.tier(), k -> new HashSet<>()).add(t.category()),
                        "two " + t.category() + " quests in " + q.tier() + " (seed " + seed + ")");
                if (!t.variants().isEmpty()) assertNotNull(t.variant(q.variant()), q.toString());
            }
            assertEquals(5, board.stream().filter(q -> q.tier() == Tier.EASY).count());
            assertEquals("2026-12-05/easy-1", board.getFirst().id());
        }
    }

    @Test
    void lockedQuestsAppearOnlyAfterTheirMilestone() {
        List<QuestTemplate> list = List.of(
                template("a", "x", Map.of(Tier.HARD, new Range(1, 1)), null),
                template("b", "y", Map.of(Tier.HARD, new Range(1, 1)), "nether"));
        Map<Tier, Integer> hardOnly = Map.of(Tier.HARD, 2);
        assertEquals(1, QuestGenerator.dailyBoard(list, hardOnly, REWARDS, m -> false, new Random(3), "d").size());
        assertEquals(2, QuestGenerator.dailyBoard(list, hardOnly, REWARDS, m -> m.equals("nether"), new Random(3), "d").size());
    }

    @Test
    void categoriesRepeatOnlyWhenNothingElseFits() {
        List<QuestTemplate> list = List.of(
                template("a", "mining", Map.of(Tier.EASY, new Range(1, 1)), null),
                template("b", "mining", Map.of(Tier.EASY, new Range(1, 1)), null),
                template("c", "fishing", Map.of(Tier.EASY, new Range(1, 1)), null));
        for (int seed = 0; seed < 50; seed++) {
            List<Quest> two = QuestGenerator.dailyBoard(list, Map.of(Tier.EASY, 2), REWARDS, m -> false, new Random(seed), "d");
            assertTrue(two.stream().anyMatch(q -> q.template().equals("c")), "fishing should be picked before a second mining quest");
            assertEquals(3, QuestGenerator.dailyBoard(list, Map.of(Tier.EASY, 3), REWARDS, m -> false, new Random(seed), "d").size());
        }
    }

    @Test
    void communityQuestUsesThePotAndSkipsPlaceQuests() {
        Map<String, QuestTemplate> all = bundled(new ArrayList<>());
        for (int seed = 0; seed < 100; seed++) {
            Quest c = QuestGenerator.communityQuest(all.values(), m -> false, new Random(seed), "week-2026-11-30", 1500);
            assertNotNull(c);
            assertEquals(Tier.COMMUNITY, c.tier());
            assertEquals(1500, c.reward());
            assertEquals("week-2026-11-30/community", c.id());
            QuestTemplate.Objective o = all.get(c.template()).objective();
            assertFalse(o == QuestTemplate.Objective.BIOME || o == QuestTemplate.Objective.STRUCTURE);
        }
    }

    @Test
    void replacementIsADifferentQuestOfTheSameTier() {
        Map<String, QuestTemplate> all = bundled(new ArrayList<>());
        List<Quest> board = QuestGenerator.dailyBoard(all.values(), COUNTS, REWARDS, m -> false, new Random(9), "d");
        Set<String> onBoard = new HashSet<>();
        board.forEach(q -> onBoard.add(q.template()));
        for (int seed = 0; seed < 100; seed++) {
            Quest r = QuestGenerator.replacement(all.values(), Tier.MEDIUM, REWARDS.get(Tier.MEDIUM), m -> false, new Random(seed), onBoard, "d/r-1");
            assertNotNull(r);
            assertEquals(Tier.MEDIUM, r.tier());
            assertFalse(onBoard.contains(r.template()));
        }
        assertNull(QuestGenerator.replacement(List.of(template("a", "x", Map.of(Tier.EASY, new Range(1, 1)), null)),
                Tier.EASY, REWARDS.get(Tier.EASY), m -> false, new Random(1), Set.of("a"), "id"));
    }
}
