package io.github.glrp.nanocore.shop;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/** Lets players put items into the sell window's top rows and keeps its bottom row locked. */
public final class SellListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof SellMenu menu)) return;
        int raw = e.getRawSlot();
        // Double-click collecting could pull items out of the button row.
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }
        if (raw >= SellMenu.DROP_SLOTS && raw < SellMenu.SIZE) {
            e.setCancelled(true);
            menu.button(raw);
            return;
        }
        if (raw >= SellMenu.SIZE && e.isShiftClick()) {
            // Vanilla would also fill the button row; move it into the drop area ourselves.
            e.setCancelled(true);
            ItemStack current = e.getCurrentItem();
            if (current != null && !current.getType().isAir()) e.setCurrentItem(menu.addToDropArea(current));
        }
        menu.refreshLater();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof SellMenu menu)) return;
        for (int raw : e.getRawSlots()) {
            if (raw >= SellMenu.DROP_SLOTS && raw < SellMenu.SIZE) {
                e.setCancelled(true);
                return;
            }
        }
        menu.refreshLater();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof SellMenu menu && e.getPlayer() instanceof Player) menu.returnItems();
    }
}
