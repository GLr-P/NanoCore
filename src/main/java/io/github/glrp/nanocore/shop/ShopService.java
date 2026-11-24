package io.github.glrp.nanocore.shop;

import io.github.glrp.nanocore.db.Database;
import io.github.glrp.nanocore.milestone.Milestone;
import io.github.glrp.nanocore.milestone.MilestoneService;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The sell-only admin shop: the price list from config, and how much each player has sold today.
 * Today's totals live in memory (main thread); every sale is written to the database.
 */
public final class ShopService {

    private final Database db;
    private final MilestoneService milestones;
    private final Logger log;
    private volatile Map<String, SellPrice> prices = Map.of();
    private volatile long dailyLimit = 150;
    private volatile LocalTime resetTime = LocalTime.MIDNIGHT;
    private volatile ZoneId zone = ZoneId.systemDefault();
    private final Map<UUID, Long> earned = new HashMap<>();
    private String cachedDay;

    public ShopService(Database db, MilestoneService milestones, Logger log) {
        this.db = db;
        this.milestones = milestones;
        this.log = log;
    }

    /** Reads the admin-shop section of config.yml. Call once at startup (before {@link #loadToday()}) and on reload. */
    public void reload(ConfigurationSection shop) {
        if (shop == null) shop = new org.bukkit.configuration.MemoryConfiguration();
        dailyLimit = Math.max(0, shop.getLong("daily-limit", 150));
        try {
            resetTime = LocalTime.parse(shop.getString("reset-time", "00:00"));
        } catch (RuntimeException e) {
            log.warning("admin-shop.reset-time must look like 00:00; using midnight");
            resetTime = LocalTime.MIDNIGHT;
        }
        String tz = shop.getString("timezone", "");
        try {
            zone = tz == null || tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz);
        } catch (RuntimeException e) {
            log.warning("admin-shop.timezone '" + tz + "' isn't a known time zone; using the server's");
            zone = ZoneId.systemDefault();
        }
        Map<String, SellPrice> list = new LinkedHashMap<>();
        ConfigurationSection section = shop.getConfigurationSection("prices");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String id = key.toLowerCase(Locale.ROOT).replace("minecraft:", "");
                Material m = Material.matchMaterial(id);
                if (m == null || !m.isItem()) {
                    log.warning("admin-shop.prices: unknown item '" + key + "', skipped");
                    continue;
                }
                long price;
                String requires = null;
                if (section.isConfigurationSection(key)) {
                    price = section.getLong(key + ".price", 0);
                    requires = section.getString(key + ".requires");
                    if (requires != null && requires.isBlank()) requires = null;
                    if (requires != null && Milestone.byId(requires).isEmpty()) {
                        log.warning("admin-shop.prices." + key + ": unknown milestone '" + requires + "' (try " + Milestone.ids() + "), skipped");
                        continue;
                    }
                } else {
                    price = section.getLong(key, 0);
                }
                if (price <= 0) {
                    log.warning("admin-shop.prices." + key + ": price must be above 0, skipped");
                    continue;
                }
                String itemId = m.getKey().getKey();
                list.put(itemId, new SellPrice(itemId, price, requires == null ? null : Milestone.byId(requires).get().id()));
            }
        }
        prices = Collections.unmodifiableMap(list);
        cachedDay = null; // the reset time may have changed
    }

    /** Loads today's totals. Startup only. */
    public void loadToday() {
        String day = today();
        Map<UUID, Long> loaded = db.sync(c -> {
            Map<UUID, Long> m = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, earned FROM shop_daily WHERE day = ?")) {
                ps.setString(1, day);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) m.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                }
            }
            return m;
        });
        earned.clear();
        earned.putAll(loaded);
        cachedDay = day;
    }

    public Map<String, SellPrice> prices() {
        return prices;
    }

    public long dailyLimit() {
        return dailyLimit;
    }

    public boolean unlocked(String milestoneId) {
        return Milestone.byId(milestoneId).map(milestones::isUnlocked).orElse(false);
    }

    public String today() {
        return DailyLimit.day(ZonedDateTime.now(zone), resetTime).toString();
    }

    public Duration untilReset() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        return Duration.between(now, DailyLimit.nextReset(now, resetTime));
    }

    private void rollDay() {
        String day = today();
        if (!day.equals(cachedDay)) {
            if (cachedDay != null) earned.clear(); // a new day: nobody has sold anything yet
            cachedDay = day;
        }
    }

    public long earnedToday(UUID id) {
        rollDay();
        return earned.getOrDefault(id, 0L);
    }

    public long remaining(UUID id) {
        return Math.max(0, dailyLimit - earnedToday(id));
    }

    public SellQuote.Result quote(java.util.List<SellQuote.Offer> offers, UUID seller) {
        return SellQuote.quote(offers, prices, this::unlocked, remaining(seller));
    }

    /** Adds a finished sale to today's total and the sales log. The money itself is paid by the caller. */
    public void record(UUID id, SellQuote.Result sale) {
        if (sale.empty()) return;
        rollDay();
        long total = earned.merge(id, sale.total(), Long::sum);
        String day = cachedDay;
        long now = System.currentTimeMillis();
        Map<String, long[]> byItem = new LinkedHashMap<>();
        for (SellQuote.Line l : sale.lines()) {
            if (l.sold() <= 0) continue;
            long[] t = byItem.computeIfAbsent(l.offer().item(), k -> new long[2]);
            t[0] += l.sold();
            t[1] += l.coins();
        }
        db.run(c -> {
            Database.update(c, "INSERT INTO shop_daily (uuid, day, earned) VALUES (?, ?, ?) "
                    + "ON CONFLICT (uuid, day) DO UPDATE SET earned = excluded.earned", id.toString(), day, total);
            for (var e : byItem.entrySet()) {
                Database.update(c, "INSERT INTO shop_sales (time, uuid, item, amount, coins) VALUES (?, ?, ?, ?, ?)",
                        now, id.toString(), e.getKey(), e.getValue()[0], e.getValue()[1]);
            }
        });
    }

    /** Admin: lets a player sell their full limit again today. */
    public void resetToday(UUID id) {
        rollDay();
        earned.remove(id);
        String day = cachedDay;
        db.run(c -> Database.update(c, "DELETE FROM shop_daily WHERE uuid = ? AND day = ?", id.toString(), day));
    }
}
