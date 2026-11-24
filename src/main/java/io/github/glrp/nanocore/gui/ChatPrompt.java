package io.github.glrp.nanocore.gui;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Asks a player to type something in chat (a name, a time zone, a webhook link): the window closes, the question is
 * shown, and the next chat line is the answer instead of a chat message. "cancel" goes back.
 */
public final class ChatPrompt implements Listener {

    private record Ask(Component question, Predicate<String> answer, Runnable back) {
    }

    private static final Map<UUID, Ask> waiting = new ConcurrentHashMap<>();

    private final NanoCorePlugin plugin;

    public ChatPrompt(NanoCorePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param answer runs on the main thread with the typed text; returns false to ask again (after saying why)
     * @param back   runs when the player types "cancel"
     */
    public static void ask(NanoCorePlugin plugin, Player p, Component question, Predicate<String> answer, Runnable back) {
        waiting.put(p.getUniqueId(), new Ask(question, answer, back));
        p.closeInventory();
        p.sendMessage(question);
        plugin.messages().send(p, "general.prompt-hint");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Ask ask = waiting.remove(e.getPlayer().getUniqueId());
        if (ask == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("cancel")) {
                ask.back().run();
                return;
            }
            if (!ask.answer().test(text)) {
                waiting.put(p.getUniqueId(), ask);
                plugin.messages().send(p, "general.prompt-hint");
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        waiting.remove(e.getPlayer().getUniqueId());
    }
}
