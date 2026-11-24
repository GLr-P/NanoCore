package io.github.glrp.nanocore.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A chest-style menu: items in slots, optional click actions. Players can't take or move anything.
 * Plain chest menus work for Bedrock players through Geyser too.
 */
public class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    public Menu(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, Math.max(1, Math.min(6, rows)) * 9, title);
    }

    public Menu set(int slot, ItemStack item) {
        inventory.setItem(slot, item);
        actions.remove(slot);
        return this;
    }

    public Menu set(int slot, ItemStack item, Consumer<InventoryClickEvent> onClick) {
        inventory.setItem(slot, item);
        actions.put(slot, onClick);
        return this;
    }

    public void open(Player p) {
        p.openInventory(inventory);
    }

    void click(InventoryClickEvent e) {
        Consumer<InventoryClickEvent> a = actions.get(e.getRawSlot());
        if (a != null) a.accept(e);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** An item with a name and lore, without the default italics. */
    public static ItemStack item(Material type, Component name, List<Component> lore) {
        ItemStack stack = ItemStack.of(type);
        stack.editMeta(meta -> {
            meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            meta.lore(lore.stream().map(c -> c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
            meta.addItemFlags(ItemFlag.values()); // buttons show only their text, not "attack damage" and the like
        });
        return stack;
    }

    /** A button: its name, then the lore lines, then a yellow "what a click does" line. */
    public static ItemStack button(Material type, Component name, List<Component> lore, Component click) {
        List<Component> all = new java.util.ArrayList<>(lore);
        if (click != null) {
            if (!all.isEmpty()) all.add(Component.empty());
            all.add(click);
        }
        return item(type, name, all);
    }

    /** Fills empty slots with dark panes and the outer frame with a lighter colour, so buttons stand out. */
    public Menu frame() {
        int size = inventory.getSize();
        ItemStack edge = item(Material.GRAY_STAINED_GLASS_PANE, Component.space(), List.of());
        ItemStack inside = item(Material.BLACK_STAINED_GLASS_PANE, Component.space(), List.of());
        for (int i = 0; i < size; i++) {
            if (inventory.getItem(i) != null) continue;
            boolean border = i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8;
            inventory.setItem(i, border && size > 18 ? edge : inside);
        }
        return this;
    }
}
