package io.github.glrp.nanocore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.event.Durations;
import io.github.glrp.nanocore.event.EventDef;
import io.github.glrp.nanocore.event.EventMenus;
import io.github.glrp.nanocore.event.EventService;
import io.github.glrp.nanocore.event.EventState;
import io.github.glrp.nanocore.event.Ranking;
import io.github.glrp.nanocore.event.VoteTally;
import io.github.glrp.nanocore.text.Messages;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** /event for players (info, join, leave, done, rules, top, claim, history), /vote, and the admin controls. */
public final class EventCommands {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private final NanoCorePlugin plugin;

    public EventCommands(NanoCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    private EventService ev() {
        return plugin.events();
    }

    private static boolean admin(CommandSourceStack s) {
        return s.getSender().hasPermission("nanocore.admin");
    }

    private interface PlayerAction {
        int run(Player p);
    }

    private int asPlayer(CommandContext<CommandSourceStack> ctx, PlayerAction a) {
        if (!(ctx.getSource().getSender() instanceof Player p)) {
            msg().send(ctx.getSource().getSender(), "general.players-only");
            return 0;
        }
        return a.run(p);
    }

    public void register(Commands commands) {
        SuggestionProvider<CommandSourceStack> eventIds = (ctx, b) -> {
            for (String id : ev().defs().keySet()) if (id.startsWith(b.getRemainingLowerCase())) b.suggest(id);
            return b.buildFuture();
        };
        SuggestionProvider<CommandSourceStack> inEvent = (ctx, b) -> {
            if (ev().running()) for (UUID id : ev().state().participants) {
                String n = plugin.economy().name(id);
                if (n.toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase())) b.suggest(n);
            }
            return b.buildFuture();
        };

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("event")
                .executes(this::info)
                .then(Commands.literal("join").executes(ctx -> asPlayer(ctx, p -> {
                    String key = switch (ev().join(p, false)) {
                        case OK -> null;
                        case NONE -> "events.none";
                        case ALREADY -> "events.already-in";
                        case CLOSED -> "events.closed";
                        case FULL -> "events.full";
                    };
                    if (key != null) msg().send(p, key);
                    return key == null ? Command.SINGLE_SUCCESS : 0;
                })))
                .then(Commands.literal("leave").executes(ctx -> asPlayer(ctx, p -> {
                    boolean ok = ev().leave(p);
                    msg().send(p, ok ? "events.left" : "events.cant-leave");
                    return ok ? Command.SINGLE_SUCCESS : 0;
                })))
                .then(Commands.literal("done").executes(ctx -> asPlayer(ctx, p -> {
                    String err = ev().ready(p);
                    if (err != null) msg().send(p, err);
                    return err == null ? Command.SINGLE_SUCCESS : 0;
                })))
                .then(Commands.literal("rules").executes(this::rules))
                .then(Commands.literal("handin").executes(ctx -> asPlayer(ctx, p -> {
                    String err = ev().parts().handIn(p);
                    if (err != null) msg().send(p, err);
                    return err == null ? Command.SINGLE_SUCCESS : 0;
                })))
                .then(Commands.literal("deliver").executes(ctx -> asPlayer(ctx, p -> {
                    String err = ev().parts().deliver(p);
                    if (err != null) msg().send(p, err);
                    return err == null ? Command.SINGLE_SUCCESS : 0;
                })))
                .then(Commands.literal("list").executes(ctx -> asPlayer(ctx, p -> {
                    ev().parts().showList(p);
                    return Command.SINGLE_SUCCESS;
                })))
                .then(Commands.literal("plots").executes(ctx -> {
                    CommandSender s = ctx.getSource().getSender();
                    var plots = ev().parts().plotList();
                    if (plots.isEmpty()) msg().send(s, "events.plots-none");
                    for (String[] p : plots) msg().send(s, "events.plots-line", "player", p[0], "x", p[1], "y", p[2], "z", p[3]);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("check").requires(EventCommands::admin)
                        .executes(ctx -> {
                            CommandSender s = ctx.getSource().getSender();
                            List<String> list = ev().parts().checklist();
                            if (list.isEmpty()) msg().send(s, "events.check-none");
                            for (int i = 0; i < list.size(); i++) msg().send(s, "events.check-line", "n", String.valueOf(i + 1), "task", list.get(i));
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 50)).executes(ctx -> {
                            String err = ev().parts().check(IntegerArgumentType.getInteger(ctx, "n"), ctx.getSource().getSender().getName());
                            if (err != null) msg().send(ctx.getSource().getSender(), err);
                            return err == null ? Command.SINGLE_SUCCESS : 0;
                        })))
                .then(Commands.literal("create").requires(EventCommands::admin)
                        .then(Commands.argument("new-id", StringArgumentType.word())
                                .then(Commands.argument("template", StringArgumentType.word()).suggests(eventIds).executes(ctx -> asPlayer(ctx, p -> {
                                    String err = io.github.glrp.nanocore.event.EventEditor.create(plugin, StringArgumentType.getString(ctx, "new-id"),
                                            StringArgumentType.getString(ctx, "template"));
                                    if (err != null) {
                                        msg().send(p, err);
                                        return 0;
                                    }
                                    io.github.glrp.nanocore.event.EventEditor.open(plugin, p, StringArgumentType.getString(ctx, "new-id").toLowerCase(Locale.ROOT));
                                    return Command.SINGLE_SUCCESS;
                                })))))
                .then(Commands.literal("edit").requires(EventCommands::admin)
                        .executes(ctx -> asPlayer(ctx, p -> {
                            io.github.glrp.nanocore.event.EventEditor.list(plugin, p);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(eventIds).executes(ctx -> asPlayer(ctx, p -> {
                            io.github.glrp.nanocore.event.EventEditor.open(plugin, p, StringArgumentType.getString(ctx, "id"));
                            return Command.SINGLE_SUCCESS;
                        }))))
                .then(Commands.literal("top").executes(this::top))
                .then(Commands.literal("claim").executes(ctx -> asPlayer(ctx, p -> {
                    ev().claim(p);
                    return Command.SINGLE_SUCCESS;
                })))
                .then(Commands.literal("history")
                        .executes(this::history)
                        .then(Commands.literal("fame").executes(this::fame)))
                // admin
                .then(Commands.literal("start").requires(EventCommands::admin)
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(eventIds).executes(ctx -> start(ctx, null))))
                .then(Commands.literal("schedule").requires(EventCommands::admin)
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(eventIds)
                                .then(Commands.argument("time", StringArgumentType.greedyString()).executes(ctx -> start(ctx, StringArgumentType.getString(ctx, "time"))))))
                .then(simple("next", () -> ev().next(), "events.admin-done-next"))
                .then(simple("pause", () -> ev().pause(), null))
                .then(simple("resume", () -> ev().resume(), null))
                .then(simple("end", () -> {
                    if (!ev().running()) return false;
                    ev().finish(true);
                    return true;
                }, null))
                .then(simple("cancel", () -> {
                    if (!ev().running()) return false;
                    ev().finish(false);
                    return true;
                }, null))
                .then(Commands.literal("addtime").requires(EventCommands::admin)
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(-600, 600)).executes(ctx -> {
                            int m = IntegerArgumentType.getInteger(ctx, "minutes");
                            boolean ok = ev().addTime(m);
                            msg().send(ctx.getSource().getSender(), ok ? "events.admin-time-added" : "events.none", "minutes", String.valueOf(m));
                            return ok ? Command.SINGLE_SUCCESS : 0;
                        })))
                .then(Commands.literal("points").requires(EventCommands::admin)
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(inEvent)
                                .then(Commands.argument("amount", LongArgumentType.longArg()).executes(ctx -> {
                                    CommandSender s = ctx.getSource().getSender();
                                    Optional<UUID> id = plugin.economy().findByName(StringArgumentType.getString(ctx, "player"));
                                    long amount = LongArgumentType.getLong(ctx, "amount");
                                    boolean ok = id.isPresent() && ev().addPoints(id.get(), amount);
                                    if (ok) msg().send(s, "events.admin-points-done", "player", plugin.economy().name(id.get()),
                                            "score", String.valueOf(ev().state().score(id.get())));
                                    else msg().send(s, "events.not-in-other");
                                    return ok ? Command.SINGLE_SUCCESS : 0;
                                }))))
                .then(Commands.literal("remove").requires(EventCommands::admin)
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(inEvent).executes(ctx -> {
                            CommandSender s = ctx.getSource().getSender();
                            Optional<UUID> id = plugin.economy().findByName(StringArgumentType.getString(ctx, "player"));
                            boolean ok = id.isPresent() && ev().remove(id.get());
                            msg().send(s, ok ? "events.admin-removed" : "events.not-in-other", "player", StringArgumentType.getString(ctx, "player"));
                            return ok ? Command.SINGLE_SUCCESS : 0;
                        })))
                .then(Commands.literal("list").requires(EventCommands::admin).executes(ctx -> {
                    CommandSender s = ctx.getSource().getSender();
                    for (EventDef d : ev().defs().values()) {
                        msg().send(s, "events.admin-list-line", "id", d.id(), "event", d.name(), "phases", String.valueOf(d.phases().size()),
                                "auto", d.auto() ? "auto" : "manual");
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("admin").requires(EventCommands::admin).executes(ctx -> asPlayer(ctx, p -> {
                    EventMenus.admin(plugin, p);
                    return Command.SINGLE_SUCCESS;
                })));
        commands.register(root.build(), "Server events", List.of("events"));
        commands.register(Commands.literal("vote").executes(ctx -> asPlayer(ctx, p -> {
            EventMenus.vote(plugin, p);
            return Command.SINGLE_SUCCESS;
        })).build(), "Vote in the running event");
    }

    private interface Action {
        boolean run();
    }

    private LiteralArgumentBuilder<CommandSourceStack> simple(String name, Action a, String okKey) {
        return Commands.literal(name).requires(EventCommands::admin).executes(ctx -> {
            boolean ok = a.run();
            if (!ok) msg().send(ctx.getSource().getSender(), ev().running() ? "events.admin-nothing" : "events.none");
            else if (okKey != null) msg().send(ctx.getSource().getSender(), okKey);
            return ok ? Command.SINGLE_SUCCESS : 0;
        });
    }

    private int start(CommandContext<CommandSourceStack> ctx, String time) {
        CommandSender s = ctx.getSource().getSender();
        String id = StringArgumentType.getString(ctx, "id");
        EventDef d = ev().defs().get(id);
        if (d == null) {
            msg().send(s, "events.admin-unknown", "id", id);
            return 0;
        }
        long at = 0;
        if (time != null) {
            LocalTime t;
            try {
                t = LocalTime.parse(time.trim().length() == 4 ? "0" + time.trim() : time.trim());
            } catch (RuntimeException e) {
                msg().send(s, "events.admin-bad-time");
                return 0;
            }
            ZonedDateTime now = ZonedDateTime.now(ev().settings().zone());
            ZonedDateTime when = now.toLocalDate().atTime(t).atZone(ev().settings().zone());
            if (!when.isAfter(now)) when = when.plusDays(1);
            at = when.toInstant().toEpochMilli();
        }
        if (ev().running()) ev().replaceAuto(); // an admin's choice comes before the day's random one
        if (!ev().start(d, at, false)) {
            msg().send(s, "events.admin-busy", "event", ev().current().name());
            return 0;
        }
        if (time != null) msg().send(s, "events.admin-scheduled", "event", d.name(), "time", time.trim());
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        if (!ev().running()) {
            msg().send(s, "events.none");
            ev().nextAuto().ifPresent(n -> msg().send(s, "events.next-auto", "when", n));
            return Command.SINGLE_SUCCESS;
        }
        EventDef d = ev().current();
        EventState st = ev().state();
        EventDef.Phase ph = ev().phase();
        long left = st.paused() ? st.pausedRemaining : st.endsAt > 0 ? st.endsAt - System.currentTimeMillis() : -1;
        msg().send(s, "events.info-header", "event", d.name());
        msg().send(s, "events.info-phase", "phase", ph == null ? msg().raw("events.announced-phase") : ph.name(),
                "time", left >= 0 ? Durations.format(left) : "-");
        msg().send(s, "events.info-players", "count", String.valueOf(st.participants.size()));
        if (s instanceof Player p) {
            if (ev().participant(p.getUniqueId())) msg().send(p, "events.info-you", "score", String.valueOf(st.score(p.getUniqueId())));
            else if (!d.autoJoin()) p.sendMessage(msg().msg("events.join-button").append(net.kyori.adventure.text.Component.space()).append(msg().msg("events.rules-button")));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int rules(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        if (!ev().running()) {
            msg().send(s, "events.none");
            return 0;
        }
        EventDef d = ev().current();
        msg().send(s, "events.info-header", "event", d.name());
        for (String r : d.rules()) msg().send(s, "events.rule-line", "rule", r);
        if (d.rules().isEmpty()) msg().send(s, "events.no-rules");
        return Command.SINGLE_SUCCESS;
    }

    private int top(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        if (!ev().running()) {
            msg().send(s, "events.none");
            return 0;
        }
        EventDef d = ev().current();
        EventDef.Phase ph = ev().phase();
        if (d.winner() == EventDef.Winner.VOTES || (ph != null && ph.vote())) {
            msg().send(s, "events.top-secret");
            return Command.SINGLE_SUCCESS;
        }
        msg().send(s, "events.top-header", "event", d.name());
        List<Ranking.Place<UUID>> places = ev().ranking();
        if (places.isEmpty()) msg().send(s, "events.sidebar-no-scores");
        int n = 0;
        for (Ranking.Place<UUID> p : places) {
            if (n++ >= 10) break;
            msg().send(s, "events.top-line", "place", String.valueOf(p.place()), "player", plugin.economy().name(p.who()),
                    "score", String.format(Locale.ROOT, "%,d", d.winner() == EventDef.Winner.VOTES ? VoteTally.shownPoints(p.score()) : p.score()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int history(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        List<EventService.HistoryEntry> all = ev().history();
        if (all.isEmpty()) {
            msg().send(s, "events.history-empty");
            return Command.SINGLE_SUCCESS;
        }
        msg().send(s, "events.history-header");
        for (int i = all.size() - 1, shown = 0; i >= 0 && shown < 10; i--, shown++) {
            EventService.HistoryEntry h = all.get(i);
            StringBuilder winners = new StringBuilder();
            for (EventService.Result r : h.results()) {
                if (r.place() > 3) break;
                if (!winners.isEmpty()) winners.append(", ");
                winners.append(r.place()).append(". ").append(plugin.economy().name(r.who()));
            }
            String day = Instant.ofEpochMilli(h.ended()).atZone(ev().settings().zone()).format(DAY);
            msg().send(s, "events.history-line", "date", day, "event", h.name(),
                    "winners", winners.isEmpty() ? msg().raw("events.history-no-winner") : winners.toString());
        }
        return Command.SINGLE_SUCCESS;
    }

    private int fame(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        Map<UUID, Long> wins = new HashMap<>();
        for (EventService.HistoryEntry h : ev().history()) for (EventService.Result r : h.results()) if (r.place() == 1) wins.merge(r.who(), 1L, Long::sum);
        msg().send(s, "events.fame-header");
        List<Ranking.Place<UUID>> places = Ranking.rank(wins);
        if (places.isEmpty()) msg().send(s, "events.history-empty");
        int n = 0;
        for (Ranking.Place<UUID> p : places) {
            if (n++ >= 10) break;
            msg().send(s, "events.fame-line", "place", String.valueOf(p.place()), "player", plugin.economy().name(p.who()), "wins", String.valueOf(p.score()));
        }
        return Command.SINGLE_SUCCESS;
    }
}
