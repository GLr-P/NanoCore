package io.github.glrp.nanocore.milestone;

import io.github.glrp.nanocore.config.Settings;
import io.github.glrp.nanocore.text.Messages;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Enforces locked milestones: Nether, Nether roof, netherite, End and elytra. Applies to every rank. */
public final class LockListener implements Listener {

    private static final long NOTICE_COOLDOWN_MS = 3000;

    private final MilestoneService milestones;
    private final Messages messages;
    private final Supplier<Settings> settings;
    private final Map<String, Long> lastNotice = new HashMap<>();

    public LockListener(MilestoneService milestones, Messages messages, Supplier<Settings> settings) {
        this.milestones = milestones;
        this.messages = messages;
        this.settings = settings;
    }

    private boolean locked(Milestone m) {
        return !milestones.isUnlocked(m);
    }

    private void notice(Entity who, Milestone m) {
        if (!(who instanceof Player p)) return;
        long now = System.currentTimeMillis();
        String key = p.getUniqueId() + ":" + m.id(); // one cooldown per lock, so different locks each get their message
        Long last = lastNotice.get(key);
        if (last != null && now - last < NOTICE_COOLDOWN_MS) return;
        lastNotice.put(key, now);
        p.sendMessage(messages.msg("milestones.blocked." + m.id()));
    }

    // --- Nether and End portals ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent e) {
        if (e.getReason() == PortalCreateEvent.CreateReason.FIRE && locked(Milestone.NETHER)) {
            e.setCancelled(true);
            if (e.getEntity() != null) notice(e.getEntity(), Milestone.NETHER);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        Milestone m = blockedPortal(e.getCause(), e.getFrom().getWorld());
        if (m != null) {
            e.setCancelled(true);
            notice(e.getPlayer(), m);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        World from = e.getFrom().getWorld();
        if (e.getPortalType() == PortalType.NETHER && from.getEnvironment() != World.Environment.NETHER && locked(Milestone.NETHER)) {
            e.setCancelled(true);
        } else if (e.getPortalType() == PortalType.ENDER && from.getEnvironment() != World.Environment.THE_END && locked(Milestone.END)) {
            e.setCancelled(true);
        }
    }

    /** Which milestone blocks this trip, or null. Leaving the Nether or End is always allowed. */
    private Milestone blockedPortal(PlayerTeleportEvent.TeleportCause cause, World from) {
        if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                && from.getEnvironment() != World.Environment.NETHER && locked(Milestone.NETHER)) return Milestone.NETHER;
        if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL
                && from.getEnvironment() != World.Environment.THE_END && locked(Milestone.END)) return Milestone.END;
        return null;
    }

    // --- Nether roof ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock()) return;
        Location to = e.getTo();
        if (!aboveRoof(to)) return;
        Location safe = aboveRoof(e.getFrom()) ? findBelowRoof(to) : e.getFrom();
        e.setTo(safe);
        notice(e.getPlayer(), Milestone.NETHER_ROOF);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (aboveRoof(e.getTo()) && e.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN
                && e.getCause() != PlayerTeleportEvent.TeleportCause.COMMAND) {
            e.setCancelled(true);
            notice(e.getPlayer(), Milestone.NETHER_ROOF);
        }
    }

    private boolean aboveRoof(Location l) {
        return l != null && l.getWorld().getEnvironment() == World.Environment.NETHER
                && l.getY() > settings.get().netherRoofY() && locked(Milestone.NETHER_ROOF);
    }

    /** A standing spot under the roof at the same x/z, or the world spawn if there is none. */
    private Location findBelowRoof(Location at) {
        World w = at.getWorld();
        int top = Math.min(settings.get().netherRoofY() - 2, w.getMaxHeight() - 2);
        for (int y = top; y > w.getMinHeight() + 1; y--) {
            Block feet = w.getBlockAt(at.getBlockX(), y, at.getBlockZ());
            if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable() && feet.getRelative(0, -1, 0).isSolid()
                    && feet.getType() != Material.LAVA && feet.getRelative(0, -1, 0).getType() != Material.MAGMA_BLOCK) {
                return new Location(w, at.getBlockX() + 0.5, y, at.getBlockZ() + 0.5, at.getYaw(), at.getPitch());
            }
        }
        return w.getSpawnLocation();
    }

    // --- Netherite ---

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (isNetherite(e.getInventory().getResult()) && locked(Milestone.NETHERITE)) e.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (isNetherite(e.getRecipe().getResult()) && locked(Milestone.NETHERITE)) {
            e.setCancelled(true);
            notice(e.getWhoClicked(), Milestone.NETHERITE);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareSmith(PrepareSmithingEvent e) {
        if (isNetherite(e.getResult()) && locked(Milestone.NETHERITE)) e.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSmith(SmithItemEvent e) {
        if (isNetherite(e.getInventory().getResult()) && locked(Milestone.NETHERITE)) {
            e.setCancelled(true);
            notice(e.getWhoClicked(), Milestone.NETHERITE);
        }
    }

    /** Netherite ingots and anything upgraded to netherite. Netherite scrap and debris stay allowed. */
    static boolean isNetherite(ItemStack item) {
        if (item == null) return false;
        String name = item.getType().name();
        return name.equals("NETHERITE_INGOT") || name.equals("NETHERITE_BLOCK")
                || (name.startsWith("NETHERITE_") && !name.equals("NETHERITE_SCRAP") && !name.equals("NETHERITE_UPGRADE_SMITHING_TEMPLATE"));
    }

    // --- Elytra ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.isGliding() && locked(Milestone.ELYTRA) && e.getEntity() instanceof Player p) {
            e.setCancelled(true);
            notice(p, Milestone.ELYTRA);
        }
    }
}
