package io.github.glrp.nanocore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.stats.StatKeys;
import io.github.glrp.nanocore.text.Messages;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** /stats [player] (a short summary) and /stats top <stat> (leaderboard). */
public final class StatsCommands {

    /** A stat players can see: its name in messages.yml, the counter, and how to show it. */
    public record Shown(String id, String stat, Kind kind) {
    }

    public enum Kind { COUNT, DISTANCE, TIME, MONEY }

    public static final List<Shown> SHOWN = List.of(
            new Shown("mined", StatKeys.MINE_TOTAL, Kind.COUNT),
            new Shown("placed", StatKeys.PLACE_TOTAL, Kind.COUNT),
            new Shown("kills", StatKeys.KILL_TOTAL, Kind.COUNT),
            new Shown("deaths", StatKeys.DEATH_TOTAL, Kind.COUNT),
            new Shown("fish", StatKeys.FISH + "fish", Kind.COUNT),
            new Shown("harvested", StatKeys.HARVEST_TOTAL, Kind.COUNT),
            new Shown("explored", StatKeys.EXPLORE_CHUNKS, Kind.COUNT),
            new Shown("walked", StatKeys.MOVE + "walk", Kind.DISTANCE),
            new Shown("played", StatKeys.TIME_PLAYED, Kind.TIME),
            new Shown("quests", StatKeys.QUESTS_DONE, Kind.COUNT),
            new Shown("earned", StatKeys.MONEY_EARNED, Kind.MONEY));

    private final NanoCorePlugin plugin;

    public StatsCommands(NanoCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    /** "1,234", "5,600 blocks", "12h 3m". Pure, for tests. */
    public static String format(Kind kind, long value, String unitBlocks) {
        return switch (kind) {
            case DISTANCE -> String.format(Locale.ROOT, "%,d %s", value / 100, unitBlocks);
            case TIME -> {
                long h = value / 3600, m = (value % 3600) / 60;
                yield h > 0 ? h + "h " + m + "m" : m + "m";
            }
            default -> String.format(Locale.ROOT, "%,d", value);
        };
    }

    private String show(Shown s, long value) {
        return s.kind() == Kind.MONEY ? plugin.economy().format(value) : format(s.kind(), value, msg().raw("stats.blocks"));
    }

    private static final SuggestionProvider<CommandSourceStack> STATS = (ctx, b) -> {
        for (Shown s : SHOWN) if (s.id().startsWith(b.getRemainingLowerCase())) b.suggest(s.id());
        return b.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> PLAYERS = (ctx, b) -> {
        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase())) b.suggest(p.getName());
        }
        return b.buildFuture();
    };

    public void register(Commands commands) {
        commands.register(Usage.wrap(Commands.literal("stats")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player p)) {
                        msg().send(ctx.getSource().getSender(), "general.players-only");
                        return 0;
                    }
                    summary(p, p.getUniqueId());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("top").then(Commands.argument("stat", StringArgumentType.word()).suggests(STATS).executes(ctx -> {
                    top(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "stat"));
                    return Command.SINGLE_SUCCESS;
                })))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS).executes(ctx -> {
                    CommandSender s = ctx.getSource().getSender();
                    String name = StringArgumentType.getString(ctx, "player");
                    Optional<UUID> id = plugin.economy().findByName(name);
                    if (id.isEmpty()) {
                        msg().send(s, "economy.unknown-player", "player", name);
                        return 0;
                    }
                    summary(s, id.get());
                    return Command.SINGLE_SUCCESS;
                }))
                .build(), "stats", plugin::messages), "Your stats, someone else's, or a leaderboard");
    }

    private void summary(CommandSender to, UUID id) {
        String name = plugin.economy().name(id);
        plugin.statsService().loadOfflineAsync(id).thenAccept(all -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            msg().send(to, "stats.header", "player", name);
            for (Shown s : SHOWN) {
                msg().send(to, "stats.line", "name", msg().raw("stats.names." + s.id()), "value", show(s, all.getOrDefault(s.stat(), 0L)));
            }
        }));
    }

    private void top(CommandSender to, String statId) {
        Shown shown = null;
        for (Shown s : SHOWN) if (s.id().equalsIgnoreCase(statId)) shown = s;
        if (shown == null) {
            msg().send(to, "stats.unknown", "stats", String.join(", ", SHOWN.stream().map(Shown::id).toList()));
            return;
        }
        Shown s = shown;
        plugin.statsService().top(s.stat(), 10).thenAccept(rows -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            msg().send(to, "stats.top-header", "name", msg().raw("stats.names." + s.id()));
            if (rows.isEmpty()) msg().send(to, "stats.top-empty");
            int rank = 1;
            for (Map.Entry<UUID, Long> r : rows) {
                msg().send(to, "stats.top-line", "rank", String.valueOf(rank++), "player", plugin.economy().name(r.getKey()), "value", show(s, r.getValue()));
            }
        }));
    }
}
