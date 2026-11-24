package io.github.glrp.nanocore.event;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.area.Area;
import io.github.glrp.nanocore.text.Messages;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the gameplay parts of the current phase (see {@link PartsDef}): waves and bosses, plots, themes, collection
 * lists, treasure, races, the hill, arena floors, hide and seek, streaks, deliveries and checklists.
 * Its state lives in {@link EventState#data}, so a restart continues where it was.
 */
public final class EventParts implements Listener {

    private static final int MAX_ALIVE = 80;
    private static final long FILL_LIMIT = 250_000;

    private final NanoCorePlugin plugin;
    private final EventService ev;
    private final Messages msg;
    private final NamespacedKey mobKey;
    private final Random rng = new Random();

    // Not saved: rebuilt after a restart.
    private final Set<UUID> alive = new HashSet<>();
    private UUID bossId;
    private final Map<UUID, double[]> raceRuns = new HashMap<>(); // [next checkpoint, start ms, in start area 1/0]
    private final Map<UUID, Double> lastHint = new HashMap<>();
    private final Map<UUID, Long> protectedUntil = new HashMap<>();
    private int seconds;
    private boolean resumeNeeded;
    private long nextAbility;
    private int abilityTurn;

    EventParts(NanoCorePlugin plugin, EventService ev, Messages msg) {
        this.plugin = plugin;
        this.ev = ev;
        this.msg = msg;
        this.mobKey = new NamespacedKey(plugin, "event_mob");
    }

    private EventState st() {
        return ev.state();
    }

    private PartsDef parts() {
        EventDef.Phase ph = ev.phase();
        return ph == null ? null : ph.parts();
    }

    private World world() {
        return ev.mainWorldForParts();
    }

    // --- phase start / end ---

    void startPhase(EventDef def, EventDef.Phase ph) {
        PartsDef p = ph.parts();
        EventState s = st();
        clearMobs();
        s.data.remove("wave");
        s.data.remove("cleared");
        s.data.remove("waveAt");
        s.data.remove("bossDead");
        s.data.remove("enraged");
        raceRuns.clear();
        if (p.revive()) revive(ph);
        if (p.fill() != null) fill(p.fill());
        if (!p.themes().isEmpty() && s.get("theme") == null) {
            String theme = p.themes().get(rng.nextInt(p.themes().size()));
            s.data.put("theme", theme);
            plugin.getServer().sendMessage(msg.msg("events.theme", "theme", theme));
            Title t = Title.title(msg.msg("events.theme-title"), msg.msg("events.theme-subtitle", "theme", theme),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(700)));
            for (Player pl : ev.onlineParticipants()) pl.showTitle(t);
        }
        if (p.plots() != null) assignPlots(p.plots());
        if (p.collect() != null && s.get("collect") == null) {
            List<String> list = PartsRules.pickList(p.collect().pool(), p.collect().count(), rng);
            s.data.put("collect", String.join(",", list));
            announceList();
        }
        if (p.treasure() != null && s.get("treasure") == null) hideTreasure(p.treasure());
        if (p.seekers() > 0 && s.data.keySet().stream().noneMatch(k -> k.startsWith("seeker."))) pickSeekers(p.seekers());
        if (p.blindSeekers()) {
            long ticks = ph.end().timerMs() > 0 ? ph.end().timerMs() / 50 : 20 * 60;
            for (Player pl : ev.onlineParticipants()) {
                if (!seeker(pl.getUniqueId())) continue;
                pl.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, (int) ticks, 0, false, false));
                pl.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) ticks, 10, false, false));
            }
        }
        if (p.teleport() != null) {
            for (Player pl : ev.onlineParticipants()) teleportIn(def, p, pl);
        }
        if (p.hasWaves()) {
            nextAbility = System.currentTimeMillis() + 8000;
            if (p.waves().isEmpty()) spawnBoss(p);
            else spawnWave(0, p);
        }
    }

    void endPhase(EventDef.Phase ph) {
        if (ph == null) return;
        PartsDef p = ph.parts();
        if (p.survivors()) {
            for (Player pl : ev.onlineParticipants()) {
                if (!st().eliminated.contains(pl.getUniqueId())) ev.credit(pl, "event.survive", 1, pl.getLocation());
            }
        }
        if (p.hasWaves()) clearMobs();
        for (Player pl : ev.onlineParticipants()) {
            if (p.blindSeekers()) {
                pl.removePotionEffect(PotionEffectType.BLINDNESS);
                pl.removePotionEffect(PotionEffectType.SLOWNESS);
            }
        }
    }

    /** The event is over (won, lost or cancelled): clean up everything the parts put in the world. */
    void finish() {
        clearMobs();
        EventState s = st();
        if (s == null) return;
        String spots = s.get("treasure");
        if (spots != null) {
            for (String spot : spots.split(";")) {
                Block b = block(spot);
                if (b != null && b.getType() == Material.CHEST) b.setType(Material.AIR);
            }
        }
        for (var e : new ArrayList<>(s.data.entrySet())) {
            if (!e.getKey().startsWith("return.")) continue;
            UUID id = UUID.fromString(e.getKey().substring(7));
            Player p = plugin.getServer().getPlayer(id);
            Location l = location(e.getValue());
            if (p != null && l != null && !s.kitted.contains(id)) p.teleportAsync(l);
        }
        raceRuns.clear();
        protectedUntil.clear();
        lastHint.clear();
    }

    /** After a restart in the middle of a wave: the old mobs are gone from memory, so start the wave again. */
    void resume() {
        PartsDef p = parts();
        if (p != null && p.hasWaves() && st().get("cleared") == null) resumeNeeded = true; // once players are back
    }

    // --- every second ---

    void tick() {
        PartsDef p = parts();
        EventState s = st();
        if (p == null || s == null || s.phase < 0 || s.paused()) return;
        seconds++;
        if (p.hasWaves()) tickWaves(p);
        if (p.holdArea() != null) tickHold(p);
        Integer below = outBelow(p);
        if (below != null) {
            for (Player pl : ev.onlineParticipants()) {
                if (!s.eliminated.contains(pl.getUniqueId()) && pl.getGameMode() != GameMode.SPECTATOR && pl.getLocation().getY() < below) {
                    ev.eliminate(pl);
                }
            }
        }
        if (p.seekers() > 0) {
            for (Player pl : ev.onlineParticipants()) if (!seeker(pl.getUniqueId())) ev.credit(pl, "event.hide", 1, pl.getLocation());
        }
        if (p.treasure() != null && seconds % 2 == 0) tickTreasureHints();
    }

    /** Players below this are out: the set height, or 2 below the floor an arena refill makes. */
    private Integer outBelow(PartsDef p) {
        if (p.outBelow() != null) return p.outBelow();
        if (p.fill() == null) return null;
        var a = plugin.areas().get(p.fill().area());
        return a.isPresent() && a.get().kind() == Area.Kind.BOX ? (int) a.get().y1() - 2 : null;
    }

    /** End conditions the parts decide: waves cleared, enough finishers, goals met, one hider left. */
    boolean ended(EventDef.Phase ph) {
        EventState s = st();
        PartsDef p = ph.parts();
        if (ph.end().cleared() && p.hasWaves() && s.get("cleared") != null) return true;
        if (ph.end().finishers() > 0 && s.getLong("finishers", 0) >= ph.end().finishers()) return true;
        if (ph.end().goal() && goalMet(p)) return true;
        if (p.treasure() != null && s.get("treasure") != null && treasureLeft() == 0) return true;
        if (p.seekers() > 0 && s.participants.size() >= 2 && hiders() <= 1 && s.data.keySet().stream().anyMatch(k -> k.startsWith("seeker."))) {
            return p.blindSeekers() ? false : true;
        }
        return false;
    }

    /** True if the event is lost right now ({@code timerUp}: the phase timer just ran out). */
    boolean lost(EventDef.Phase ph, boolean timerUp) {
        PartsDef.Lose l = ph.parts().lose();
        if (l == null) return false;
        EventState s = st();
        if (l.timer() && timerUp && s.get("cleared") == null && !goalMet(ph.parts())) return true;
        if (l.allDown() && !s.participants.isEmpty()) {
            for (UUID id : s.participants) if (!s.eliminated.contains(id)) return false;
            return true;
        }
        return false;
    }

    boolean goalMet(PartsDef p) {
        EventState s = st();
        if (!p.checklist().isEmpty()) {
            for (int i = 0; i < p.checklist().size(); i++) if (s.get("check." + i) == null) return false;
            return true;
        }
        if (p.deliver() != null && !p.deliver().need().isEmpty()) return PartsRules.needsMet(p.deliver().need(), delivered(p.deliver()));
        return false;
    }

    // --- display ---

    /** Extra sidebar lines for everyone. */
    List<Component> lines() {
        List<Component> out = new ArrayList<>();
        PartsDef p = parts();
        EventState s = st();
        if (p == null) return out;
        if (s.get("theme") != null) out.add(msg.msg("events.sidebar-theme", "theme", s.get("theme")));
        if (p.hasWaves()) {
            int wave = (int) s.getLong("wave", 0);
            if (s.get("cleared") != null) out.add(msg.msg("events.sidebar-cleared"));
            else if (wave >= p.waves().size() && p.boss() != null) out.add(msg.msg("events.sidebar-boss", "boss", p.boss().name()));
            else out.add(msg.msg("events.sidebar-wave", "wave", String.valueOf(wave + 1), "waves", String.valueOf(p.waves().size()),
                        "left", String.valueOf(alive.size())));
        }
        if (!p.checklist().isEmpty()) {
            for (int i = 0; i < p.checklist().size() && i < 6; i++) {
                out.add(msg.msg(s.get("check." + i) != null ? "events.sidebar-check-done" : "events.sidebar-check", "n", String.valueOf(i + 1),
                        "task", p.checklist().get(i)));
            }
        }
        if (p.deliver() != null && !p.deliver().need().isEmpty()) {
            Map<String, Long> got = delivered(p.deliver());
            int n = 0;
            for (var e : p.deliver().need().entrySet()) {
                if (n++ >= 6) break;
                out.add(msg.rich("events.sidebar-need", "item", itemName(e.getKey()), "have", fmt(Math.min(e.getValue(), got.getOrDefault(e.getKey(), 0L))),
                        "need", fmt(e.getValue())));
            }
        }
        if (p.treasure() != null) out.add(msg.msg("events.sidebar-treasure", "left", String.valueOf(treasureLeft())));
        if (p.seekers() > 0) out.add(msg.msg("events.sidebar-hiders", "count", String.valueOf(hiders())));
        if (p.collect() != null && s.get("collect") != null) out.add(msg.msg("events.sidebar-handed", "count", String.valueOf(s.getLong("finishers", 0))));
        return out;
    }

    /** Extra sidebar lines for one participant. */
    List<Component> lines(Player pl) {
        List<Component> out = new ArrayList<>();
        PartsDef p = parts();
        EventState s = st();
        if (p == null) return out;
        UUID id = pl.getUniqueId();
        String plot = s.get("plot." + id);
        if (plot != null) {
            String[] f = plot.split(",");
            out.add(msg.msg("events.sidebar-plot", "x", f[1], "z", f[2]));
        }
        if (p.collect() != null && s.get("collect") != null) {
            List<String> list = Arrays.asList(s.get("collect").split(","));
            int have = 0;
            for (String it : list) {
                Material m = Material.matchMaterial(it);
                if (m != null && pl.getInventory().contains(m)) have++;
            }
            out.add(msg.msg(s.get("done." + id) != null ? "events.sidebar-list-done" : "events.sidebar-list", "have", String.valueOf(have),
                    "count", String.valueOf(list.size())));
        }
        if (p.seekers() > 0) out.add(msg.msg(seeker(id) ? "events.sidebar-seeker" : "events.sidebar-hider"));
        if (p.race() != null) {
            long score = s.score(id);
            if (score > 0) out.add(msg.msg("events.sidebar-best", "time", PartsRules.raceTime(EventDef.TIME_BASE - score)));
        }
        return out;
    }

    /** Boss health on the boss bar while a boss is up. */
    BossBarState bossBar() {
        PartsDef p = parts();
        if (p == null || p.boss() == null || bossId == null) return null;
        Entity e = plugin.getServer().getEntity(bossId);
        if (!(e instanceof LivingEntity le) || le.isDead()) return null;
        double max = maxHealth(le);
        return new BossBarState(msg.msg("events.bossbar-boss", "boss", p.boss().name(), "health", String.valueOf((int) Math.ceil(le.getHealth()))),
                (float) Math.max(0, Math.min(1, le.getHealth() / max)), BossBar.Color.RED);
    }

    record BossBarState(Component name, float progress, BossBar.Color color) {
    }

    // --- waves and bosses ---

    private void spawnWave(int index, PartsDef p) {
        EventState s = st();
        s.data.put("wave", String.valueOf(index));
        s.data.put("waveAt", String.valueOf(System.currentTimeMillis()));
        PartsDef.Wave w = p.waves().get(index);
        int players = Math.max(1, ev.onlineParticipants().size());
        int spawned = 0;
        for (PartsDef.MobSpec m : w.mobs()) {
            int n = PartsRules.scaled(m.count(), players, p.scale());
            for (int i = 0; i < n && alive.size() < MAX_ALIVE; i++) {
                LivingEntity e = spawnMob(m.type(), p, m.name());
                if (e == null) continue;
                dress(e, w);
                spawned++;
            }
        }
        if (spawned == 0) {
            s.data.put("retry", "1"); // no room to spawn (unloaded chunks, nobody online): try again next second
            return;
        }
        s.data.remove("retry");
        plugin.getServer().sendMessage(msg.msg("events.wave", "wave", String.valueOf(index + 1), "waves", String.valueOf(p.waves().size())));
        for (Player pl : ev.onlineParticipants()) {
            pl.showTitle(Title.title(msg.msg("events.wave-title", "wave", String.valueOf(index + 1)), Component.empty(),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
            pl.playSound(pl.getLocation(), Sound.EVENT_RAID_HORN, 0.8f, 1f);
        }
    }

    private void spawnBoss(PartsDef p) {
        EventState s = st();
        s.data.put("wave", String.valueOf(p.waves().size()));
        if (p.boss() == null) {
            s.data.put("cleared", "1");
            return;
        }
        PartsDef.Boss b = p.boss();
        if (b.existing()) {
            bossId = null; // found in tickWaves
            plugin.getServer().sendMessage(msg.msg("events.boss-existing", "boss", b.name()));
            return;
        }
        LivingEntity e = spawnMob(b.type(), p, b.name());
        if (e == null) {
            s.data.put("cleared", "1");
            return;
        }
        AttributeInstance hp = e.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(b.health());
            e.setHealth(b.health());
        }
        e.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, "boss");
        bossId = e.getUniqueId();
        alive.remove(bossId);
        plugin.getServer().sendMessage(msg.msg("events.boss", "boss", b.name()));
        for (Player pl : ev.onlineParticipants()) {
            pl.showTitle(Title.title(msg.msg("events.boss-title", "boss", b.name()), msg.msg("events.boss-subtitle"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
            pl.playSound(pl.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.7f, 1f);
        }
    }

    private void tickWaves(PartsDef p) {
        EventState s = st();
        if (s.get("cleared") != null || ev.onlineParticipants().isEmpty()) return;
        if (resumeNeeded) {
            resumeNeeded = false;
            clearMobs();
            int w0 = (int) s.getLong("wave", 0);
            if (w0 >= p.waves().size()) {
                if (s.get("bossDead") == null) spawnBoss(p);
            } else {
                spawnWave(w0, p);
            }
            return;
        }
        if (s.get("retry") != null && s.getLong("wave", 0) < p.waves().size()) {
            spawnWave((int) s.getLong("wave", 0), p);
            return;
        }
        alive.removeIf(id -> {
            Entity e = plugin.getServer().getEntity(id);
            return e == null || e.isDead() || !e.isValid();
        });
        int wave = (int) s.getLong("wave", 0);
        boolean bossUp = wave >= p.waves().size();
        if (bossUp && p.boss() != null && p.boss().existing() && bossId == null) {
            for (World w : plugin.getServer().getWorlds()) {
                for (LivingEntity le : w.getLivingEntities()) {
                    if (le.getType().getKey().getKey().equals(p.boss().type())) {
                        bossId = le.getUniqueId();
                        break;
                    }
                }
                if (bossId != null) break;
            }
        }
        if (bossUp) {
            Entity boss = bossId == null ? null : plugin.getServer().getEntity(bossId);
            boolean bossDead = p.boss() == null || s.get("bossDead") != null;
            if (boss instanceof LivingEntity le && !le.isDead()) abilities(p.boss(), le);
            if (bossDead && alive.isEmpty()) {
                s.data.put("cleared", "1");
                plugin.getServer().sendMessage(msg.msg("events.cleared"));
            }
            target();
            return;
        }
        PartsDef.Wave w = p.waves().get(wave);
        long at = s.getLong("waveAt", System.currentTimeMillis());
        boolean timeUp = w.nextMs() > 0 && System.currentTimeMillis() - at >= w.nextMs();
        if (alive.isEmpty() || timeUp) {
            if (wave + 1 < p.waves().size()) spawnWave(wave + 1, p);
            else spawnBoss(p);
        }
        target();
    }

    /** Every few seconds: idle event mobs go for the nearest player taking part. */
    private void target() {
        if (seconds % 3 != 0) return;
        List<Player> players = new ArrayList<>();
        for (Player pl : ev.onlineParticipants()) if (pl.getGameMode() == GameMode.SURVIVAL || pl.getGameMode() == GameMode.ADVENTURE) players.add(pl);
        if (players.isEmpty()) return;
        List<UUID> ids = new ArrayList<>(alive);
        if (bossId != null) ids.add(bossId);
        for (UUID id : ids) {
            if (!(plugin.getServer().getEntity(id) instanceof Mob m) || (m.getTarget() instanceof Player t && t.isValid())) continue;
            Player best = null;
            double bestD = 48 * 48;
            for (Player pl : players) {
                if (!pl.getWorld().equals(m.getWorld())) continue;
                double d = pl.getLocation().distanceSquared(m.getLocation());
                if (d < bestD) {
                    bestD = d;
                    best = pl;
                }
            }
            if (best != null) m.setTarget(best);
        }
    }

    private void abilities(PartsDef.Boss b, LivingEntity boss) {
        double max = maxHealth(boss);
        if (b.abilities().contains("enrage") && boss.getHealth() < max * 0.25 && st().get("enraged") == null) {
            st().data.put("enraged", "1");
            boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 1));
            boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1));
            plugin.getServer().sendMessage(msg.msg("events.boss-enraged", "boss", b.name()));
        }
        long now = System.currentTimeMillis();
        List<String> active = new ArrayList<>(b.abilities());
        active.remove("enrage");
        if (active.isEmpty() || now < nextAbility) return;
        nextAbility = now + 8000;
        String a = active.get(abilityTurn++ % active.size());
        Location at = boss.getLocation();
        List<Player> near = new ArrayList<>();
        for (Player pl : ev.onlineParticipants()) {
            if (pl.getWorld().equals(at.getWorld()) && pl.getLocation().distanceSquared(at) < 30 * 30 && pl.getGameMode() != GameMode.SPECTATOR) near.add(pl);
        }
        if (near.isEmpty()) return;
        switch (a) {
            case "minions" -> {
                for (int i = 0; i < 3 && alive.size() < MAX_ALIVE; i++) {
                    Location l = safeSpot(at.getWorld(), at.getBlockX() + rng.nextInt(7) - 3, at.getBlockZ() + rng.nextInt(7) - 3, at.getBlockY());
                    if (l == null) continue;
                    LivingEntity m = spawnAt(b.minion(), l, null);
                    if (m != null) m.getWorld().spawnParticle(Particle.LARGE_SMOKE, l, 10, 0.3, 0.5, 0.3, 0.01);
                }
                say("events.ability-minions", b);
            }
            case "slam" -> {
                at.getWorld().spawnParticle(Particle.EXPLOSION, at, 6, 2, 0.2, 2, 0.1);
                at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.7f);
                for (Player pl : near) {
                    if (pl.getLocation().distanceSquared(at) > 36) continue;
                    pl.damage(6, boss);
                    Vector away = pl.getLocation().toVector().subtract(at.toVector()).setY(0);
                    if (away.lengthSquared() > 0) away.normalize().multiply(0.8);
                    pl.setVelocity(away.setY(0.9));
                }
                say("events.ability-slam", b);
            }
            case "leap" -> {
                Player t = near.get(rng.nextInt(near.size()));
                Vector v = t.getLocation().toVector().subtract(at.toVector());
                boss.setVelocity(v.multiply(0.15).setY(0.9));
                say("events.ability-leap", b);
            }
            case "shield" -> {
                boss.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 120, 4));
                boss.getWorld().spawnParticle(Particle.END_ROD, at.clone().add(0, 1, 0), 30, 0.6, 1, 0.6, 0.02);
                say("events.ability-shield", b);
            }
            case "teleport" -> {
                Player t = near.get(rng.nextInt(near.size()));
                Location behind = t.getLocation().clone().add(t.getLocation().getDirection().setY(0).normalize().multiply(-2));
                Location l = safeSpot(behind.getWorld(), behind.getBlockX(), behind.getBlockZ(), behind.getBlockY());
                if (l != null) {
                    boss.teleport(l);
                    l.getWorld().playSound(l, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);
                }
                say("events.ability-teleport", b);
            }
            case "pull" -> {
                for (Player pl : near) {
                    if (pl.getLocation().distanceSquared(at) > 15 * 15) continue;
                    Vector to = at.toVector().subtract(pl.getLocation().toVector());
                    pl.setVelocity(to.multiply(0.12).setY(0.4));
                }
                say("events.ability-pull", b);
            }
            default -> {
            }
        }
    }

    private void say(String key, PartsDef.Boss b) {
        for (Player pl : ev.onlineParticipants()) pl.sendActionBar(msg.msg(key, "boss", b.name()));
    }

    private LivingEntity spawnMob(String type, PartsDef p, String name) {
        Location l = spawnPoint(p);
        return l == null ? null : spawnAt(type, l, name);
    }

    private LivingEntity spawnAt(String type, Location l, String name) {
        EntityType t = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(type));
        if (t == null || !t.isAlive() || !t.isSpawnable()) return null;
        Entity e = l.getWorld().spawnEntity(l, t);
        if (!(e instanceof LivingEntity le)) {
            e.remove();
            return null;
        }
        le.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, "wave");
        le.setRemoveWhenFarAway(false);
        le.setPersistent(true);
        if (le instanceof PiglinAbstract pa) pa.setImmuneToZombification(true);
        if (le instanceof Hoglin h) h.setImmuneToZombification(true);
        if (name != null) {
            le.customName(Messages.parse(name));
            le.setCustomNameVisible(true);
        }
        alive.add(le.getUniqueId());
        return le;
    }

    private void dress(LivingEntity e, PartsDef.Wave w) {
        AttributeInstance hp = e.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null && w.health() != 1) {
            hp.setBaseValue(Math.max(1, hp.getBaseValue() * w.health()));
            e.setHealth(hp.getBaseValue());
        }
        AttributeInstance dmg = e.getAttribute(Attribute.ATTACK_DAMAGE);
        if (dmg != null && w.damage() != 1) dmg.setBaseValue(dmg.getBaseValue() * w.damage());
        EntityEquipment eq = e.getEquipment();
        if (eq != null) {
            if (w.armor() != null) {
                String a = w.armor().equals("gold") ? "golden" : w.armor();
                eq.setHelmet(item(a + "_helmet"));
                eq.setChestplate(item(a + "_chestplate"));
                eq.setLeggings(item(a + "_leggings"));
                eq.setBoots(item(a + "_boots"));
            }
            if (w.weapon() != null) eq.setItemInMainHand(item(w.weapon()));
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                try {
                    eq.setDropChance(slot, 0f);
                } catch (IllegalArgumentException ignored) {
                    // body/saddle slots on mobs that don't have them
                }
            }
        }
        for (String fx : w.effects()) {
            String[] f = fx.trim().toLowerCase(Locale.ROOT).split("\\s+");
            PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(f[0]));
            int level = 0;
            if (f.length > 1) {
                try {
                    level = Math.max(0, Integer.parseInt(f[1]) - 1);
                } catch (NumberFormatException ignored) {
                    // keep level 1
                }
            }
            if (type != null) e.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, level));
        }
    }

    private static ItemStack item(String id) {
        Material m = Material.matchMaterial(id);
        return m == null ? null : ItemStack.of(m);
    }

    private Location spawnPoint(PartsDef p) {
        if (p.spawnArea() != null) {
            var a = plugin.areas().get(p.spawnArea());
            if (a.isPresent()) {
                Location l = randomIn(a.get());
                if (l != null) return l;
            }
        }
        List<Player> players = new ArrayList<>(ev.onlineParticipants());
        players.removeIf(pl -> pl.getGameMode() == GameMode.SPECTATOR);
        if (players.isEmpty()) return null;
        Player pl = players.get(rng.nextInt(players.size()));
        for (int tries = 0; tries < 12; tries++) {
            double angle = rng.nextDouble() * Math.PI * 2, dist = 10 + rng.nextInt(7);
            Location c = pl.getLocation();
            Location l = safeSpot(c.getWorld(), (int) Math.floor(c.getX() + Math.cos(angle) * dist), (int) Math.floor(c.getZ() + Math.sin(angle) * dist), c.getBlockY());
            if (l != null) return l;
        }
        return pl.getLocation().clone().add(2, 0, 2);
    }

    private Location randomIn(Area a) {
        World w = plugin.getServer().getWorld(a.world());
        if (w == null) return null;
        for (int tries = 0; tries < 12; tries++) {
            int x, z, y;
            if (a.kind() == Area.Kind.CIRCLE) {
                double angle = rng.nextDouble() * Math.PI * 2, r = Math.sqrt(rng.nextDouble()) * a.radius();
                x = (int) Math.floor(a.x1() + Math.cos(angle) * r);
                z = (int) Math.floor(a.z1() + Math.sin(angle) * r);
                y = w.getHighestBlockYAt(x, z);
            } else {
                x = (int) a.x1() + rng.nextInt((int) (a.x2() - a.x1()) + 1);
                z = (int) a.z1() + rng.nextInt((int) (a.z2() - a.z1()) + 1);
                y = (int) a.y1();
            }
            Location l = safeSpot(w, x, z, y);
            if (l != null) return l;
        }
        return null;
    }

    /** A spot with solid ground and two blocks of air, searched up and down from {@code aroundY}. */
    static Location safeSpot(World w, int x, int z, int aroundY) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        for (int d = 0; d <= 10; d++) {
            for (int y : new int[]{aroundY + d, aroundY - d}) {
                if (y <= w.getMinHeight() || y >= w.getMaxHeight() - 2) continue;
                Block feet = w.getBlockAt(x, y, z);
                if (feet.isPassable() && !feet.isLiquid() && feet.getRelative(0, 1, 0).isPassable() && feet.getRelative(0, -1, 0).getType().isSolid()) {
                    return new Location(w, x + 0.5, y, z + 0.5);
                }
            }
        }
        return null;
    }

    private void clearMobs() {
        for (UUID id : alive) {
            Entity e = plugin.getServer().getEntity(id);
            if (e != null) e.remove();
        }
        alive.clear();
        if (bossId != null) {
            Entity e = plugin.getServer().getEntity(bossId);
            PartsDef p = parts();
            boolean existing = p != null && p.boss() != null && p.boss().existing();
            if (e != null && !existing && e.getPersistentDataContainer().has(mobKey)) e.remove();
            bossId = null;
        }
        // Mobs from before a restart.
        for (World w : plugin.getServer().getWorlds()) {
            for (LivingEntity le : w.getLivingEntities()) if (le.getPersistentDataContainer().has(mobKey)) le.remove();
        }
    }

    private static double maxHealth(LivingEntity le) {
        AttributeInstance a = le.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    // --- plots ---

    private void assignPlots(PartsDef.Plots plots) {
        var area = plugin.areas().get(plots.area());
        if (area.isEmpty() || area.get().kind() != Area.Kind.BOX) {
            plugin.getLogger().warning("Event plots need a box area called " + plots.area());
            return;
        }
        Area a = area.get();
        int width = (int) (a.x2() - a.x1()) + 1, depth = (int) (a.z2() - a.z1()) + 1;
        int cap = PartsRules.plotCapacity(width, depth, plots.size(), plots.gap());
        int i = 0;
        for (UUID id : st().participants) {
            if (st().get("plot." + id) != null) {
                i++;
                continue;
            }
            if (i >= cap) {
                Player pl = plugin.getServer().getPlayer(id);
                if (pl != null) msg.send(pl, "events.no-plot");
                continue;
            }
            int[] c = PartsRules.plotCorner(i++, (int) a.x1(), (int) a.z1(), width, plots.size(), plots.gap());
            st().data.put("plot." + id, a.world() + "," + c[0] + "," + c[1] + "," + (int) a.y1());
        }
    }

    /** The owner of the plot this block is in, or null when it's in no plot. */
    private UUID plotOwner(Block b, PartsDef.Plots plots) {
        for (var e : st().data.entrySet()) {
            if (!e.getKey().startsWith("plot.")) continue;
            String[] f = e.getValue().split(",");
            if (!f[0].equals(b.getWorld().getName())) continue;
            int x = Integer.parseInt(f[1]), z = Integer.parseInt(f[2]);
            if (b.getX() >= x && b.getX() < x + plots.size() && b.getZ() >= z && b.getZ() < z + plots.size()) {
                return UUID.fromString(e.getKey().substring(5));
            }
        }
        return null;
    }

    /** /event plots: everyone's plot and the way there. */
    public List<String[]> plotList() {
        List<String[]> out = new ArrayList<>();
        EventState s = st();
        if (s == null) return out;
        for (var e : s.data.entrySet()) {
            if (!e.getKey().startsWith("plot.")) continue;
            String[] f = e.getValue().split(",");
            out.add(new String[]{plugin.economy().name(UUID.fromString(e.getKey().substring(5))), f[1], f[3], f[2]});
        }
        return out;
    }

    // --- teleports in and back ---

    private void teleportIn(EventDef def, PartsDef p, Player pl) {
        EventState s = st();
        UUID id = pl.getUniqueId();
        if (s.eliminated.contains(id)) return;
        if (s.get("return." + id) == null) s.data.put("return." + id, text(pl.getLocation()));
        Location to = null;
        if (p.teleport().equals("plot")) {
            String plot = s.get("plot." + id);
            PartsDef.Plots plots = p.plots();
            if (plot != null && plots != null) {
                String[] f = plot.split(",");
                World w = plugin.getServer().getWorld(f[0]);
                if (w != null) to = new Location(w, Integer.parseInt(f[1]) + plots.size() / 2.0, Integer.parseInt(f[3]) + 1, Integer.parseInt(f[2]) + plots.size() / 2.0);
            }
        } else {
            String areaName = def.area() != null ? def.area() : p.holdArea();
            if (areaName != null) {
                var a = plugin.areas().get(areaName);
                if (a.isPresent()) to = randomIn(a.get());
            }
        }
        if (to != null) pl.teleportAsync(to);
    }

    // --- revive, fill, elimination ---

    private void revive(EventDef.Phase ph) {
        EventState s = st();
        for (UUID id : new ArrayList<>(s.eliminated)) {
            Player pl = plugin.getServer().getPlayer(id);
            if (pl != null && pl.getGameMode() == GameMode.SPECTATOR) {
                pl.setGameMode(ph.kit() != null && "creative".equals(ph.kit().gamemode()) ? GameMode.CREATIVE
                        : ph.kit() != null && "adventure".equals(ph.kit().gamemode()) ? GameMode.ADVENTURE : GameMode.SURVIVAL);
            }
        }
        s.eliminated.clear();
        s.lives.clear();
        if (ph.lives() > 0) for (UUID id : s.participants) s.lives.put(id, ph.lives());
    }

    private void fill(PartsDef.Fill f) {
        var area = plugin.areas().get(f.area());
        Material m = Material.matchMaterial(f.block());
        if (area.isEmpty() || area.get().kind() != Area.Kind.BOX || m == null || !m.isBlock()) {
            plugin.getLogger().warning("Event fill needs a box area called " + f.area() + " and a block, not " + f.block());
            return;
        }
        Area a = area.get();
        World w = plugin.getServer().getWorld(a.world());
        long volume = (long) (a.x2() - a.x1() + 1) * (long) (a.y2() - a.y1() + 1) * (long) (a.z2() - a.z1() + 1);
        if (w == null || volume > FILL_LIMIT) {
            plugin.getLogger().warning("Event fill area " + f.area() + " is too big (" + volume + " blocks, limit " + FILL_LIMIT + ")");
            return;
        }
        for (int x = (int) a.x1(); x <= (int) a.x2(); x++)
            for (int y = (int) a.y1(); y <= (int) a.y2(); y++)
                for (int z = (int) a.z1(); z <= (int) a.z2(); z++) w.getBlockAt(x, y, z).setType(m, false);
    }

    // --- collection list ---

    private void announceList() {
        String list = st().get("collect");
        if (list == null) return;
        List<String> names = new ArrayList<>();
        for (String it : list.split(",")) names.add(it.replace('_', ' '));
        plugin.getServer().sendMessage(msg.msg("events.collect-list", "items", String.join(", ", names)));
    }

    /** /event list. */
    public void showList(Player p) {
        if (st() == null || st().get("collect") == null) {
            msg.send(p, "events.collect-none");
            return;
        }
        List<String> missing = new ArrayList<>(), have = new ArrayList<>();
        for (String it : st().get("collect").split(",")) {
            Material m = Material.matchMaterial(it);
            (m != null && p.getInventory().contains(m) ? have : missing).add(it.replace('_', ' '));
        }
        msg.send(p, "events.collect-status", "have", String.join(", ", have), "missing", String.join(", ", missing));
    }

    /** /event handin: the full list, in one go. Returns a message key. */
    public String handIn(Player p) {
        PartsDef parts = parts();
        EventState s = st();
        if (parts == null || parts.collect() == null || s.get("collect") == null) return "events.collect-none";
        UUID id = p.getUniqueId();
        if (!s.participants.contains(id)) return "events.not-in";
        if (s.get("done." + id) != null) return "events.collect-already";
        if (parts.collect().atBoard() && !plugin.questBoards().boards().isEmpty() && !plugin.questBoards().nearBoard(p, plugin.quests().settings().boardReach())) {
            return "events.collect-at-board";
        }
        List<Material> need = new ArrayList<>();
        for (String it : s.get("collect").split(",")) {
            Material m = Material.matchMaterial(it);
            if (m != null) need.add(m);
        }
        for (Material m : need) if (!p.getInventory().contains(m)) return "events.collect-missing";
        for (Material m : need) p.getInventory().removeItem(ItemStack.of(m));
        int order = (int) s.getLong("finishers", 0) + 1;
        s.data.put("finishers", String.valueOf(order));
        s.data.put("done." + id, String.valueOf(order));
        s.scores.put(id, PartsRules.finishScore(order));
        plugin.getServer().sendMessage(msg.msg("events.collect-done", "player", p.getName(), "place", ev.ordinalFor(order)));
        return null;
    }

    // --- treasure ---

    private void hideTreasure(PartsDef.Treasure t) {
        Location center = null;
        if (t.around() != null) {
            var a = plugin.areas().get(t.around());
            if (a.isPresent()) {
                World w = plugin.getServer().getWorld(a.get().world());
                if (w != null) {
                    double x = a.get().kind() == Area.Kind.CIRCLE ? a.get().x1() : (a.get().x1() + a.get().x2()) / 2;
                    double z = a.get().kind() == Area.Kind.CIRCLE ? a.get().z1() : (a.get().z1() + a.get().z2()) / 2;
                    center = new Location(w, x, 64, z);
                }
            }
        }
        if (center == null) center = world().getSpawnLocation();
        World w = center.getWorld();
        List<String> spots = new ArrayList<>();
        for (int i = 0, tries = 0; i < t.chests() && tries < t.chests() * 20; tries++) {
            double angle = rng.nextDouble() * Math.PI * 2, r = 20 + rng.nextDouble() * (t.radius() - 20);
            int x = (int) Math.floor(center.getX() + Math.cos(angle) * r), z = (int) Math.floor(center.getZ() + Math.sin(angle) * r);
            w.getChunkAt(x >> 4, z >> 4).load(true);
            Block top = w.getHighestBlockAt(x, z);
            if (top.isLiquid() || top.getType() == Material.ICE) continue;
            Block b = top.getRelative(0, 1, 0);
            b.setType(Material.CHEST);
            spots.add(w.getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ());
            i++;
        }
        st().data.put("treasure", String.join(";", spots));
        plugin.getServer().sendMessage(msg.msg("events.treasure-hidden", "count", String.valueOf(spots.size()), "radius", String.valueOf(t.radius())));
    }

    private int treasureLeft() {
        String s = st().get("treasure");
        return s == null || s.isEmpty() ? 0 : s.split(";").length;
    }

    private void tickTreasureHints() {
        String spots = st().get("treasure");
        if (spots == null || spots.isEmpty()) return;
        List<Location> ls = new ArrayList<>();
        for (String spot : spots.split(";")) {
            Location l = location(spot + ",0,0");
            if (l != null) ls.add(l);
        }
        for (Player pl : ev.onlineParticipants()) {
            double best = Double.MAX_VALUE;
            for (Location l : ls) if (l.getWorld().equals(pl.getWorld())) best = Math.min(best, l.distance(pl.getLocation()));
            if (best == Double.MAX_VALUE) continue;
            String h = PartsRules.hint(lastHint.getOrDefault(pl.getUniqueId(), -1.0), best);
            lastHint.put(pl.getUniqueId(), best);
            pl.sendActionBar(msg.msg("events.hint-" + h));
        }
    }

    private boolean isTreasure(Block b) {
        String spots = st() == null ? null : st().get("treasure");
        if (spots == null || spots.isEmpty()) return false;
        String key = b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
        for (String s : spots.split(";")) if (s.equals(key)) return true;
        return false;
    }

    private void foundTreasure(Player p, Block b) {
        PartsDef.Treasure t = parts().treasure();
        String key = b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
        List<String> spots = new ArrayList<>(Arrays.asList(st().get("treasure").split(";")));
        spots.remove(key);
        st().data.put("treasure", String.join(";", spots));
        b.setType(Material.AIR);
        b.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, b.getLocation().add(0.5, 0.5, 0.5), 40, 0.4, 0.6, 0.4, 0.2);
        b.getWorld().playSound(b.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        if (t.coins() > 0) plugin.economy().deposit(p.getUniqueId(), t.coins(), "event treasure");
        if (t.trophy()) ev.trophyFor(p.getUniqueId(), 0);
        ev.credit(p, "event.treasure", 1, p.getLocation());
        plugin.getServer().sendMessage(msg.msg("events.treasure-found", "player", p.getName(), "left", String.valueOf(spots.size()),
                "coins", plugin.economy().format(t.coins())));
    }

    // --- hold the hill ---

    private void tickHold(PartsDef p) {
        var area = plugin.areas().get(p.holdArea());
        if (area.isEmpty()) return;
        List<Player> in = new ArrayList<>();
        List<Integer> teams = new ArrayList<>();
        for (Player pl : ev.onlineParticipants()) {
            if (st().eliminated.contains(pl.getUniqueId()) || pl.getGameMode() == GameMode.SPECTATOR) continue;
            Location l = pl.getLocation();
            if (area.get().contains(l.getWorld().getName(), l.getX(), l.getY(), l.getZ())) {
                in.add(pl);
                teams.add(st().teams.get(pl.getUniqueId()));
            }
        }
        boolean scores = PartsRules.holderScores(teams);
        for (Player pl : in) {
            if (scores) ev.credit(pl, "event.hold", 1, pl.getLocation());
            else pl.sendActionBar(msg.msg("events.hill-contested"));
        }
    }

    // --- hide and seek ---

    private void pickSeekers(int n) {
        List<UUID> ids = new ArrayList<>(st().participants);
        java.util.Collections.shuffle(ids, rng);
        for (int i = 0; i < Math.min(n, Math.max(0, ids.size() - 1)); i++) {
            st().data.put("seeker." + ids.get(i), "1");
            Player pl = plugin.getServer().getPlayer(ids.get(i));
            if (pl != null) msg.send(pl, "events.you-seek");
        }
        for (UUID id : ids) {
            Player pl = plugin.getServer().getPlayer(id);
            if (pl != null && !seeker(id)) msg.send(pl, "events.you-hide");
        }
    }

    private boolean seeker(UUID id) {
        return st() != null && st().get("seeker." + id) != null;
    }

    private int hiders() {
        int n = 0;
        for (UUID id : st().participants) if (!seeker(id)) n++;
        return n;
    }

    // --- deliveries ---

    private Map<String, Long> delivered(PartsDef.Deliver d) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String k : d.need().keySet()) out.put(k, st().getLong("delivered." + k, 0));
        return out;
    }

    /** The key ("diamond" or "#planks") an item counts for, or null. */
    private static String keyFor(PartsDef.Deliver d, Material m) {
        String id = m.getKey().getKey();
        if (d.has(id)) return id;
        for (String k : d.need().keySet()) if (k.startsWith("#") && inTag(k, m)) return k;
        for (String k : d.points().keySet()) if (k.startsWith("#") && inTag(k, m)) return k;
        return null;
    }

    private static boolean inTag(String key, Material m) {
        Tag<Material> tag = org.bukkit.Bukkit.getTag(Tag.REGISTRY_ITEMS, NamespacedKey.minecraft(key.substring(1)), Material.class);
        return tag != null && tag.isTagged(m);
    }

    /** /event deliver: hands in everything in your inventory the event takes. Returns a message key, or null when done. */
    public String deliver(Player p) {
        PartsDef parts = parts();
        EventState s = st();
        if (parts == null || parts.deliver() == null) return "events.deliver-none";
        PartsDef.Deliver d = parts.deliver();
        if (!s.participants.contains(p.getUniqueId())) {
            if (ev.join(p, true) != EventService.JoinResult.OK) return "events.not-in";
        }
        if (d.atBoard() && !plugin.questBoards().boards().isEmpty() && !plugin.questBoards().nearBoard(p, plugin.quests().settings().boardReach())) {
            return "events.deliver-at-board";
        }
        ItemStack[] bag = p.getInventory().getStorageContents();
        long points = 0;
        int items = 0;
        for (int i = 0; i < bag.length; i++) {
            ItemStack it = bag[i];
            if (it == null || it.isEmpty() || it.hasItemMeta() && it.getItemMeta().hasDisplayName()) continue;
            String key = keyFor(d, it.getType());
            if (key == null) continue;
            int take = it.getAmount();
            Long need = d.need().get(key);
            if (need != null && !d.points().containsKey(key)) {
                long left = need - s.getLong("delivered." + key, 0);
                if (left <= 0) continue;
                take = (int) Math.min(take, left);
            }
            if (need != null) s.data.put("delivered." + key, String.valueOf(s.getLong("delivered." + key, 0) + take));
            points += take * d.points().getOrDefault(key, 1L);
            items += take;
            if (d.storehouse()) plugin.questBoards().store(it.getType(), take);
            if (take == it.getAmount()) bag[i] = null;
            else it.setAmount(it.getAmount() - take);
        }
        if (items == 0) return "events.deliver-nothing";
        p.getInventory().setStorageContents(bag);
        ev.credit(p, "event.deliver", points, p.getLocation());
        msg.send(p, "events.delivered", "items", String.valueOf(items), "points", fmt(points));
        return null;
    }

    // --- checklist ---

    /** /event check n: ticks (or unticks) a checklist task. Returns a message key. */
    public String check(int n, String by) {
        PartsDef parts = parts();
        if (parts == null || parts.checklist().isEmpty()) return "events.check-none";
        if (n < 1 || n > parts.checklist().size()) return "events.check-bad";
        String key = "check." + (n - 1);
        boolean done = st().data.remove(key) == null;
        if (done) st().data.put(key, by);
        plugin.getServer().sendMessage(msg.msg(done ? "events.check-done" : "events.check-undone", "task", parts.checklist().get(n - 1)));
        return null;
    }

    public List<String> checklist() {
        PartsDef p = parts();
        return p == null ? List.of() : p.checklist();
    }

    // --- race ---

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        PartsDef p = parts();
        if (p == null || p.race() == null || !ev.participant(e.getPlayer().getUniqueId()) || st().paused()) return;
        PartsDef.Race race = p.race();
        List<String> cps = new ArrayList<>();
        for (String c : race.checkpoints()) if (plugin.areas().get(c).isPresent()) cps.add(c); // checkpoints that exist
        Player pl = e.getPlayer();
        Location l = e.getTo();
        boolean inStart = inArea(race.start(), l);
        double[] run = raceRuns.computeIfAbsent(pl.getUniqueId(), k -> new double[]{0, 0, 0});
        // Standing on the start line when the race began, then straight out: that's a start too.
        if (!inStart && run[2] == 0 && run[1] == 0 && inArea(race.start(), e.getFrom())) run[2] = 1;
        if (inStart) {
            if (run[2] == 0) pl.sendActionBar(msg.msg("events.race-ready"));
            run[0] = 0;
            run[1] = 0;
            run[2] = 1;
            return;
        }
        if (run[2] == 1) {
            run[2] = 0;
            run[1] = System.currentTimeMillis();
            run[0] = 0;
            pl.sendActionBar(msg.msg("events.race-go"));
            pl.playSound(pl.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.5f);
            return;
        }
        if (run[1] == 0) return;
        int next = (int) run[0];
        if (next < cps.size()) {
            if (inArea(cps.get(next), l)) {
                run[0] = next + 1;
                pl.sendActionBar(msg.msg("events.race-checkpoint", "n", String.valueOf(next + 1), "of", String.valueOf(cps.size()),
                        "time", PartsRules.raceTime(System.currentTimeMillis() - (long) run[1])));
                pl.playSound(pl.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.6f);
            }
            return;
        }
        if (inArea(race.finish(), l)) {
            long time = System.currentTimeMillis() - (long) run[1];
            run[1] = 0;
            long oldScore = st().score(pl.getUniqueId());
            long score = EventDef.TIME_BASE - time;
            boolean best = score > oldScore;
            if (best) st().scores.put(pl.getUniqueId(), score);
            st().actions.merge(pl.getUniqueId(), 50L, Long::sum);
            msg.send(pl, best ? "events.race-best" : "events.race-time", "time", PartsRules.raceTime(time),
                    "best", PartsRules.raceTime(EventDef.TIME_BASE - Math.max(score, oldScore)));
            pl.playSound(pl.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        }
    }

    private boolean inArea(String name, Location l) {
        return plugin.areas().inside(name, l);
    }

    // --- listeners ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        EventState s = st();
        if (s == null || s.phase < 0) return;
        Player attacker = attacker(e.getDamager());
        Entity victim = e.getEntity() instanceof ComplexEntityPart part ? part.getParent() : e.getEntity();
        PartsDef p = parts();
        if (p == null) return;
        // Boss damage scores.
        if (attacker != null && p.boss() != null && victim instanceof LivingEntity
                && (victim.getUniqueId().equals(bossId) || p.boss().existing() && victim.getType().getKey().getKey().equals(p.boss().type()))) {
            if (bossId == null) bossId = victim.getUniqueId();
            ev.credit(attacker, "event.boss_damage", Math.max(1, Math.round(e.getFinalDamage())), attacker.getLocation());
            return;
        }
        if (!(victim instanceof Player hurt) || attacker == null || !ev.participant(hurt.getUniqueId()) || !ev.participant(attacker.getUniqueId())) return;
        // Hide and seek: a seeker's hit finds a hider; no other harm between players.
        if (p.seekers() > 0) {
            e.setCancelled(true);
            if (p.blindSeekers()) return;
            if (seeker(attacker.getUniqueId()) && !seeker(hurt.getUniqueId())) {
                s.data.put("seeker." + hurt.getUniqueId(), "1");
                plugin.getServer().sendMessage(msg.msg("events.found", "player", hurt.getName(), "seeker", attacker.getName(),
                        "left", String.valueOf(hiders())));
                hurt.playSound(hurt.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            }
            return;
        }
        // Spawn protection after dying.
        long now = System.currentTimeMillis();
        if (protectedUntil.getOrDefault(hurt.getUniqueId(), 0L) > now) {
            e.setCancelled(true);
            attacker.sendActionBar(msg.msg("events.protected", "player", hurt.getName()));
            return;
        }
        protectedUntil.remove(attacker.getUniqueId()); // attacking ends your own protection
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMobDeath(EntityDeathEvent e) {
        if (!e.getEntity().getPersistentDataContainer().has(mobKey)) {
            // An existing boss (the Ender Dragon) dying counts too.
            PartsDef p = parts();
            if (p != null && p.boss() != null && p.boss().existing() && st() != null
                    && (e.getEntity().getUniqueId().equals(bossId) || e.getEntity().getType().getKey().getKey().equals(p.boss().type()))) {
                st().data.put("bossDead", "1");
                Player k = e.getEntity().getKiller();
                if (k != null && ev.participant(k.getUniqueId())) ev.credit(k, "event.boss_kill", 1, k.getLocation());
            }
            return;
        }
        String kind = e.getEntity().getPersistentDataContainer().get(mobKey, PersistentDataType.STRING);
        if ("boss".equals(kind) && st() != null) st().data.put("bossDead", "1");
        e.getDrops().clear();
        e.setDroppedExp(0);
        alive.remove(e.getEntity().getUniqueId());
        Player k = e.getEntity().getKiller();
        if (k != null && ev.participant(k.getUniqueId())) ev.credit(k, "boss".equals(kind) ? "event.boss_kill" : "event.wave_kill", 1, k.getLocation());
    }

    /** Event mobs don't burn in daylight. */
    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (e instanceof EntityCombustByBlockEvent || e instanceof EntityCombustByEntityEvent) return;
        if (e.getEntity().getPersistentDataContainer().has(mobKey)) e.setCancelled(true);
    }

    /** Event mobs fight players, not each other. */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (e.getTarget() != null && e.getTarget().getPersistentDataContainer().has(mobKey) && e.getEntity().getPersistentDataContainer().has(mobKey)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        EventState s = st();
        if (s == null || s.phase < 0 || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        // Eyes of ender thrown count for "use.ender_eye" (the End Times).
        boolean intoFrame = e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.END_PORTAL_FRAME;
        if ((e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK && !intoFrame) && e.getItem() != null
                && e.getItem().getType() == Material.ENDER_EYE && ev.participant(p.getUniqueId())) {
            ev.credit(p, "use.ender_eye", 1, p.getLocation());
        }
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.CHEST && isTreasure(e.getClickedBlock())) {
            e.setCancelled(true);
            if (ev.participant(p.getUniqueId())) foundTreasure(p, e.getClickedBlock());
            else msg.send(p, "events.treasure-join");
        }
    }

    /** Plots: only the owner builds in their plot; nobody builds between plots. Treasure chests can't be broken. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (st() == null) return;
        if (isTreasure(e.getBlock())) {
            e.setCancelled(true);
            return;
        }
        if (!plotAllows(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (st() != null && !plotAllows(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    private boolean plotAllows(Player p, Block b) {
        PartsDef parts = parts();
        if (parts == null || parts.plots() == null || p.hasPermission("nanocore.admin")) return true;
        var area = plugin.areas().get(parts.plots().area());
        if (area.isEmpty() || !area.get().contains(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())) return true;
        UUID owner = plotOwner(b, parts.plots());
        if (p.getUniqueId().equals(owner)) return true;
        p.sendActionBar(msg.msg(owner == null ? "events.plot-between" : "events.plot-not-yours"));
        return false;
    }

    /** Blocks this phase lets you break even with building off. */
    public boolean breakable(Block b) {
        PartsDef p = parts();
        return p != null && p.breakable().contains(b.getType().getKey().getKey());
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (st() != null) e.blockList().removeIf(this::isTreasure);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (st() != null) e.blockList().removeIf(this::isTreasure);
    }

    /** Kill streaks (Purge). */
    void onPlayerKill(Player killer) {
        PartsDef p = parts();
        if (p == null || p.streak() == null) return;
        String key = "streak." + killer.getUniqueId();
        long streak = st().getLong(key, 0) + 1;
        st().data.put(key, String.valueOf(streak));
        if (streak >= p.streak().at() && p.streak().points() > 0) {
            ev.addPoints(killer.getUniqueId(), p.streak().points());
            msg.send(killer, "events.streak", "streak", String.valueOf(streak), "points", String.valueOf(p.streak().points()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        EventState s = st();
        if (s == null || s.phase < 0 || !ev.participant(e.getEntity().getUniqueId())) return;
        s.data.remove("streak." + e.getEntity().getUniqueId());
        Player k = e.getEntity().getKiller();
        if (k != null && !k.equals(e.getEntity()) && ev.participant(k.getUniqueId())) onPlayerKill(k);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        PartsDef p = parts();
        if (p == null || !ev.participant(e.getPlayer().getUniqueId())) return;
        // Arena events: back into the arena after dying.
        if (p.teleport() != null && ev.current() != null && ev.current().area() != null && !st().eliminated.contains(e.getPlayer().getUniqueId())) {
            var a = plugin.areas().get(ev.current().area());
            Location to = a.isPresent() ? randomIn(a.get()) : null;
            if (to != null) e.setRespawnLocation(to);
        }
        if (p.respawnProtectionMs() <= 0) return;
        protectedUntil.put(e.getPlayer().getUniqueId(), System.currentTimeMillis() + p.respawnProtectionMs());
        msg.send(e.getPlayer(), "events.respawn-protected", "time", Durations.format(p.respawnProtectionMs()));
    }

    /** Player shop tax while this phase runs (Market Day), or null. */
    public Double shopTax() {
        PartsDef p = parts();
        return p == null ? null : p.shopTax();
    }

    // --- helpers ---

    private Block block(String spot) {
        String[] f = spot.split(",");
        if (f.length < 4) return null;
        World w = plugin.getServer().getWorld(f[0]);
        return w == null ? null : w.getBlockAt(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]));
    }

    private static String text(Location l) {
        return l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ() + "," + l.getYaw() + "," + l.getPitch();
    }

    private Location location(String text) {
        String[] f = text.split(",");
        if (f.length < 6) return null;
        World w = plugin.getServer().getWorld(f[0]);
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]), Float.parseFloat(f[4]), Float.parseFloat(f[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Component itemName(String key) {
        if (key.startsWith("#")) return Component.text(key.substring(1).replace('_', ' '));
        Material m = Material.matchMaterial(key);
        return m == null ? Component.text(key) : Component.translatable(m.translationKey());
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
