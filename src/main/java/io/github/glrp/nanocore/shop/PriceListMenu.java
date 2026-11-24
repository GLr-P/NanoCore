package io.github.glrp.nanocore.shop;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.gui.Menu;
import io.github.glrp.nanocore.milestone.Milestone;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** What the admin shop buys and for how much. Read-only; the back button returns to /sell. */
public final class PriceListMenu {

    private static final int PER_PAGE = 45;

    private PriceListMenu() {
    }

    public static void open(NanoCorePlugin plugin, Player p) {
        open(plugin, p, 0);
    }

    private static void open(NanoCorePlugin plugin, Player p, int page) {
        var msg = plugin.messages();
        List<SellPrice> all = new ArrayList<>(plugin.shop().prices().values());
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.min(page, pages - 1);
        Menu menu = new Menu(6, msg.msg("shop.prices-title"));
        for (int i = 0; i < PER_PAGE && current * PER_PAGE + i < all.size(); i++) {
            SellPrice price = all.get(current * PER_PAGE + i);
            Material m = Material.matchMaterial(price.item());
            if (m == null) continue;
            boolean locked = price.requires() != null && !plugin.shop().unlocked(price.requires());
            List<Component> lore = new ArrayList<>();
            lore.add(msg.item("shop.price-each", "price", plugin.economy().format(price.price())));
            if (price.requires() != null) {
                String name = Milestone.byId(price.requires()).map(plugin.milestones()::displayName).orElse(price.requires());
                lore.add(msg.item(locked ? "shop.price-requires" : "shop.price-unlocked", "milestone", name));
            }
            Component title = SellMenu.name(price.item()).color(locked ? NamedTextColor.GRAY : NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false);
            menu.set(i, Menu.item(locked ? Material.GRAY_DYE : m, title, lore));
        }
        menu.set(49, Menu.item(Material.ARROW, msg.item("shop.back"), List.of()), e -> later(plugin, () -> SellMenu.open(plugin, p)));
        if (current > 0) menu.set(45, Menu.item(Material.PAPER, msg.item("shop.page-back"), List.of()), e -> later(plugin, () -> open(plugin, p, current - 1)));
        if (current < pages - 1) menu.set(53, Menu.item(Material.PAPER, msg.item("shop.page-next"), List.of()), e -> later(plugin, () -> open(plugin, p, current + 1)));
        menu.frame().open(p);
    }

    /** Opening another window from inside a click is done on the next tick. */
    private static void later(NanoCorePlugin plugin, Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }
}
