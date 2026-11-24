package io.github.glrp.nanocore.event;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.db.Database;
import io.github.glrp.nanocore.milestone.Milestone;
import io.github.glrp.nanocore.stats.StatKeys;
import io.github.glrp.nanocore.text.Messages;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Runs events: announcement, phases and their end conditions, joining, points from the stats tracker, the
 * sidebar and boss bar, kits, votes, results, prizes and history. One event at a time. The running event is saved
 * to the database, so a restart continues it.
 */
public final class EventService implements Listener {

    public enum JoinResult { OK, NONE, ALREADY, CLOSED, FULL }

    public record Result(UUID who, int place, long score) {
    }

    public record HistoryEntry(String event, String name, long ended, List<Result> results) {
    }

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private final NanoCorePlugin plugin;
    private final Database db;
    private final Messages msg;
    private final KitService kits;
    private final EventDisplay display = new EventDisplay();
    private final Discord discord;
    private final NamespacedKey trophyKey;
    private final Random rng = new Random();
    private volatile EventSettings settings;
    private volatile Map<String, EventDef> defs = Map.of();

    private EventState state;
    private EventDef def;
    private final Map<String, LocalDate> lastRun = new HashMap<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private final Map<UUID, Integer> pendingClaims = new HashMap<>();
    private String autoDay;
    private boolean dirty;
    private int ticks;
    private long lastTod = -1;
    private final Map<UUID, Long> lastBar = new HashMap<>();

    public EventService(NanoCorePlugin plugin, Database db, Messages msg) {
        this.plugin = plugin;
        this.db = db;
        this.msg = msg;
        this.kits = new KitService(plugin, db);
        this.discord = new Discord(plugin.getLogger());
        this.trophyKey = new NamespacedKey(plugin, "trophy");
        reload();
        load();
    }

    // --- config ---

    public void reload() {
        settings = EventSettings.from(plugin.getConfig().getConfigurationSection("events"), plugin.getLogger()::warning);
        discord.configure(settings.webhookUrl(), settings.webhookName());
        File main = new File(plugin.getDataFolder(), "events.yml");
        if (!main.exists()) plugin.saveResource("events.yml", false);
        Map<String, EventDef> all = new LinkedHashMap<>(parseFile(main));
        // Extra files, one per custom event if you like: plugins/NanoCore/events/*.yml
        File dir = new File(plugin.getDataFolder(), "events");
        File[] extra = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (extra != null) for (File f : extra) all.putAll(parseFile(f));
        defs = all;
        if (state != null && defs.containsKey(state.eventId)) def = defs.get(state.eventId);
    }

    private Map<String, EventDef> parseFile(File f) {
        return EventDefs.parse(YamlConfiguration.loadConfiguration(f), settings.prizes(),
                id -> Milestone.byId(id).isPresent(),
                id -> {
                    Material m = Material.matchMaterial(id);
                    return m != null && m.isItem();
                },
                w -> plugin.getLogger().warning(f.getName() + ": " + w));
    }

    public Map<String, EventDef> defs() {
        return defs;
    }

    public EventSettings settings() {
        return settings;
    }

    public Discord discord() {
        return discord;
    }

    public KitService kits() {
        return kits;
    }

