package io.github.glrp.nanocore.gui;

import io.github.glrp.nanocore.NanoCorePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * "Click a block": the window closes, and the next block the player hits or right-clicks (within a minute) is
 * handed to the action instead of being broken or used. Used for quest boards, the storehouse and area corners.
 */
public final class BlockPicker implements Listener {

    private record Pick(Consumer<Block> action, long until) {
    }

    private static final long WAIT_MS = 60_000;
    private static final Map<UUID, Pick> waiting = new ConcurrentHashMap<>();

    private final NanoCorePlugin plugin;

    public BlockPicker(NanoCorePlugin plugin) {
        this.plugin = plugin;
    }

    public static void pick(Player p, Component instructions, Consumer<Block> action) {
        waiting.put(p.getUniqueId(), new Pick(action, System.currentTimeMillis() + WAIT_MS));
        p.closeInventory();
        p.sendMessage(instructions);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Pick pick = waiting.remove(e.getPlayer().getUniqueId());
        if (pick == null || System.currentTimeMillis() > pick.until()) return; // too late: the click works as normal
        e.setCancelled(true);
        Block b = e.getClickedBlock();
        plugin.getServer().getScheduler().runTask(plugin, () -> pick.action().accept(b));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        waiting.remove(e.getPlayer().getUniqueId());
    }
}
