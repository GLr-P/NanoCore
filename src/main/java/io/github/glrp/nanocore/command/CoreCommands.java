package io.github.glrp.nanocore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.area.Area;
import io.github.glrp.nanocore.economy.Accounts;
import io.github.glrp.nanocore.economy.EconomyService;
import io.github.glrp.nanocore.milestone.Milestone;
import io.github.glrp.nanocore.milestone.MilestoneMenu;
import io.github.glrp.nanocore.shop.SellMenu;
import io.github.glrp.nanocore.text.Messages;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Player commands (/balance, /pay, /baltop, /milestones) and the /nanocore admin tree. */
public final class CoreCommands {

    private static final int TOP_PAGE = 10;

    private final NanoCorePlugin plugin;
    private final Map<UUID, Location[]> corners = new HashMap<>();

    public CoreCommands(NanoCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    private EconomyService eco() {
        return plugin.economy();
    }

    private static final SuggestionProvider<CommandSourceStack> ONLINE = (ctx, b) -> {
        String typed = b.getRemainingLowerCase();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(typed)) b.suggest(p.getName());
        }
        return b.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> MILESTONES = (ctx, b) -> {
        for (Milestone m : Milestone.values()) if (m.id().startsWith(b.getRemainingLowerCase())) b.suggest(m.id());
        return b.buildFuture();
    };

    private static RequiredArgumentBuilder<CommandSourceStack, String> playerArg(String name) {
        return Commands.argument(name, StringArgumentType.word()).suggests(ONLINE);
    }

    public void register(Commands commands) {
        commands.register(balance().build(), "Shows your balance", List.of("bal", "money"));
        commands.register(pay().build(), "Pays another player");
        commands.register(baltop().build(), "Richest players", List.of("balancetop"));
        commands.register(Commands.literal("milestones").executes(this::milestones).build(), "Shows the server's milestones");
        commands.register(Commands.literal("sell").executes(ctx -> {
            if (!(ctx.getSource().getSender() instanceof Player p)) {
                msg().send(ctx.getSource().getSender(), "general.players-only");
                return 0;
            }
            SellMenu.open(plugin, p);
            return Command.SINGLE_SUCCESS;
        }).build(), "Sells rare items to the server");
        commands.register(admin().build(), "NanoCore menu (everything in one window)", List.of("nc"));
    }

    // --- /balance [player] ---

