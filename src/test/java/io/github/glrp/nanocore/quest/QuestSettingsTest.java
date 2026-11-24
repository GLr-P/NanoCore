package io.github.glrp.nanocore.quest;

import io.github.glrp.nanocore.command.StatsCommands;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuestSettingsTest {

    @Test
    void bundledConfigMatchesTheDesign() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        List<String> warnings = new ArrayList<>();
        QuestSettings s = QuestSettings.from(yml.getConfigurationSection("quests"), warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals(5, s.counts().get(Tier.EASY));
        assertEquals(4, s.counts().get(Tier.MEDIUM));
        assertEquals(2, s.counts().get(Tier.HARD));
        assertEquals(new Range(300, 500), s.rewards().get(Tier.HARD));
        assertEquals(3, s.maxActive());
        assertEquals(25, s.rerollCost());
        assertEquals(LocalTime.of(6, 0), s.refreshTime());
        assertEquals(1500, s.communityPot());
        assertEquals(DayOfWeek.MONDAY, s.communityDay());
    }

    @Test
    void badValuesFallBack() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("refresh-time", "breakfast");
        yml.set("rewards.easy", "cheap");
        yml.set("community.day", "someday");
        List<String> warnings = new ArrayList<>();
        QuestSettings s = QuestSettings.from(yml, warnings::add);
        assertEquals(3, warnings.size());
        assertEquals(LocalTime.of(6, 0), s.refreshTime());
        assertEquals(new Range(40, 80), s.rewards().get(Tier.EASY));
    }

    @Test
    void statsReadWell() {
        assertEquals("1,234", StatsCommands.format(StatsCommands.Kind.COUNT, 1234, "blocks"));
        assertEquals("5,600 blocks", StatsCommands.format(StatsCommands.Kind.DISTANCE, 560_000, "blocks"));
        assertEquals("12h 3m", StatsCommands.format(StatsCommands.Kind.TIME, 12 * 3600 + 3 * 60 + 20, "blocks"));
        assertEquals("45m", StatsCommands.format(StatsCommands.Kind.TIME, 45 * 60, "blocks"));
    }
}
