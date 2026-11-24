package io.github.glrp.nanocore.admin;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.area.Area;
import io.github.glrp.nanocore.economy.Accounts;
import io.github.glrp.nanocore.event.Discord;
import io.github.glrp.nanocore.event.EventAreas;
import io.github.glrp.nanocore.event.EventDef;
import io.github.glrp.nanocore.event.EventEditor;
import io.github.glrp.nanocore.event.EventMenus;
import io.github.glrp.nanocore.gui.BlockPicker;
import io.github.glrp.nanocore.gui.ChatPrompt;
import io.github.glrp.nanocore.gui.Confirm;
import io.github.glrp.nanocore.gui.Menu;
import io.github.glrp.nanocore.milestone.Milestone;
import io.github.glrp.nanocore.pshop.PlayerShopMenus;
import io.github.glrp.nanocore.quest.QuestMenu;
import io.github.glrp.nanocore.quest.QuestTemplate;
import io.github.glrp.nanocore.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

/**
 * /nc for admins: one window that reaches everything NanoCore does (money, milestones, events, quests, the admin shop,
 * player shops, areas, a setup checklist and every setting in config.yml), so nothing needs a file edited by hand.
 */
public final class ControlPanel {

    private static final int PER_PAGE = 45;

    /** What kind of value a setting holds, which decides the window that changes it. */
    enum Kind { NUMBER, TOGGLE, TIME, TEXT, DAY, RANGE, PLACE, ITEM, DECIMAL, ZONE }

    /** One setting in config.yml the panel can change. {@code index} is the list position for PLACE (prize places). */
    record Setting(String id, Kind kind, Material icon, String path, long min, long max, long[] steps, int index) {
        static Setting number(String id, String path, Material icon, long min, long max, long... steps) {
            return new Setting(id, Kind.NUMBER, icon, path, min, max, steps, 0);
        }

        static Setting of(String id, Kind kind, String path, Material icon) {
            return new Setting(id, kind, icon, path, 0, 0, new long[0], 0);
        }

        static Setting place(String id, int index, Material icon) {
            return new Setting(id, Kind.PLACE, icon, "events.prizes.places", 0, 1_000_000, new long[]{10, 50, 100}, index);
        }
    }

    /** A page of settings in the Settings window. */
    record Group(String id, Material icon, List<Setting> settings) {
    }

    static final List<String> ZONE_PATHS = List.of("events.timezone", "quests.timezone", "admin-shop.timezone");

    static final List<Group> GROUPS = List.of(
            new Group("money", Material.GOLD_INGOT, List.of(
                    Setting.number("starting-balance", "currency.starting-balance", Material.GOLD_NUGGET, 0, 1_000_000, 10, 100, 1000),
                    Setting.of("currency-singular", Kind.TEXT, "currency.singular", Material.NAME_TAG),
                    Setting.of("currency-plural", Kind.TEXT, "currency.plural", Material.NAME_TAG),
                    Setting.number("sell-limit", "admin-shop.daily-limit", Material.EMERALD, 0, 1_000_000, 10, 50, 100),
                    Setting.of("sell-reset", Kind.TIME, "admin-shop.reset-time", Material.CLOCK),
                    Setting.number("shop-limit", "player-shops.limit", Material.CHEST, 0, 1000, 1, 5, 10),
                    Setting.number("shop-tax", "player-shops.tax-percent", Material.IRON_INGOT, 0, 100, 1, 5, 10),
                    Setting.number("shop-pick-seconds", "player-shops.pick-seconds", Material.SPYGLASS, 5, 300, 5, 30))),
            new Group("quests", Material.WRITABLE_BOOK, List.of(
                    Setting.number("quests-max-active", "quests.max-active", Material.BOOK, 1, 12, 1),
                    Setting.number("quests-reroll-cost", "quests.reroll-cost", Material.PAPER, 0, 100_000, 5, 25, 100),
                    Setting.number("board-easy", "quests.board.easy", Material.LIME_DYE, 0, 7, 1),
                    Setting.number("board-medium", "quests.board.medium", Material.YELLOW_DYE, 0, 7, 1),
                    Setting.number("board-hard", "quests.board.hard", Material.RED_DYE, 0, 7, 1),
                    Setting.of("rewards-easy", Kind.RANGE, "quests.rewards.easy", Material.LIME_CONCRETE),
                    Setting.of("rewards-medium", Kind.RANGE, "quests.rewards.medium", Material.YELLOW_CONCRETE),
                    Setting.of("rewards-hard", Kind.RANGE, "quests.rewards.hard", Material.RED_CONCRETE),
                    Setting.of("quests-refresh", Kind.TIME, "quests.refresh-time", Material.CLOCK),
                    Setting.number("quests-community-pot", "quests.community.pot", Material.BELL, 0, 1_000_000, 100, 500, 1000),
                    Setting.number("community-min-share", "quests.community.min-share", Material.GOLD_NUGGET, 0, 100_000, 5, 25, 100),
                    Setting.of("community-day", Kind.DAY, "quests.community.day", Material.SUNFLOWER),
                    Setting.number("board-reach", "quests.board-reach", Material.LEAD, 1, 32, 1, 5),
                    Setting.of("label-height", Kind.DECIMAL, "quests.label-height", Material.OAK_SIGN))),
            new Group("events", Material.CLOCK, List.of(
                    Setting.of("events-auto", Kind.TOGGLE, "events.auto.enabled", Material.CLOCK),
                    Setting.of("announce-time", Kind.TIME, "events.auto.announce-time", Material.BELL),
                    Setting.of("start-time", Kind.TIME, "events.auto.start-time", Material.CLOCK),
                    Setting.number("events-no-repeat", "events.auto.no-repeat-days", Material.COMPASS, 0, 30, 1, 5),
                    Setting.of("reminders", Kind.TEXT, "events.reminders", Material.PAPER),
                    Setting.place("prize-1", 0, Material.GOLD_BLOCK),
                    Setting.place("prize-2", 1, Material.IRON_BLOCK),
                    Setting.place("prize-3", 2, Material.COPPER_BLOCK),
                    Setting.number("events-participation", "events.prizes.participation", Material.GOLD_INGOT, 0, 100_000, 5, 25, 100),
                    Setting.number("events-active-actions", "events.prizes.active-actions", Material.IRON_PICKAXE, 0, 10_000, 5, 10, 50),
                    Setting.of("events-trophy", Kind.TOGGLE, "events.prizes.trophy", Material.GOLDEN_HELMET),
                    Setting.of("trophy-item", Kind.ITEM, "events.prizes.trophy-item", Material.GOLD_INGOT),
                    Setting.of("discord-webhook", Kind.TEXT, "events.discord.webhook-url", Material.LIGHTNING_ROD),
                    Setting.of("discord-name", Kind.TEXT, "events.discord.username", Material.NAME_TAG))),
            new Group("server", Material.COMPARATOR, List.of(
                    Setting.of("timezone", Kind.ZONE, "events.timezone", Material.COMPASS),
                    Setting.of("welcome", Kind.TOGGLE, "welcome-message", Material.OAK_SIGN),
                    Setting.number("nether-roof-y", "milestones.nether-roof-y", Material.BEDROCK, 0, 320, 1, 10),
                    Setting.of("stats-creative", Kind.TOGGLE, "stats.count-creative", Material.GRASS_BLOCK))));

