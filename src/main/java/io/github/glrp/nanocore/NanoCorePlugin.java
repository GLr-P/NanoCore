package io.github.glrp.nanocore;

import io.github.glrp.nanocore.area.AreaService;
import io.github.glrp.nanocore.blocks.PlacedBlockTracker;
import io.github.glrp.nanocore.command.CoreCommands;
import io.github.glrp.nanocore.command.QuestCommands;
import io.github.glrp.nanocore.command.StatsCommands;
import io.github.glrp.nanocore.config.Settings;
import io.github.glrp.nanocore.db.Database;
import io.github.glrp.nanocore.economy.EconomyService;
import io.github.glrp.nanocore.gui.MenuListener;
import io.github.glrp.nanocore.milestone.LockListener;
import io.github.glrp.nanocore.milestone.MilestoneService;
import io.github.glrp.nanocore.quest.QuestBoards;
import io.github.glrp.nanocore.quest.QuestService;
import io.github.glrp.nanocore.shop.SellListener;
import io.github.glrp.nanocore.shop.SellMenu;
import io.github.glrp.nanocore.shop.ShopService;
import io.github.glrp.nanocore.stats.StatKeys;
import io.github.glrp.nanocore.stats.StatsListener;
import io.github.glrp.nanocore.stats.StatsService;
import io.github.glrp.nanocore.text.Messages;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class NanoCorePlugin extends JavaPlugin implements Listener {

    private volatile Settings settings;
    private Messages messages;
    private Database db;
    private EconomyService economy;
    private StatsService stats;
    private PlacedBlockTracker placed;
    private MilestoneService milestones;
    private AreaService areas;
    private ShopService shop;
    private QuestService quests;
    private QuestBoards questBoards;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.from(getConfig());
        messages = new Messages(this);
        getDataFolder().mkdirs();
        db = new Database(getDataFolder().toPath().resolve(settings.databaseFile()), getLogger());

        economy = new EconomyService(db, () -> settings.startingBalance(), settings.currencySingular(), settings.currencyPlural());
        stats = new StatsService(db);
        placed = new PlacedBlockTracker(this);
        milestones = new MilestoneService(db, messages);
        areas = new AreaService(db);
        shop = new ShopService(db, milestones, getLogger());
        shop.reload(getConfig().getConfigurationSection("admin-shop"));
        shop.loadToday();
        quests = new QuestService(this, db, messages);
        questBoards = new QuestBoards(this, db);
        stats.subscribe((player, stat, amount, where) -> quests.onStat(player, stat, amount));

        // Money earned/spent feeds the stats (players only; offline players are skipped).
        economy.addListener((id, delta, reason) -> {
            Player p = getServer().getPlayer(id);
            if (p == null || !getServer().isPrimaryThread()) return;
            stats.add(p, delta > 0 ? StatKeys.MONEY_EARNED : StatKeys.MONEY_SPENT, Math.abs(delta), p.getLocation());
        });

        var pm = getServer().getPluginManager();
        StatsListener statsListener = new StatsListener(this, stats, placed, () -> settings);
        pm.registerEvents(statsListener, this); // must come before the tracker (see StatsListener)
        pm.registerEvents(placed, this);
        pm.registerEvents(new LockListener(this, milestones, messages, () -> settings), this);
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(new SellListener(), this);
        pm.registerEvents(questBoards, this);
        pm.registerEvents(this, this);
        statsListener.start();
        long flushTicks = settings.statsSaveSeconds() * 20L;
        getServer().getScheduler().runTaskTimer(this, () -> {
            stats.flush();
            quests.saveDirty();
        }, flushTicks, flushTicks);
        quests.checkRefresh();
        getServer().getScheduler().runTaskTimer(this, this::questTick, 20L, 100L);

        if (pm.getPlugin("Vault") != null) {
            try {
                io.github.glrp.nanocore.economy.VaultHook.register(this, economy);
            } catch (LinkageError | RuntimeException e) {
                getLogger().warning("Vault is installed but its economy API couldn't be used: " + e);
            }
        }

        CoreCommands commands = new CoreCommands(this);
        QuestCommands questCommands = new QuestCommands(this);
        StatsCommands statsCommands = new StatsCommands(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            commands.register(event.registrar());
            questCommands.register(event.registrar());
            statsCommands.register(event.registrar());
        });

        // Players already online (after a reload) need accounts and stats.
        for (Player p : getServer().getOnlinePlayers()) economy.ensureAccount(p.getUniqueId(), p.getName());
        getComponentLogger().info("NanoCore {} ready ({} accounts)", getPluginMeta().getVersion(), economy.top().size());
    }

    @Override
    public void onDisable() {
        // Items still in a sell window go back to their owner before the server stops.
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof SellMenu m) {
                m.returnItems(); // directly: listeners of a disabled plugin don't run
                p.closeInventory();
            }
        }
        if (quests != null) quests.saveDirty();
        if (questBoards != null) {
            questBoards.flushBacklog();
            questBoards.removeLabels();
        }
        if (stats != null) stats.flush();
        if (placed != null) placed.saveAll(null);
        if (db != null) db.close();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        economy.ensureAccount(e.getPlayer().getUniqueId(), e.getPlayer().getName());
    }

    public void reloadSettings() {
        reloadConfig();
        settings = Settings.from(getConfig());
        messages.reload();
        economy.setCurrencyNames(settings.currencySingular(), settings.currencyPlural());
        shop.reload(getConfig().getConfigurationSection("admin-shop"));
        quests.reload();
    }

    /** Every 5 s: new board when it's time, biome/structure quests, board labels, storehouse backlog. */
    private void questTick() {
        quests.checkRefresh();
        quests.pollPlaces();
        long count = quests.board().size() + (quests.community() == null ? 0 : 1);
        questBoards.updateLabels(messages.msg("quests.board-label", "count", String.valueOf(count),
                "time", io.github.glrp.nanocore.shop.DailyLimit.format(quests.untilRefresh())), quests.settings().labelHeight());
        if (questBoards.backlogSize() > 0 && ++backlogTicks % 12 == 0) questBoards.flushBacklog();
    }

    private int backlogTicks;

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public EconomyService economy() {
        return economy;
    }

    public StatsService statsService() {
        return stats;
    }

    public MilestoneService milestones() {
        return milestones;
    }

    public AreaService areas() {
        return areas;
    }

    public ShopService shop() {
        return shop;
    }

    public QuestService quests() {
        return quests;
    }

    public QuestBoards questBoards() {
        return questBoards;
    }
}
