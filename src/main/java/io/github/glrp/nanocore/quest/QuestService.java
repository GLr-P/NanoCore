package io.github.glrp.nanocore.quest;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.db.Database;
import io.github.glrp.nanocore.economy.Accounts;
import io.github.glrp.nanocore.milestone.Milestone;
import io.github.glrp.nanocore.stats.Places;
import io.github.glrp.nanocore.stats.StatKeys;
import io.github.glrp.nanocore.text.Messages;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
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
 * The quest board: today's quests, the week's community quest, every player's progress, rerolls and payouts.
 * Everything lives in memory on the server thread; changes are written to the database in the background.
 */
public final class QuestService {

    public enum Result { OK, ALREADY, FULL, NOT_AVAILABLE, NOT_ENOUGH, NONE }

    private final NanoCorePlugin plugin;
    private final Database db;
    private final Messages msg;
    private final Random rng = new Random();
    private volatile QuestSettings settings;
    private volatile Map<String, QuestTemplate> templates = Map.of();

    private String day;
    private String week;
    private final List<Quest> board = new ArrayList<>();
    private Quest community;
    private boolean communityDone;
    private final Map<UUID, List<Quest>> personal = new HashMap<>();
    private final Map<UUID, PlayerQuests> players = new HashMap<>();
    private final Map<UUID, Long> contributions = new LinkedHashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private final Map<UUID, Long> lastBar = new HashMap<>();

    public QuestService(NanoCorePlugin plugin, Database db, Messages msg) {
        this.plugin = plugin;
        this.db = db;
        this.msg = msg;
        reload();
        load();
    }

    // --- config ---

    public void reload() {
        settings = QuestSettings.from(plugin.getConfig().getConfigurationSection("quests"), plugin.getLogger()::warning);
        File f = new File(plugin.getDataFolder(), "quests.yml");
        if (!f.exists()) plugin.saveResource("quests.yml", false);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        templates = QuestTemplates.parse(yml,
                id -> {
                    Material m = Material.matchMaterial(id);
                    return m != null && m.isItem();
                },
                id -> Milestone.byId(id).isPresent(),
                plugin.getLogger()::warning);
    }

    public QuestSettings settings() {
        return settings;
    }

    public Map<String, QuestTemplate> templates() {
        return templates;
    }

    // --- loading and refreshing ---

