package io.github.glrp.nanocore.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;

/** A yes/no window for actions that can't be undone (deleting, locking, announcing). */
public final class Confirm {

    private Confirm() {
    }

    public static void open(Plugin plugin, Player p, Component question, List<Component> details, Runnable yes, Runnable no) {
        Menu menu = new Menu(3, question);
        menu.set(4, Menu.item(Material.PAPER, question, details));
        menu.set(11, Menu.item(Material.LIME_CONCRETE, Component.text("Yes", NamedTextColor.GREEN), details),
                e -> plugin.getServer().getScheduler().runTask(plugin, yes));
        menu.set(15, Menu.item(Material.RED_CONCRETE, Component.text("No, go back", NamedTextColor.RED), List.of()),
                e -> plugin.getServer().getScheduler().runTask(plugin, no));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }
}
