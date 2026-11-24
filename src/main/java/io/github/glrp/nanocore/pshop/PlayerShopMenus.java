package io.github.glrp.nanocore.pshop;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.gui.Confirm;
import io.github.glrp.nanocore.gui.Menu;
import io.github.glrp.nanocore.milestone.Milestone;
import io.github.glrp.nanocore.text.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongConsumer;

/**
 * Every player shop window: /pshop, My shops, the owner's setup window, the customer's buy/sell window,
 * Browse, and the admin page for one shop. Plain chest menus, so they work on Bedrock too.
 */
public final class PlayerShopMenus {

    private static final int PER_PAGE = 45;

    private PlayerShopMenus() {
    }

    private static void later(NanoCorePlugin plugin, Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }

    private static Messages msg(NanoCorePlugin plugin) {
        return plugin.messages();
    }

    /** The shop's own item with a menu name and lore (keeps enchantments and so on visible). */
    private static ItemStack icon(ItemStack item, int amount, Component name, List<Component> lore) {
        ItemStack it = item.asQuantity(Math.max(1, Math.min(item.getMaxStackSize(), amount)));
        it.editMeta(m -> {
            m.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            m.lore(lore.stream().map(c -> c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
        });
        return it;
    }

    /** Price, stock and place of a shop, for lore. */
    static List<Component> info(NanoCorePlugin plugin, PlayerShop s) {
        Messages m = msg(plugin);
        PlayerShopService svc = plugin.playerShops();
        var eco = plugin.economy();
        List<Component> lore = new ArrayList<>();
        lore.add(m.item("pshop.info-owner", "player", eco.name(s.owner())));
        if (s.mode().ownerSells()) lore.add(m.item("pshop.info-sells", "price", eco.format(s.sellPrice()), "stock", String.valueOf(svc.stock(s))));
        if (s.mode().ownerBuys()) lore.add(m.item("pshop.info-buys", "price", eco.format(s.buyPrice())));
        lore.add(m.item("pshop.info-where", "x", String.valueOf(s.pos().x()), "y", String.valueOf(s.pos().y()), "z", String.valueOf(s.pos().z()),
                "world", s.pos().world()));
        return lore;
    }

    // --- /pshop ---

    public static void main(NanoCorePlugin plugin, Player p) {
        Messages m = msg(plugin);
        PlayerShopService svc = plugin.playerShops();
        Menu menu = new Menu(3, m.msg("pshop.main-title"));
        boolean open = plugin.milestones().isUnlocked(Milestone.PLAYER_SHOPS);
        List<Component> how = new ArrayList<>();
        if (!open) how.add(m.item("milestones.blocked.player_shops"));
        for (String line : m.raw("pshop.new-help").split("\n")) how.add(Messages.parse(line).decoration(TextDecoration.ITALIC, false));
        menu.set(11, Menu.item(open ? Material.CHEST : Material.GRAY_DYE, m.item("pshop.new"), how), e -> later(plugin, () -> {
            p.closeInventory();
            pick(plugin, p);
        }));
        int mine = svc.owned(p.getUniqueId()).size();
        menu.set(13, Menu.item(Material.ENDER_CHEST, m.item("pshop.mine"),
                List.of(m.item("pshop.mine-count", "count", String.valueOf(mine), "limit", String.valueOf(svc.limit())))),
                e -> later(plugin, () -> mine(plugin, p)));
        menu.set(15, Menu.item(Material.SPYGLASS, m.item("pshop.browse"), List.of(m.item("pshop.browse-lore", "count", String.valueOf(svc.all().size())))),
                e -> later(plugin, () -> browse(plugin, p, 0, false, null)));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    /** Starts "click your chest" mode and says so. */
    public static void pick(NanoCorePlugin plugin, Player p) {
        String err = plugin.playerShops().startPicking(p);
        if (err != null) msg(plugin).send(p, err, "limit", String.valueOf(plugin.playerShops().limit()));
        else msg(plugin).send(p, "pshop.pick", "seconds", String.valueOf(plugin.playerShops().pickSeconds()));
    }

    public static void mine(NanoCorePlugin plugin, Player p) {
        Messages m = msg(plugin);
        Menu menu = new Menu(6, m.msg("pshop.mine-title"));
        int slot = 0;
        for (PlayerShop s : plugin.playerShops().owned(p.getUniqueId())) {
            if (slot >= PER_PAGE) break;
            List<Component> lore = new ArrayList<>(info(plugin, s));
            lore.add(m.item("pshop.click-edit"));
            menu.set(slot++, icon(s.item, 1, PlayerShopService.name(s.item), lore), e -> later(plugin, () -> editor(plugin, p, s)));
        }
        if (slot == 0) menu.set(22, Menu.item(Material.GRAY_DYE, m.item("pshop.mine-none"), List.of()));
        menu.set(49, Menu.item(Material.ARROW, m.item("pshop.back"), List.of()), e -> later(plugin, () -> main(plugin, p)));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    // --- the owner's setup window ---

    public static void editor(NanoCorePlugin plugin, Player p, PlayerShop s) {
        PlayerShopService svc = plugin.playerShops();
        if (svc.get(s.id()) == null) {
            p.closeInventory();
            return;
        }
        Messages m = msg(plugin);
        var eco = plugin.economy();
        Menu menu = new Menu(6, m.msg("pshop.editor-title"));
        menu.set(4, icon(s.item, 1, PlayerShopService.name(s.item), info(plugin, s)));

        ShopTrade.Mode[] modes = ShopTrade.Mode.values();
        Material[] modeIcons = {Material.EMERALD, Material.HOPPER, Material.COMPARATOR};
        int[] modeSlots = {19, 21, 23};
        for (int i = 0; i < modes.length; i++) {
            ShopTrade.Mode mode = modes[i];
            String key = mode.name().toLowerCase(Locale.ROOT);
            ItemStack it = Menu.item(modeIcons[i], m.item("pshop.mode-" + key),
                    List.of(m.item("pshop.mode-" + key + "-lore"), m.item(s.mode() == mode ? "pshop.mode-current" : "pshop.mode-click")));
            if (s.mode() == mode) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            menu.set(modeSlots[i], it, e -> later(plugin, () -> {
                svc.setMode(s, mode);
                editor(plugin, p, s);
            }));
        }
        menu.set(25, Menu.item(Material.ITEM_FRAME, m.item("pshop.use-hand"), List.of(m.item("pshop.use-hand-lore"))), e -> later(plugin, () -> {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.isEmpty()) msg(plugin).send(p, "pshop.need-item");
            else svc.setItem(s, hand);
            editor(plugin, p, s);
        }));
        if (s.mode().ownerSells()) {
            priceRow(plugin, menu, 27, m.item("pshop.sell-price", "price", eco.format(s.sellPrice())), Material.GOLD_INGOT, s.sellPrice(),
                    v -> svc.setSellPrice(s, v), () -> editor(plugin, p, s));
        }
        if (s.mode().ownerBuys()) {
            priceRow(plugin, menu, 36, m.item("pshop.buy-price", "price", eco.format(s.buyPrice())), Material.GOLD_NUGGET, s.buyPrice(),
                    v -> svc.setBuyPrice(s, v), () -> editor(plugin, p, s));
        }
        menu.set(45, Menu.item(Material.ARROW, m.item("pshop.back"), List.of()), e -> later(plugin, () -> mine(plugin, p)));
        menu.set(49, Menu.item(Material.BOOK, m.item("pshop.editor-help-title"), List.of(m.item("pshop.editor-help"), m.item("pshop.editor-help-2"))));
        menu.set(53, Menu.item(Material.BARRIER, m.item("pshop.delete"), List.of(m.item("pshop.delete-lore"))), e -> later(plugin, () ->
                Confirm.open(plugin, p, m.item("pshop.delete-confirm"), List.of(m.item("pshop.delete-lore")), () -> {
                    svc.delete(s, "deleted by " + p.getName());
                    msg(plugin).send(p, "pshop.removed");
                    mine(plugin, p);
                }, () -> editor(plugin, p, s))));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    /** -100 -10 -1 [price] +1 +10 +100 in the row starting at {@code start}. */
    private static void priceRow(NanoCorePlugin plugin, Menu menu, int start, Component title, Material icon, long current,
                                 LongConsumer set, Runnable reopen) {
        long[] steps = {-100, -10, -1, 0, 1, 10, 100};
        for (int i = 0; i < steps.length; i++) {
            long step = steps[i];
            int slot = start + 1 + i;
            if (step == 0) {
                menu.set(slot, Menu.item(icon, title, List.of(plugin.messages().item("pshop.price-tip"))));
                continue;
            }
            Component name = plugin.messages().item(step > 0 ? "pshop.price-up" : "pshop.price-down", "amount", String.valueOf(Math.abs(step)));
            menu.set(slot, Menu.item(step > 0 ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE, name, List.of()),
                    e -> later(plugin, () -> {
                        set.accept(Math.max(step > 0 ? 1 : 0, current + step));
                        reopen.run();
                    }));
        }
    }

    // --- the customer's window ---

    public static void customer(NanoCorePlugin plugin, Player p, PlayerShop s) {
        PlayerShopService svc = plugin.playerShops();
        if (svc.get(s.id()) == null) {
            p.closeInventory();
            return;
        }
        Messages m = msg(plugin);
        var eco = plugin.economy();
        Menu menu = new Menu(3, m.msg("pshop.customer-title", "player", eco.name(s.owner())));
        menu.set(4, icon(s.item, 1, PlayerShopService.name(s.item), info(plugin, s)));
        int[] amounts = ShopTrade.clickAmounts(s.item.getMaxStackSize());
        if (s.mode().ownerSells()) {
            for (int i = 0; i < amounts.length; i++) {
                int n = amounts[i];
                menu.set(10 + i, icon(s.item, n, m.item("pshop.buy-n", "amount", String.valueOf(n)),
                                List.of(m.item("pshop.costs", "price", eco.format(s.sellPrice() * n)))),
                        e -> later(plugin, () -> {
                            ShopTrade.Result r = svc.buy(p, s, n);
                            if (r.ok()) {
                                p.sendMessage(m.rich("pshop.bought", "amount", String.valueOf(r.amount()), "item", PlayerShopService.name(s.item),
                                        "total", eco.format(r.total())));
                            } else {
                                fail(plugin, p, r);
                            }
                            customer(plugin, p, s);
                        }));
            }
        }
        if (s.mode().ownerBuys()) {
            for (int i = 0; i < amounts.length; i++) {
                int n = amounts[i];
                menu.set(14 + i, icon(s.item, n, m.item("pshop.sell-n", "amount", String.valueOf(n)),
                                List.of(m.item("pshop.pays", "price", eco.format(s.buyPrice() * n)))),
                        e -> later(plugin, () -> {
                            ShopTrade.Result r = svc.sell(p, s, n);
                            if (r.ok()) {
                                p.sendMessage(m.rich("pshop.sold", "amount", String.valueOf(r.amount()), "item", PlayerShopService.name(s.item),
                                        "paid", eco.format(r.paid())));
                            } else {
                                fail(plugin, p, r);
                            }
                            customer(plugin, p, s);
                        }));
            }
        }
        List<Component> you = new ArrayList<>();
        you.add(m.item("pshop.your-balance", "balance", eco.format(eco.balance(p.getUniqueId()))));
        if (s.mode().ownerBuys()) you.add(m.item("pshop.you-carry", "amount", String.valueOf(svc.carried(p, s))));
        if (svc.taxPercent() > 0) you.add(m.item("pshop.tax-note", "percent", trim(svc.taxPercent())));
        menu.set(22, Menu.item(Material.PAPER, m.item("pshop.you"), you));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private static void fail(NanoCorePlugin plugin, Player p, ShopTrade.Result r) {
        msg(plugin).send(p, "pshop.fail." + r.status().name().toLowerCase(Locale.ROOT));
    }

    static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // --- browse ---

    /**
     * Every shop, by item and cheapest first. {@code admin} adds a click to manage each shop;
     * {@code owner} limits it to one player's shops.
     */
    public static void browse(NanoCorePlugin plugin, Player p, int page, boolean admin, UUID owner) {
        Messages m = msg(plugin);
        List<PlayerShop> all = new ArrayList<>(plugin.playerShops().browseOrder());
        if (owner != null) all.removeIf(s -> !s.owner().equals(owner));
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(6, m.msg("pshop.browse-title", "page", String.valueOf(current + 1), "pages", String.valueOf(pages)));
        for (int i = 0; i < PER_PAGE && current * PER_PAGE + i < all.size(); i++) {
            PlayerShop s = all.get(current * PER_PAGE + i);
            List<Component> lore = new ArrayList<>(info(plugin, s));
            if (admin) {
                lore.add(m.item("pshop.click-manage"));
                menu.set(i, icon(s.item, 1, PlayerShopService.name(s.item), lore), e -> later(plugin, () -> adminShop(plugin, p, s)));
            } else {
                menu.set(i, icon(s.item, 1, PlayerShopService.name(s.item), lore));
            }
        }
        if (all.isEmpty()) menu.set(22, Menu.item(Material.GRAY_DYE, m.item("pshop.browse-none"), List.of()));
        if (current > 0) menu.set(45, Menu.item(Material.PAPER, m.item("pshop.page-back"), List.of()),
                e -> later(plugin, () -> browse(plugin, p, current - 1, admin, owner)));
        if (current < pages - 1) menu.set(53, Menu.item(Material.PAPER, m.item("pshop.page-next"), List.of()),
                e -> later(plugin, () -> browse(plugin, p, current + 1, admin, owner)));
        menu.set(49, Menu.item(Material.ARROW, m.item("pshop.back"), List.of()), e -> later(plugin, () -> {
            if (admin) io.github.glrp.nanocore.admin.ControlPanel.open(plugin, p);
            else main(plugin, p);
        }));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    /** Admin tools for one shop: go there, open its chest or setup, or delete it. */
    public static void adminShop(NanoCorePlugin plugin, Player p, PlayerShop s) {
        PlayerShopService svc = plugin.playerShops();
        if (svc.get(s.id()) == null) {
            browse(plugin, p, 0, true, null);
            return;
        }
        Messages m = msg(plugin);
        Menu menu = new Menu(3, m.msg("pshop.admin-title", "player", plugin.economy().name(s.owner())));
        menu.set(4, icon(s.item, 1, PlayerShopService.name(s.item), info(plugin, s)));
        menu.set(10, Menu.item(Material.ENDER_PEARL, m.item("pshop.admin-tp"), List.of()), e -> later(plugin, () -> {
            Location at = svc.location(s);
            if (at != null) p.teleport(at.add(0, 1, 0));
            p.closeInventory();
        }));
        menu.set(12, Menu.item(Material.CHEST, m.item("pshop.admin-open"), List.of(m.item("pshop.admin-open-lore"))), e -> later(plugin, () -> {
            Inventory inv = svc.inventory(s);
            if (inv == null) msg(plugin).send(p, "pshop.admin-unloaded");
            else p.openInventory(inv);
        }));
        menu.set(14, Menu.item(Material.WRITABLE_BOOK, m.item("pshop.admin-edit"), List.of()), e -> later(plugin, () -> editor(plugin, p, s)));
        menu.set(16, Menu.item(Material.BARRIER, m.item("pshop.delete"), List.of(m.item("pshop.delete-lore"))), e -> later(plugin, () ->
                Confirm.open(plugin, p, m.item("pshop.delete-confirm"), List.of(m.item("pshop.delete-lore")), () -> {
                    svc.delete(s, "deleted by admin " + p.getName());
                    msg(plugin).send(p, "pshop.removed");
                    browse(plugin, p, 0, true, null);
                }, () -> adminShop(plugin, p, s))));
        menu.set(22, Menu.item(Material.ARROW, m.item("pshop.back"), List.of()), e -> later(plugin, () -> browse(plugin, p, 0, true, null)));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }
}
