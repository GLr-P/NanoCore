package io.github.glrp.nanocore.admin;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.area.Area;
import io.github.glrp.nanocore.economy.Accounts;
import io.github.glrp.nanocore.event.EventMenus;
import io.github.glrp.nanocore.gui.Confirm;
import io.github.glrp.nanocore.gui.Menu;
import io.github.glrp.nanocore.milestone.Milestone;
import io.github.glrp.nanocore.pshop.PlayerShopMenus;
import io.github.glrp.nanocore.quest.QuestMenu;
import io.github.glrp.nanocore.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /nc for admins: one window that reaches everything NanoCore does (money, milestones, events, quests,
 * the admin shop, player shops, areas and the settings in config.yml).
 */
public final class ControlPanel {

    private static final int PER_PAGE = 45;

    /** A number or on/off setting in config.yml the panel can change. */
    record Setting(String id, String path, Material icon, boolean toggle, long min, long max, long[] steps) {
        static Setting number(String id, String path, Material icon, long min, long max, long... steps) {
            return new Setting(id, path, icon, false, min, max, steps);
        }

        static Setting toggle(String id, String path, Material icon) {
            return new Setting(id, path, icon, true, 0, 1, new long[0]);
        }
    }

    static final List<Setting> SETTINGS = List.of(
            Setting.number("starting-balance", "currency.starting-balance", Material.GOLD_NUGGET, 0, 1_000_000, 10, 100, 1000),
            Setting.number("sell-limit", "admin-shop.daily-limit", Material.EMERALD, 0, 1_000_000, 10, 50, 100),
            Setting.number("quests-max-active", "quests.max-active", Material.BOOK, 1, 12, 1),
            Setting.number("quests-reroll-cost", "quests.reroll-cost", Material.PAPER, 0, 100_000, 5, 25, 100),
            Setting.number("quests-community-pot", "quests.community.pot", Material.BELL, 0, 1_000_000, 100, 500, 1000),
            Setting.number("shop-limit", "player-shops.limit", Material.CHEST, 0, 1000, 1, 5, 10),
            Setting.number("shop-tax", "player-shops.tax-percent", Material.IRON_INGOT, 0, 100, 1, 5, 10),
            Setting.toggle("events-auto", "events.auto.enabled", Material.CLOCK),
            Setting.number("events-no-repeat", "events.auto.no-repeat-days", Material.COMPASS, 0, 30, 1, 5),
            Setting.number("events-participation", "events.prizes.participation", Material.GOLD_INGOT, 0, 100_000, 5, 25, 100),
            Setting.number("events-active-actions", "events.prizes.active-actions", Material.IRON_PICKAXE, 0, 10_000, 5, 10, 50),
            Setting.toggle("events-trophy", "events.prizes.trophy", Material.GOLDEN_HELMET),
            Setting.toggle("stats-creative", "stats.count-creative", Material.GRASS_BLOCK),
            Setting.number("nether-roof-y", "milestones.nether-roof-y", Material.BEDROCK, 0, 320, 1, 10));

    private static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    private ControlPanel() {
    }

    private static void later(NanoCorePlugin plugin, Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }

    private static Messages msg(NanoCorePlugin plugin) {
        return plugin.messages();
    }

