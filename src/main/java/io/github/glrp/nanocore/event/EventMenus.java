package io.github.glrp.nanocore.event;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.gui.Menu;
import io.github.glrp.nanocore.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** The /vote window and the /event admin panel. Plain chest menus, so they work on Bedrock too. */
public final class EventMenus {

    private EventMenus() {
    }

    private static void later(NanoCorePlugin plugin, Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }

    static ItemStack head(UUID id, Component name, List<Component> lore) {
        ItemStack it = Menu.item(Material.PLAYER_HEAD, name, lore);
        it.editMeta(SkullMeta.class, m -> m.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(id)));
        return it;
    }

    // --- voting ---

    /** Everyone in the event except you; click to add to your top 3 (click again to take back). */
    public static void vote(NanoCorePlugin plugin, Player p) {
        EventService ev = plugin.events();
        Messages msg = plugin.messages();
        EventDef.Phase ph = ev.phase();
        if (ph == null || !ph.vote()) {
            msg.send(p, "events.vote-not-now");
            return;
        }
        UUID me = p.getUniqueId();
        List<UUID> ballot = new ArrayList<>(ev.ballot(me));
        Menu menu = new Menu(6, msg.msg("events.vote-title"));
        int slot = 0;
        for (UUID id : ev.state().participants) {
            if (id.equals(me) || slot >= 45) continue;
            int rank = ballot.indexOf(id);
            List<Component> lore = new ArrayList<>();
            lore.add(rank >= 0 ? msg.item("events.vote-your-pick", "place", msg.raw("events.places." + (rank + 1)).replace("<n>", String.valueOf(rank + 1)))
                    : msg.item("events.vote-click"));
            ItemStack h = head(id, msg.item("events.vote-entry", "player", plugin.economy().name(id)), lore);
            if (rank >= 0) h.editMeta(m -> m.setEnchantmentGlintOverride(true));
            menu.set(slot++, h, e -> later(plugin, () -> {
                List<UUID> b = new ArrayList<>(ev.ballot(me));
                if (!b.remove(id)) {
                    if (b.size() >= VoteTally.WEIGHTS.length) {
                        msg.send(p, "events.vote-full");
                    } else {
                        b.add(id);
                        msg.send(p, "events.voted", "player", plugin.economy().name(id),
                                "place", msg.raw("events.places." + b.size()).replace("<n>", String.valueOf(b.size())));
                    }
                }
                ev.setBallot(me, b);
                vote(plugin, p);
            }));
        }
        if (slot == 0) menu.set(22, Menu.item(Material.GRAY_DYE, msg.item("events.vote-nobody"), List.of()));
        menu.set(49, Menu.item(Material.BOOK, msg.item("events.vote-help-title"), List.of(msg.item("events.vote-help"))));
        menu.set(53, Menu.item(Material.BARRIER, msg.item("events.vote-clear"), List.of()), e -> later(plugin, () -> {
            ev.setBallot(me, List.of());
            vote(plugin, p);
        }));
        menu.frame().open(p);
    }

    // --- admin panel ---

    public static void admin(NanoCorePlugin plugin, Player p) {
        EventService ev = plugin.events();
        Messages msg = plugin.messages();
        if (!ev.running()) {
            Menu menu = new Menu(6, msg.msg("events.admin-start-title"));
            int slot = 0;
            for (EventDef d : ev.defs().values()) {
                if (slot >= 54) break;
                Material icon = Material.matchMaterial(d.icon().toLowerCase(Locale.ROOT));
                List<Component> lore = new ArrayList<>();
                for (String r : d.rules()) lore.add(msg.item("events.rule-line", "rule", r));
                lore.add(msg.item(d.auto() ? "events.admin-in-pool" : "events.admin-not-in-pool"));
                lore.add(msg.item("events.admin-click-start"));
                menu.set(slot++, Menu.item(icon == null || !icon.isItem() ? Material.CLOCK : icon, msg.item("events.admin-def", "event", d.name()), lore),
                        e -> later(plugin, () -> {
                            ev.start(d, 0, false);
                            admin(plugin, p);
                        }));
            }
            menu.frame().open(p);
            return;
        }
        EventDef d = ev.current();
        EventState s = ev.state();
        EventDef.Phase ph = ev.phase();
        Menu menu = new Menu(6, msg.msg("events.admin-title", "event", d.name()));
        List<Component> info = new ArrayList<>();
        info.add(msg.item("events.admin-phase", "phase", ph == null ? msg.raw("events.announced-phase") : ph.name()));
        long left = s.paused() ? s.pausedRemaining : s.endsAt > 0 ? s.endsAt - System.currentTimeMillis() : -1;
        info.add(msg.item(s.paused() ? "events.admin-paused" : "events.admin-time", "time", left >= 0 ? Durations.format(left) : "-"));
        info.add(msg.item("events.admin-players", "count", String.valueOf(s.participants.size())));
        menu.set(4, Menu.item(Material.CLOCK, msg.item("events.admin-def", "event", d.name()), info));
        menu.set(10, Menu.item(Material.LIME_CONCRETE, msg.item("events.admin-next"), List.of()), e -> later(plugin, () -> {
            ev.next();
            admin(plugin, p);
        }));
        menu.set(11, Menu.item(Material.CLOCK, msg.item("events.admin-add5"), List.of()), e -> later(plugin, () -> {
            ev.addTime(5);
            admin(plugin, p);
        }));
        menu.set(12, Menu.item(s.paused() ? Material.LIME_DYE : Material.GRAY_DYE, msg.item(s.paused() ? "events.admin-resume" : "events.admin-pause"), List.of()),
                e -> later(plugin, () -> {
                    if (!ev.resume()) ev.pause();
                    admin(plugin, p);
                }));
        menu.set(14, Menu.item(Material.GOLD_INGOT, msg.item("events.admin-end"), List.of(msg.item("events.admin-end-lore"))), e -> later(plugin, () -> {
            ev.finish(true);
            p.closeInventory();
        }));
        menu.set(16, Menu.item(Material.BARRIER, msg.item("events.admin-cancel"), List.of(msg.item("events.admin-cancel-lore"))), e -> later(plugin, () -> {
            ev.finish(false);
            p.closeInventory();
        }));
        int slot = 27;
        for (UUID id : s.participants) {
            if (slot >= 54) break;
            List<Component> lore = List.of(msg.item("events.admin-score", "score", String.format(Locale.ROOT, "%,d", s.score(id))), msg.item("events.admin-player-click"));
            menu.set(slot++, head(id, msg.item("events.vote-entry", "player", plugin.economy().name(id)), lore), e -> later(plugin, () -> player(plugin, p, id)));
        }
        menu.frame().open(p);
    }

    private static void player(NanoCorePlugin plugin, Player admin, UUID id) {
        EventService ev = plugin.events();
        Messages msg = plugin.messages();
        if (!ev.participant(id)) {
            admin(plugin, admin);
            return;
        }
        Menu menu = new Menu(3, msg.msg("events.admin-player-title", "player", plugin.economy().name(id)));
        menu.set(4, head(id, msg.item("events.vote-entry", "player", plugin.economy().name(id)),
                List.of(msg.item("events.admin-score", "score", String.format(Locale.ROOT, "%,d", ev.state().score(id))))));
        long[] amounts = {1, 5, -1, -5};
        int[] slots = {10, 11, 15, 16};
        for (int i = 0; i < amounts.length; i++) {
            long a = amounts[i];
            menu.set(slots[i], Menu.item(a > 0 ? Material.LIME_DYE : Material.RED_DYE, msg.item("events.admin-points", "amount", (a > 0 ? "+" : "") + a), List.of()),
                    e -> later(plugin, () -> {
                        ev.addPoints(id, a);
                        player(plugin, admin, id);
                    }));
        }
        menu.set(13, Menu.item(Material.BARRIER, msg.item("events.admin-remove"), List.of()), e -> later(plugin, () -> {
            ev.remove(id);
            admin(plugin, admin);
        }));
        menu.set(22, Menu.item(Material.ARROW, msg.item("events.admin-back"), List.of()), e -> later(plugin, () -> admin(plugin, admin)));
        menu.frame().open(admin);
    }
}
