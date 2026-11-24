package io.github.glrp.nanocore.event;

import io.github.glrp.nanocore.NanoCorePlugin;
import org.bukkit.Location;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** The rule switches of the running event phase: PvP, drops on death, griefing, building, damage, hunger and more. */
public final class EventRulesListener implements Listener {

    private final NanoCorePlugin plugin;
    private final Map<UUID, Long> lastNotice = new HashMap<>();

    public EventRulesListener(NanoCorePlugin plugin) {
        this.plugin = plugin;
    }

    private EventDef.Rules at(Location l) {
        return plugin.events().rulesAt(l);
    }

    private void notice(Player p, String key) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(p.getUniqueId());
        if (last != null && now - last < 3000) return;
        lastNotice.put(p.getUniqueId(), now);
        plugin.messages().send(p, key, "event", plugin.events().current() == null ? "" : plugin.events().current().name());
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player hitter = attacker(e.getDamager());
        if (hitter == null || hitter.equals(victim)) return;
        EventDef.Rules r = at(victim.getLocation());
        if (r != null && Boolean.FALSE.equals(r.pvp())) {
            e.setCancelled(true);
            notice(hitter, "events.rule-pvp");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        EventDef.Rules r = at(p.getLocation());
        if (r == null) return;
        if (Boolean.TRUE.equals(r.keepInventory())) {
            e.setKeepInventory(true);
            e.getDrops().clear();
            e.setKeepLevel(true);
            e.setDroppedExp(0);
        } else if (r.dropChance() != null) {
            // Each stack has a chance to drop; the rest stays with the player.
            e.setKeepInventory(true);
            e.getDrops().clear();
            PlayerInventory inv = p.getInventory();
            Location at = p.getLocation();
            int chance = r.dropChance();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s == null || s.getType().isAir()) continue;
                if (ThreadLocalRandom.current().nextInt(100) < chance) {
                    at.getWorld().dropItemNaturally(at, s.clone());
                    inv.setItem(i, null);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobChange(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player) return;
        EventDef.Rules r = at(e.getBlock().getLocation());
        if (r != null && Boolean.FALSE.equals(r.mobGriefing())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobExplode(EntityExplodeEvent e) {
        if (e.getEntity() instanceof TNTPrimed) return; // TNT isn't a mob
        EventDef.Rules r = at(e.getLocation());
        if (r != null && Boolean.FALSE.equals(r.mobGriefing())) e.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getPlayer().hasPermission("nanocore.admin")) return;
        EventDef.Rules r = at(e.getBlock().getLocation());
        if (r != null && Boolean.FALSE.equals(r.building())) {
            e.setCancelled(true);
            notice(e.getPlayer(), "events.rule-building");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getPlayer().hasPermission("nanocore.admin")) return;
        EventDef.Rules r = at(e.getBlock().getLocation());
        if (r != null && Boolean.FALSE.equals(r.building())) {
            e.setCancelled(true);
            notice(e.getPlayer(), "events.rule-building");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        EventDef.Rules r = at(p.getLocation());
        if (r != null && Boolean.FALSE.equals(r.fallDamage())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getFoodLevel() >= p.getFoodLevel()) return;
        EventDef.Rules r = at(p.getLocation());
        if (r != null && Boolean.FALSE.equals(r.hunger())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        EntityRegainHealthEvent.RegainReason why = e.getRegainReason();
        if (why != EntityRegainHealthEvent.RegainReason.SATIATED && why != EntityRegainHealthEvent.RegainReason.REGEN) return;
        EventDef.Rules r = at(p.getLocation());
        if (r != null && Boolean.FALSE.equals(r.naturalRegen())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPearl(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof EnderPearl pearl) || !(pearl.getShooter() instanceof Player p)) return;
        EventDef.Rules r = at(p.getLocation());
        if (r != null && Boolean.FALSE.equals(r.enderPearls())) {
            e.setCancelled(true);
            notice(p, "events.rule-pearls");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (!e.isGliding() || !(e.getEntity() instanceof Player p)) return;
        EventDef.Rules r = at(p.getLocation());
        if (r != null && Boolean.FALSE.equals(r.elytra())) {
            e.setCancelled(true);
            notice(p, "events.rule-elytra");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent e) {
        EventDef.Phase ph = plugin.events().phase();
        if (ph == null || ph.rules().weather() == null) return;
        boolean wantRain = ph.rules().weather().equals("rain");
        if (e.toWeatherState() != wantRain) e.setCancelled(true);
    }
}