    private static List<Component> lore(Messages m, String key) {
        List<Component> out = new ArrayList<>();
        for (String line : m.raw(key).split("\n")) out.add(Messages.parse(line).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        return out;
    }

    private static ItemStack head(UUID id, Component name, List<Component> lore) {
        ItemStack it = Menu.item(Material.PLAYER_HEAD, name, lore);
        it.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(id)));
        return it;
    }

    // --- main window ---

    public static void open(NanoCorePlugin plugin, Player p) {
        Messages m = msg(plugin);
        Menu menu = new Menu(6, m.msg("panel.title"));
        var ev = plugin.events();
        long unlocked = java.util.Arrays.stream(Milestone.values()).filter(plugin.milestones()::isUnlocked).count();
        menu.set(4, Menu.item(Material.NETHER_STAR, m.item("panel.info", "version", plugin.getPluginMeta().getVersion()), List.of(
                m.item("panel.info-online", "count", String.valueOf(plugin.getServer().getOnlinePlayers().size())),
                m.item("panel.info-accounts", "count", String.valueOf(plugin.economy().top().size())),
                m.item("panel.info-milestones", "count", String.valueOf(unlocked), "total", String.valueOf(Milestone.values().length)),
                m.item("panel.info-event", "event", ev.running() ? ev.current().name() : m.raw("panel.none")),
                m.item("panel.info-shops", "count", String.valueOf(plugin.playerShops().all().size())))));
        menu.set(19, Menu.item(Material.PLAYER_HEAD, m.item("panel.players"), lore(m, "panel.players-lore")), e -> later(plugin, () -> players(plugin, p, 0)));
        menu.set(20, Menu.item(Material.BEACON, m.item("panel.milestones"), lore(m, "panel.milestones-lore")), e -> later(plugin, () -> milestones(plugin, p)));
        menu.set(21, Menu.item(Material.CLOCK, m.item("panel.events"), lore(m, "panel.events-lore")), e -> later(plugin, () -> EventMenus.admin(plugin, p)));
        menu.set(22, Menu.item(Material.WRITABLE_BOOK, m.item("panel.quests"), lore(m, "panel.quests-lore")), e -> later(plugin, () -> quests(plugin, p)));
        menu.set(23, Menu.item(Material.EMERALD, m.item("panel.admin-shop"), lore(m, "panel.admin-shop-lore")), e -> later(plugin, () -> adminShop(plugin, p)));
        menu.set(24, Menu.item(Material.CHEST, m.item("panel.player-shops"), lore(m, "panel.player-shops-lore")),
                e -> later(plugin, () -> PlayerShopMenus.browse(plugin, p, 0, true, null)));
        menu.set(25, Menu.item(Material.FILLED_MAP, m.item("panel.areas"), lore(m, "panel.areas-lore")), e -> later(plugin, () -> areas(plugin, p)));
        menu.set(31, Menu.item(Material.COMPARATOR, m.item("panel.settings"), lore(m, "panel.settings-lore")), e -> later(plugin, () -> settings(plugin, p)));
        menu.set(40, Menu.item(Material.COMPASS, m.item("panel.player-menu"), lore(m, "panel.player-menu-lore")), e -> later(plugin, () -> PlayerMenu.open(plugin, p)));
        menu.set(49, Menu.item(Material.BARRIER, m.item("panel.close"), List.of()), e -> later(plugin, p::closeInventory));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private static void back(NanoCorePlugin plugin, Menu menu, int slot, Player p, Runnable to) {
        menu.set(slot, Menu.item(Material.ARROW, msg(plugin).item("panel.back"), List.of()), e -> later(plugin, to));
    }

    // --- players and money ---

    static void players(NanoCorePlugin plugin, Player p, int page) {
        Messages m = msg(plugin);
        List<Accounts.Entry> all = plugin.economy().top();
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(6, m.msg("panel.players-title", "page", String.valueOf(current + 1), "pages", String.valueOf(pages)));
        for (int i = 0; i < PER_PAGE && current * PER_PAGE + i < all.size(); i++) {
            Accounts.Entry a = all.get(current * PER_PAGE + i);
            boolean online = plugin.getServer().getPlayer(a.id()) != null;
            menu.set(i, head(a.id(), m.item("panel.player-name", "player", a.name()), List.of(
                    m.item("panel.player-balance", "balance", plugin.economy().format(a.balance())),
                    m.item(online ? "panel.online" : "panel.offline"), m.item("panel.click-manage"))),
                    e -> later(plugin, () -> player(plugin, p, a.id())));
        }
        if (current > 0) menu.set(45, Menu.item(Material.PAPER, m.item("panel.page-back"), List.of()), e -> later(plugin, () -> players(plugin, p, current - 1)));
        if (current < pages - 1) menu.set(53, Menu.item(Material.PAPER, m.item("panel.page-next"), List.of()), e -> later(plugin, () -> players(plugin, p, current + 1)));
        back(plugin, menu, 49, p, () -> open(plugin, p));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    static void player(NanoCorePlugin plugin, Player p, UUID id) {
        Messages m = msg(plugin);
        var eco = plugin.economy();
        String name = eco.name(id);
        Player target = plugin.getServer().getPlayer(id);
        Menu menu = new Menu(5, m.msg("panel.player-title", "player", name));
        menu.set(4, head(id, m.item("panel.player-name", "player", name), List.of(
                m.item("panel.player-balance", "balance", eco.format(eco.balance(id))),
                m.item("panel.player-sold-today", "earned", eco.format(plugin.shop().earnedToday(id)), "limit", eco.format(plugin.shop().dailyLimit())),
                m.item("panel.player-shops-count", "count", String.valueOf(plugin.playerShops().owned(id).size())),
                m.item(target != null ? "panel.online" : "panel.offline"))));
        long[] amounts = {10, 100, 1000};
        for (int i = 0; i < amounts.length; i++) {
            long a = amounts[i];
            menu.set(10 + i, Menu.item(Material.LIME_DYE, m.item("panel.give", "amount", eco.format(a)), List.of()), e -> later(plugin, () -> {
                eco.deposit(id, a, "admin give by " + p.getName());
                m.send(p, "economy.admin-done", "player", name, "balance", eco.format(eco.balance(id)));
                player(plugin, p, id);
            }));
            menu.set(14 + i, Menu.item(Material.RED_DYE, m.item("panel.take", "amount", eco.format(a)), List.of()), e -> later(plugin, () -> {
                long take = Math.min(a, eco.balance(id));
                if (take > 0) eco.withdraw(id, take, "admin take by " + p.getName());
                m.send(p, "economy.admin-done", "player", name, "balance", eco.format(eco.balance(id)));
                player(plugin, p, id);
            }));
        }
        menu.set(19, Menu.item(Material.BUCKET, m.item("panel.set-zero"), List.of()), e -> later(plugin, () ->
                Confirm.open(plugin, p, m.item("panel.set-zero-confirm", "player", name), List.of(), () -> {
                    eco.set(id, 0, "admin set by " + p.getName());
                    m.send(p, "economy.admin-done", "player", name, "balance", eco.format(eco.balance(id)));
                    player(plugin, p, id);
                }, () -> player(plugin, p, id))));
        menu.set(21, Menu.item(Material.EMERALD, m.item("panel.reset-sell"), List.of(m.item("panel.reset-sell-lore"))), e -> later(plugin, () -> {
            plugin.shop().resetToday(id);
            m.send(p, "shop.admin-reset", "player", name);
            player(plugin, p, id);
        }));
        menu.set(23, Menu.item(Material.CHEST, m.item("panel.their-shops"), List.of()),
                e -> later(plugin, () -> PlayerShopMenus.browse(plugin, p, 0, true, id)));
        menu.set(25, Menu.item(Material.BOOK, m.item("panel.their-stats"), List.of(m.item("panel.their-stats-lore"))), e -> later(plugin, () -> {
            p.closeInventory();
            p.performCommand("nanocore stats " + name);
        }));
        if (target != null) {
            menu.set(31, Menu.item(Material.ENDER_PEARL, m.item("panel.tp-to"), List.of()), e -> later(plugin, () -> {
                p.closeInventory();
                p.teleport(target);
            }));
        }
        back(plugin, menu, 40, p, () -> players(plugin, p, 0));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    // --- milestones ---

    static void milestones(NanoCorePlugin plugin, Player p) {
        Messages m = msg(plugin);
        var ms = plugin.milestones();
        Menu menu = new Menu(3, m.msg("panel.milestones-title"));
        Milestone[] all = Milestone.values();
        int start = (9 - all.length) / 2;
        for (int i = 0; i < all.length; i++) {
            Milestone mi = all[i];
            boolean on = ms.isUnlocked(mi);
            String name = ms.displayName(mi);
            Material icon = on ? Material.matchMaterial(mi.icon()) : Material.GRAY_DYE;
            menu.set(9 + start + i, Menu.item(icon == null ? Material.PAPER : icon, m.item(on ? "panel.milestone-on" : "panel.milestone-off", "milestone", name),
                    List.of(m.item(on ? "panel.milestone-click-lock" : "panel.milestone-click-unlock"))), e -> later(plugin, () ->
                    Confirm.open(plugin, p, m.item(on ? "panel.milestone-lock-confirm" : "panel.milestone-unlock-confirm", "milestone", name),
                            List.of(m.item(on ? "panel.milestone-lock-note" : "panel.milestone-unlock-note")), () -> {
                                if (ms.set(mi, !on, p.getName()) && on) m.send(p, "milestones.locked-admin", "milestone", name);
                                milestones(plugin, p);
                            }, () -> milestones(plugin, p))));
        }
        back(plugin, menu, 22, p, () -> open(plugin, p));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    // --- quests ---

    static void quests(NanoCorePlugin plugin, Player p) {
        Messages m = msg(plugin);
        var qs = plugin.quests();
        Menu menu = new Menu(4, m.msg("panel.quests-title"));
        menu.set(4, Menu.item(Material.WRITABLE_BOOK, m.item("panel.quests"), List.of(
                m.item("panel.quests-boards", "count", String.valueOf(plugin.questBoards().boards().size())),
                m.item("panel.quests-storehouse", "place", plugin.questBoards().storehouse() == null ? m.raw("panel.none") : plugin.questBoards().storehouse().toString()))));
        menu.set(10, Menu.item(Material.BOOKSHELF, m.item("panel.quests-open"), List.of()), e -> later(plugin, () -> QuestMenu.open(plugin, p, true)));
        menu.set(11, Menu.item(Material.SUNFLOWER, m.item("panel.quests-new-daily"), List.of(m.item("panel.quests-new-lore"))), e -> later(plugin, () ->
                Confirm.open(plugin, p, m.item("panel.quests-new-daily"), List.of(m.item("panel.quests-new-lore")), () -> {
                    qs.refreshDaily(qs.currentDay());
                    m.send(p, "quests.admin-refreshed");
                    quests(plugin, p);
                }, () -> quests(plugin, p))));
        menu.set(12, Menu.item(Material.BELL, m.item("panel.quests-new-community"), List.of(m.item("panel.quests-new-lore"))), e -> later(plugin, () ->
                Confirm.open(plugin, p, m.item("panel.quests-new-community"), List.of(m.item("panel.quests-new-lore")), () -> {
                    qs.refreshWeekly(qs.currentWeek());
                    m.send(p, "quests.admin-refreshed");
                    quests(plugin, p);
                }, () -> quests(plugin, p))));
        command(plugin, menu, 14, p, Material.LECTERN, "panel.quests-board-set", "quests board set");
        command(plugin, menu, 15, p, Material.BARRIER, "panel.quests-board-remove", "quests board remove");
        command(plugin, menu, 16, p, Material.CHEST, "panel.quests-storehouse-set", "quests storehouse set");
        command(plugin, menu, 22, p, Material.PAPER, "panel.quests-list", "quests list");
        back(plugin, menu, 31, p, () -> open(plugin, p));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    /** A button that closes the window and runs a command (for things aimed at the block you're looking at). */
    private static void command(NanoCorePlugin plugin, Menu menu, int slot, Player p, Material icon, String key, String command) {
        menu.set(slot, Menu.item(icon, msg(plugin).item(key), List.of(msg(plugin).item(key + "-lore"))), e -> later(plugin, () -> {
            p.closeInventory();
            p.performCommand(command);
        }));
    }

    // --- admin shop ---

    static void adminShop(NanoCorePlugin plugin, Player p) {
        Messages m = msg(plugin);
        Menu menu = new Menu(6, m.msg("panel.admin-shop-title"));
        ConfigurationSection prices = plugin.getConfig().getConfigurationSection("admin-shop.prices");
        int slot = 0;
        if (prices != null) {
            for (String item : prices.getKeys(false)) {
                if (slot >= PER_PAGE) break;
                Material mat = Material.matchMaterial(item);
                String path = "admin-shop.prices." + item + (prices.isConfigurationSection(item) ? ".price" : "");
                long price = plugin.getConfig().getLong(path);
                String requires = prices.isConfigurationSection(item) ? prices.getString(item + ".requires", "") : "";
                List<Component> lore = new ArrayList<>();
                lore.add(m.item("shop.price-each", "price", plugin.economy().format(price)));
                if (!requires.isEmpty()) lore.add(m.item("panel.price-requires", "milestone", requires));
                lore.add(m.item("panel.price-click"));
                lore.add(m.item("panel.price-remove"));
                Component title = mat == null ? Component.text(item) : Component.translatable(mat.translationKey());
                menu.set(slot++, Menu.item(mat == null || !mat.isItem() ? Material.PAPER : mat, title, lore), e -> later(plugin, () -> {
                    if (e.isRightClick()) {
                        Confirm.open(plugin, p, m.item("panel.price-remove-confirm", "item", item), List.of(), () -> {
                            setConfig(plugin, "admin-shop.prices." + item, null);
                            adminShop(plugin, p);
                        }, () -> adminShop(plugin, p));
                    } else {
                        number(plugin, p, m.raw("panel.price-of").replace("<item>", item), path, 0, 1_000_000, new long[]{1, 10, 100}, () -> adminShop(plugin, p));
                    }
                }));
            }
        }
        menu.set(46, Menu.item(Material.EMERALD, m.item("panel.setting.sell-limit"), List.of(m.item("panel.setting-value",
                "value", String.valueOf(plugin.getConfig().getLong("admin-shop.daily-limit"))))), e -> later(plugin, () ->
                editSetting(plugin, p, SETTINGS.get(1), () -> adminShop(plugin, p))));
        menu.set(48, Menu.item(Material.ITEM_FRAME, m.item("panel.price-add"), List.of(m.item("panel.price-add-lore"))), e -> later(plugin, () -> {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.isEmpty()) {
                m.send(p, "pshop.need-item");
                adminShop(plugin, p);
                return;
            }
            String item = hand.getType().getKey().getKey();
            String path = "admin-shop.prices." + item;
            if (plugin.getConfig().get(path) == null) {
                setConfig(plugin, path, 1);
            }
            String edit = plugin.getConfig().isConfigurationSection(path) ? path + ".price" : path;
            number(plugin, p, m.raw("panel.price-of").replace("<item>", item), edit, 0, 1_000_000, new long[]{1, 10, 100}, () -> adminShop(plugin, p));
        }));
        menu.set(50, Menu.item(Material.SPYGLASS, m.item("panel.price-list"), List.of()),
                e -> later(plugin, () -> io.github.glrp.nanocore.shop.PriceListMenu.open(plugin, p)));
        back(plugin, menu, 49, p, () -> open(plugin, p));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    // --- areas ---

    static void areas(NanoCorePlugin plugin, Player p) {
        Messages m = msg(plugin);
        Menu menu = new Menu(6, m.msg("panel.areas-title"));
        int slot = 0;
        for (Area a : plugin.areas().all()) {
            if (slot >= PER_PAGE) break;
            menu.set(slot++, Menu.item(a.kind() == Area.Kind.CIRCLE ? Material.CLOCK : Material.MAP, m.item("panel.area-name", "name", a.name()),
                    List.of(m.item("panel.area-desc", "desc", a.describe()), m.item("panel.area-click"))), e -> later(plugin, () -> {
                if (e.isRightClick()) {
                    Confirm.open(plugin, p, m.item("panel.area-delete-confirm", "name", a.name()), List.of(), () -> {
                        plugin.areas().delete(a.name());
                        m.send(p, "areas.deleted", "name", a.name());
                        areas(plugin, p);
                    }, () -> areas(plugin, p));
                } else {
                    var w = plugin.getServer().getWorld(a.world());
                    if (w == null) return;
                    double x = a.kind() == Area.Kind.CIRCLE ? a.x1() : (a.x1() + a.x2()) / 2 + 0.5;
                    double z = a.kind() == Area.Kind.CIRCLE ? a.z1() : (a.z1() + a.z2()) / 2 + 0.5;
                    p.closeInventory();
                    p.teleport(new org.bukkit.Location(w, x, w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1, z));
                }
            }));
        }
        if (slot == 0) menu.set(22, Menu.item(Material.GRAY_DYE, m.item("areas.list-empty"), List.of()));
        menu.set(47, Menu.item(Material.BOOK, m.item("panel.areas-help"), lore(m, "panel.areas-help-lore")));
        back(plugin, menu, 49, p, () -> open(plugin, p));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    // --- settings ---

    static void settings(NanoCorePlugin plugin, Player p) {
        Messages m = msg(plugin);
        Menu menu = new Menu(6, m.msg("panel.settings-title"));
        for (int i = 0; i < SETTINGS.size() && i < GRID.length; i++) {
            Setting s = SETTINGS.get(i);
            String value = s.toggle() ? m.raw(plugin.getConfig().getBoolean(s.path()) ? "panel.on" : "panel.off")
                    : String.valueOf(plugin.getConfig().getLong(s.path()));
            menu.set(GRID[i], Menu.item(s.icon(), m.item("panel.setting." + s.id()), List.of(
                    m.item("panel.setting-value", "value", value),
                    m.item("panel.setting-help." + s.id()),
                    m.item(s.toggle() ? "panel.click-toggle" : "panel.click-change"))), e -> later(plugin, () -> editSetting(plugin, p, s, () -> settings(plugin, p))));
        }
        menu.set(40, Menu.item(Material.REPEATER, m.item("panel.reload"), List.of(m.item("panel.reload-lore"))), e -> later(plugin, () -> {
            plugin.reloadSettings();
            m.send(p, "general.reloaded");
            settings(plugin, p);
        }));
        menu.set(42, Menu.item(Material.BOOK, m.item("panel.more-settings"), lore(m, "panel.more-settings-lore")));
        back(plugin, menu, 49, p, () -> open(plugin, p));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private static void editSetting(NanoCorePlugin plugin, Player p, Setting s, Runnable back) {
        Messages m = msg(plugin);
        String name = m.raw("panel.setting." + s.id());
        if (s.toggle()) {
            plugin.reloadConfig();
            boolean v = !plugin.getConfig().getBoolean(s.path());
            setConfig(plugin, s.path(), v);
            m.send(p, "panel.saved", "setting", name, "value", m.raw(v ? "panel.on" : "panel.off"));
            back.run();
            return;
        }
        number(plugin, p, name, s.path(), s.min(), s.max(), s.steps(), back);
    }

    /** A window to change one number in config.yml: - and + buttons for each step; every click saves. */
    static void number(NanoCorePlugin plugin, Player p, String name, String path, long min, long max, long[] steps, Runnable back) {
        Messages m = msg(plugin);
        long value = plugin.getConfig().getLong(path);
        Menu menu = new Menu(3, m.msg("panel.number-title", "setting", name));
        menu.set(13, Menu.item(Material.PAPER, m.item("panel.number-value", "setting", name, "value", String.valueOf(value)), List.of()));
        for (int i = 0; i < steps.length && i < 3; i++) {
            long step = steps[i];
            menu.set(12 - i, Menu.item(Material.RED_STAINED_GLASS_PANE, m.item("panel.minus", "amount", String.valueOf(step)), List.of()),
                    e -> later(plugin, () -> setNumber(plugin, p, name, path, Math.max(min, value - step), min, max, steps, back)));
            menu.set(14 + i, Menu.item(Material.LIME_STAINED_GLASS_PANE, m.item("panel.plus", "amount", String.valueOf(step)), List.of()),
                    e -> later(plugin, () -> setNumber(plugin, p, name, path, Math.min(max, value + step), min, max, steps, back)));
        }
        menu.set(22, Menu.item(Material.ARROW, m.item("panel.back"), List.of()), e -> later(plugin, back));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private static void setNumber(NanoCorePlugin plugin, Player p, String name, String path, long v, long min, long max, long[] steps, Runnable back) {
        setConfig(plugin, path, v);
        msg(plugin).send(p, "panel.saved", "setting", name, "value", String.valueOf(v));
        number(plugin, p, name, path, min, max, steps, back);
    }

    /**
     * Changes one value in config.yml and applies it. The file is read again first, so hand edits that weren't
     * reloaded yet aren't overwritten (comments are kept).
     */
    static void setConfig(NanoCorePlugin plugin, String path, Object value) {
        plugin.reloadConfig();
        plugin.getConfig().set(path, value);
        plugin.saveConfig();
        plugin.reloadSettings();
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