    private void load() {
        db.sync(c -> {
            day = meta(c, "quests.day");
            week = meta(c, "quests.week");
            String paid = meta(c, "quests.community-paid");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, tier, template, variant, amount, reward, owner, replaces FROM quest_board ORDER BY slot");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Tier tier = Tier.byId(rs.getString(2)).orElse(Tier.EASY);
                    Quest q = new Quest(rs.getString(1), tier, rs.getString(3), rs.getString(4), rs.getLong(5), rs.getLong(6),
                            rs.getString(7), rs.getString(8));
                    if (q.owner() != null) personal.computeIfAbsent(UUID.fromString(q.owner()), k -> new ArrayList<>()).add(q);
                    else if (tier == Tier.COMMUNITY) community = q;
                    else board.add(q);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, quest, state, progress FROM quest_progress");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString(1));
                    String state = rs.getString(3);
                    if (state.equals("COMMUNITY")) {
                        contributions.put(id, rs.getLong(4));
                    } else {
                        try {
                            players.computeIfAbsent(id, k -> new PlayerQuests())
                                    .put(rs.getString(2), PlayerQuests.State.valueOf(state), rs.getLong(4));
                        } catch (IllegalArgumentException ignored) {
                            // unknown state from a newer version: skip
                        }
                    }
                }
            }
            communityDone = community != null && community.id().equals(paid);
            return null;
        });
    }

    private static String meta(java.sql.Connection c, String key) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void setMeta(java.sql.Connection c, String key, String value) throws java.sql.SQLException {
        Database.update(c, "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", key, value);
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(settings.zone());
    }

    public String currentDay() {
        return QuestClock.day(now(), settings.refreshTime()).toString();
    }

    public String currentWeek() {
        return "week-" + QuestClock.weekStart(now(), settings.communityDay(), settings.refreshTime());
    }

    public Duration untilRefresh() {
        ZonedDateTime n = now();
        return Duration.between(n, QuestClock.nextDay(n, settings.refreshTime()));
    }

    public Duration untilWeekEnds() {
        ZonedDateTime n = now();
        return Duration.between(n, QuestClock.nextWeek(n, settings.communityDay(), settings.refreshTime()));
    }

    /** Called every few seconds: starts a new board or week when it's time. */
    public void checkRefresh() {
        String d = currentDay();
        if (!d.equals(day)) refreshDaily(d);
        String w = currentWeek();
        if (!w.equals(week)) refreshWeekly(w);
    }

    private boolean unlocked(String milestone) {
        return Milestone.byId(milestone).map(plugin.milestones()::isUnlocked).orElse(false);
    }

    /** New daily quests. Quests done but not claimed are paid out first. */
    public void refreshDaily(String newDay) {
        boolean first = day == null;
        List<Quest> old = new ArrayList<>(board);
        personal.values().forEach(old::addAll);
        for (var e : players.entrySet()) {
            for (Quest q : old) {
                if (e.getValue().state(q.id()) == PlayerQuests.State.DONE) pay(e.getKey(), q, true);
                e.getValue().forget(q.id());
            }
        }
        board.clear();
        personal.clear();
        // Ids carry a fresh tag, so a second board on the same day (admin refresh) never inherits old progress.
        board.addAll(QuestGenerator.dailyBoard(templates.values(), settings.counts(), settings.rewards(), this::unlocked, rng, newDay + "/" + tag()));
        day = newDay;
        List<Quest> snapshot = List.copyOf(board);
        db.run(c -> {
            c.setAutoCommit(false);
            try {
                Database.update(c, "DELETE FROM quest_board WHERE tier <> 'community' OR owner IS NOT NULL");
                for (int i = 0; i < snapshot.size(); i++) insert(c, snapshot.get(i), i);
                Database.update(c, "DELETE FROM quest_progress WHERE quest NOT IN (SELECT id FROM quest_board)");
                setMeta(c, "quests.day", newDay);
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        });
        if (!first) plugin.getServer().sendMessage(msg.msg("quests.refreshed"));
        plugin.getLogger().info("Quest board for " + newDay + ": " + board.size() + " quests");
    }

    /** A new community quest. An unfinished one pays out what the server got done. */
    public void refreshWeekly(String newWeek) {
        if (community != null && !communityDone) settleCommunity();
        contributions.clear();
        community = QuestGenerator.communityQuest(templates.values(), this::unlocked, rng, newWeek + "/" + tag(), settings.communityPot());
        communityDone = false;
        week = newWeek;
        Quest q = community;
        db.run(c -> {
            Database.update(c, "DELETE FROM quest_board WHERE tier = 'community'");
            if (q != null) insert(c, q, 100);
            Database.update(c, "DELETE FROM quest_progress WHERE quest NOT IN (SELECT id FROM quest_board)");
            setMeta(c, "quests.week", newWeek);
            setMeta(c, "quests.community-paid", "");
        });
    }

    /** A short random tag that makes each generation's quest ids unique. */
    private String tag() {
        return Integer.toHexString(0x100000 + rng.nextInt(0xF00000));
    }

    private static void insert(java.sql.Connection c, Quest q, int slot) throws java.sql.SQLException {
        Database.update(c, "INSERT OR REPLACE INTO quest_board (id, period, slot, tier, template, variant, amount, reward, owner, replaces) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                q.id(), q.id().substring(0, Math.max(0, q.id().indexOf('/'))), slot, q.tier().id(), q.template(), q.variant(),
                q.amount(), q.reward(), q.owner(), q.replaces());
    }

    // --- what players see ---

    public List<Quest> board() {
        return List.copyOf(board);
    }

    public Quest community() {
        return community;
    }

    public boolean communityDone() {
        return communityDone;
    }

    public long communityProgress() {
        long s = 0;
        for (long v : contributions.values()) s += v;
        return s;
    }

    public long contribution(UUID id) {
        return contributions.getOrDefault(id, 0L);
    }

    /** The board as this player sees it: rerolled quests replace the ones they stand in for. */
    public List<Quest> view(UUID id) {
        List<Quest> mine = personal.getOrDefault(id, List.of());
        List<Quest> out = new ArrayList<>(board.size());
        for (Quest b : board) {
            Quest swap = null;
            for (Quest p : mine) if (b.id().equals(p.replaces())) swap = p;
            out.add(swap == null ? b : swap);
        }
        return out;
    }

    public Quest find(UUID id, String questId) {
        if (community != null && community.id().equals(questId)) return community;
        for (Quest q : view(id)) if (q.id().equals(questId)) return q;
        return null;
    }

    public PlayerQuests player(UUID id) {
        return players.computeIfAbsent(id, k -> new PlayerQuests());
    }

    public QuestTemplate.Resolved resolve(Quest q) {
        QuestTemplate t = templates.get(q.template());
        if (t == null) return new QuestTemplate.Resolved(msg.raw("quests.unknown-quest"), "barrier", QuestTemplate.Objective.COUNT, List.of(), 1, "unknown");
        return t.resolve(q.variant());
    }

    /** The goal in raw stat units. */
    public long target(Quest q) {
        return q.amount() * resolve(q).scale();
    }

    /** Raw units shown as quest units (centimetres as blocks). */
    public long shown(Quest q, long raw) {
        return raw / resolve(q).scale();
    }

    public int activeCount(UUID id) {
        PlayerQuests pq = players.get(id);
        return pq == null ? 0 : pq.activeCount();
    }

    // --- player actions ---

    public Result take(Player p, Quest q) {
        if (q == null || q.tier() == Tier.COMMUNITY) return Result.NOT_AVAILABLE;
        return switch (player(p.getUniqueId()).take(q.id(), settings.maxActive())) {
            case OK -> {
                save(p.getUniqueId(), q.id());
                yield Result.OK;
            }
            case ALREADY -> Result.ALREADY;
            case FULL -> Result.FULL;
        };
    }

    public boolean abandon(Player p, Quest q) {
        if (q == null || !player(p.getUniqueId()).abandon(q.id())) return false;
        UUID id = p.getUniqueId();
        db.run(c -> Database.update(c, "DELETE FROM quest_progress WHERE uuid = ? AND quest = ?", id.toString(), q.id()));
        return true;
    }

    /** Swaps a quest the player hasn't taken for a new one of the same tier, for the reroll cost. */
    public Result reroll(Player p, Quest q) {
        UUID id = p.getUniqueId();
        if (q == null || q.tier() == Tier.COMMUNITY || player(id).state(q.id()) != null) return Result.NOT_AVAILABLE;
        Set<String> exclude = new HashSet<>();
        for (Quest v : view(id)) exclude.add(v.template());
        String newId = day + "/r-" + Integer.toHexString(rng.nextInt(1 << 24)) + "-" + id.toString().substring(0, 8);
        Quest fresh = QuestGenerator.replacement(templates.values(), q.tier(), settings.rewards().get(q.tier()), this::unlocked, rng, exclude, newId);
        if (fresh == null) return Result.NONE;
        long cost = settings.rerollCost();
        if (cost > 0 && plugin.economy().withdraw(id, cost, "quest reroll") != Accounts.Result.OK) return Result.NOT_ENOUGH;
        String replaces = q.replaces() != null ? q.replaces() : q.id();
        Quest mine = fresh.personal(id.toString(), replaces);
        List<Quest> list = personal.computeIfAbsent(id, k -> new ArrayList<>());
        list.removeIf(x -> x.id().equals(q.id()));
        list.add(mine);
        int slot = 50 + list.size();
        db.run(c -> {
            if (q.owner() != null) Database.update(c, "DELETE FROM quest_board WHERE id = ?", q.id());
            insert(c, mine, slot);
        });
        return Result.OK;
    }

    /** Hands in items for a delivery quest (or the community quest). Returns how many were taken. */
    public int deliver(Player p, Quest q) {
        QuestTemplate.Resolved r = resolve(q);
        if (r.objective() != QuestTemplate.Objective.DELIVER || r.targets().isEmpty()) return 0;
        Material m = Material.matchMaterial(r.targets().getFirst());
        if (m == null) return 0;
        UUID id = p.getUniqueId();
        long missing;
        if (q.tier() == Tier.COMMUNITY) {
            if (communityDone) return 0;
            missing = Math.max(0, target(q) - communityProgress());
        } else {
            missing = player(id).missing(q.id(), target(q));
        }
        if (missing <= 0) return 0;
        ItemStack plain = ItemStack.of(m);
        PlayerInventory inv = p.getInventory();
        int taken = 0;
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && taken < missing; i++) {
            ItemStack s = contents[i];
            if (s == null || !s.isSimilar(plain)) continue;
            int take = (int) Math.min(s.getAmount(), missing - taken);
            taken += take;
            if (take == s.getAmount()) inv.setItem(i, null);
            else {
                s.setAmount(s.getAmount() - take);
                inv.setItem(i, s); // don't rely on the array holding live stacks
            }
        }
        if (taken == 0) return 0;
        plugin.questBoards().store(m, taken);
        if (q.tier() == Tier.COMMUNITY) contribute(p, q, taken);
        else progress(p, q, r, taken);
        return taken;
    }

    public Result claim(Player p, Quest q) {
        UUID id = p.getUniqueId();
        if (q == null || !player(id).claim(q.id())) return Result.NOT_AVAILABLE;
        save(id, q.id());
        pay(id, q, false);
        plugin.statsService().add(p, StatKeys.QUESTS_DONE, 1, p.getLocation());
        return Result.OK;
    }

    private void pay(UUID id, Quest q, boolean atRefresh) {
        String name = resolve(q).name();
        // Events like Double Quest Day raise what quests pay while they run.
        long reward = atRefresh ? q.reward() : Math.round(q.reward() * plugin.events().questMultiplier());
        plugin.economy().deposit(id, reward, "quest: " + name);
        Player online = plugin.getServer().getPlayer(id);
        if (online != null) {
            msg.send(online, atRefresh ? "quests.paid-at-refresh" : "quests.claimed",
                    "quest", name, "reward", plugin.economy().format(reward));
        }
    }

    // --- progress ---

    /** Every counted action (from StatsService). */
    public void onStat(Player p, String stat, long amount) {
        UUID id = p.getUniqueId();
        PlayerQuests pq = players.get(id);
        if (pq != null && pq.activeCount() > 0) {
            for (Quest q : view(id)) {
                if (pq.state(q.id()) != PlayerQuests.State.ACTIVE) continue;
                QuestTemplate.Resolved r = resolve(q);
                if (r.objective() == QuestTemplate.Objective.COUNT && r.targets().contains(stat)) progress(p, q, r, amount);
            }
        }
        Quest c = community;
        if (c != null && !communityDone) {
            QuestTemplate.Resolved r = resolve(c);
            if (r.objective() == QuestTemplate.Objective.COUNT && r.targets().contains(stat)) contribute(p, c, amount);
        }
    }

    /** Checks biome and structure quests for everyone online (every few seconds). */
    public void pollPlaces() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            PlayerQuests pq = players.get(p.getUniqueId());
            if (pq == null || pq.activeCount() == 0) continue;
            Location at = p.getLocation();
            String biome = null;
            List<String> structures = null;
            for (Quest q : view(p.getUniqueId())) {
                if (pq.state(q.id()) != PlayerQuests.State.ACTIVE) continue;
                QuestTemplate.Resolved r = resolve(q);
                try {
                    if (r.objective() == QuestTemplate.Objective.BIOME) {
                        if (biome == null) biome = at.getBlock().getBiome().getKey().getKey();
                        if (r.targets().contains(biome)) progress(p, q, r, target(q));
                    } else if (r.objective() == QuestTemplate.Objective.STRUCTURE) {
                        if (structures == null) {
                            structures = new ArrayList<>();
                            for (GeneratedStructure s : at.getChunk().getStructures()) {
                                if (s.getBoundingBox().contains(at.toVector())) structures.add(Places.structureId(s.getStructure()));
                            }
                        }
                        for (String s : structures) {
                            if (r.targets().contains(s)) {
                                progress(p, q, r, target(q));
                                break;
                            }
                        }
                    }
                } catch (RuntimeException ignored) {
                    // unknown biome or structure in a custom world
                }
            }
        }
    }

    private void progress(Player p, Quest q, QuestTemplate.Resolved r, long raw) {
        UUID id = p.getUniqueId();
        PlayerQuests pq = player(id);
        long target = target(q);
        boolean done = pq.add(q.id(), raw, target);
        if (done) {
            save(id, q.id());
            p.showTitle(Title.title(msg.msg("quests.complete-title"), msg.msg("quests.complete-subtitle", "quest", r.name()),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            msg.send(p, "quests.complete", "quest", r.name(), "reward", plugin.economy().format(q.reward()));
        } else {
            dirty.add(id);
            actionBar(p, "quests.progress-bar", r.name(), pq.progress(q.id()) / r.scale(), q.amount());
        }
    }

    private void contribute(Player p, Quest q, long raw) {
        UUID id = p.getUniqueId();
        long target = target(q);
        long before = communityProgress();
        long add = Math.min(raw, Math.max(0, target - before));
        if (add <= 0) return;
        contributions.merge(id, add, Long::sum);
        dirty.add(id);
        QuestTemplate.Resolved r = resolve(q);
        actionBar(p, "quests.community-bar", r.name(), (before + add) / r.scale(), q.amount());
        if (before + add >= target) settleCommunity();
    }

    /** Pays the community pot (all of it if the goal was reached, else the share done) split by contribution. */
    private void settleCommunity() {
        Quest q = community;
        if (q == null || communityDone) return;
        communityDone = true;
        long total = communityProgress();
        long target = target(q);
        long pot = CommunitySplit.potFor(total, target, q.reward());
        Map<UUID, Long> shares = CommunitySplit.split(contributions, pot, settings.communityMinShare());
        String name = resolve(q).name();
        for (var e : shares.entrySet()) {
            plugin.economy().deposit(e.getKey(), e.getValue(), "community quest: " + name);
            Player online = plugin.getServer().getPlayer(e.getKey());
            if (online != null) msg.send(online, "quests.community-share", "reward", plugin.economy().format(e.getValue()));
        }
        if (!shares.isEmpty()) { // a week nobody helped with ends quietly
            plugin.getServer().sendMessage(msg.msg(total >= target ? "quests.community-done" : "quests.community-ended",
                    "quest", name, "players", String.valueOf(shares.size()), "pot", plugin.economy().format(pot)));
        }
        saveDirty();
        String paidId = q.id();
        db.run(c -> setMeta(c, "quests.community-paid", paidId));
    }

    /** Drops per-player screen state when they leave. */
    public void forgetPlayer(UUID id) {
        lastBar.remove(id);
    }

    private void actionBar(Player p, String key, String name, long progress, long amount) {
        long now = System.currentTimeMillis();
        Long last = lastBar.get(p.getUniqueId());
        if (last != null && now - last < 400) return;
        lastBar.put(p.getUniqueId(), now);
        p.sendActionBar(msg.msg(key, "quest", name, "progress", String.format(Locale.ROOT, "%,d", progress),
                "amount", String.format(Locale.ROOT, "%,d", amount)));
    }

    // --- saving ---

    private void save(UUID id, String questId) {
        PlayerQuests.Entry e = player(id).get(questId);
        if (e == null) return;
        String state = e.state().name();
        long progress = e.progress();
        db.run(c -> upsert(c, id, questId, state, progress));
    }

    private static void upsert(java.sql.Connection c, UUID id, String quest, String state, long progress) throws java.sql.SQLException {
        Database.update(c, "INSERT INTO quest_progress (uuid, quest, state, progress) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (uuid, quest) DO UPDATE SET state = excluded.state, progress = excluded.progress", id.toString(), quest, state, progress);
    }

    /** Writes progress changed since the last save (every 30 s and at shutdown). */
    public void saveDirty() {
        if (dirty.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>();
        String communityId = community == null ? null : community.id();
        for (UUID id : dirty) {
            PlayerQuests pq = players.get(id);
            if (pq != null) {
                for (var e : pq.entries().entrySet()) rows.add(new Object[]{id, e.getKey(), e.getValue().state().name(), e.getValue().progress()});
            }
            Long contributed = contributions.get(id);
            if (contributed != null && communityId != null) rows.add(new Object[]{id, communityId, "COMMUNITY", contributed});
        }
        dirty.clear();
        db.run(c -> {
            c.setAutoCommit(false);
            try {
                for (Object[] r : rows) upsert(c, (UUID) r[0], (String) r[1], (String) r[2], (long) r[3]);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        });
    }

    // --- admin ---

    /**
     * Replaces quest number {@code number} (1 = first easy quest; the number after the last daily quest is the community quest).
     * Everyone's progress on the old quest is dropped. Returns the new quest, or null if the number is wrong.
     */
    public Quest adminSet(int number, QuestTemplate t, String variant, long amount) {
        if (number < 1 || number > board.size() + 1) return null;
        boolean isCommunity = number == board.size() + 1;
        Tier tier = isCommunity ? Tier.COMMUNITY : board.get(number - 1).tier();
        Quest old = isCommunity ? community : board.get(number - 1);
        Range amounts = t.amounts().getOrDefault(tier, new Range(amount, amount));
        long reward = isCommunity ? settings.communityPot() : QuestGenerator.reward(amount, amounts, settings.rewards().get(tier));
        String base = isCommunity ? currentWeek() + "/community" : day + "/" + tier.id() + "-" + number;
        Quest q = new Quest(base + "-set" + Integer.toHexString(rng.nextInt(1 << 16)), tier, t.id(), variant, amount, reward, null, null);
        List<String> dropped = new ArrayList<>();
        if (old != null) {
            dropped.add(old.id());
            // Rerolls that stood in for the old quest go too; a finished one is paid like at a refresh.
            for (var e : personal.entrySet()) {
                PlayerQuests pq = players.get(e.getKey());
                e.getValue().removeIf(x -> {
                    if (!old.id().equals(x.replaces())) return false;
                    if (pq != null && pq.state(x.id()) == PlayerQuests.State.DONE) pay(e.getKey(), x, true);
                    if (pq != null) pq.forget(x.id());
                    dropped.add(x.id());
                    return true;
                });
            }
            for (PlayerQuests pq : players.values()) pq.forget(old.id());
        }
        if (isCommunity) {
            community = q;
            communityDone = false;
            contributions.clear();
        } else {
            board.set(number - 1, q);
        }
        int slot = isCommunity ? 100 : number - 1;
        String oldId = old == null ? null : old.id();
        db.run(c -> {
            if (oldId != null) Database.update(c, "DELETE FROM quest_board WHERE id = ? OR replaces = ?", oldId, oldId);
            for (String id : List.copyOf(dropped)) Database.update(c, "DELETE FROM quest_progress WHERE quest = ?", id);
            if (q.tier() == Tier.COMMUNITY) Database.update(c, "DELETE FROM quest_progress WHERE state = 'COMMUNITY'");
            insert(c, q, slot);
            if (q.tier() == Tier.COMMUNITY) setMeta(c, "quests.community-paid", "");
        });
        return q;
    }
}
