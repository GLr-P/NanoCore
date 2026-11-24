package io.github.glrp.nanocore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.glrp.nanocore.text.Messages;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Makes half-typed commands helpful: instead of Minecraft's "Unknown or incomplete command", a command typed without
 * everything it needs (/pay, /nc eco give) lists the ways to finish it. Each line can be clicked to type it.
 */
public final class Usage {

    private static final int MAX_LINES = 12;

    private Usage() {
    }

    /** A copy of the command tree where every node that can't run by itself shows its usage instead. */
    public static LiteralCommandNode<CommandSourceStack> wrap(LiteralCommandNode<CommandSourceStack> root, String label, Supplier<Messages> msg) {
        return (LiteralCommandNode<CommandSourceStack>) copy(root, "/" + label, msg, true);
    }

    private static CommandNode<CommandSourceStack> copy(CommandNode<CommandSourceStack> node, String path, Supplier<Messages> msg, boolean root) {
        ArgumentBuilder<CommandSourceStack, ?> b;
        String here;
        if (node instanceof LiteralCommandNode<CommandSourceStack> l) {
            b = Commands.literal(l.getLiteral());
            here = root ? path : path + " " + l.getLiteral();
        } else if (node instanceof ArgumentCommandNode<CommandSourceStack, ?> a) {
            b = argument(a);
            here = path + " <" + a.getName() + ">";
        } else {
            throw new IllegalArgumentException("unexpected node " + node);
        }
        b.requires(node.getRequirement());
        Command<CommandSourceStack> run = node.getCommand();
        b.executes(run != null ? run : ctx -> {
            show(ctx.getSource().getSender(), ctx.getSource(), node, here, msg.get());
            return 0;
        });
        for (CommandNode<CommandSourceStack> child : node.getChildren()) b.then(copy(child, here, msg, false));
        return b.build();
    }

    private static <T> RequiredArgumentBuilder<CommandSourceStack, T> argument(ArgumentCommandNode<CommandSourceStack, T> a) {
        ArgumentType<T> type = a.getType();
        RequiredArgumentBuilder<CommandSourceStack, T> b = Commands.argument(a.getName(), type);
        if (a.getCustomSuggestions() != null) b.suggests(a.getCustomSuggestions());
        return b;
    }

    /** Every complete command below {@code node} the sender may use, like "/pay <player> <amount>". */
    static List<String> completions(CommandNode<CommandSourceStack> node, String path, CommandSourceStack source) {
        List<String> out = new ArrayList<>();
        walk(node, path, source, out, 0);
        return out;
    }

    private static void walk(CommandNode<CommandSourceStack> node, String path, CommandSourceStack source, List<String> out, int depth) {
        if (out.size() >= MAX_LINES || depth > 8) return;
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            if (!child.canUse(source)) continue;
            String next = path + " " + (child instanceof ArgumentCommandNode<?, ?> a ? "<" + a.getName() + ">" : child.getName());
            if (child.getCommand() != null) out.add(next);
            walk(child, next, source, out, depth + 1);
        }
    }

    /** What to put in the chat box when a usage line is clicked: everything before the first <...>. */
    static String fill(String usage) {
        int i = usage.indexOf(" <");
        return i < 0 ? usage : usage.substring(0, i + 1);
    }

    private static void show(CommandSender to, CommandSourceStack source, CommandNode<CommandSourceStack> node, String path, Messages m) {
        List<String> lines = completions(node, path, source);
        m.send(to, "general.usage-header", "command", path);
        for (String line : lines) {
            Component text = Component.text(line, NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.suggestCommand(fill(line)))
                    .hoverEvent(HoverEvent.showText(m.msg("general.usage-hover")));
            to.sendMessage(m.rich("general.usage-line", "usage", text));
        }
    }
}