    private LiteralArgumentBuilder<CommandSourceStack> balance() {
        return Commands.literal("balance")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player p)) {
                        msg().send(ctx.getSource().getSender(), "general.players-only");
                        return 0;
                    }
                    msg().send(p, "economy.balance-self", "balance", eco().format(eco().balance(p.getUniqueId())));
                    return Command.SINGLE_SUCCESS;
                })
                .then(playerArg("player").executes(ctx -> {
                    CommandSender s = ctx.getSource().getSender();
                    String name = StringArgumentType.getString(ctx, "player");
                    Optional<UUID> id = eco().findByName(name);
                    if (id.isEmpty()) {
                        msg().send(s, "economy.unknown-player", "player", name);
                        return 0;
                    }
                    msg().send(s, "economy.balance-other", "player", eco().name(id.get()), "balance", eco().format(eco().balance(id.get())));
                    return Command.SINGLE_SUCCESS;
                }));
    }

    // --- /pay <player> <amount> ---

    private LiteralArgumentBuilder<CommandSourceStack> pay() {
        return Commands.literal("pay").then(playerArg("player").then(
                Commands.argument("amount", LongArgumentType.longArg(1)).executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player p)) {
                        msg().send(ctx.getSource().getSender(), "general.players-only");
                        return 0;
                    }
                    String name = StringArgumentType.getString(ctx, "player");
                    long amount = LongArgumentType.getLong(ctx, "amount");
                    Optional<UUID> to = eco().findByName(name);
                    if (to.isEmpty()) {
                        msg().send(p, "economy.unknown-player", "player", name);
                        return 0;
                    }
                    Accounts.Result r = eco().transfer(p.getUniqueId(), to.get(), amount, "pay");
                    switch (r) {
                        case OK -> {
                            msg().send(p, "economy.paid", "player", eco().name(to.get()), "amount", eco().format(amount));
                            Player target = plugin.getServer().getPlayer(to.get());
                            if (target != null) msg().send(target, "economy.received", "player", p.getName(), "amount", eco().format(amount));
                        }
                        case SAME_ACCOUNT -> msg().send(p, "economy.self-pay");
                        case NOT_ENOUGH -> msg().send(p, "economy.not-enough", "balance", eco().format(eco().balance(p.getUniqueId())));
                        default -> msg().send(p, "economy.invalid-amount");
                    }
                    return r == Accounts.Result.OK ? Command.SINGLE_SUCCESS : 0;
                })));
    }

    // --- /baltop [page] ---

    private LiteralArgumentBuilder<CommandSourceStack> baltop() {
        return Commands.literal("baltop")
                .executes(ctx -> baltopPage(ctx.getSource().getSender(), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> baltopPage(ctx.getSource().getSender(), IntegerArgumentType.getInteger(ctx, "page"))));
    }

    private int baltopPage(CommandSender s, int page) {
        List<Accounts.Entry> top = eco().top();
        if (top.isEmpty()) {
            msg().send(s, "economy.top-empty");
            return 0;
        }
        msg().send(s, "economy.top-header", "page", String.valueOf(page));
        int from = (page - 1) * TOP_PAGE;
        for (int i = from; i < Math.min(top.size(), from + TOP_PAGE); i++) {
            Accounts.Entry e = top.get(i);
            msg().send(s, "economy.top-line", "rank", String.valueOf(i + 1), "player", e.name(), "balance", eco().format(e.balance()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int milestones(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player p)) {
            for (Milestone m : Milestone.values()) {
                msg().send(ctx.getSource().getSender(), "milestones.list-line", "id", m.id(),
                        "state", plugin.milestones().isUnlocked(m) ? "unlocked" : "locked");
            }
            return Command.SINGLE_SUCCESS;
        }
        MilestoneMenu.open(p, plugin.milestones(), msg());
        return Command.SINGLE_SUCCESS;
    }

    // --- /nanocore ... (admins) ---

    private LiteralArgumentBuilder<CommandSourceStack> admin() {
        return Commands.literal("nanocore")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player p)) {
                        msg().send(ctx.getSource().getSender(), "general.players-only");
                        return 0;
                    }
                    if (p.hasPermission("nanocore.admin")) io.github.glrp.nanocore.admin.ControlPanel.open(plugin, p);
                    else io.github.glrp.nanocore.admin.PlayerMenu.open(plugin, p);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("version").requires(CoreCommands::admin).executes(ctx -> {
                    ctx.getSource().getSender().sendMessage(Component.text(
                            "NanoCore " + plugin.getPluginMeta().getVersion() + " on " + plugin.getServer().getMinecraftVersion(), NamedTextColor.GREEN));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("reload").requires(CoreCommands::admin).executes(ctx -> {
                    plugin.reloadSettings();
                    msg().send(ctx.getSource().getSender(), "general.reloaded");
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ecoAdmin())
                .then(milestoneAdmin())
                .then(area())
                .then(shopAdmin())
                .then(Commands.literal("stats").requires(CoreCommands::admin).then(playerArg("player")
                        .executes(ctx -> stats(ctx, ""))
                        .then(Commands.argument("prefix", StringArgumentType.word())
                                .executes(ctx -> stats(ctx, StringArgumentType.getString(ctx, "prefix"))))));
    }

    private static boolean admin(CommandSourceStack src) {
        return src.getSender().hasPermission("nanocore.admin");
    }

    private LiteralArgumentBuilder<CommandSourceStack> ecoAdmin() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("eco").requires(CoreCommands::admin);
        for (String op : List.of("give", "take", "set")) {
            root.then(Commands.literal(op).then(playerArg("player").then(
                    Commands.argument("amount", LongArgumentType.longArg(0)).executes(ctx -> {
                        CommandSender s = ctx.getSource().getSender();
                        String name = StringArgumentType.getString(ctx, "player");
                        long amount = LongArgumentType.getLong(ctx, "amount");
                        Optional<UUID> id = eco().findByName(name);
                        if (id.isEmpty()) {
                            msg().send(s, "economy.unknown-player", "player", name);
                            return 0;
                        }
                        String reason = "admin " + op + " by " + s.getName();
                        Accounts.Result r = switch (op) {
                            case "give" -> eco().deposit(id.get(), amount, reason);
                            case "take" -> eco().withdraw(id.get(), amount, reason);
                            default -> eco().set(id.get(), amount, reason);
                        };
                        if (r != Accounts.Result.OK) {
                            msg().send(s, r == Accounts.Result.NOT_ENOUGH ? "economy.not-enough" : "economy.invalid-amount",
                                    "balance", eco().format(eco().balance(id.get())));
                            return 0;
                        }
                        msg().send(s, "economy.admin-done", "player", eco().name(id.get()), "balance", eco().format(eco().balance(id.get())));
                        return Command.SINGLE_SUCCESS;
                    }))));
        }
        return root;
    }

    private LiteralArgumentBuilder<CommandSourceStack> milestoneAdmin() {
        return Commands.literal("milestone").requires(CoreCommands::admin)
                .then(Commands.literal("list").executes(ctx -> {
                    for (Milestone m : Milestone.values()) {
                        msg().send(ctx.getSource().getSender(), "milestones.list-line", "id", m.id(),
                                "state", plugin.milestones().isUnlocked(m) ? "unlocked" : "locked");
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("unlock").then(Commands.argument("id", StringArgumentType.word()).suggests(MILESTONES)
                        .executes(ctx -> setMilestone(ctx, true))))
                .then(Commands.literal("lock").then(Commands.argument("id", StringArgumentType.word()).suggests(MILESTONES)
                        .executes(ctx -> setMilestone(ctx, false))));
    }

    private int setMilestone(CommandContext<CommandSourceStack> ctx, boolean unlock) {
        CommandSender s = ctx.getSource().getSender();
        Optional<Milestone> m = Milestone.byId(StringArgumentType.getString(ctx, "id"));
        if (m.isEmpty()) {
            msg().send(s, "milestones.unknown", "ids", Milestone.ids());
            return 0;
        }
        String name = plugin.milestones().displayName(m.get());
        if (!plugin.milestones().set(m.get(), unlock, s.getName())) {
            msg().send(s, "milestones.already", "milestone", name, "state", unlock ? "unlocked" : "locked");
            return 0;
        }
        if (!unlock) msg().send(s, "milestones.locked-admin", "milestone", name);
        return Command.SINGLE_SUCCESS;
    }

    private LiteralArgumentBuilder<CommandSourceStack> shopAdmin() {
        return Commands.literal("shop").requires(CoreCommands::admin)
                .then(Commands.literal("status").then(playerArg("player").executes(ctx -> shopPlayer(ctx, false))))
                .then(Commands.literal("reset").then(playerArg("player").executes(ctx -> shopPlayer(ctx, true))))
                .then(Commands.literal("prices").executes(ctx -> {
                    CommandSender s = ctx.getSource().getSender();
                    plugin.shop().prices().values().forEach(p -> msg().send(s, "shop.admin-price-line", "item", p.item(),
                            "price", eco().format(p.price()), "requires", p.requires() == null ? "" : " (after " + p.requires() + ")"));
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private int shopPlayer(CommandContext<CommandSourceStack> ctx, boolean reset) {
        CommandSender s = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        Optional<UUID> id = eco().findByName(name);
        if (id.isEmpty()) {
            msg().send(s, "economy.unknown-player", "player", name);
            return 0;
        }
        if (reset) {
            plugin.shop().resetToday(id.get());
            msg().send(s, "shop.admin-reset", "player", eco().name(id.get()));
        } else {
            msg().send(s, "shop.admin-status", "player", eco().name(id.get()),
                    "earned", eco().format(plugin.shop().earnedToday(id.get())), "limit", eco().format(plugin.shop().dailyLimit()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private LiteralArgumentBuilder<CommandSourceStack> area() {
        return Commands.literal("area").requires(CoreCommands::admin)
                .then(Commands.literal("pos1").executes(ctx -> corner(ctx, 0)))
                .then(Commands.literal("pos2").executes(ctx -> corner(ctx, 1)))
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player p)) return 0;
                    Location[] c = corners.get(p.getUniqueId());
                    if (c == null || c[0] == null || c[1] == null || !c[0].getWorld().equals(c[1].getWorld())) {
                        msg().send(p, "areas.need-corners");
                        return 0;
                    }
                    String name = StringArgumentType.getString(ctx, "name");
                    plugin.areas().save(Area.box(name, c[0].getWorld().getName(),
                            c[0].getBlockX(), c[0].getBlockY(), c[0].getBlockZ(), c[1].getBlockX(), c[1].getBlockY(), c[1].getBlockZ()));
                    msg().send(p, "areas.created", "name", name);
                    return Command.SINGLE_SUCCESS;
                })))
                .then(Commands.literal("circle").then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1)).executes(ctx -> {
                            Location at = ctx.getSource().getLocation();
                            String name = StringArgumentType.getString(ctx, "name");
                            plugin.areas().save(Area.circle(name, at.getWorld().getName(), at.getBlockX() + 0.5, at.getBlockZ() + 0.5,
                                    IntegerArgumentType.getInteger(ctx, "radius")));
                            msg().send(ctx.getSource().getSender(), "areas.created", "name", name);
                            return Command.SINGLE_SUCCESS;
                        }))))
                .then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "name");
                    msg().send(ctx.getSource().getSender(), plugin.areas().delete(name) ? "areas.deleted" : "areas.unknown", "name", name);
                    return Command.SINGLE_SUCCESS;
                })))
                .then(Commands.literal("list").executes(ctx -> {
                    var all = plugin.areas().all();
                    if (all.isEmpty()) msg().send(ctx.getSource().getSender(), "areas.list-empty");
                    for (Area a : all) msg().send(ctx.getSource().getSender(), "areas.list-line", "name", a.name(), "desc", a.describe());
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private int corner(CommandContext<CommandSourceStack> ctx, int index) {
        if (!(ctx.getSource().getSender() instanceof Player p)) {
            msg().send(ctx.getSource().getSender(), "general.players-only");
            return 0;
        }
        Location l = p.getLocation().toBlockLocation();
        corners.computeIfAbsent(p.getUniqueId(), k -> new Location[2])[index] = l;
        msg().send(p, "areas.pos-set", "n", String.valueOf(index + 1),
                "x", String.valueOf(l.getBlockX()), "y", String.valueOf(l.getBlockY()), "z", String.valueOf(l.getBlockZ()));
        return Command.SINGLE_SUCCESS;
    }

    private int stats(CommandContext<CommandSourceStack> ctx, String prefix) {
        CommandSender s = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        Optional<UUID> id = eco().findByName(name);
        if (id.isEmpty()) {
            msg().send(s, "economy.unknown-player", "player", name);
            return 0;
        }
        plugin.statsService().flush();
        Map<String, Long> all = new TreeMap<>(plugin.statsService().loadOffline(id.get()));
        StringBuilder line = new StringBuilder("Stats for " + eco().name(id.get()) + ":");
        all.forEach((k, v) -> {
            if (k.startsWith(prefix)) line.append(' ').append(k).append('=').append(v);
        });
        s.sendMessage(Component.text(line.toString(), NamedTextColor.GRAY));
        return Command.SINGLE_SUCCESS;
    }
}