    private void load() {
        db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT data FROM event_active WHERE id = 1"); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try {
                        state = EventState.fromYaml(rs.getString(1));
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("The saved event couldn't be read and was dropped: " + e.getMessage());
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT event, name, ended, results FROM event_history ORDER BY ended");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HistoryEntry h = new HistoryEntry(rs.getString(1), rs.getString(2), rs.getLong(3), results(rs.getString(4)));
                    history.add(h);
                    lastRun.put(h.event(), Instant.ofEpochMilli(h.ended()).atZone(settings.zone()).toLocalDate());
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, COUNT(*) FROM event_claims GROUP BY uuid"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) pendingClaims.put(UUID.fromString(rs.getString(1)), rs.getInt(2));
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM meta WHERE key = 'events.auto-day'"); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) autoDay = rs.getString(1);
            }
            return null;
        });
        if (state != null) {
            def = defs.get(state.eventId);
            if (def == null) {
                plugin.getLogger().warning("The running event '" + state.eventId + "' is no longer in events.yml; it was stopped");
                state = null;
                db.run(c -> Database.update(c, "DELETE FROM event_active"));
            } else {
                plugin.getLogger().info("Continuing the event " + def.name());
            }
        }
    }

    private static List<Result> results(String text) {
        List<Result> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String part : text.split(";")) {
            String[] f = part.split(":");
            if (f.length != 3) continue;
            try {
                out.add(new Result(UUID.fromString(f[0]), Integer.parseInt(f[1]), Long.parseLong(f[2])));
            } catch (IllegalArgumentException ignored) {
                // a broken row: skip it
            }
        }
        return out;
    }

    private void save() {
        dirty = false;
        if (state == null) {
            db.run(c -> Database.update(c, "DELETE FROM event_active"));
            return;
        }
        String data = state.toYaml();
        db.run(c -> Database.update(c, "INSERT OR REPLACE INTO event_active (id, data) VALUES (1, ?)", data));
    }

    /** Saves the running event (every few seconds and at shutdown). */
    public void saveNow() {
        if (dirty || state != null) save();
    }

    // --- what others read ---

    public EventState state() {
        return state;
    }

    public EventDef current() {
        return def;
    }

    public EventDef.Phase phase() {
        return state == null || def == null ? null : def.phase(state.phase);
    }

    public boolean running() {
        return state != null;
    }

    public boolean participant(UUID id) {
        return state != null && state.participants.contains(id);
    }

    public List<HistoryEntry> history() {
        return List.copyOf(history);
    }

    /** Rules in force at a place right now, or null for none. Safe zones only switch PvP off. */
    public EventDef.Rules rulesAt(Location l) {
        EventDef.Phase ph = phase();
        if (ph == null || ph.rules().empty() || l == null) return null;
        for (String zone : def.safeZones()) if (plugin.areas().inside(zone, l)) return SAFE;
        if (ph.rules().inAreaOnly() && def.area() != null && !plugin.areas().inside(def.area(), l)) return null;
        return ph.rules();
    }

    private static final EventDef.Rules SAFE = new EventDef.Rules(false, null, null, null, null, null, null, null, null, null, null, null, null, null, false);

    /** Quest reward multiplier from the running phase (1 when none). */
    public double questMultiplier() {
        EventDef.Phase ph = phase();
        return ph == null || ph.rules().questMultiplier() == null ? 1.0 : ph.rules().questMultiplier();
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(settings.zone());
    }

    private World mainWorld() {
        if (def != null && def.area() != null) {
            var a = plugin.areas().get(def.area());
            if (a.isPresent()) {
                World w = plugin.getServer().getWorld(a.get().world());
                if (w != null) return w;
            }
        }
        return plugin.getServer().getWorlds().getFirst();
    }

    // --- starting and stopping ---

    /**
     * Starts an event. With {@code announceUntil} in the future it's announced first (sign-up time) and the first
     * phase begins then; otherwise the first phase begins now. Returns false if an event is already running.
     */
    public boolean start(EventDef d, long announceUntil, boolean auto) {
        if (state != null) return false;
        long now = System.currentTimeMillis();
        state = new EventState();
        state.eventId = d.id();
        state.autoStarted = auto;
        state.startedAt = now;
        def = d;
        lastTod = -1;
        if (announceUntil > now) {
            state.phase = -1;
            state.phaseStartedAt = now;
            state.endsAt = announceUntil;
            broadcastIntro("events.announced", Durations.format(announceUntil - now));
            discord.send(msg.raw("events.discord-announced").replace("<event>", d.name()),
                    String.join("\n", d.rules()) + "\n" + msg.raw("events.discord-starts").replace("<time>", Durations.format(announceUntil - now)),
                    Discord.BLUE, List.of());
            dirty = true;
            save();
        } else {
            broadcastIntro("events.started-now", "");
            beginPhase(0);
        }
        return true;
    }

    private void broadcastIntro(String key, String time) {
        Component join = def.autoJoin() ? msg.msg("events.everyone-in") : msg.msg("events.join-button");
        plugin.getServer().sendMessage(msg.msg("events.intro-line"));
        plugin.getServer().sendMessage(msg.msg(key, "event", def.name(), "time", time));
        for (String line : def.rules()) plugin.getServer().sendMessage(msg.msg("events.rule-line", "rule", line));
        plugin.getServer().sendMessage(join.append(Component.space()).append(msg.msg("events.rules-button")));
        plugin.getServer().sendMessage(msg.msg("events.intro-line"));
    }

    private void beginPhase(int i) {
        if (i >= def.phases().size()) {
            finish(true);
            return;
        }
        long now = System.currentTimeMillis();
        boolean first = state.phase < 0;
        state.phase = i;
        state.phaseStartedAt = now;
        state.ready.clear();
        state.pausedRemaining = -1;
        lastTod = -1;
        EventDef.Phase ph = def.phase(i);
        state.endsAt = ph.end().timerMs() > 0 ? now + ph.end().timerMs() : 0;
        state.mcEnd = ph.end().mcTicks() > 0 ? mainWorld().getFullTime() + ph.end().mcTicks() : -1;
        if (first) {
            if (def.autoJoin()) for (Player p : plugin.getServer().getOnlinePlayers()) add(p, true);
            if (def.teams() > 0) {
                state.teams.clear();
                state.teams.putAll(EventPicks.teams(new ArrayList<>(state.participants), def.teams()));
            }
            discord.send(msg.raw("events.discord-started").replace("<event>", def.name()),
                    msg.raw("events.discord-players").replace("<count>", String.valueOf(state.participants.size())), Discord.GREEN, List.of());
        }
        if (ph.lives() > 0) for (UUID id : state.participants) state.lives.putIfAbsent(id, ph.lives());
        applyWorldRules(ph.rules());
        if (ph.kit() != null) for (UUID id : state.participants) giveKit(id, ph.kit());

        Title title = Title.title(msg.msg("events.phase-title", "event", def.name()), msg.msg("events.phase-subtitle", "phase", ph.name()),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700)));
        for (Player p : viewers()) {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
        }
        String time = ph.end().timerMs() > 0 ? Durations.format(ph.end().timerMs()) : "";
        plugin.getServer().sendMessage(msg.msg(time.isEmpty() ? "events.phase-started" : "events.phase-started-timed",
                "event", def.name(), "phase", ph.name(), "time", time));
        if (ph.vote()) plugin.getServer().sendMessage(msg.msg("events.vote-open"));
        if (ph.end().ready() != null) for (Player p : onlineParticipants()) msg.send(p, "events.ready-hint");
        dirty = true;
        save();
    }

    private void giveKit(UUID id, EventDef.Kit kit) {
        Player p = plugin.getServer().getPlayer(id);
        if (p == null || state.kitted.contains(id)) return;
        kits.save(p);
        kits.give(p, kit);
        state.kitted.add(id);
        msg.send(p, "events.kit-given");
    }

    private void applyWorldRules(EventDef.Rules r) {
        World w = mainWorld();
        if (r.difficulty() != null) {
            state.restore.putIfAbsent("difficulty." + w.getName(), w.getDifficulty().name());
            w.setDifficulty(Difficulty.valueOf(r.difficulty().toUpperCase(Locale.ROOT)));
        }
        if (r.weather() != null) {
            boolean rain = r.weather().equals("rain");
            w.setStorm(rain);
            w.setThundering(false);
            w.setWeatherDuration(20 * 60 * 30);
        }
        if (r.timeLock() != null) w.setTime(r.timeLock());
    }

    private void restoreWorld() {
        for (var e : state.restore.entrySet()) {
            if (!e.getKey().startsWith("difficulty.")) continue;
            World w = plugin.getServer().getWorld(e.getKey().substring("difficulty.".length()));
            try {
                if (w != null) w.setDifficulty(Difficulty.valueOf(e.getValue()));
            } catch (IllegalArgumentException ignored) {
                // unknown value: leave it
            }
        }
    }

    /** Ends the event. With prizes: results, payouts, trophies, history and milestone unlock. Without: just stops. */
    public void finish(boolean prizes) {
        if (state == null) return;
        EventDef d = def;
        EventState s = state;
        List<Ranking.Place<UUID>> places = prizes ? ranking() : List.of();
        if (prizes) {
            Set<UUID> active = new HashSet<>();
            for (UUID id : s.participants) {
                if (s.actions.getOrDefault(id, 0L) >= d.prizes().activeActions() || s.score(id) > 0) active.add(id);
            }
            boolean goal = d.prizes().goal() > 0 && s.totalPoints() >= d.prizes().goal();
            Map<UUID, PrizePlan.Payout> pay = d.winner() == EventDef.Winner.NONE && d.prizes().goal() <= 0 && d.prizes().participation() <= 0
                    ? Map.of() : PrizePlan.plan(places, s.participants, active, d.prizes(), goal);
            plugin.getServer().sendMessage(msg.msg("events.intro-line"));
            plugin.getServer().sendMessage(msg.msg("events.results-header", "event", d.name()));
            if (places.isEmpty()) plugin.getServer().sendMessage(msg.msg("events.results-none"));
            for (Ranking.Place<UUID> p : places) {
                if (p.place() > 5) break;
                plugin.getServer().sendMessage(msg.msg("events.results-line", "place", ordinal(p.place()),
                        "player", plugin.economy().name(p.who()), "score", shownScore(p.score())));
            }
            if (goal) plugin.getServer().sendMessage(msg.msg("events.goal-reached", "bonus", plugin.economy().format(d.prizes().goalBonus())));
            plugin.getServer().sendMessage(msg.msg("events.intro-line"));
            for (var e : pay.entrySet()) {
                PrizePlan.Payout po = e.getValue();
                if (po.coins() > 0) plugin.economy().deposit(e.getKey(), po.coins(), "event: " + d.name());
                if (po.trophy()) giveTrophy(e.getKey(), d, po.place());
                Player online = plugin.getServer().getPlayer(e.getKey());
                if (online != null) {
                    msg.send(online, po.place() > 0 ? "events.you-placed" : "events.you-took-part",
                            "place", ordinal(po.place()), "coins", plugin.economy().format(po.coins()));
                }
            }
            for (UUID id : s.participants) {
                Player p = plugin.getServer().getPlayer(id);
                if (p != null) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
            }
            List<DiscordPayload.Field> fields = new ArrayList<>();
            for (Ranking.Place<UUID> p : places) {
                if (p.place() > 3) break;
                fields.add(new DiscordPayload.Field(ordinal(p.place()), plugin.economy().name(p.who()) + " (" + shownScore(p.score()) + ")"));
            }
            discord.send(msg.raw("events.discord-ended").replace("<event>", d.name()),
                    msg.raw("events.discord-players").replace("<count>", String.valueOf(s.participants.size())), Discord.GOLD, fields);
            recordHistory(d, s, places);
            if (d.unlocks() != null) Milestone.byId(d.unlocks()).ifPresent(m -> plugin.milestones().set(m, true, "event " + d.name()));
        } else {
            plugin.getServer().sendMessage(msg.msg("events.cancelled", "event", d.name()));
        }
        for (UUID id : new ArrayList<>(s.kitted)) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null && kits.restore(p)) msg.send(p, "events.kit-returned");
        }
        restoreWorld();
        display.clear();
        lastBar.clear();
        state = null;
        def = null;
        save();
    }

    private void recordHistory(EventDef d, EventState s, List<Ranking.Place<UUID>> places) {
        long now = System.currentTimeMillis();
        List<Result> res = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Ranking.Place<UUID> p : places) {
            if (p.place() > 10) break;
            res.add(new Result(p.who(), p.place(), p.score()));
            if (!text.isEmpty()) text.append(';');
            text.append(p.who()).append(':').append(p.place()).append(':').append(p.score());
        }
        history.add(new HistoryEntry(d.id(), d.name(), now, res));
        lastRun.put(d.id(), now().toLocalDate());
        String t = text.toString();
        long started = s.startedAt;
        db.run(c -> Database.update(c, "INSERT INTO event_history (event, name, started, ended, results) VALUES (?, ?, ?, ?, ?)",
                d.id(), d.name(), started, now, t));
    }

    /** Places at the end, by the event's way of winning. Team events give each member their team's place. */
    public List<Ranking.Place<UUID>> ranking() {
        if (state == null) return List.of();
        Map<UUID, Long> scores = new LinkedHashMap<>();
        switch (def.winner()) {
            case VOTES -> scores.putAll(VoteTally.tally(state.votes, state.participants));
            case LAST_STANDING -> {
                for (UUID id : state.participants) if (!state.eliminated.contains(id)) scores.put(id, 1L);
            }
            case NONE -> {
                return List.of();
            }
            default -> {
                if (def.teams() > 0) {
                    List<Ranking.Place<Integer>> teamPlaces = Ranking.rank(state.teamScores());
                    List<Ranking.Place<UUID>> out = new ArrayList<>();
                    for (Ranking.Place<Integer> tp : teamPlaces) {
                        for (UUID id : state.participants) if (tp.who().equals(state.teams.get(id))) out.add(new Ranking.Place<>(id, tp.place(), tp.score()));
                    }
                    return out;
                }
                for (UUID id : state.participants) scores.put(id, state.score(id));
            }
        }
        return Ranking.rank(scores);
    }

    private String shownScore(long score) {
        return String.format(Locale.ROOT, "%,d", def != null && def.winner() == EventDef.Winner.VOTES ? VoteTally.shownPoints(score) : score);
    }

    private String ordinal(int n) {
        if (n <= 0) return "";
        return msg.raw("events.places." + Math.min(n, 10)).replace("<n>", String.valueOf(n));
    }

    private void giveTrophy(UUID id, EventDef d, int place) {
        Material m = Material.matchMaterial(d.prizes().trophyItem());
        ItemStack t = ItemStack.of(m == null ? Material.GOLD_INGOT : m);
        String when = now().format(MONTH);
        t.editMeta(meta -> {
            meta.displayName(msg.item("events.trophy-name", "event", d.name()).color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(msg.item("events.trophy-lore", "event", d.name(), "place", ordinal(place), "date", when)));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(trophyKey, PersistentDataType.STRING, d.id() + ":" + place);
        });
        Player p = plugin.getServer().getPlayer(id);
        if (p != null) {
            for (ItemStack left : p.getInventory().addItem(t).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            return;
        }
        byte[] bytes = t.serializeAsBytes();
        pendingClaims.merge(id, 1, Integer::sum);
        db.run(c -> Database.update(c, "INSERT INTO event_claims (uuid, item) VALUES (?, ?)", id.toString(), (Object) bytes));
    }

    /** Gives a player the trophies they won while offline. Returns how many. */
    public void claim(Player p) {
        UUID id = p.getUniqueId();
        if (!pendingClaims.containsKey(id)) {
            msg.send(p, "events.claim-none");
            return;
        }
        pendingClaims.remove(id);
        db.submit(c -> {
            List<byte[]> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT item FROM event_claims WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) items.add(rs.getBytes(1));
                }
            }
            Database.update(c, "DELETE FROM event_claims WHERE uuid = ?", id.toString());
            return items;
        }).thenAccept(items -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            int n = 0;
            for (byte[] b : items) {
                try {
                    ItemStack t = ItemStack.deserializeBytes(b);
                    for (ItemStack left : p.getInventory().addItem(t).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                    n++;
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("A prize for " + p.getName() + " couldn't be read: " + e.getMessage());
                }
            }
            msg.send(p, "events.claimed", "count", String.valueOf(n));
        }));
    }

    // --- admin controls ---

    public boolean next() {
        if (state == null) return false;
        beginPhase(state.phase + 1);
        return true;
    }

    public boolean pause() {
        if (state == null || state.paused()) return false;
        state.pausedRemaining = state.endsAt > 0 ? Math.max(0, state.endsAt - System.currentTimeMillis()) : 0;
        dirty = true;
        plugin.getServer().sendMessage(msg.msg("events.paused", "event", def.name()));
        return true;
    }

    public boolean resume() {
        if (state == null || !state.paused()) return false;
        if (state.endsAt > 0) state.endsAt = System.currentTimeMillis() + state.pausedRemaining;
        state.pausedRemaining = -1;
        dirty = true;
        plugin.getServer().sendMessage(msg.msg("events.resumed", "event", def.name()));
        return true;
    }

    /** Adds (or with a negative number takes) minutes from the current timer. */
    public boolean addTime(long minutes) {
        if (state == null) return false;
        long ms = minutes * 60_000L;
        if (state.paused()) state.pausedRemaining = Math.max(0, state.pausedRemaining + ms);
        else if (state.endsAt > 0) state.endsAt = Math.max(System.currentTimeMillis(), state.endsAt + ms);
        else state.endsAt = System.currentTimeMillis() + Math.max(0, ms); // a phase without a timer gets one
        dirty = true;
        return true;
    }

    public boolean addPoints(UUID id, long amount) {
        if (state == null || !state.participants.contains(id)) return false;
        state.scores.merge(id, amount, Long::sum);
        if (state.score(id) < 0) state.scores.put(id, 0L);
        dirty = true;
        return true;
    }

    public boolean remove(UUID id) {
        if (state == null || !state.participants.remove(id)) return false;
        dropFromState(id);
        Player p = plugin.getServer().getPlayer(id);
        if (p != null) {
            if (kits.restore(p)) msg.send(p, "events.kit-returned");
            msg.send(p, "events.removed", "event", def.name());
        }
        return true;
    }

    private void dropFromState(UUID id) {
        state.scores.remove(id);
        state.teams.remove(id);
        state.ready.remove(id);
        state.votes.remove(id);
        state.lives.remove(id);
        state.eliminated.remove(id);
        state.kitted.remove(id);
        dirty = true;
    }

    // --- players ---

    public JoinResult join(Player p, boolean quiet) {
        if (state == null) return JoinResult.NONE;
        UUID id = p.getUniqueId();
        if (state.participants.contains(id)) return JoinResult.ALREADY;
        if (state.phase >= 0 && !def.lateJoin()) return JoinResult.CLOSED;
        if (def.maxPlayers() > 0 && state.participants.size() >= def.maxPlayers()) return JoinResult.FULL;
        add(p, quiet);
        return JoinResult.OK;
    }

    /** Puts a player in without the sign-up checks (everyone-is-in events at their start). */
    private void add(Player p, boolean quiet) {
        UUID id = p.getUniqueId();
        if (!state.participants.add(id)) return;
        state.scores.putIfAbsent(id, 0L);
        if (def.teams() > 0 && state.phase >= 0) state.teams.put(id, EventPicks.smallestTeam(state.teams, def.teams()));
        EventDef.Phase ph = phase();
        if (ph != null && ph.lives() > 0) state.lives.putIfAbsent(id, ph.lives());
        if (ph != null && ph.kit() != null) giveKit(id, ph.kit());
        dirty = true;
        if (!quiet) msg.send(p, "events.joined", "event", def.name());
    }

    /** Leaves an opt-in event. Returns false if the player isn't in it (or everyone is in automatically). */
    public boolean leave(Player p) {
        if (state == null || def.autoJoin() || !state.participants.remove(p.getUniqueId())) return false;
        dropFromState(p.getUniqueId());
        if (kits.restore(p)) msg.send(p, "events.kit-returned");
        return true;
    }

    /** /event done for ready checks. */
    public String ready(Player p) {
        EventDef.Phase ph = phase();
        if (ph == null || ph.end().ready() == null) return "events.ready-not-now";
        if (!participant(p.getUniqueId())) return "events.not-in";
        if (!state.ready.add(p.getUniqueId())) return "events.ready-already";
        dirty = true;
        int need = ReadyRule.needed(ph.end().ready(), state.participants.size(), onlineParticipants().size());
        plugin.getServer().sendMessage(msg.msg("events.ready-count", "player", p.getName(),
                "count", String.valueOf(state.ready.size()), "need", String.valueOf(need)));
        return null;
    }

    public void setBallot(UUID voter, List<UUID> picks) {
        if (state == null) return;
        state.votes.put(voter, new ArrayList<>(picks));
        dirty = true;
    }

    public List<UUID> ballot(UUID voter) {
        return state == null ? List.of() : state.votes.getOrDefault(voter, List.of());
    }

    public Collection<Player> onlineParticipants() {
        List<Player> out = new ArrayList<>();
        if (state == null) return out;
        for (UUID id : state.participants) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    private Collection<? extends Player> viewers() {
        return def != null && def.autoJoin() ? plugin.getServer().getOnlinePlayers() : onlineParticipants();
    }

    /** Every counted action (from StatsService): activity for the AFK check, and points. */
    public void onStat(Player p, String stat, long amount, Location where) {
        if (state == null || state.phase < 0) return;
        UUID id = p.getUniqueId();
        if (!state.participants.contains(id) || state.eliminated.contains(id)) return;
        if (!stat.equals(StatKeys.TIME_PLAYED)) state.actions.merge(id, 1L, Long::sum);
        EventDef.Phase ph = phase();
        long per = ph.pointsFor(stat);
        if (per == 0) return;
        if (def.area() != null && !plugin.areas().inside(def.area(), where)) return;
        long got = per * amount;
        state.scores.merge(id, got, Long::sum);
        dirty = true;
        long now = System.currentTimeMillis();
        Long last = lastBar.get(id);
        if (last == null || now - last > 400) {
            lastBar.put(id, now);
            p.sendActionBar(msg.msg("events.points-bar", "points", String.valueOf(got), "total", String.format(Locale.ROOT, "%,d", state.score(id))));
        }
    }

    // --- the clock ---

    /** Every second: phase changes, reminders, the automatic schedule, the display. */
    public void tick() {
        if (state == null) {
            checkAuto();
            return;
        }
        long now = System.currentTimeMillis();
        if (state.phase < 0) {
            long left = state.endsAt - now;
            if (!state.paused()) {
                for (int m : settings.reminders()) {
                    if (left > 0 && left <= m * 60_000L && state.remindersSent.add(m)) {
                        plugin.getServer().sendMessage(msg.msg("events.reminder", "event", def.name(), "time", Durations.format(left))
                                .append(Component.space()).append(def.autoJoin() ? Component.empty() : msg.msg("events.join-button")));
                        dirty = true;
                    }
                }
                if (left <= 0) {
                    int online = plugin.getServer().getOnlinePlayers().size();
                    if (state.autoStarted && online < def.minPlayers()) {
                        state.endsAt += Duration.ofDays(1).toMillis();
                        state.remindersSent.clear();
                        plugin.getServer().sendMessage(msg.msg("events.postponed", "event", def.name(), "min", String.valueOf(def.minPlayers())));
                        dirty = true;
                    } else {
                        beginPhase(0);
                    }
                }
            }
        } else if (shouldEnd(def.phase(state.phase), now)) {
            beginPhase(state.phase + 1);
        }
        if (state != null) {
            ticks++;
            EventDef.Phase ph = phase();
            if (ph != null && ph.rules().timeLock() != null && ticks % 5 == 0) mainWorld().setTime(ph.rules().timeLock());
            updateDisplay(now);
            if (dirty && ticks % 10 == 0) save();
        }
    }

    private boolean shouldEnd(EventDef.Phase ph, long now) {
        EventDef.End e = ph.end();
        if (!state.paused() && state.endsAt > 0 && now >= state.endsAt) return true;
        World w = mainWorld();
        if (state.mcEnd >= 0 && w.getFullTime() >= state.mcEnd) return true;
        if (e.mcTime() >= 0) {
            long tod = w.getTime();
            boolean crossed = lastTod >= 0 && crossed(lastTod, tod, e.mcTime());
            lastTod = tod;
            if (crossed) return true;
        }
        if (e.ready() != null) {
            int readyOnline = 0;
            for (UUID id : state.ready) if (plugin.getServer().getPlayer(id) != null) readyOnline++;
            if (ReadyRule.met(e.ready(), state.ready.size(), readyOnline, state.participants.size(), onlineParticipants().size())) return true;
        }
        if (e.goal() && ph.goal() > 0 && state.totalPoints() >= ph.goal()) return true;
        if (e.lastStanding() && state.participants.size() >= 2) {
            int alive = 0;
            Set<Integer> aliveTeams = new HashSet<>();
            for (UUID id : state.participants) {
                if (state.eliminated.contains(id)) continue;
                alive++;
                Integer t = state.teams.get(id);
                if (t != null) aliveTeams.add(t);
            }
            if (def.teams() > 0 ? aliveTeams.size() <= 1 : alive <= 1) return true;
        }
        return false;
    }

    /** True if the time of day passed {@code target} going from {@code from} to {@code to} (it wraps at 24,000). */
    static boolean crossed(long from, long to, long target) {
        if (from == to) return false;
        if (from < to) return from < target && target <= to;
        return target > from || target <= to;
    }

    private void checkAuto() {
        if (!settings.autoEnabled() || defs.isEmpty()) return;
        ZonedDateTime now = now();
        String today = now.toLocalDate().toString();
        if (today.equals(autoDay) || now.toLocalTime().isBefore(settings.announceTime())) return;
        autoDay = today;
        db.run(c -> Database.update(c, "INSERT INTO meta (key, value) VALUES ('events.auto-day', ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", today));
        ZonedDateTime start = now.toLocalDate().atTime(settings.startTime()).atZone(settings.zone());
        if (!start.isAfter(now)) return; // past today's start time: wait for tomorrow
        EventDef pick = EventPicks.pick(defs.values(), lastRun, now.toLocalDate(), settings.noRepeatDays(), rng);
        if (pick != null) start(pick, start.toInstant().toEpochMilli(), true);
    }

    /** Replaces an automatic event that's only been announced (an admin's schedule comes first). */
    public boolean replaceAuto() {
        if (state == null || !state.autoStarted || state.phase >= 0) return false;
        plugin.getServer().sendMessage(msg.msg("events.replaced", "event", def.name()));
        display.clear();
        state = null;
        def = null;
        save();
        return true;
    }

    // --- display ---

    private void updateDisplay(long now) {
        EventDef.Phase ph = phase();
        Component title = msg.msg("events.sidebar-title", "event", def.name());
        List<Component> common = new ArrayList<>();
        long left = state.paused() ? state.pausedRemaining : state.endsAt > 0 ? state.endsAt - now : -1;
        if (left < 0 && state.mcEnd >= 0) left = Math.max(0, (state.mcEnd - mainWorld().getFullTime()) * 50);
        if (ph == null) {
            common.add(msg.msg("events.sidebar-starts", "time", Durations.format(Math.max(0, left))));
        } else {
            common.add(msg.msg("events.sidebar-phase", "phase", ph.name()));
            if (state.paused()) common.add(msg.msg("events.sidebar-paused"));
            else if (left >= 0) common.add(msg.msg("events.sidebar-time", "time", Durations.format(left)));
            if (ph.goal() > 0) common.add(msg.msg("events.sidebar-goal", "progress", fmt(state.totalPoints()), "goal", fmt(ph.goal())));
            if (ph.end().ready() != null) {
                common.add(msg.msg("events.sidebar-ready", "count", String.valueOf(state.ready.size()),
                        "need", String.valueOf(ReadyRule.needed(ph.end().ready(), state.participants.size(), onlineParticipants().size()))));
            }
        }
        common.add(Component.empty());
        if (ph != null && ph.vote()) {
            common.add(msg.msg("events.sidebar-votes-secret"));
        } else if (ph != null && def.winner() != EventDef.Winner.VOTES) {
            if (def.teams() > 0) {
                for (Ranking.Place<Integer> t : Ranking.rank(state.teamScores())) {
                    if (common.size() > 10) break;
                    common.add(msg.msg("events.sidebar-team", "team", String.valueOf(t.who()), "score", fmt(t.score())));
                }
            } else {
                int shown = 0;
                for (Ranking.Place<UUID> p : Ranking.rank(scoresOfParticipants())) {
                    if (shown++ >= 5) break;
                    common.add(msg.msg("events.sidebar-line", "place", String.valueOf(p.place()), "player", plugin.economy().name(p.who()), "score", fmt(p.score())));
                }
                if (shown == 0) common.add(msg.msg("events.sidebar-no-scores"));
            }
        } else {
            common.add(msg.msg("events.sidebar-players", "count", String.valueOf(state.participants.size())));
        }
        common.add(Component.text(" "));

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            List<Component> lines = new ArrayList<>(common);
            if (state.participants.contains(id)) {
                if (ph != null && ph.scores()) lines.add(msg.msg("events.sidebar-you", "score", fmt(state.score(id))));
                Integer team = state.teams.get(id);
                if (team != null) lines.add(msg.msg("events.sidebar-your-team", "team", String.valueOf(team)));
                Integer lives = state.lives.get(id);
                if (state.eliminated.contains(id)) lines.add(msg.msg("events.sidebar-out"));
                else if (lives != null && ph != null && ph.lives() > 0) lines.add(msg.msg("events.sidebar-lives", "lives", String.valueOf(lives)));
            } else if (!def.autoJoin()) {
                lines.add(msg.msg(state.phase >= 0 && !def.lateJoin() ? "events.sidebar-closed" : "events.sidebar-join"));
            }
            display.sidebar(p, title, lines);
        }

        float progress;
        BossBar.Color color = BossBar.Color.BLUE;
        if (ph != null && ph.goal() > 0) {
            progress = (float) state.totalPoints() / ph.goal();
            color = BossBar.Color.GREEN;
        } else {
            long total = ph == null ? state.endsAt - state.phaseStartedAt : ph.end().timerMs();
            progress = total > 0 && left >= 0 ? (float) left / total : 1f;
            if (ph != null) color = BossBar.Color.YELLOW;
        }
        Component name = msg.msg("events.bossbar", "event", def.name(), "phase", ph == null ? msg.raw("events.announced-phase") : ph.name(),
                "time", left >= 0 ? Durations.format(left) : "-");
        display.bossBar(plugin.getServer().getOnlinePlayers(), name, progress, color);
    }

    private Map<UUID, Long> scoresOfParticipants() {
        Map<UUID, Long> m = new LinkedHashMap<>();
        for (UUID id : state.participants) m.put(id, state.score(id));
        return m;
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    // --- player events ---

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        // Back from a crash or from leaving mid-event: give their own things back if the event no longer needs the kit.
        if (kits.has(id) && (state == null || !state.kitted.contains(id))) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && kits.restore(p)) msg.send(p, "events.kit-returned");
            }, 20L);
        }
        if (state != null && def.autoJoin() && state.phase >= 0) join(p, true);
        if (pendingClaims.containsKey(id)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) msg.send(p, "events.claim-waiting");
            }, 60L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        display.forget(e.getPlayer());
        lastBar.remove(e.getPlayer().getUniqueId());
    }

    /** Lives and elimination; kit items never drop. Runs after the rule switches. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        if (state == null || state.phase < 0) return;
        Player p = e.getEntity();
        UUID id = p.getUniqueId();
        if (!state.participants.contains(id)) return;
        if (state.kitted.contains(id)) {
            e.setKeepInventory(true);
            e.getDrops().clear();
            e.setKeepLevel(true);
            e.setDroppedExp(0);
        }
        EventDef.Phase ph = phase();
        if (ph == null || ph.lives() <= 0 || state.eliminated.contains(id)) return;
        int left = state.lives.getOrDefault(id, ph.lives()) - 1;
        state.lives.put(id, Math.max(0, left));
        dirty = true;
        if (left <= 0) {
            state.eliminated.add(id);
            plugin.getServer().sendMessage(msg.msg("events.eliminated", "player", p.getName()));
        } else {
            msg.send(p, "events.lives-left", "lives", String.valueOf(left));
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (state == null || !state.eliminated.contains(p.getUniqueId())) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (state == null || !p.isOnline()) return;
            if (!state.kitted.contains(p.getUniqueId())) {
                kits.save(p);
                state.kitted.add(p.getUniqueId());
            }
            p.setGameMode(GameMode.SPECTATOR);
            msg.send(p, "events.spectating");
        });
    }

    public void shutdown() {
        saveNow();
        display.clear();
        discord.close();
    }

    /** Next automatic announcement, for /event when nothing runs. */
    public Optional<String> nextAuto() {
        if (!settings.autoEnabled()) return Optional.empty();
        ZonedDateTime n = now();
        boolean today = !n.toLocalDate().toString().equals(autoDay) && n.toLocalTime().isBefore(settings.announceTime());
        return Optional.of((today ? msg.raw("events.today") : msg.raw("events.tomorrow")) + " " + settings.announceTime());
    }
}
