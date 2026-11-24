package io.github.glrp.nanocore.shop;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.gui.Menu;
import io.github.glrp.nanocore.milestone.Milestone;
import io.github.glrp.nanocore.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /sell: the top four rows take items, the bottom row shows the price list button, the running total and the sell button.
 * Items left in the window when it closes (or the server stops) go back to the player.
 */
public final class SellMenu implements InventoryHolder {

    static final int DROP_SLOTS = 36;
    static final int SIZE = 45;
    static final int PRICES_SLOT = 36;
    static final int SUMMARY_SLOT = 40;
    static final int CONFIRM_SLOT = 44;

    private final NanoCorePlugin plugin;
    private final Player player;
    private final Inventory inventory;
    private boolean returned;
    private boolean refreshQueued;

    private SellMenu(NanoCorePlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, SIZE, plugin.messages().msg("shop.menu-title"));
    }

    /** Opens the sell window, or explains that today's limit is used up. */
    public static void open(NanoCorePlugin plugin, Player p) {
        ShopService shop = plugin.shop();
        if (shop.remaining(p.getUniqueId()) <= 0) {
            plugin.messages().send(p, "shop.limit-reached", "time", DailyLimit.format(shop.untilReset()));
            return;
        }
        SellMenu m = new SellMenu(plugin, p);
        m.render();
        p.openInventory(m.inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private Messages msg() {
        return plugin.messages();
    }

    /** The stacks in the drop area, as offers. */
    List<SellQuote.Offer> offers() {
        List<SellQuote.Offer> list = new ArrayList<>();
        for (int i = 0; i < DROP_SLOTS; i++) {
            ItemStack s = inventory.getItem(i);
            if (s == null || s.getType().isAir()) continue;
            boolean intact = !(s.getItemMeta() instanceof Damageable d && d.hasDamage());
            list.add(new SellQuote.Offer(i, s.getType().getKey().getKey(), s.getAmount(), intact));
        }
        return list;
    }

    SellQuote.Result quote() {
        return plugin.shop().quote(offers(), player.getUniqueId());
    }

    /** Updates the bottom row next tick, after the click has moved the items. */
    void refreshLater() {
        if (refreshQueued) return;
        refreshQueued = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            refreshQueued = false;
            if (!returned) render();
        });
    }

    void render() {
        ShopService shop = plugin.shop();
        ItemStack filler = Menu.item(Material.GRAY_STAINED_GLASS_PANE, Component.space(), List.of());
        for (int i = DROP_SLOTS; i < SIZE; i++) inventory.setItem(i, filler);

        inventory.setItem(PRICES_SLOT, Menu.item(Material.BOOK, msg().item("shop.prices-button"),
                List.of(msg().item("shop.prices-button-lore"))));

        SellQuote.Result q = quote();
        long left = shop.remaining(player.getUniqueId());
        List<Component> lore = new ArrayList<>();
        Map<String, long[]> sold = new LinkedHashMap<>();
        Map<String, Object[]> kept = new LinkedHashMap<>();
        for (SellQuote.Line l : q.lines()) {
            String item = l.offer().item();
            if (l.sold() > 0) {
                long[] t = sold.computeIfAbsent(item, k -> new long[2]);
                t[0] += l.sold();
                t[1] += l.coins();
            }
            if (l.kept() > 0) {
                Object[] k = kept.computeIfAbsent(item + "|" + l.reason(), x -> new Object[]{item, l.reason(), 0});
                k[2] = (int) k[2] + l.kept();
            }
        }
        if (sold.isEmpty() && kept.isEmpty()) lore.add(msg().item("shop.summary-empty"));
        sold.forEach((item, t) -> lore.add(msg().richItem("shop.summary-line",
                "count", String.valueOf(t[0]), "item", name(item), "coins", plugin.economy().format(t[1]))));
        for (Object[] k : kept.values()) {
            String item = (String) k[0];
            SellQuote.Reason reason = (SellQuote.Reason) k[1];
            String key = switch (reason) {
                case LOCKED -> "shop.summary-locked";
                case DAMAGED -> "shop.summary-damaged";
                case LIMIT -> "shop.summary-limit";
                default -> "shop.summary-not-bought";
            };
            lore.add(msg().richItem(key, "count", String.valueOf(k[2]), "item", name(item), "milestone", milestoneName(item)));
        }
        lore.add(Component.empty());
        lore.add(msg().item("shop.limit-line", "left", plugin.economy().format(left), "limit", plugin.economy().format(shop.dailyLimit())));
        inventory.setItem(SUMMARY_SLOT, Menu.item(Material.PAPER, msg().item("shop.summary-title"), lore));

        inventory.setItem(CONFIRM_SLOT, q.empty()
                ? Menu.item(Material.GRAY_DYE, msg().item("shop.confirm-empty"), List.of())
                : Menu.item(Material.LIME_DYE, msg().item("shop.confirm-button", "total", plugin.economy().format(q.total())),
                List.of(msg().item("shop.confirm-lore"))));
    }

    /** A bottom-row button was clicked. */
    void button(int slot) {
        if (slot == PRICES_SLOT) {
            Bukkit.getScheduler().runTask(plugin, () -> PriceListMenu.open(plugin, player));
        } else if (slot == CONFIRM_SLOT) {
            confirm();
        }
    }

    private void confirm() {
        SellQuote.Result q = quote();
        if (q.empty()) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f);
            return;
        }
        // Take the sold items out first, then pay, so nothing can be sold twice.
        for (SellQuote.Line l : q.lines()) {
            if (l.sold() <= 0) continue;
            ItemStack s = inventory.getItem(l.offer().slot());
            if (s == null) continue;
            int rest = s.getAmount() - l.sold();
            if (rest <= 0) {
                inventory.setItem(l.offer().slot(), null);
            } else {
                ItemStack keep = s.clone();
                keep.setAmount(rest);
                inventory.setItem(l.offer().slot(), keep);
            }
        }
        plugin.economy().deposit(player.getUniqueId(), q.total(), "admin shop");
        plugin.shop().record(player.getUniqueId(), q);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        msg().send(player, "shop.sold", "count", String.valueOf(q.itemsSold()), "total", plugin.economy().format(q.total()),
                "left", plugin.economy().format(plugin.shop().remaining(player.getUniqueId())));
        boolean leftovers = false;
        for (int i = 0; i < DROP_SLOTS; i++) if (inventory.getItem(i) != null) leftovers = true;
        if (leftovers) msg().send(player, "shop.returned");
        Bukkit.getScheduler().runTask(plugin, () -> player.closeInventory());
    }

    /** Gives back everything still in the drop area (closing, quitting, server stop). Safe to call twice. */
    public void returnItems() {
        if (returned) return;
        returned = true;
        List<ItemStack> back = new ArrayList<>();
        for (int i = 0; i < DROP_SLOTS; i++) {
            ItemStack s = inventory.getItem(i);
            if (s != null && !s.getType().isAir()) back.add(s);
            inventory.setItem(i, null);
        }
        if (back.isEmpty()) return;
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(back.toArray(ItemStack[]::new));
        for (ItemStack s : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), s);
    }

    /** Puts a stack into the drop area (shift-click from the player's inventory). Returns what didn't fit. */
    ItemStack addToDropArea(ItemStack stack) {
        ItemStack rest = stack.clone();
        for (int i = 0; i < DROP_SLOTS && rest.getAmount() > 0; i++) {
            ItemStack s = inventory.getItem(i);
            if (s == null || !s.isSimilar(rest)) continue;
            int move = Math.min(rest.getAmount(), s.getMaxStackSize() - s.getAmount());
            if (move <= 0) continue;
            ItemStack merged = s.clone();
            merged.setAmount(s.getAmount() + move);
            inventory.setItem(i, merged);
            rest.setAmount(rest.getAmount() - move);
        }
        for (int i = 0; i < DROP_SLOTS && rest.getAmount() > 0; i++) {
            if (inventory.getItem(i) != null) continue;
            inventory.setItem(i, rest.clone());
            rest.setAmount(0);
        }
        return rest.getAmount() > 0 ? rest : null;
    }

    static Component name(String item) {
        Material m = Material.matchMaterial(item);
        return m == null ? Component.text(item) : Component.translatable(m.translationKey());
    }

    private String milestoneName(String item) {
        SellPrice p = plugin.shop().prices().get(item);
        if (p == null || p.requires() == null) return "";
        return Milestone.byId(p.requires()).map(plugin.milestones()::displayName).orElse(p.requires());
    }
}
