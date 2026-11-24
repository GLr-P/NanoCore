package io.github.glrp.nanocore.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** A yes/no window for actions that can't be undone (deleting, locking, announcing). */
public final class Confirm {

    private Confirm() {
    }

    public static void open(io.github.glrp.nanocore.NanoCorePlugin plugin, Player p, Component question, List<Component> details, Runnable yes, Runnable no) {
        Menu menu = new Menu(3, question);
        menu.set(4, Menu.item(Material.PAPER, question, details));
        menu.set(11, Menu.item(Material.LIME_CONCRETE, plugin.messages().item("general.confirm-yes"), details),
                e -> plugin.getServer().getScheduler().runTask(plugin, yes));
        menu.set(15, Menu.item(Material.RED_CONCRETE, plugin.messages().item("general.confirm-no"), List.of()),
                e -> plugin.getServer().getScheduler().runTask(plugin, no));
        menu.frame().open(p);
    }
}
