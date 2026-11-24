package io.github.glrp.nanocore.command;

import io.github.glrp.nanocore.text.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * /nc help: every command with a short description, in pages that fit the chat box. Lines in messages.yml are
 * "usage | description"; a line without "|" is a section heading. Clicking a command types it into the chat box.
 */
public final class Help {

    static final int PAGE_LINES = 9;

    private Help() {
    }

    /** One help line: a heading (usage null) or a command with its description. */
    record Line(String usage, String text) {
        static Line parse(String raw) {
            int bar = raw.indexOf('|');
            if (bar < 0) return new Line(null, raw.trim());
            return new Line(raw.substring(0, bar).trim(), raw.substring(bar + 1).trim());
        }
    }

    /** The lines split into pages; a heading never ends a page (it moves to the next one). */
    static List<List<Line>> pages(List<Line> lines) {
        List<List<Line>> pages = new ArrayList<>();
        List<Line> page = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            boolean headingAtEnd = l.usage() == null && page.size() == PAGE_LINES - 1;
            if (page.size() >= PAGE_LINES || (headingAtEnd && !page.isEmpty())) {
                pages.add(page);
                page = new ArrayList<>();
            }
            page.add(l);
        }
        if (!page.isEmpty()) pages.add(page);
        return pages;
    }

    public static void show(CommandSender to, Messages m, boolean admin, int pageNumber) {
        List<Line> lines = new ArrayList<>();
        for (String raw : m.rawList("help.player")) lines.add(Line.parse(raw));
        if (admin) for (String raw : m.rawList("help.admin")) lines.add(Line.parse(raw));
        List<List<Line>> pages = pages(lines);
        int page = Math.max(1, Math.min(pageNumber, pages.size()));
        to.sendMessage(m.msg("help.header", "page", String.valueOf(page), "pages", String.valueOf(pages.size())));
        for (Line l : pages.get(page - 1)) {
            if (l.usage() == null) {
                to.sendMessage(m.msg("help.section", "name", l.text()));
                continue;
            }
            Component usage = Component.text(l.usage(), NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.suggestCommand(Usage.fill(l.usage())))
                    .hoverEvent(HoverEvent.showText(m.msg("general.usage-hover")));
            to.sendMessage(m.rich("help.line", "usage", usage, "what", l.text()));
        }
        Component nav = Component.empty();
        if (page > 1) nav = nav.append(m.msg("help.previous").clickEvent(ClickEvent.runCommand("/nc help " + (page - 1)))).append(Component.space());
        if (page < pages.size()) nav = nav.append(m.msg("help.next").clickEvent(ClickEvent.runCommand("/nc help " + (page + 1))));
        if (pages.size() > 1) to.sendMessage(nav);
    }
}