    /** Time zones offered with one click (anything else can be typed). */
    static final Map<String, String> ZONES = new LinkedHashMap<>();

    static {
        ZONES.put("America/Vancouver", "Pacific");
        ZONES.put("America/Edmonton", "Mountain");
        ZONES.put("America/Winnipeg", "Central");
        ZONES.put("America/Toronto", "Eastern");
        ZONES.put("America/Halifax", "Atlantic");
        ZONES.put("Europe/London", "UK");
        ZONES.put("Europe/Berlin", "Central Europe");
        ZONES.put("Australia/Sydney", "Australia East");
    }

    private static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    private ControlPanel() {
    }

    static Setting setting(String id) {
        for (Group g : GROUPS) for (Setting s : g.settings()) if (s.id().equals(id)) return s;
        throw new IllegalArgumentException(id);
    }

    private static void later(NanoCorePlugin plugin, Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }

    private static Messages msg(NanoCorePlugin plugin) {
        return plugin.messages();
    }

    private static ItemStack head(UUID id, Component name, List<Component> lore) {
        ItemStack it = Menu.item(Material.PLAYER_HEAD, name, lore);
        it.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(id)));
        return it;
    }

    /** A main-window button: name, description lines, "Click to open". */
    private static ItemStack open(Messages m, Material icon, String key) {
        return Menu.button(icon, m.item(key), m.lines(key + "-lore"), m.item("panel.click-open"));
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
        int todo = setupLeft(plugin);
        menu.set(13, Menu.button(todo == 0 ? Material.LIME_CONCRETE : Material.ORANGE_CONCRETE, m.item("panel.setup"),
                List.of(m.item(todo == 0 ? "panel.setup-done" : "panel.setup-left", "count", String.valueOf(todo)), m.item("panel.setup-lore")),
                m.item("panel.click-open")), e -> later(plugin, () -> setup(plugin, p)));
        menu.set(19, open(m, Material.PLAYER_HEAD, "panel.players"), e -> later(plugin, () -> players(plugin, p, 0)));
        menu.set(20, open(m, Material.BEACON, "panel.milestones"), e -> later(plugin, () -> milestones(plugin, p)));
        menu.set(21, open(m, Material.CLOCK, "panel.events"), e -> later(plugin, () -> EventMenus.admin(plugin, p)));
        menu.set(22, open(m, Material.WRITABLE_BOOK, "panel.quests"), e -> later(plugin, () -> quests(plugin, p)));
        menu.set(23, open(m, Material.EMERALD, "panel.admin-shop"), e -> later(plugin, () -> adminShop(plugin, p)));
        menu.set(24, open(m, Material.CHEST, "panel.player-shops"), e -> later(plugin, () -> PlayerShopMenus.browse(plugin, p, 0, true, null)));
        menu.set(25, open(m, Material.FILLED_MAP, "panel.areas"), e -> later(plugin, () -> areas(plugin, p)));
        menu.set(30, open(m, Material.BOOKSHELF, "panel.edit-events"), e -> later(plugin, () -> EventEditor.list(plugin, p)));
        menu.set(31, open(m, Material.COMPARATOR, "panel.settings"), e -> later(plugin, () -> settings(plugin, p)));
        menu.set(32, open(m, Material.COMPASS, "panel.player-menu"), e -> later(plugin, () -> PlayerMenu.open(plugin, p)));
        menu.set(48, Menu.button(Material.KNOWLEDGE_BOOK, m.item("panel.commands"), m.lines("panel.commands-lore"), m.item("panel.click-chat")),
                e -> later(plugin, () -> {
                    p.closeInventory();
                    p.performCommand("nc help");
                }));
        menu.set(50, Menu.item(Material.BARRIER, m.item("panel.close"), List.of()), e -> later(plugin, p::closeInventory));
        menu.frame().open(p);
    }

    private static void back(NanoCorePlugin plugin, Menu menu, int slot, Runnable to) {
        menu.set(slot, Menu.item(Material.ARROW, msg(plugin).item("panel.back"), List.of()), e -> later(plugin, to));
    }

    // --- setup checklist ---

    /** How many required setup steps are still open (time zone, quest board, storehouse, areas events need). */
    static int setupLeft(NanoCorePlugin plugin) {
        int left = 0;
        if (zone(plugin).isEmpty()) left++;
        if (plugin.questBoards().boards().isEmpty()) left++;
        if (plugin.questBoards().storehouse() == null) left++;
        for (EventAreas.Need n : EventAreas.needed(plugin.events().defs().values())) {
            if (!n.optional() && plugin.areas().get(n.area()).isEmpty()) left++;
        }
        return left;
    }

    static String zone(NanoCorePlugin plugin) {
        return plugin.getConfig().getString("events.timezone", "");
    }

    /** One checklist line: a tick or a cross, what it is, why, and what a click does. */
    private static ItemStack step(Messages m, Material icon, boolean done, boolean optional, String name, List<Component> why, String click) {
        String state = done ? "panel.step-done" : optional ? "panel.step-optional" : "panel.step-todo";
        return Menu.button(done ? icon : optional ? Material.GRAY_DYE : Material.RED_DYE, m.item(state, "step", name), why, m.item(click));
    }

    public static void setup(NanoCorePlugin plugin, Player p) {
        Messages m = msg(plugin);
        Menu menu = new Menu(6, m.msg("panel.setup"));
        int left = setupLeft(plugin);
        menu.set(4, Menu.item(Material.KNOWLEDGE_BOOK, m.item("panel.setup"), List.of(
                m.item(left == 0 ? "panel.setup-done" : "panel.setup-left", "count", String.valueOf(left)), m.item("panel.setup-lore"))));
        int[] slot = {0};
        Runnable here = () -> setup(plugin, p);

        String zone = zone(plugin);
        put(plugin, menu, slot, step(m, Material.COMPASS, !zone.isEmpty(), false, m.raw("panel.step-zone"),
                m.lines("panel.step-zone-lore", "zone", zone.isEmpty() ? ZoneId.systemDefault().getId() : zone), "panel.click-change"),
                () -> zones(plugin, p, here));
        int boards = plugin.questBoards().boards().size();
        put(plugin, menu, slot, step(m, Material.LECTERN, boards > 0, false, m.raw("panel.step-board"),
                m.lines("panel.step-board-lore", "count", String.valueOf(boards)), "panel.click-pick"),
                () -> pickForCommand(plugin, p, "panel.pick-board", "quests board set"));
        var store = plugin.questBoards().storehouse();
        put(plugin, menu, slot, step(m, Material.CHEST, store != null, false, m.raw("panel.step-storehouse"),
                m.lines("panel.step-storehouse-lore"), "panel.click-pick"),
                () -> pickForCommand(plugin, p, "panel.pick-storehouse", "quests storehouse set"));
        String hook = plugin.getConfig().getString("events.discord.webhook-url", "");
        put(plugin, menu, slot, step(m, Material.LIGHTNING_ROD, !hook.isBlank(), true, m.raw("panel.step-discord"),
                m.lines("panel.step-discord-lore"), "panel.click-type"),
                () -> editSetting(plugin, p, setting("discord-webhook"), here));

        List<EventAreas.Need> needs = new ArrayList<>(EventAreas.needed(plugin.events().defs().values()));
        needs.sort((a, b) -> Boolean.compare(a.optional(), b.optional()));
        for (EventAreas.Need n : needs) {
            boolean exists = plugin.areas().get(n.area()).isPresent();
            List<Component> why = new ArrayList<>();
            why.add(m.item("panel.step-area-for", "events", String.join(", ", n.events())));
            why.add(m.item(n.box() ? "panel.step-area-box" : "panel.step-area-any"));
            if (n.optional()) why.add(m.item("panel.step-area-optional"));
            put(plugin, menu, slot, step(m, Material.FILLED_MAP, exists, n.optional(), m.raw("panel.step-area").replace("<name>", n.area()), why,
                    exists ? "panel.click-manage" : "panel.click-make"), () -> areaMaker(plugin, p, n.area(), n.box(), here));
        }
        back(plugin, menu, 49, () -> open(plugin, p));
        menu.frame().open(p);
    }

    private static void put(NanoCorePlugin plugin, Menu menu, int[] slot, ItemStack item, Runnable click) {
        int s = slot[0] < GRID.length ? GRID[slot[0]] : 37 + (slot[0] - GRID.length);
        if (s > 43) return;
        slot[0]++;
        menu.set(s, item, e -> later(plugin, click));
    }

    /** Closes the window; the next block the admin clicks is passed to a command like "quests board set x y z". */
    private static void pickForCommand(NanoCorePlugin plugin, Player p, String instructions, String command) {
        later(plugin, () -> BlockPicker.pick(p, msg(plugin).msg(instructions),
                b -> p.performCommand(command + " " + b.getX() + " " + b.getY() + " " + b.getZ())));
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
                            m.item(online ? "panel.online" : "panel.offline"), Component.empty(), m.item("panel.click-manage"))),
                    e -> later(plugin, () -> player(plugin, p, a.id())));
        }
        if (current > 0) menu.set(45, Menu.item(Material.PAPER, m.item("panel.page-back"), List.of()), e -> later(plugin, () -> players(plugin, p, current - 1)));
        if (current < pages - 1) menu.set(53, Menu.item(Material.PAPER, m.item("panel.page-next"), List.of()), e -> later(plugin, () -> players(plugin, p, current + 1)));
        back(plugin, menu, 49, () -> open(plugin, p));
        menu.frame().open(p);
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
        menu.set(13, Menu.button(Material.GOLD_INGOT, m.item("panel.set-balance"), List.of(), m.item("panel.click-type")), e -> later(plugin, () ->
                ChatPrompt.ask(plugin, p, m.msg("panel.set-balance-prompt", "player", name), text -> {
                    Long v = parseLong(text);
                    if (v == null || v < 0) {
                        m.send(p, "panel.number-bad", "min", "0", "max", "1000000000");
                        return false;
                    }
                    eco.set(id, v, "admin set by " + p.getName());
                    m.send(p, "economy.admin-done", "player", name, "balance", eco.format(eco.balance(id)));
                    player(plugin, p, id);
                    return true;
                }, () -> player(plugin, p, id))));
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
            p.performCommand("stats " + name);
        }));
        if (target != null) {
            menu.set(31, Menu.item(Material.ENDER_PEARL, m.item("panel.tp-to"), List.of()), e -> later(plugin, () -> {
                p.closeInventory();
                p.teleport(target);
            }));
        }
        back(plugin, menu, 40, () -> players(plugin, p, 0));
        menu.frame().open(p);
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
                            List.of(m.item("milestones.hints." + mi.id()), Component.empty(), m.item(on ? "panel.milestone-click-lock" : "panel.milestone-click-unlock"))),
                    e -> later(plugin, () ->
                            Confirm.open(plugin, p, m.item(on ? "panel.milestone-lock-confirm" : "panel.milestone-unlock-confirm", "milestone", name),
                                    List.of(m.item(on ? "panel.milestone-lock-note" : "panel.milestone-unlock-note")), () -> {
                                        if (ms.set(mi, !on, p.getName()) && on) m.send(p, "milestones.locked-admin", "milestone", name);
                                        milestones(plugin, p);
                                    }, () -> milestones(plugin, p))));
        }
        back(plugin, menu, 22, () -> open(plugin, p));
        menu.frame().open(p);
    }

    // --- quests ---

    static void quests(NanoCorePlugin plugin, Player p) {
        Messages m = msg(plugin);
        var qs = plugin.quests();
        Menu menu = new Menu(4, m.msg("panel.quests-title"));
        menu.set(4, Menu.item(Material.WRITABLE_BOOK, m.item("panel.quests"), List.of(
                m.item("panel.quests-boards", "count", String.valueOf(plugin.questBoards().boards().size())),
                m.item("panel.quests-storehouse", "place", plugin.questBoards().storehouse() == null ? m.raw("panel.none") : plugin.questBoards().storehouse().toString()))));
        menu.set(10, Menu.button(Material.BOOKSHELF, m.item("panel.quests-open"), List.of(), m.item("panel.click-open")), e -> later(plugin, () -> QuestMenu.open(plugin, p, true)));
        menu.set(11, Menu.button(Material.SUNFLOWER, m.item("panel.quests-new-daily"), List.of(m.item("panel.quests-new-lore")), null), e -> later(plugin, () ->
                Confirm.open(plugin, p, m.item("panel.quests-new-daily"), List.of(m.item("panel.quests-new-lore")), () -> {
                    qs.refreshDaily(qs.currentDay());
                    m.send(p, "quests.admin-refreshed");
                    quests(plugin, p);
                }, () -> quests(plugin, p))));
        menu.set(12, Menu.button(Material.BELL, m.item("panel.quests-new-community"), List.of(m.item("panel.quests-new-lore")), null), e -> later(plugin, () ->
                Confirm.open(plugin, p, m.item("panel.quests-new-community"), List.of(m.item("panel.quests-new-lore")), () -> {
                    qs.refreshWeekly(qs.currentWeek());
                    m.send(p, "quests.admin-refreshed");
                    quests(plugin, p);
                }, () -> quests(plugin, p))));
        menu.set(14, Menu.button(Material.LECTERN, m.item("panel.quests-board-set"), m.lines("panel.quests-board-set-lore"), m.item("panel.click-pick")),
                e -> pickForCommand(plugin, p, "panel.pick-board", "quests board set"));
        menu.set(15, Menu.button(Material.BARRIER, m.item("panel.quests-board-remove"), m.lines("panel.quests-board-remove-lore"), m.item("panel.click-pick")),
                e -> pickForCommand(plugin, p, "panel.pick-board-remove", "quests board remove"));
        menu.set(16, Menu.button(Material.CHEST, m.item("panel.quests-storehouse-set"), m.lines("panel.quests-storehouse-set-lore"), m.item("panel.click-pick")),
                e -> pickForCommand(plugin, p, "panel.pick-storehouse", "quests storehouse set"));
        menu.set(21, Menu.button(Material.ITEM_FRAME, m.item("panel.quest-kinds"), m.lines("panel.quest-kinds-lore"), m.item("panel.click-open")),
                e -> later(plugin, () -> questKinds(plugin, p, 0)));
        menu.set(22, Menu.button(Material.COMPARATOR, m.item("panel.quest-settings"), List.of(), m.item("panel.click-open")),
                e -> later(plugin, () -> group(plugin, p, GROUPS.get(1), () -> quests(plugin, p))));
        menu.set(23, Menu.button(Material.PAPER, m.item("panel.quests-list"), List.of(m.item("panel.quests-list-lore")), m.item("panel.click-chat")), e -> later(plugin, () -> {
            p.closeInventory();
            p.performCommand("quests list");
        }));
        back(plugin, menu, 31, () -> open(plugin, p));
        menu.frame().open(p);
    }

    /** Every kind of quest in quests.yml: click to switch it off (it is no longer put on the board) or on again. */
    static void questKinds(NanoCorePlugin plugin, Player p, int page) {
        Messages m = msg(plugin);
        List<QuestTemplate> all = new ArrayList<>(plugin.quests().templates().values());
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(6, m.msg("panel.quest-kinds-title", "page", String.valueOf(current + 1), "pages", String.valueOf(pages)));
        for (int i = 0; i < PER_PAGE && current * PER_PAGE + i < all.size(); i++) {
            QuestTemplate t = all.get(current * PER_PAGE + i);
            boolean on = t.weight() > 0;
            Material icon = on ? Material.matchMaterial(t.icon()) : Material.GRAY_DYE;
            List<Component> lore = new ArrayList<>();
            lore.add(m.item(on ? "panel.kind-on" : "panel.kind-off"));
            List<String> tiers = new ArrayList<>();
            t.amounts().keySet().forEach(tier -> tiers.add(m.raw("quests.tier." + tier.id())));
            lore.add(m.item("panel.kind-tiers", "tiers", String.join(", ", tiers)));
            menu.set(i, Menu.button(icon == null || !icon.isItem() ? Material.PAPER : icon, m.item("panel.kind-name", "name",
                            t.name().replace("<variant>", "...")), lore, m.item("panel.click-toggle")),
                    e -> later(plugin, () -> {
                        setQuestWeight(plugin, t.id(), on ? 0 : 1);
                        questKinds(plugin, p, current);
                    }));
        }
        if (current > 0) menu.set(45, Menu.item(Material.PAPER, m.item("panel.page-back"), List.of()), e -> later(plugin, () -> questKinds(plugin, p, current - 1)));
        if (current < pages - 1) menu.set(53, Menu.item(Material.PAPER, m.item("panel.page-next"), List.of()), e -> later(plugin, () -> questKinds(plugin, p, current + 1)));
        back(plugin, menu, 49, () -> quests(plugin, p));
        menu.frame().open(p);
    }

    private static void setQuestWeight(NanoCorePlugin plugin, String id, int weight) {
        File f = new File(plugin.getDataFolder(), "quests.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        if (!y.isConfigurationSection(id)) return;
        y.set(id + ".weight", weight == 1 ? null : weight);
        try {
            y.save(f);
        } catch (IOException ex) {
            plugin.getLogger().warning("Couldn't save quests.yml: " + ex.getMessage());
        }
        plugin.quests().reload();
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
                String requires = prices.isConfigurationSection(item) ? prices.getString(item + ".requires", "") : "";
                List<Component> lore = new ArrayList<>();
                lore.add(m.item("shop.price-each", "price", plugin.economy().format(price(plugin, item))));
                if (!requires.isEmpty()) lore.add(m.item("panel.price-requires", "milestone", milestoneName(plugin, requires)));
                Component title = mat == null ? Component.text(item) : Component.translatable(mat.translationKey());
                menu.set(slot++, Menu.button(mat == null || !mat.isItem() ? Material.PAPER : mat, title, lore, m.item("panel.click-change")),
                        e -> later(plugin, () -> shopItem(plugin, p, item)));
            }
        }
        menu.set(46, Menu.button(Material.EMERALD, m.item("panel.setting.sell-limit"), List.of(m.item("panel.setting-value",
                "value", String.valueOf(plugin.getConfig().getLong("admin-shop.daily-limit")))), m.item("panel.click-change")), e -> later(plugin, () ->
                editSetting(plugin, p, setting("sell-limit"), () -> adminShop(plugin, p))));
        menu.set(48, Menu.button(Material.ITEM_FRAME, m.item("panel.price-add"), m.lines("panel.price-add-lore"), null), e -> later(plugin, () -> {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.isEmpty()) {
                m.send(p, "panel.hold-item");
                return;
            }
            String item = hand.getType().getKey().getKey();
            if (plugin.getConfig().get("admin-shop.prices." + item) == null) setConfig(plugin, "admin-shop.prices." + item, 1);
            shopItem(plugin, p, item);
        }));
        menu.set(50, Menu.button(Material.SPYGLASS, m.item("panel.price-list"), List.of(), m.item("panel.click-open")),
                e -> later(plugin, () -> io.github.glrp.nanocore.shop.PriceListMenu.open(plugin, p)));
        back(plugin, menu, 49, () -> open(plugin, p));
        menu.frame().open(p);
    }

    private static String pricePath(NanoCorePlugin plugin, String item) {
        String path = "admin-shop.prices." + item;
        return plugin.getConfig().isConfigurationSection(path) ? path + ".price" : path;
    }

    private static long price(NanoCorePlugin plugin, String item) {
        return plugin.getConfig().getLong(pricePath(plugin, item));
    }

    private static String milestoneName(NanoCorePlugin plugin, String id) {
        return Milestone.byId(id).map(plugin.milestones()::displayName).orElse(id);
    }

    /** One item the server buys: its price, the milestone it waits for, or stop buying it. */
    static void shopItem(NanoCorePlugin plugin, Player p, String item) {
        Messages m = msg(plugin);
        String path = "admin-shop.prices." + item;
        if (plugin.getConfig().get(path) == null) {
            adminShop(plugin, p);
            return;
        }
        Material mat = Material.matchMaterial(item);
        Component title = mat == null ? Component.text(item) : Component.translatable(mat.translationKey());
        Menu menu = new Menu(3, m.msg("panel.price-of", "item", item));
        menu.set(4, Menu.item(mat == null || !mat.isItem() ? Material.PAPER : mat, title, List.of()));
        long price = price(plugin, item);
        menu.set(11, Menu.button(Material.GOLD_NUGGET, m.item("shop.price-each", "price", plugin.economy().format(price)), List.of(), m.item("panel.click-change")),
                e -> later(plugin, () -> number(plugin, p, NumberField.of(m.raw("panel.price-of").replace("<item>", item),
                        () -> price(plugin, item), v -> setConfig(plugin, pricePath(plugin, item), v), 0, 1_000_000, 1, 10, 100), () -> shopItem(plugin, p, item))));
        String requires = plugin.getConfig().isConfigurationSection(path) ? plugin.getConfig().getString(path + ".requires", "") : "";
        menu.set(13, Menu.button(Material.BEACON, m.item("panel.price-after", "milestone", requires.isEmpty() ? m.raw("panel.price-any-time") : milestoneName(plugin, requires)),
                m.lines("panel.price-after-lore"), m.item("panel.click-cycle")), e -> later(plugin, () -> {
            List<String> options = new ArrayList<>();
            options.add("");
            for (Milestone mi : Milestone.values()) options.add(mi.id());
            String next = options.get((options.indexOf(requires) + 1) % options.size());
            long current = price(plugin, item);
            if (next.isEmpty()) {
                setConfig(plugin, path, current);
            } else {
                Map<String, Object> both = new LinkedHashMap<>();
                both.put("price", current);
                both.put("requires", next);
                setConfig(plugin, path, both);
            }
            shopItem(plugin, p, item);
        }));
        menu.set(15, Menu.button(Material.BARRIER, m.item("panel.price-stop"), List.of(), null), e -> later(plugin, () ->
                Confirm.open(plugin, p, m.item("panel.price-remove-confirm", "item", item), List.of(), () -> {
                    setConfig(plugin, path, null);
                    adminShop(plugin, p);
                }, () -> shopItem(plugin, p, item))));
        back(plugin, menu, 22, () -> adminShop(plugin, p));
        menu.frame().open(p);
    }

    // --- areas ---

    static void areas(NanoCorePlugin plugin, Player p) {
        Messages m = msg(plugin);
        Menu menu = new Menu(6, m.msg("panel.areas-title"));
        int slot = 0;
        for (Area a : plugin.areas().all()) {
            if (slot >= PER_PAGE) break;
            menu.set(slot++, Menu.button(a.kind() == Area.Kind.CIRCLE ? Material.CLOCK : Material.MAP, m.item("panel.area-name", "name", a.name()),
                    List.of(m.item("panel.area-desc", "desc", a.describe())), m.item("panel.click-manage")), e -> later(plugin, () ->
                    areaMaker(plugin, p, a.name(), a.kind() == Area.Kind.BOX, () -> areas(plugin, p))));
        }
        if (slot == 0) menu.set(22, Menu.item(Material.GRAY_DYE, m.item("areas.list-empty"), List.of()));
        menu.set(47, Menu.button(Material.WRITABLE_BOOK, m.item("panel.area-new"), m.lines("panel.area-new-lore"), m.item("panel.click-type")), e -> later(plugin, () ->
                ChatPrompt.ask(plugin, p, m.msg("panel.area-name-prompt"), text -> {
                    String name = text.toLowerCase(Locale.ROOT).replace(' ', '_');
                    if (!name.matches("[a-z0-9_]{1,32}")) {
                        m.send(p, "panel.area-bad-name");
                        return false;
                    }
                    areaMaker(plugin, p, name, false, () -> areas(plugin, p));
                    return true;
                }, () -> areas(plugin, p))));
        menu.set(51, Menu.button(Material.KNOWLEDGE_BOOK, m.item("panel.setup"), List.of(m.item("panel.areas-setup-lore")), m.item("panel.click-open")),
                e -> later(plugin, () -> setup(plugin, p)));
        back(plugin, menu, 49, () -> open(plugin, p));
        menu.frame().open(p);
    }

    /**
     * Make (or remake) one area: a circle around where you stand, or a box between two blocks you click.
     * {@code boxOnly}: the events using it need a box (plots, fills).
     */
    static void areaMaker(NanoCorePlugin plugin, Player p, String name, boolean boxOnly, Runnable back) {
        Messages m = msg(plugin);
        var existing = plugin.areas().get(name);
        Menu menu = new Menu(4, m.msg("panel.area-title", "name", name));
        List<Component> info = new ArrayList<>();
        info.add(existing.map(a -> m.item("panel.area-desc", "desc", a.describe())).orElse(m.item("panel.area-missing")));
        for (EventAreas.Need n : EventAreas.needed(plugin.events().defs().values())) {
            if (n.area().equalsIgnoreCase(name)) info.add(m.item("panel.step-area-for", "events", String.join(", ", n.events())));
        }
        menu.set(4, Menu.item(Material.FILLED_MAP, m.item("panel.area-name", "name", name), info));
        if (!boxOnly) {
            int[] radii = {5, 10, 20, 50, 100};
            menu.set(10, Menu.item(Material.CLOCK, m.item("panel.area-circle"), m.lines("panel.area-circle-lore")));
            for (int i = 0; i < radii.length; i++) {
                int r = radii[i];
                menu.set(11 + i, Menu.button(Material.SNOWBALL, m.item("panel.area-radius", "radius", String.valueOf(r)), List.of(), m.item("panel.area-click-circle")),
                        e -> later(plugin, () -> {
                            Location at = p.getLocation();
                            saveArea(plugin, p, Area.circle(name, at.getWorld().getName(), at.getBlockX() + 0.5, at.getBlockZ() + 0.5, r), back);
                        }));
            }
        }
        menu.set(boxOnly ? 13 : 22, Menu.button(Material.MAP, m.item("panel.area-box"), m.lines("panel.area-box-lore"), m.item("panel.click-pick")),
                e -> later(plugin, () -> BlockPicker.pick(p, m.msg("panel.pick-corner-1"), first ->
                        BlockPicker.pick(p, m.msg("panel.pick-corner-2"), second -> {
                            if (!first.getWorld().equals(second.getWorld())) {
                                m.send(p, "areas.need-corners");
                                return;
                            }
                            saveArea(plugin, p, Area.box(name, first.getWorld().getName(), first.getX(), first.getY(), first.getZ(),
                                    second.getX(), second.getY(), second.getZ()), back);
                        }))));
        if (existing.isPresent()) {
            Area a = existing.get();
            menu.set(boxOnly ? 21 : 20, Menu.button(Material.ENDER_PEARL, m.item("panel.area-tp"), List.of(), null), e -> later(plugin, () -> {
                var w = plugin.getServer().getWorld(a.world());
                if (w == null) return;
                double x = a.kind() == Area.Kind.CIRCLE ? a.x1() : (a.x1() + a.x2()) / 2 + 0.5;
                double z = a.kind() == Area.Kind.CIRCLE ? a.z1() : (a.z1() + a.z2()) / 2 + 0.5;
                p.closeInventory();
                p.teleport(new Location(w, x, w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1, z));
            }));
            menu.set(boxOnly ? 23 : 24, Menu.button(Material.BARRIER, m.item("panel.area-delete"), List.of(), null), e -> later(plugin, () ->
                    Confirm.open(plugin, p, m.item("panel.area-delete-confirm", "name", name), List.of(), () -> {
                        plugin.areas().delete(name);
                        m.send(p, "areas.deleted", "name", name);
                        back.run();
                    }, () -> areaMaker(plugin, p, name, boxOnly, back))));
        }
        back(plugin, menu, 31, back);
        menu.frame().open(p);
    }

    /** Saves an area; if that completes the areas an event needs and it isn't in the daily pool yet, offers to add it. */
    private static void saveArea(NanoCorePlugin plugin, Player p, Area a, Runnable back) {
        Messages m = msg(plugin);
        plugin.areas().save(a);
        m.send(p, "areas.created", "name", a.name());
        for (EventDef d : plugin.events().defs().values()) {
            if (d.auto() || d.unlocks() != null && !d.unlocks().isBlank()) continue;
            boolean uses = false, complete = true;
            for (EventAreas.Need n : EventAreas.needed(List.of(d))) {
                if (n.area().equalsIgnoreCase(a.name())) uses = true;
                if (!n.optional() && plugin.areas().get(n.area()).isEmpty()) complete = false;
            }
            if (uses && complete) {
                Confirm.open(plugin, p, m.item("panel.area-add-event", "event", d.name()), List.of(m.item("panel.area-add-event-lore")), () -> {
                    EventEditor.setInPool(plugin, d.id(), true);
                    m.send(p, "panel.area-event-added", "event", d.name());
                    back.run();
                }, back);
                return;
            }
        }
        back.run();
    }

    // --- settings ---

    static void settings(NanoCorePlugin plugin, Player p) {
        Messages m = msg(plugin);
        Menu menu = new Menu(4, m.msg("panel.settings-title"));
        int[] slots = {10, 12, 14, 16};
        for (int i = 0; i < GROUPS.size(); i++) {
            Group g = GROUPS.get(i);
            menu.set(slots[i], Menu.button(g.icon(), m.item("panel.group." + g.id()), m.lines("panel.group." + g.id() + "-lore"), m.item("panel.click-open")),
                    e -> later(plugin, () -> group(plugin, p, g, () -> settings(plugin, p))));
        }
        menu.set(22, Menu.button(Material.KNOWLEDGE_BOOK, m.item("panel.setup"), List.of(m.item("panel.setup-lore")), m.item("panel.click-open")),
                e -> later(plugin, () -> setup(plugin, p)));
        menu.set(35, Menu.button(Material.REPEATER, m.item("panel.reload"), List.of(m.item("panel.reload-lore")), null), e -> later(plugin, () -> {
            plugin.reloadSettings();
            m.send(p, "general.reloaded");
            settings(plugin, p);
        }));
        back(plugin, menu, 31, () -> open(plugin, p));
        menu.frame().open(p);
    }

    static void group(NanoCorePlugin plugin, Player p, Group g, Runnable back) {
        Messages m = msg(plugin);
        Menu menu = new Menu(6, m.msg("panel.group-title", "group", m.raw("panel.group." + g.id())));
        Runnable here = () -> group(plugin, p, g, back);
        for (int i = 0; i < g.settings().size() && i < GRID.length; i++) {
            Setting s = g.settings().get(i);
            String click = switch (s.kind()) {
                case TOGGLE -> "panel.click-toggle";
                case DAY -> "panel.click-cycle";
                case TEXT -> "panel.click-type";
                case ITEM -> "panel.click-hand";
                default -> "panel.click-change";
            };
            List<Component> lore = new ArrayList<>();
            lore.add(m.item("panel.setting-value", "value", value(plugin, s)));
            lore.addAll(m.lines("panel.setting-help." + s.id()));
            menu.set(GRID[i], Menu.button(s.icon(), m.item("panel.setting." + s.id()), lore, m.item(click)),
                    e -> later(plugin, () -> editSetting(plugin, p, s, here)));
        }
        back(plugin, menu, 49, back);
        menu.frame().open(p);
    }

    /** The current value as shown in the menu. */
    static String value(NanoCorePlugin plugin, Setting s) {
        Messages m = msg(plugin);
        var c = plugin.getConfig();
        return switch (s.kind()) {
            case NUMBER -> String.valueOf(c.getLong(s.path()));
            case TOGGLE -> m.raw(c.getBoolean(s.path()) ? "panel.on" : "panel.off");
            case TIME, RANGE, ITEM -> c.getString(s.path(), "");
            case DAY -> capital(c.getString(s.path(), "monday"));
            case DECIMAL -> String.format(Locale.ROOT, "%.1f", c.getDouble(s.path()));
            case PLACE -> String.valueOf(placeValue(plugin, s.index()));
            case ZONE -> zone(plugin).isEmpty() ? m.raw("panel.zone-server").replace("<zone>", ZoneId.systemDefault().getId()) : zone(plugin);
            case TEXT -> switch (s.id()) {
                case "discord-webhook" -> m.raw(c.getString(s.path(), "").isBlank() ? "panel.not-set" : "panel.is-set");
                case "reminders" -> String.join(", ", c.getIntegerList(s.path()).stream().map(String::valueOf).toList()) + " min";
                default -> c.getString(s.path(), "");
            };
        };
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private static long placeValue(NanoCorePlugin plugin, int index) {
        List<Long> places = plugin.getConfig().getLongList("events.prizes.places");
        return index < places.size() ? places.get(index) : 0;
    }

    static void editSetting(NanoCorePlugin plugin, Player p, Setting s, Runnable back) {
        Messages m = msg(plugin);
        String name = m.raw("panel.setting." + s.id());
        var c = plugin.getConfig();
        switch (s.kind()) {
            case TOGGLE -> {
                boolean v = !c.getBoolean(s.path());
                setConfig(plugin, s.path(), v);
                m.send(p, "panel.saved", "setting", name, "value", m.raw(v ? "panel.on" : "panel.off"));
                back.run();
            }
            case NUMBER -> number(plugin, p, NumberField.of(name, () -> plugin.getConfig().getLong(s.path()), v -> setConfig(plugin, s.path(), v),
                    s.min(), s.max(), s.steps()), back);
            case PLACE -> number(plugin, p, NumberField.of(name, () -> placeValue(plugin, s.index()), v -> {
                List<Long> places = new ArrayList<>(plugin.getConfig().getLongList(s.path()));
                while (places.size() <= s.index()) places.add(0L);
                places.set(s.index(), v);
                setConfig(plugin, s.path(), places);
            }, s.min(), s.max(), s.steps()), back);
            case DECIMAL -> number(plugin, p, new NumberField(name, () -> Math.round(plugin.getConfig().getDouble(s.path()) * 10),
                    v -> setConfig(plugin, s.path(), v / 10.0), 0, 100, new long[]{1, 5},
                    v -> String.format(Locale.ROOT, "%.1f", v / 10.0), text -> {
                Double d = parseDouble(text);
                return d == null ? null : Math.round(d * 10);
            }), back);
            case TIME -> time(plugin, p, name, s.path(), back);
            case DAY -> {
                DayOfWeek day;
                try {
                    day = DayOfWeek.valueOf(c.getString(s.path(), "monday").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    day = DayOfWeek.SUNDAY;
                }
                String next = day.plus(1).name().toLowerCase(Locale.ROOT);
                setConfig(plugin, s.path(), next);
                m.send(p, "panel.saved", "setting", name, "value", capital(next));
                back.run();
            }
            case RANGE -> range(plugin, p, name, s.path(), back);
            case ITEM -> {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.isEmpty()) {
                    m.send(p, "panel.hold-item");
                    return;
                }
                String item = hand.getType().getKey().getKey();
                setConfig(plugin, s.path(), item);
                m.send(p, "panel.saved", "setting", name, "value", item);
                back.run();
            }
            case ZONE -> zones(plugin, p, back);
            case TEXT -> ChatPrompt.ask(plugin, p, m.msg("panel.prompt." + s.id()), text -> {
                Object v = parseText(s.id(), text);
                if (v == null) {
                    m.send(p, "panel.bad." + s.id());
                    return false;
                }
                setConfig(plugin, s.path(), v);
                m.send(p, "panel.saved", "setting", name, "value", value(plugin, s));
                if (s.id().equals("discord-webhook") && !String.valueOf(v).isEmpty()) {
                    plugin.events().discord().send(m.raw("panel.discord-test"), m.raw("panel.discord-test-text"), Discord.GREEN, List.of());
                    m.send(p, "panel.discord-test-sent");
                }
                back.run();
                return true;
            }, back);
        }
    }

    /** What a typed answer becomes in config.yml, or null if it can't be used. */
    static Object parseText(String id, String text) {
        String t = text.trim();
        return switch (id) {
            case "discord-webhook" -> {
                if (t.equalsIgnoreCase("off") || t.equalsIgnoreCase("none")) yield "";
                yield t.startsWith("https://") && t.contains("/api/webhooks/") && !t.contains(" ") ? t : null;
            }
            case "reminders" -> {
                List<Integer> out = new ArrayList<>();
                for (String part : t.split("[,\\s]+")) {
                    if (part.isEmpty()) continue;
                    Long v = parseLong(part);
                    if (v == null || v < 1 || v > 1440) yield null;
                    if (!out.contains(v.intValue())) out.add(v.intValue());
                }
                if (out.isEmpty()) yield null;
                out.sort((a, b) -> b - a);
                yield out;
            }
            default -> t.isEmpty() || t.length() > 32 || t.contains("<") ? null : t;
        };
    }

    static Long parseLong(String text) {
        try {
            return Long.parseLong(text.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double parseDouble(String text) {
        try {
            return Double.parseDouble(text.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A number the number window changes: how to read, save, show and type it. */
    record NumberField(String name, LongSupplier get, LongConsumer set, long min, long max, long[] steps, LongFunction<String> show,
                       Function<String, Long> parse) {
        static NumberField of(String name, LongSupplier get, LongConsumer set, long min, long max, long... steps) {
            return new NumberField(name, get, set, min, max, steps, String::valueOf, ControlPanel::parseLong);
        }
    }

    /** A window to change one number: - and + buttons for each step, or click the middle to type it. Every change saves. */
    static void number(NanoCorePlugin plugin, Player p, NumberField f, Runnable back) {
        Messages m = msg(plugin);
        long value = f.get().getAsLong();
        Menu menu = new Menu(3, m.msg("panel.number-title", "setting", f.name()));
        menu.set(13, Menu.button(Material.PAPER, m.item("panel.number-value", "setting", f.name(), "value", f.show().apply(value)),
                        List.of(m.item("panel.number-range", "min", f.show().apply(f.min()), "max", f.show().apply(f.max()))), m.item("panel.click-type")),
                e -> later(plugin, () -> ChatPrompt.ask(plugin, p, m.msg("panel.number-prompt", "setting", f.name()), text -> {
                    Long v = f.parse().apply(text);
                    if (v == null || v < f.min() || v > f.max()) {
                        m.send(p, "panel.number-bad", "min", f.show().apply(f.min()), "max", f.show().apply(f.max()));
                        return false;
                    }
                    saveNumber(plugin, p, f, v, back);
                    return true;
                }, () -> number(plugin, p, f, back))));
        for (int i = 0; i < f.steps().length && i < 3; i++) {
            long step = f.steps()[i];
            menu.set(12 - i, Menu.item(Material.RED_STAINED_GLASS_PANE, m.item("panel.minus", "amount", f.show().apply(step)), List.of()),
                    e -> later(plugin, () -> saveNumber(plugin, p, f, Math.max(f.min(), value - step), back)));
            menu.set(14 + i, Menu.item(Material.LIME_STAINED_GLASS_PANE, m.item("panel.plus", "amount", f.show().apply(step)), List.of()),
                    e -> later(plugin, () -> saveNumber(plugin, p, f, Math.min(f.max(), value + step), back)));
        }
        menu.set(22, Menu.item(Material.ARROW, m.item("panel.back"), List.of()), e -> later(plugin, back));
        menu.frame().open(p);
    }

    private static void saveNumber(NanoCorePlugin plugin, Player p, NumberField f, long v, Runnable back) {
        f.set().accept(v);
        msg(plugin).send(p, "panel.saved", "setting", f.name(), "value", f.show().apply(v));
        number(plugin, p, f, back);
    }

    /** A time of day like "19:00": an hour or 15 minutes earlier or later, or type it. */
    static void time(NanoCorePlugin plugin, Player p, String name, String path, Runnable back) {
        Messages m = msg(plugin);
        LocalTime t = parseTime(plugin.getConfig().getString(path, "00:00"));
        LocalTime now = t == null ? LocalTime.MIDNIGHT : t;
        Menu menu = new Menu(3, m.msg("panel.number-title", "setting", name));
        menu.set(13, Menu.button(Material.CLOCK, m.item("panel.number-value", "setting", name, "value", now.toString()), List.of(), m.item("panel.click-type")),
                e -> later(plugin, () -> ChatPrompt.ask(plugin, p, m.msg("panel.time-prompt", "setting", name), text -> {
                    LocalTime typed = parseTime(text);
                    if (typed == null) {
                        m.send(p, "events.admin-bad-time");
                        return false;
                    }
                    saveTime(plugin, p, name, path, typed, back);
                    return true;
                }, () -> time(plugin, p, name, path, back))));
        long[] minutes = {-60, -15, 15, 60};
        int[] slots = {10, 11, 15, 16};
        for (int i = 0; i < minutes.length; i++) {
            long mins = minutes[i];
            String key = mins < 0 ? "panel.time-earlier" : "panel.time-later";
            String amount = Math.abs(mins) == 60 ? m.raw("panel.one-hour") : Math.abs(mins) + " min";
            menu.set(slots[i], Menu.item(mins < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE, m.item(key, "amount", amount), List.of()),
                    e -> later(plugin, () -> saveTime(plugin, p, name, path, now.plusMinutes(mins), back)));
        }
        menu.set(22, Menu.item(Material.ARROW, m.item("panel.back"), List.of()), e -> later(plugin, back));
        menu.frame().open(p);
    }

    private static void saveTime(NanoCorePlugin plugin, Player p, String name, String path, LocalTime t, Runnable back) {
        String v = t.format(DateTimeFormatter.ofPattern("HH:mm"));
        setConfig(plugin, path, v);
        msg(plugin).send(p, "panel.saved", "setting", name, "value", v);
        time(plugin, p, name, path, back);
    }

    /** "19:00", "7:30", "1900" or "7pm" / "7:30 pm". Null if it isn't a time. */
    static LocalTime parseTime(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace(".", ":");
        boolean pm = t.endsWith("pm"), am = t.endsWith("am");
        if (pm || am) t = t.substring(0, t.length() - 2);
        if (t.matches("\\d{3,4}")) t = t.substring(0, t.length() - 2) + ":" + t.substring(t.length() - 2);
        if (t.matches("\\d{1,2}")) t = t + ":00";
        if (!t.matches("\\d{1,2}:\\d{2}")) return null;
        int h = Integer.parseInt(t.substring(0, t.indexOf(':')));
        int min = Integer.parseInt(t.substring(t.indexOf(':') + 1));
        if (pm || am) {
            if (h < 1 || h > 12) return null;
            h = h % 12 + (pm ? 12 : 0);
        }
        if (h > 23 || min > 59) return null;
        return LocalTime.of(h, min);
    }

    /** A reward range like "40-80": the lowest and the highest as two numbers. */
    static void range(NanoCorePlugin plugin, Player p, String name, String path, Runnable back) {
        Messages m = msg(plugin);
        long[] r = parseRange(plugin.getConfig().getString(path, "0-0"));
        Menu menu = new Menu(3, m.msg("panel.number-title", "setting", name));
        Runnable here = () -> range(plugin, p, name, path, back);
        menu.set(11, Menu.button(Material.IRON_NUGGET, m.item("panel.range-low", "value", String.valueOf(r[0])), List.of(), m.item("panel.click-change")),
                e -> later(plugin, () -> number(plugin, p, NumberField.of(m.raw("panel.range-low-name").replace("<setting>", name),
                        () -> parseRange(plugin.getConfig().getString(path, "0-0"))[0],
                        v -> setConfig(plugin, path, v + "-" + Math.max(v, parseRange(plugin.getConfig().getString(path, "0-0"))[1])), 0, 1_000_000, 5, 25, 100), here)));
        menu.set(15, Menu.button(Material.GOLD_NUGGET, m.item("panel.range-high", "value", String.valueOf(r[1])), List.of(), m.item("panel.click-change")),
                e -> later(plugin, () -> number(plugin, p, NumberField.of(m.raw("panel.range-high-name").replace("<setting>", name),
                        () -> parseRange(plugin.getConfig().getString(path, "0-0"))[1],
                        v -> setConfig(plugin, path, Math.min(v, parseRange(plugin.getConfig().getString(path, "0-0"))[0]) + "-" + v), 0, 1_000_000, 5, 25, 100), here)));
        menu.set(22, Menu.item(Material.ARROW, m.item("panel.back"), List.of()), e -> later(plugin, back));
        menu.frame().open(p);
    }

    static long[] parseRange(String s) {
        try {
            String[] parts = s.replace(" ", "").split("-");
            long a = Long.parseLong(parts[0]);
            long b = parts.length > 1 ? Long.parseLong(parts[1]) : a;
            return new long[]{Math.min(a, b), Math.max(a, b)};
        } catch (RuntimeException e) {
            return new long[]{0, 0};
        }
    }

    /** One click for a common time zone (with the time there right now), or type any other. Sets all of NanoCore's times. */
    static void zones(NanoCorePlugin plugin, Player p, Runnable back) {
        Messages m = msg(plugin);
        Menu menu = new Menu(4, m.msg("panel.zone-title"));
        String current = zone(plugin);
        DateTimeFormatter hhmm = DateTimeFormatter.ofPattern("HH:mm");
        int slot = 10;
        for (var z : ZONES.entrySet()) {
            boolean chosen = z.getKey().equals(current);
            String now = ZonedDateTime.now(ZoneId.of(z.getKey())).format(hhmm);
            menu.set(slot, Menu.button(chosen ? Material.LIME_DYE : Material.CLOCK, m.item("panel.zone-name", "name", z.getValue()),
                    List.of(m.item("panel.zone-id", "zone", z.getKey()), m.item("panel.zone-now", "time", now)),
                    m.item(chosen ? "pshop.mode-current" : "panel.click-choose")), e -> later(plugin, () -> setZone(plugin, p, z.getKey(), back)));
            slot += slot == 16 ? 4 : 1;
        }
        menu.set(slot, Menu.button(Material.COMMAND_BLOCK, m.item("panel.zone-server-button"),
                List.of(m.item("panel.zone-id", "zone", ZoneId.systemDefault().getId())),
                m.item(current.isEmpty() ? "pshop.mode-current" : "panel.click-choose")), e -> later(plugin, () -> setZone(plugin, p, "", back)));
        menu.set(slot + 1, Menu.button(Material.WRITABLE_BOOK, m.item("panel.zone-other"), m.lines("panel.zone-other-lore"), m.item("panel.click-type")),
                e -> later(plugin, () -> ChatPrompt.ask(plugin, p, m.msg("panel.zone-prompt"), text -> {
                    String id = parseZone(text);
                    if (id == null) {
                        m.send(p, "panel.zone-bad");
                        return false;
                    }
                    setZone(plugin, p, id, back);
                    return true;
                }, () -> zones(plugin, p, back))));
        menu.set(31, Menu.item(Material.ARROW, m.item("panel.back"), List.of()), e -> later(plugin, back));
        menu.frame().open(p);
    }

    /** A zone id like "America/Vancouver" (any case), or a short one like "PST"/"EST". Null if unknown. */
    static String parseZone(String text) {
        String t = text.trim().replace(' ', '_');
        String shortId = switch (t.toUpperCase(Locale.ROOT)) {
            case "PST", "PDT", "PT", "PACIFIC" -> "America/Los_Angeles";
            case "MST", "MDT", "MT", "MOUNTAIN" -> "America/Denver";
            case "CST", "CDT", "CT", "CENTRAL" -> "America/Chicago";
            case "EST", "EDT", "ET", "EASTERN" -> "America/New_York";
            case "AST", "ADT", "ATLANTIC" -> "America/Halifax";
            case "UTC", "GMT", "Z" -> "UTC";
            default -> ZoneId.SHORT_IDS.get(t.toUpperCase(Locale.ROOT));
        };
        if (shortId != null) t = shortId;
        for (String id : ZoneId.getAvailableZoneIds()) if (id.equalsIgnoreCase(t)) return id;
        return null;
    }

    private static void setZone(NanoCorePlugin plugin, Player p, String zone, Runnable back) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String path : ZONE_PATHS) changes.put(path, zone);
        setConfig(plugin, changes);
        msg(plugin).send(p, "panel.saved", "setting", msg(plugin).raw("panel.setting.timezone"), "value", value(plugin, setting("timezone")));
        back.run();
    }

    /**
     * Changes one value in config.yml and applies it. The file is read again first, so hand edits that weren't
     * reloaded yet aren't overwritten (comments are kept).
     */
    static void setConfig(NanoCorePlugin plugin, String path, Object value) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put(path, value);
        setConfig(plugin, one);
    }

    static void setConfig(NanoCorePlugin plugin, Map<String, Object> changes) {
        plugin.reloadConfig();
        changes.forEach((path, value) -> plugin.getConfig().set(path, value));
        plugin.saveConfig();
        plugin.reloadSettings();
    }
}
