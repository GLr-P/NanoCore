package io.github.glrp.nanocore;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class NanoCorePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        registerCommands();
        getComponentLogger().info("SQLite driver bundled with the server: {}", sqliteAvailable());
        getComponentLogger().info("NanoCore {} ready", getPluginMeta().getVersion());
    }

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
                Commands.literal("nanocore")
                        .requires(source -> source.getSender().hasPermission("nanocore.admin"))
                        .then(Commands.literal("version").executes(ctx -> {
                            ctx.getSource().getSender().sendMessage(Component.text(
                                    "NanoCore " + getPluginMeta().getVersion() + " on " + getServer().getMinecraftVersion(),
                                    NamedTextColor.GREEN));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .build(),
                "NanoCore admin commands",
                List.of("nc")));
    }

    private static boolean sqliteAvailable() {
        try {
            Class.forName("org.sqlite.JDBC");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
