package io.github.glrp.nanocore.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
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

    public Menu fill(Material filler) {
        ItemStack pane = item(filler, Component.space(), List.of());
        for (int i = 0; i < inventory.getSize(); i++) if (inventory.getItem(i) == null) inventory.setItem(i, pane);
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
        });
        return stack;
    }
}
