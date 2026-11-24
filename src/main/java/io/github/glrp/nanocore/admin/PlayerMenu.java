package io.github.glrp.nanocore.admin;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.economy.Accounts;
import io.github.glrp.nanocore.gui.ChatPrompt;
import io.github.glrp.nanocore.gui.Confirm;
import io.github.glrp.nanocore.gui.Menu;
import io.github.glrp.nanocore.milestone.Milestone;
import io.github.glrp.nanocore.milestone.MilestoneMenu;
import io.github.glrp.nanocore.pshop.PlayerShopMenus;
import io.github.glrp.nanocore.quest.QuestMenu;
import io.github.glrp.nanocore.shop.SellMenu;
import io.github.glrp.nanocore.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** /nc for everyone: one window with everything a player can do, each button saying what it is and what's going on. */
public final class PlayerMenu {

    private PlayerMenu() {
    }

    private static void later(NanoCorePlugin plugin, Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }

    /** The player guide as a book on screen (nothing is put in the inventory). */
    public static void guide(NanoCorePlugin plugin, Player p) {
        List<Component> pages = new ArrayList<>();
        for (String page : plugin.messages().rawList("guide.pages")) pages.add(Messages.parse(page));
        p.openBook(net.kyori.adventure.inventory.Book.book(plugin.messages().msg("guide.title"), plugin.messages().msg("guide.author"), pages));
    }

    /** Lore for a button: what it is, how things stand right now, and what a click does. */
    private static ItemStack button(Messages m, Material icon, String key, List<Component> status, String click) {
        List<Component> lore = new ArrayList<>(m.lines("panel." + key + "-lore"));
        lore.addAll(status);
        return Menu.button(icon, m.item("panel." + key), lore, m.item(click));
    }

    private static void chat(NanoCorePlugin plugin, Player p, String command) {
        later(plugin, () -> {
            p.closeInventory();
            p.performCommand(command);
        });
    }

    public static void open(NanoCorePlugin plugin, Player p) {
        Messages m = plugin.messages();
        var eco = plugin.economy();
        UUID id = p.getUniqueId();
        Menu menu = new Menu(5, m.msg("panel.player-menu-title"));

        ItemStack me = Menu.item(Material.PLAYER_HEAD, m.item("panel.player-name", "player", p.getName()), List.of(
                m.item("panel.player-balance", "balance", eco.format(eco.balance(id))),
                Component.empty(), m.item("panel.pm-help-hint")));
        me.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(p));
        menu.set(4, me);

        int active = plugin.quests().activeCount(id);
        menu.set(20, button(m, Material.WRITABLE_BOOK, "pm-quests", List.of(m.item("panel.pm-quests-status", "active", String.valueOf(active),
                "max", String.valueOf(plugin.quests().settings().maxActive()))), "panel.click-open"), e -> later(plugin, () ->
                QuestMenu.open(plugin, p, plugin.questBoards().nearBoard(p, plugin.quests().settings().boardReach()))));

        long left = Math.max(0, plugin.shop().dailyLimit() - plugin.shop().earnedToday(id));
        menu.set(21, button(m, Material.EMERALD, "pm-sell", List.of(m.item("panel.pm-sell-status", "left", eco.format(left))), "panel.click-open"),
                e -> later(plugin, () -> SellMenu.open(plugin, p)));

        boolean shopsOpen = plugin.milestones().isUnlocked(Milestone.PLAYER_SHOPS);
        menu.set(22, button(m, Material.CHEST, "pm-shops", List.of(shopsOpen
                        ? m.item("panel.pm-shops-status", "count", String.valueOf(plugin.playerShops().owned(id).size()))
                        : m.item("milestones.blocked.player_shops")), "panel.click-open"),
                e -> later(plugin, () -> PlayerShopMenus.main(plugin, p)));

        var ev = plugin.events();
        Component eventStatus = ev.running()
                ? m.item(ev.participant(id) ? "panel.pm-event-in" : "panel.pm-event-on", "event", ev.current().name())
                : ev.nextAuto().map(n -> m.item("panel.pm-event-next", "when", n)).orElse(m.item("events.none"));
        menu.set(23, button(m, Material.CLOCK, "pm-event", List.of(eventStatus),
                ev.running() && !ev.participant(id) ? "panel.click-join" : "panel.click-chat"), e -> {
            if (ev.running() && !ev.participant(id)) chat(plugin, p, "event join");
            else chat(plugin, p, "event");
        });

        long unlocked = java.util.Arrays.stream(Milestone.values()).filter(plugin.milestones()::isUnlocked).count();
        menu.set(24, button(m, Material.BEACON, "pm-milestones", List.of(m.item("panel.pm-milestones-status",
                        "count", String.valueOf(unlocked), "total", String.valueOf(Milestone.values().length))), "panel.click-open"),
                e -> later(plugin, () -> MilestoneMenu.open(p, plugin.milestones(), m)));

        menu.set(29, button(m, Material.GOLD_INGOT, "pm-pay", List.of(), "panel.click-open"), e -> later(plugin, () -> pay(plugin, p, 0)));
        menu.set(30, button(m, Material.GOLDEN_HELMET, "pm-top", List.of(), "panel.click-chat"), e -> chat(plugin, p, "baltop"));
        menu.set(31, button(m, Material.BOOK, "pm-stats", List.of(), "panel.click-chat"), e -> chat(plugin, p, "stats"));
        menu.set(32, button(m, Material.KNOWLEDGE_BOOK, "pm-guide", List.of(), "panel.click-read"), e -> later(plugin, () -> {
            p.closeInventory();
            guide(plugin, p);
        }));
        menu.set(33, button(m, Material.OAK_SIGN, "pm-help", List.of(), "panel.click-chat"), e -> chat(plugin, p, "nc help"));

