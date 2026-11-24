package io.github.glrp.nanocore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.quest.PlayerQuests;
import io.github.glrp.nanocore.quest.Quest;
import io.github.glrp.nanocore.quest.QuestMenu;
import io.github.glrp.nanocore.quest.QuestTemplate;
import io.github.glrp.nanocore.text.Messages;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver;
import io.papermc.paper.math.BlockPosition;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/** /quests for players (the window), plus admin subcommands for boards, the storehouse and the board's quests. */
public final class QuestCommands {

    private final NanoCorePlugin plugin;

    public QuestCommands(NanoCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    private static boolean admin(CommandSourceStack s) {
        return s.getSender().hasPermission("nanocore.admin");
    }

    public void register(Commands commands) {
        SuggestionProvider<CommandSourceStack> templates = (ctx, b) -> {
            for (String id : plugin.quests().templates().keySet()) if (id.startsWith(b.getRemainingLowerCase())) b.suggest(id);
            return b.buildFuture();
        };
        commands.register(Usage.wrap(Commands.literal("quests")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player p)) {
                        msg().send(ctx.getSource().getSender(), "general.players-only");
                        return 0;
                    }
                    QuestMenu.open(plugin, p, plugin.questBoards().nearBoard(p, plugin.quests().settings().boardReach()));
                    return Command.SINGLE_SUCCESS;
                })
                .then(place("board", (block, add) -> {
                    if (add) return plugin.questBoards().addBoard(block) ? "quests.board-set" : "quests.board-already";
                    return plugin.questBoards().removeBoard(block) ? "quests.board-removed" : "quests.board-none";
                }))
                .then(place("storehouse", (block, add) -> {
                    if (!(block.getState(false) instanceof Container)) return "quests.storehouse-not-chest";
                    plugin.questBoards().setStorehouse(block);
                    return "quests.storehouse-set";
                }))
                .then(Commands.literal("list").requires(QuestCommands::admin).executes(this::list))
                .then(Commands.literal("refresh").requires(QuestCommands::admin)
                        .executes(ctx -> refresh(ctx, true, false))
                        .then(Commands.literal("daily").executes(ctx -> refresh(ctx, true, false)))
                        .then(Commands.literal("community").executes(ctx -> refresh(ctx, false, true))))
                .then(Commands.literal("set").requires(QuestCommands::admin)
                        .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                .then(Commands.argument("template", StringArgumentType.word()).suggests(templates)
                                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                                .executes(ctx -> set(ctx, null))
                                                .then(Commands.argument("variant", StringArgumentType.word())
                                                        .executes(ctx -> set(ctx, StringArgumentType.getString(ctx, "variant"))))))))
                .then(Commands.literal("status").requires(QuestCommands::admin)
                        .then(Commands.argument("player", StringArgumentType.word()).executes(this::status)))
                .build(), "quests", plugin::messages), "Your quests (take and claim them at the quest board)", List.of("quest"));
    }

    /** "board|storehouse set|remove [x y z]": the block looked at, or the given position. */
    private LiteralArgumentBuilder<CommandSourceStack> place(String name, BiFunction<Block, Boolean, String> action) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name).requires(QuestCommands::admin);
        for (String op : name.equals("board") ? List.of("set", "remove") : List.of("set")) {
            boolean add = op.equals("set");
            root.then(Commands.literal(op)
                    .executes(ctx -> {
                        CommandSender s = ctx.getSource().getSender();
                        if (!(s instanceof Player p)) {
                            msg().send(s, "general.players-only");
                            return 0;
                        }
                        Block b = p.getTargetBlockExact(6);
                        if (b == null || b.getType().isAir()) {
                            msg().send(p, "quests.look-at-block");
                            return 0;
                        }
                        return reply(s, action.apply(b, add), b);
                    })
                    .then(Commands.argument("pos", ArgumentTypes.blockPosition()).executes(ctx -> {
                        Block b = block(ctx);
                        return reply(ctx.getSource().getSender(), action.apply(b, add), b);
                    })));
        }
        return root;
    }

    private static Block block(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BlockPosition pos = ctx.getArgument("pos", BlockPositionResolver.class).resolve(ctx.getSource());
        return ctx.getSource().getLocation().getWorld().getBlockAt(pos.blockX(), pos.blockY(), pos.blockZ());
    }

    private int reply(CommandSender s, String key, Block b) {
        msg().send(s, key, "x", String.valueOf(b.getX()), "y", String.valueOf(b.getY()), "z", String.valueOf(b.getZ()));
        return key.endsWith("-set") || key.endsWith("-removed") ? Command.SINGLE_SUCCESS : 0;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        var qs = plugin.quests();
        List<Quest> board = qs.board();
        for (int i = 0; i < board.size(); i++) line(s, i + 1, board.get(i));
        if (qs.community() != null) line(s, board.size() + 1, qs.community());
        msg().send(s, "quests.admin-places", "boards", String.valueOf(plugin.questBoards().boards().size()),
                "storehouse", plugin.questBoards().storehouse() == null ? msg().raw("quests.storehouse-not-set") : plugin.questBoards().storehouse().toString(),
                "backlog", String.valueOf(plugin.questBoards().backlogSize()));
        return Command.SINGLE_SUCCESS;
    }

    private void line(CommandSender s, int n, Quest q) {
        msg().send(s, "quests.admin-line", "n", String.valueOf(n), "tier", q.tier().id(),
                "template", q.template() + (q.variant() == null ? "" : ":" + q.variant()),
                "name", plugin.quests().resolve(q).name(), "amount", String.valueOf(q.amount()), "reward", plugin.economy().format(q.reward()));
    }

    private int refresh(CommandContext<CommandSourceStack> ctx, boolean daily, boolean community) {
        var qs = plugin.quests();
        if (daily) qs.refreshDaily(qs.currentDay());
        if (community) qs.refreshWeekly(qs.currentWeek());
        msg().send(ctx.getSource().getSender(), "quests.admin-refreshed");
        return Command.SINGLE_SUCCESS;
    }

    private int set(CommandContext<CommandSourceStack> ctx, String variant) {
        CommandSender s = ctx.getSource().getSender();
        String id = StringArgumentType.getString(ctx, "template");
        QuestTemplate t = plugin.quests().templates().get(id);
        if (t == null) {
            msg().send(s, "quests.admin-unknown-template", "template", id);
            return 0;
        }
        if (variant != null && t.variant(variant) == null) {
            msg().send(s, "quests.admin-unknown-variant", "variant", variant,
                    "variants", String.join(", ", t.variants().stream().map(QuestTemplate.Variant::id).toList()));
            return 0;
        }
        if (variant == null && !t.variants().isEmpty()) variant = t.variants().getFirst().id();
        int n = IntegerArgumentType.getInteger(ctx, "number");
        Quest q = plugin.quests().adminSet(n, t, variant, LongArgumentType.getLong(ctx, "amount"));
        if (q == null) {
            msg().send(s, "quests.admin-bad-number", "max", String.valueOf(plugin.quests().board().size() + 1));
            return 0;
        }
        line(s, n, q);
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        Optional<UUID> id = plugin.economy().findByName(name);
        if (id.isEmpty()) {
            msg().send(s, "economy.unknown-player", "player", name);
            return 0;
        }
        var qs = plugin.quests();
        PlayerQuests pq = qs.player(id.get());
        List<Quest> view = qs.view(id.get());
        StringBuilder out = new StringBuilder(msg().raw("quests.admin-status-header").replace("<player>", plugin.economy().name(id.get())));
        for (int i = 0; i < view.size(); i++) {
            Quest q = view.get(i);
            PlayerQuests.State st = pq.state(q.id());
            out.append(" #").append(i + 1).append(' ').append(q.template()).append(q.owner() != null ? "(reroll)" : "")
                    .append('=').append(st == null ? "open" : st.name().toLowerCase())
                    .append(' ').append(qs.shown(q, pq.progress(q.id()))).append('/').append(q.amount());
        }
        Quest c = qs.community();
        if (c != null) {
            out.append(" | community ").append(c.template()).append(' ').append(qs.shown(c, qs.communityProgress())).append('/').append(c.amount())
                    .append(" yours ").append(qs.shown(c, qs.contribution(id.get()))).append(qs.communityDone() ? " done" : "");
        }
        s.sendPlainMessage(out.toString());
        return Command.SINGLE_SUCCESS;
    }
}
