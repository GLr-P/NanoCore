package io.github.glrp.nanocore.admin;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.gui.Menu;
import io.github.glrp.nanocore.milestone.MilestoneMenu;
import io.github.glrp.nanocore.pshop.PlayerShopMenus;
import io.github.glrp.nanocore.quest.QuestMenu;
import io.github.glrp.nanocore.shop.SellMenu;
import io.github.glrp.nanocore.text.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/** /nc for everyone else: one window with everything a player can use. */
public final class PlayerMenu {

    private PlayerMenu() {
    }

    private static void later(NanoCorePlugin plugin, Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }

    /** The player guide as a book on screen (nothing is put in the inventory). */
    public static void guide(NanoCorePlugin plugin, Player p) {
        List<net.kyori.adventure.text.Component> pages = new java.util.ArrayList<>();
        for (String page : plugin.messages().rawList("guide.pages")) pages.add(Messages.parse(page));
        p.openBook(net.kyori.adventure.inventory.Book.book(plugin.messages().msg("guide.title"), plugin.messages().msg("guide.author"), pages));
    }

    public static void open(NanoCorePlugin plugin, Player p) {
        Messages m = plugin.messages();
        Menu menu = new Menu(3, m.msg("panel.player-menu-title"));
        ItemStack me = Menu.item(Material.PLAYER_HEAD, m.item("panel.player-name", "player", p.getName()),
                List.of(m.item("panel.player-balance", "balance", plugin.economy().format(plugin.economy().balance(p.getUniqueId())))));
        me.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(p));
        menu.set(4, me);
        menu.set(10, Menu.item(Material.WRITABLE_BOOK, m.item("panel.pm-quests"), List.of(m.item("panel.pm-quests-lore"))), e -> later(plugin, () ->
                QuestMenu.open(plugin, p, plugin.questBoards().nearBoard(p, plugin.quests().settings().boardReach()))));
        menu.set(11, Menu.item(Material.EMERALD, m.item("panel.pm-sell"), List.of(m.item("panel.pm-sell-lore"))), e -> later(plugin, () -> SellMenu.open(plugin, p)));
        menu.set(12, Menu.item(Material.CHEST, m.item("panel.pm-shops"), List.of(m.item("panel.pm-shops-lore"))), e -> later(plugin, () -> PlayerShopMenus.main(plugin, p)));
        menu.set(14, Menu.item(Material.CLOCK, m.item("panel.pm-event"), List.of(m.item("panel.pm-event-lore"))), e -> later(plugin, () -> {
            p.closeInventory();
            p.performCommand("event");
        }));
        menu.set(15, Menu.item(Material.BEACON, m.item("panel.pm-milestones"), List.of(m.item("panel.pm-milestones-lore"))),
                e -> later(plugin, () -> MilestoneMenu.open(p, plugin.milestones(), m)));
        menu.set(16, Menu.item(Material.BOOK, m.item("panel.pm-stats"), List.of(m.item("panel.pm-stats-lore"))), e -> later(plugin, () -> {
            p.closeInventory();
            p.performCommand("stats");
        }));
        menu.set(13, Menu.item(Material.KNOWLEDGE_BOOK, m.item("panel.pm-guide"), List.of(m.item("panel.pm-guide-lore"))), e -> later(plugin, () -> {
            p.closeInventory();
            guide(plugin, p);
        }));
        if (p.hasPermission("nanocore.admin")) {
            menu.set(22, Menu.item(Material.COMPARATOR, m.item("panel.title"), List.of()), e -> later(plugin, () -> ControlPanel.open(plugin, p)));
        }
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }
}
