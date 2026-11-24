package io.github.glrp.nanocore.milestone;

import io.github.glrp.nanocore.gui.Menu;
import io.github.glrp.nanocore.text.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /milestones: the server's progress path, locked and unlocked. */
public final class MilestoneMenu {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(ZoneId.systemDefault());

    private MilestoneMenu() {
    }

    public static void open(Player p, MilestoneService service, Messages messages) {
        Menu menu = new Menu(3, messages.msg("milestones.menu-title"));
        Milestone[] all = Milestone.values();
        int start = (9 - all.length) / 2;
        for (int i = 0; i < all.length; i++) {
            Milestone m = all[i];
            MilestoneService.State s = service.state(m);
            List<Component> lore = new ArrayList<>();
            lore.add(messages.item(s.unlocked() ? "milestones.unlocked" : "milestones.locked"));
            if (s.unlocked() && s.changedAt() > 0) {
                lore.add(messages.item("milestones.unlocked-on", "date", DATE.format(Instant.ofEpochMilli(s.changedAt()))));
            } else {
                lore.add(messages.item("milestones.hints." + m.id()).colorIfAbsent(NamedTextColor.GRAY));
            }
            Material icon = s.unlocked() ? Material.matchMaterial(m.icon()) : Material.GRAY_DYE;
            if (icon == null) icon = Material.PAPER;
            Component name = Component.text(service.displayName(m), s.unlocked() ? NamedTextColor.GOLD : NamedTextColor.GRAY);
            menu.set(9 + start + i, Menu.item(icon, name, lore));
        }
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }
}
