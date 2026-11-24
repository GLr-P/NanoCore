package io.github.glrp.nanocore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.pshop.PlayerShop;
import io.github.glrp.nanocore.pshop.PlayerShopMenus;
import io.github.glrp.nanocore.pshop.PlayerShopService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/** /pshop: player shops (menus, plus quick commands for the shop you're looking at). */
public final class PlayerShopCommands {

    private final NanoCorePlugin plugin;

    public PlayerShopCommands(NanoCorePlugin plugin) {
        this.plugin = plugin;
    }

    private static boolean admin(CommandSourceStack src) {
        return src.getSender().hasPermission("nanocore.admin");
    }

    public void register(Commands commands) {
        commands.register(Usage.wrap(Commands.literal("pshop")
                .executes(ctx -> player(ctx, p -> PlayerShopMenus.main(plugin, p)))
                .then(Commands.literal("new").executes(ctx -> player(ctx, p -> PlayerShopMenus.pick(plugin, p))))
                .then(Commands.literal("browse").executes(ctx -> player(ctx, p -> PlayerShopMenus.browse(plugin, p, 0, false, null))))
                .then(Commands.literal("mine").executes(ctx -> player(ctx, p -> PlayerShopMenus.mine(plugin, p))))
                .then(Commands.literal("price").then(Commands.argument("price", LongArgumentType.longArg(0, PlayerShopService.MAX_PRICE))
                        .executes(ctx -> price(ctx, false))))
                .then(Commands.literal("buyprice").then(Commands.argument("price", LongArgumentType.longArg(0, PlayerShopService.MAX_PRICE))
                        .executes(ctx -> price(ctx, true))))
                .then(Commands.literal("delete").executes(ctx -> player(ctx, p -> {
                    PlayerShop s = looked(p);
                    if (s == null) return;
                    plugin.playerShops().delete(s, "deleted by " + p.getName());
                    plugin.messages().send(p, "pshop.removed");
                })))
                .then(Commands.literal("list").requires(PlayerShopCommands::admin).executes(this::list))
                .build(), "pshop", plugin::messages), "Make a shop from your chest, or browse all shops", List.of("playershop", "shops"));
    }

    private int player(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        if (!(ctx.getSource().getSender() instanceof Player p)) {
            plugin.messages().send(ctx.getSource().getSender(), "general.players-only");
            return 0;
        }
        action.accept(p);
        return Command.SINGLE_SUCCESS;
    }

    /** The shop the player is looking at, if it's theirs (or they're an admin). Says why not otherwise. */
    private PlayerShop looked(Player p) {
        Block b = p.getTargetBlockExact(6);
        PlayerShop s = b == null ? null : plugin.playerShops().shopFor(b);
        if (s == null) {
            plugin.messages().send(p, "pshop.look-at-shop");
            return null;
        }
        if (!s.owner().equals(p.getUniqueId()) && !p.hasPermission("nanocore.admin")) {
            plugin.messages().send(p, "pshop.protected", "player", plugin.economy().name(s.owner()));
            return null;
        }
        return s;
    }

    /** "price" sets what customers pay (or, for a shop that only buys, what you pay); "buyprice" what you pay. */
    private int price(CommandContext<CommandSourceStack> ctx, boolean buy) {
        long price = LongArgumentType.getLong(ctx, "price");
        return player(ctx, p -> {
            PlayerShop s = looked(p);
            if (s == null) return;
            boolean buySide = buy || !s.mode().ownerSells();
            if (buySide) plugin.playerShops().setBuyPrice(s, price);
            else plugin.playerShops().setSellPrice(s, price);
            plugin.messages().send(p, buySide ? "pshop.buy-price-set" : "pshop.sell-price-set", "price", plugin.economy().format(price));
        });
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        var all = plugin.playerShops().all();
        if (all.isEmpty()) {
            plugin.messages().send(s, "pshop.list-empty");
            return Command.SINGLE_SUCCESS;
        }
        for (PlayerShop shop : all) {
            plugin.messages().send(s, "pshop.list-line", "id", String.valueOf(shop.id()), "player", plugin.economy().name(shop.owner()),
                    "item", shop.itemId(), "where", shop.pos().toString(), "mode", shop.mode().name().toLowerCase(java.util.Locale.ROOT),
                    "sell", String.valueOf(shop.sellPrice()), "buy", String.valueOf(shop.buyPrice()),
                    "stock", String.valueOf(plugin.playerShops().stock(shop)));
        }
        return Command.SINGLE_SUCCESS;
    }
}