        if (p.hasPermission("nanocore.admin")) {
            menu.set(36, Menu.button(Material.COMPARATOR, m.item("panel.title"), List.of(), m.item("panel.click-open")),
                    e -> later(plugin, () -> ControlPanel.open(plugin, p)));
        }
        menu.set(40, Menu.item(Material.BARRIER, m.item("panel.close"), List.of()), e -> later(plugin, p::closeInventory));
        menu.frame().open(p);
    }

    // --- paying another player ---

    /** Everyone who has played here, online players first: pick who to pay. */
    static void pay(NanoCorePlugin plugin, Player p, int page) {
        Messages m = plugin.messages();
        List<Accounts.Entry> all = new ArrayList<>();
        for (Accounts.Entry a : plugin.economy().top()) if (!a.id().equals(p.getUniqueId())) all.add(a);
        all.sort((a, b) -> Boolean.compare(plugin.getServer().getPlayer(b.id()) != null, plugin.getServer().getPlayer(a.id()) != null));
        int per = 45;
        int pages = Math.max(1, (all.size() + per - 1) / per);
        int current = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(6, m.msg("panel.pay-title"));
        for (int i = 0; i < per && current * per + i < all.size(); i++) {
            Accounts.Entry a = all.get(current * per + i);
            boolean online = plugin.getServer().getPlayer(a.id()) != null;
            ItemStack head = Menu.button(Material.PLAYER_HEAD, m.item("panel.player-name", "player", a.name()),
                    List.of(m.item(online ? "panel.online" : "panel.offline")), m.item("panel.pay-click"));
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(plugin.getServer().getOfflinePlayer(a.id())));
            menu.set(i, head, e -> later(plugin, () -> payAmount(plugin, p, a.id(), 0)));
        }
        if (all.isEmpty()) menu.set(22, Menu.item(Material.GRAY_DYE, m.item("panel.pay-nobody"), List.of()));
        if (current > 0) menu.set(45, Menu.item(Material.PAPER, m.item("panel.page-back"), List.of()), e -> later(plugin, () -> pay(plugin, p, current - 1)));
        if (current < pages - 1) menu.set(53, Menu.item(Material.PAPER, m.item("panel.page-next"), List.of()), e -> later(plugin, () -> pay(plugin, p, current + 1)));
        menu.set(49, Menu.item(Material.ARROW, m.item("panel.back"), List.of()), e -> later(plugin, () -> open(plugin, p)));
        menu.frame().open(p);
    }

    /** Pick the amount with + and - (or type it), then pay. */
    static void payAmount(NanoCorePlugin plugin, Player p, UUID to, long amount) {
        Messages m = plugin.messages();
        var eco = plugin.economy();
        String name = eco.name(to);
        long balance = eco.balance(p.getUniqueId());
        long amt = Math.max(0, Math.min(amount, balance));
        Menu menu = new Menu(4, m.msg("panel.pay-to", "player", name));
        menu.set(4, Menu.item(Material.GOLD_INGOT, m.item("panel.pay-amount", "amount", eco.format(amt)),
                List.of(m.item("panel.player-balance", "balance", eco.format(balance)))));
        long[] steps = {1, 10, 100};
        for (int i = 0; i < steps.length; i++) {
            long s = steps[i];
            menu.set(12 - i, Menu.item(Material.RED_STAINED_GLASS_PANE, m.item("panel.minus", "amount", String.valueOf(s)), List.of()),
                    e -> later(plugin, () -> payAmount(plugin, p, to, amt - s)));
            menu.set(14 + i, Menu.item(Material.LIME_STAINED_GLASS_PANE, m.item("panel.plus", "amount", String.valueOf(s)), List.of()),
                    e -> later(plugin, () -> payAmount(plugin, p, to, amt + s)));
        }
        menu.set(13, Menu.button(Material.PAPER, m.item("panel.pay-type"), List.of(), m.item("panel.click-type")), e -> later(plugin, () ->
                ChatPrompt.ask(plugin, p, m.msg("panel.pay-prompt", "player", name), text -> {
                    Long v = ControlPanel.parseLong(text);
                    if (v == null || v < 1) {
                        m.send(p, "economy.invalid-amount");
                        return false;
                    }
                    payAmount(plugin, p, to, v);
                    return true;
                }, () -> payAmount(plugin, p, to, amt))));
        if (amt > 0) {
            menu.set(22, Menu.button(Material.LIME_CONCRETE, m.item("panel.pay-button", "amount", eco.format(amt), "player", name), List.of(), null),
                    e -> later(plugin, () -> Confirm.open(plugin, p, m.item("panel.pay-confirm", "amount", eco.format(amt), "player", name), List.of(), () -> {
                        p.closeInventory();
                        p.performCommand("pay " + name + " " + amt);
                    }, () -> payAmount(plugin, p, to, amt))));
        }
        menu.set(31, Menu.item(Material.ARROW, m.item("panel.back"), List.of()), e -> later(plugin, () -> pay(plugin, p, 0)));
        menu.frame().open(p);
    }
}
