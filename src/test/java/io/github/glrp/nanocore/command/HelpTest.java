package io.github.glrp.nanocore.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpTest {

    @Test
    void linesAndHeadings() {
        Help.Line cmd = Help.Line.parse("/pay <player> <amount> | give coins to a player");
        assertEquals("/pay <player> <amount>", cmd.usage());
        assertEquals("give coins to a player", cmd.text());
        assertNull(Help.Line.parse("Money").usage());
    }

    @Test
    void pagesNeverEndOnAHeading() {
        List<Help.Line> lines = new ArrayList<>();
        for (int i = 0; i < 8; i++) lines.add(new Help.Line("/a" + i, "x"));
        lines.add(new Help.Line(null, "Heading"));
        lines.add(new Help.Line("/b", "x"));
        List<List<Help.Line>> pages = Help.pages(lines);
        assertEquals(2, pages.size());
        assertEquals(8, pages.get(0).size());
        assertNull(pages.get(1).getFirst().usage(), "the heading starts the next page");
        for (List<Help.Line> p : pages) assertTrue(p.size() <= Help.PAGE_LINES);
    }

    @Test
    void bundledHelpParsesAndFitsInPages() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File("src/main/resources/messages.yml"));
        List<Help.Line> lines = new ArrayList<>();
        for (String raw : y.getStringList("help.player")) lines.add(Help.Line.parse(raw));
        for (String raw : y.getStringList("help.admin")) lines.add(Help.Line.parse(raw));
        assertTrue(lines.size() > 15);
        for (Help.Line l : lines) if (l.usage() != null) assertTrue(l.usage().startsWith("/"), l.usage());
        assertNotNull(y.getString("help.header"));
    }

    @Test
    void usageFillStopsAtTheFirstArgument() {
        assertEquals("/pay ", Usage.fill("/pay <player> <amount>"));
        assertEquals("/nc eco give ", Usage.fill("/nc eco give <player> <amount>"));
        assertEquals("/baltop", Usage.fill("/baltop"));
    }

    @Test
    void usageListsEveryWayToFinishACommand() {
        LiteralCommandNode<CommandSourceStack> root = LiteralArgumentBuilder.<CommandSourceStack>literal("eco")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("give")
                        .then(RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("amount", IntegerArgumentType.integer()).executes(c -> 1)))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("secret").requires(s -> false).executes(c -> 1))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("list").executes(c -> 1))
                .build();
        assertEquals(List.of("/nc eco give <amount>", "/nc eco list"), Usage.completions(root, "/nc eco", null));
    }
}
