package io.github.glrp.nanocore.admin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every message the code asks for exists in messages.yml (a missing one would show its key to players). */
class MessageKeysTest {

    static final YamlConfiguration MESSAGES = YamlConfiguration.loadConfiguration(new File("src/main/resources/messages.yml"));
    static final YamlConfiguration CONFIG = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
    static final Pattern KEY = Pattern.compile("\"((?:panel|events|general|help|shop|quests|pshop|economy|milestones|areas|stats|guide|welcome)\\.[a-z0-9_.\\-]+)\"");

    @Test
    void everyKeyInTheCodeExists() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = KEY.matcher(Files.readString(f));
                while (m.find()) {
                    String key = m.group(1);
                    if (key.endsWith(".") || key.endsWith("-") || key.endsWith(".yml")) continue; // built further in code, or a file name
                    if (Set.of("quests.day", "quests.week", "quests.community-paid").contains(key)) continue; // saved state, not text
                    if (CONFIG.contains(key)) continue; // a config.yml path, not a message
                    if (!MESSAGES.contains(key)) missing.add(f.getFileName() + ": " + key);
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void everySettingHasItsText() {
        List<String> missing = new ArrayList<>();
        for (ControlPanel.Group g : ControlPanel.GROUPS) {
            for (String k : List.of("panel.group." + g.id(), "panel.group." + g.id() + "-lore")) if (!MESSAGES.contains(k)) missing.add(k);
            for (ControlPanel.Setting s : g.settings()) {
                List<String> keys = new ArrayList<>(List.of("panel.setting." + s.id(), "panel.setting-help." + s.id()));
                if (s.kind() == ControlPanel.Kind.TEXT) keys.addAll(List.of("panel.prompt." + s.id(), "panel.bad." + s.id()));
                for (String k : keys) if (!MESSAGES.contains(k)) missing.add(k);
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void noKeyIsWrittenTwice() throws IOException {
        // YAML keeps the last of two equal keys without a word, so a duplicate would silently hide text.
        List<String> stack = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> dupes = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("src/main/resources/messages.yml"))) {
            if (line.isBlank() || line.trim().startsWith("#") || line.trim().startsWith("-")) continue;
            Matcher m = Pattern.compile("^( *)(\"[^\"]+\"|[^:\" ]+):").matcher(line);
            if (!m.find()) continue;
            int depth = m.group(1).length() / 2;
            while (stack.size() > depth) stack.removeLast();
            stack.add(m.group(2).replace("\"", ""));
            String path = String.join(".", stack);
            if (!seen.add(path)) dupes.add(path);
        }
        assertEquals(List.of(), dupes);
    }

    @Test
    void typedAnswers() {
        assertEquals(LocalTime.of(19, 0), ControlPanel.parseTime("19:00"));
        assertEquals(LocalTime.of(19, 0), ControlPanel.parseTime("7pm"));
        assertEquals(LocalTime.of(7, 30), ControlPanel.parseTime("7:30 am"));
        assertEquals(LocalTime.of(0, 15), ControlPanel.parseTime("12:15am"));
        assertEquals(LocalTime.of(9, 5), ControlPanel.parseTime("0905"));
        assertNull(ControlPanel.parseTime("25:00"));
        assertNull(ControlPanel.parseTime("soon"));

        assertEquals("America/Vancouver", ControlPanel.parseZone("america/vancouver"));
        assertEquals("America/New_York", ControlPanel.parseZone("EST"));
        assertNull(ControlPanel.parseZone("Narnia"));

        assertEquals(List.of(10, 1), ControlPanel.parseText("reminders", "1, 10"));
        assertNull(ControlPanel.parseText("reminders", "soon"));
        assertNull(ControlPanel.parseText("reminders", ""));
        assertEquals("", ControlPanel.parseText("discord-webhook", "off"));
        assertNull(ControlPanel.parseText("discord-webhook", "https://example.com"));
        assertEquals("https://discord.com/api/webhooks/1/abc", ControlPanel.parseText("discord-webhook", " https://discord.com/api/webhooks/1/abc "));
        assertNull(ControlPanel.parseText("currency-plural", "<red>coins"));

        assertTrue(java.util.Arrays.equals(new long[]{40, 80}, ControlPanel.parseRange("80-40")));
        assertTrue(java.util.Arrays.equals(new long[]{5, 5}, ControlPanel.parseRange("5")));
    }
}
