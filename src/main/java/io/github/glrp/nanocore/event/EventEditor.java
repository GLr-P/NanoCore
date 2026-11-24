package io.github.glrp.nanocore.event;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.gui.Confirm;
import io.github.glrp.nanocore.gui.Menu;
import io.github.glrp.nanocore.milestone.Milestone;
import io.github.glrp.nanocore.text.Messages;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The in-game event editor (/event create, /event edit, Control panel > Events > Edit): copy an event as a template,
 * then change its name, joining, schedule, winner, milestone, phase timers and prizes in menus. Changes are written
 * to the event's .yml file and loaded right away.
 */
public final class EventEditor implements Listener {

    private static final Map<UUID, String> naming = new ConcurrentHashMap<>();
    private static final String[] WINNERS = {"score", "votes", "last-standing", "time", "none"};

    private final NanoCorePlugin plugin;

    public EventEditor(NanoCorePlugin plugin) {
        this.plugin = plugin;
    }

    private static void later(NanoCorePlugin plugin, Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }

    // --- files ---

    private static File folder(NanoCorePlugin plugin) {
        return new File(plugin.getDataFolder(), "events");
    }

    /** The file an event is defined in: events/*.yml first, then events.yml. */
    static File fileFor(NanoCorePlugin plugin, String id) {
        File[] extra = folder(plugin).listFiles((d, n) -> n.endsWith(".yml"));
        if (extra != null) {
            for (File f : extra) if (YamlConfiguration.loadConfiguration(f).isConfigurationSection(id)) return f;
        }
        File main = new File(plugin.getDataFolder(), "events.yml");
        return YamlConfiguration.loadConfiguration(main).isConfigurationSection(id) ? main : null;
    }

    static Map<String, Object> toMap(ConfigurationSection s) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : s.getKeys(false)) {
            Object v = s.get(k);
            out.put(k, v instanceof ConfigurationSection c ? toMap(c) : v);
        }
        return out;
    }

    /** Copies {@code template} to a new event in events/{newId}.yml (out of the daily pool until you add it). Returns an error key. */
    public static String create(NanoCorePlugin plugin, String newId, String template) {
        String id = newId.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_]{2,32}")) return "events.editor-bad-id";
        if (plugin.events().defs().containsKey(id)) return "events.editor-exists";
        File src = fileFor(plugin, template);
        if (src == null) return "events.admin-unknown";
        ConfigurationSection t = YamlConfiguration.loadConfiguration(src).getConfigurationSection(template);
        YamlConfiguration y = new YamlConfiguration();
        y.createSection(id, toMap(t));
        y.set(id + ".name", t.getString("name", template) + " (copy)");
        y.set(id + ".auto", false);
        folder(plugin).mkdirs();
        try {
            y.save(new File(folder(plugin), id + ".yml"));
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save the new event: " + e.getMessage());
            return "events.editor-save-failed";
        }
        plugin.events().reload();
        return null;
    }

    /** Loads the event's file, lets {@code change} edit its section, saves and reloads. */
    private static void edit(NanoCorePlugin plugin, Player p, String id, Consumer<ConfigurationSection> change) {
        File f = fileFor(plugin, id);
        if (f == null) {
            plugin.messages().send(p, "events.admin-unknown", "id", id);
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        change.accept(y.getConfigurationSection(id));
        try {
            y.save(f);
        } catch (IOException e) {
            plugin.messages().send(p, "events.editor-save-failed");
            return;
        }
        plugin.events().reload();
    }

    // --- menus ---

    /** Every event: click to edit, right-click to make a copy and edit that. */
    public static void list(NanoCorePlugin plugin, Player p) {
        Messages m = plugin.messages();
        Menu menu = new Menu(6, m.msg("events.editor-list-title"));
        int slot = 0;
        for (EventDef d : plugin.events().defs().values()) {
            if (slot >= 45) break;
            Material icon = Material.matchMaterial(d.icon());
            menu.set(slot++, Menu.item(icon == null || !icon.isItem() ? Material.CLOCK : icon, m.item("events.admin-def", "event", d.name()),
                    List.of(m.item("events.editor-id", "id", d.id()), m.item(d.auto() ? "events.admin-in-pool" : "events.admin-not-in-pool"),
                            m.item("events.editor-list-click"))), e -> later(plugin, () -> {
                if (e.isRightClick()) {
                    String copy = freeId(plugin, d.id() + "_copy");
                    String err = create(plugin, copy, d.id());
                    if (err != null) m.send(p, err);
                    else open(plugin, p, copy);
                } else {
                    open(plugin, p, d.id());
                }
            }));
        }
        menu.set(49, Menu.item(Material.BOOK, m.item("events.editor-help-title"), List.of(m.item("events.editor-help"), m.item("events.editor-help-2"))));
        menu.set(45, Menu.item(Material.ARROW, m.item("panel.back"), List.of()), e -> later(plugin, () -> io.github.glrp.nanocore.admin.ControlPanel.open(plugin, p)));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private static String freeId(NanoCorePlugin plugin, String base) {
        String id = base;
        for (int i = 2; plugin.events().defs().containsKey(id); i++) id = base + i;
        return id;
    }

    public static void open(NanoCorePlugin plugin, Player p, String id) {
        Messages m = plugin.messages();
        File f = fileFor(plugin, id);
        if (f == null) {
            m.send(p, "events.admin-unknown", "id", id);
            return;
        }
        ConfigurationSection s = YamlConfiguration.loadConfiguration(f).getConfigurationSection(id);
        Menu menu = new Menu(6, m.msg("events.editor-title", "event", s.getString("name", id)));
        menu.set(4, Menu.item(Material.WRITABLE_BOOK, m.item("events.admin-def", "event", s.getString("name", id)),
                List.of(m.item("events.editor-id", "id", id), m.item("events.editor-file", "file", f.getName()))));

        menu.set(10, Menu.item(Material.NAME_TAG, m.item("events.editor-name", "name", s.getString("name", id)), List.of(m.item("events.editor-name-lore"))),
                e -> later(plugin, () -> {
                    naming.put(p.getUniqueId(), id);
                    p.closeInventory();
                    m.send(p, "events.editor-name-prompt");
                }));
        boolean auto = "auto".equalsIgnoreCase(s.getString("join", "opt-in"));
        menu.set(11, Menu.item(auto ? Material.LIME_DYE : Material.GRAY_DYE, m.item(auto ? "events.editor-join-auto" : "events.editor-join-opt"),
                List.of(m.item("panel.click-toggle"))), e -> later(plugin, () -> {
            edit(plugin, p, id, sec -> sec.set("join", auto ? "opt-in" : "auto"));
            open(plugin, p, id);
        }));
        boolean pool = s.getBoolean("auto", true);
        menu.set(12, Menu.item(pool ? Material.CLOCK : Material.BARRIER, m.item(pool ? "events.admin-in-pool" : "events.admin-not-in-pool"),
                List.of(m.item("panel.click-toggle"))), e -> later(plugin, () -> {
            edit(plugin, p, id, sec -> sec.set("auto", !pool));
            open(plugin, p, id);
        }));
        number(plugin, menu, 13, p, id, Material.HOPPER, "events.editor-weight", s.getInt("weight", 1), 1, 1, 100, "weight");
        number(plugin, menu, 14, p, id, Material.PLAYER_HEAD, "events.editor-min-players", s.getInt("min-players", 2), 1, 0, 100, "min-players");
        String winner = s.getString("winner", "score").toLowerCase(Locale.ROOT);
        menu.set(15, Menu.item(Material.GOLD_BLOCK, m.item("events.editor-winner", "winner", winner), List.of(m.item("events.editor-cycle"))),
                e -> later(plugin, () -> {
                    int i = java.util.Arrays.asList(WINNERS).indexOf(winner);
                    edit(plugin, p, id, sec -> sec.set("winner", WINNERS[(i + 1) % WINNERS.length]));
                    open(plugin, p, id);
                }));
        String unlocks = s.getString("unlocks", "");
        menu.set(16, Menu.item(Material.BEACON, m.item("events.editor-unlocks", "milestone", unlocks.isEmpty() ? m.raw("panel.none") : unlocks),
                List.of(m.item("events.editor-cycle"))), e -> later(plugin, () -> {
            List<String> options = new ArrayList<>();
            options.add("");
            for (Milestone mi : Milestone.values()) options.add(mi.id());
            int i = options.indexOf(unlocks);
            String next = options.get((i + 1) % options.size());
            edit(plugin, p, id, sec -> sec.set("unlocks", next.isEmpty() ? null : next));
            open(plugin, p, id);
        }));

        List<Map<?, ?>> phases = s.getMapList("phases");
        for (int i = 0; i < phases.size() && i < 7; i++) {
            int index = i;
            Map<?, ?> ph = phases.get(i);
            Object end = ph.get("end");
            String timer = end instanceof Map<?, ?> em && em.get("timer") != null ? String.valueOf(em.get("timer")) : "-";
            menu.set(19 + i, Menu.item(Material.CLOCK, m.item("events.editor-phase", "n", String.valueOf(i + 1), "phase", String.valueOf(ph.get("name") == null ? "" : ph.get("name"))),
                    List.of(m.item("events.editor-timer", "time", timer), m.item("events.editor-timer-lore"))), e -> later(plugin, () -> {
                long step = (e.isShiftClick() ? 1 : 5) * (e.isRightClick() ? -1 : 1);
                edit(plugin, p, id, sec -> sec.set("phases", changeTimer(sec.getMapList("phases"), index, step)));
                open(plugin, p, id);
            }));
        }

        List<Long> places = s.contains("prizes.places") ? s.getLongList("prizes.places") : new ArrayList<>(plugin.events().settings().prizes().places());
        for (int i = 0; i < 3; i++) {
            int place = i;
            long v = i < places.size() ? places.get(i) : 0;
            menu.set(28 + i, Menu.item(Material.GOLD_INGOT, m.item("events.editor-place", "place", String.valueOf(i + 1), "coins", String.valueOf(v)),
                    List.of(m.item("events.editor-plus-minus", "step", "50"))), e -> later(plugin, () -> {
                edit(plugin, p, id, sec -> {
                    List<Long> pl = sec.contains("prizes.places") ? new ArrayList<>(sec.getLongList("prizes.places"))
                            : new ArrayList<>(plugin.events().settings().prizes().places());
                    while (pl.size() <= place) pl.add(0L);
                    pl.set(place, Math.max(0, pl.get(place) + (e.isRightClick() ? -50 : 50)));
                    sec.set("prizes.places", pl);
                });
                open(plugin, p, id);
            }));
        }
        long part = s.getLong("prizes.participation", plugin.events().settings().prizes().participation());
        menu.set(31, Menu.item(Material.GOLD_NUGGET, m.item("events.editor-participation", "coins", String.valueOf(part)),
                List.of(m.item("events.editor-plus-minus", "step", "5"))), e -> later(plugin, () -> {
            edit(plugin, p, id, sec -> sec.set("prizes.participation", Math.max(0, part + (e.isRightClick() ? -5 : 5))));
            open(plugin, p, id);
        }));
        boolean trophy = s.getBoolean("prizes.trophy", plugin.events().settings().prizes().trophy());
        menu.set(32, Menu.item(trophy ? Material.GOLDEN_HELMET : Material.LEATHER_HELMET, m.item(trophy ? "events.editor-trophy-on" : "events.editor-trophy-off"),
                List.of(m.item("panel.click-toggle"))), e -> later(plugin, () -> {
            edit(plugin, p, id, sec -> sec.set("prizes.trophy", !trophy));
            open(plugin, p, id);
        }));

        menu.set(45, Menu.item(Material.ARROW, m.item("panel.back"), List.of()), e -> later(plugin, () -> list(plugin, p)));
        menu.set(47, Menu.item(Material.LIME_CONCRETE, m.item("events.editor-start"), List.of(m.item("events.editor-start-lore"))), e -> later(plugin, () -> {
            EventDef d = plugin.events().defs().get(id);
            if (d == null || !plugin.events().start(d, 0, false)) m.send(p, "events.already-running");
            p.closeInventory();
        }));
        menu.set(49, Menu.item(Material.BOOK, m.item("events.editor-help-title"), List.of(m.item("events.editor-file-help", "file", f.getName()))));
        if (f.getParentFile().getName().equals("events")) {
            menu.set(53, Menu.item(Material.BARRIER, m.item("events.editor-delete"), List.of()), e -> later(plugin, () ->
                    Confirm.open(plugin, p, m.item("events.editor-delete-confirm", "event", s.getString("name", id)), List.of(), () -> {
                        File file = fileFor(plugin, id);
                        if (file != null && file.getParentFile().getName().equals("events")) {
                            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
                            y.set(id, null);
                            try {
                                if (y.getKeys(false).isEmpty()) java.nio.file.Files.deleteIfExists(file.toPath());
                                else y.save(file);
                            } catch (IOException ex) {
                                m.send(p, "events.editor-save-failed");
                            }
                            plugin.events().reload();
                        }
                        list(plugin, p);
                    }, () -> open(plugin, p, id))));
        }
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private static void number(NanoCorePlugin plugin, Menu menu, int slot, Player p, String id, Material icon, String key, int value, int step, int min, int max,
                               String path) {
        menu.set(slot, Menu.item(icon, plugin.messages().item(key, "value", String.valueOf(value)),
                List.of(plugin.messages().item("events.editor-plus-minus", "step", String.valueOf(step)))), e -> later(plugin, () -> {
            int v = Math.max(min, Math.min(max, value + (e.isRightClick() ? -step : step)));
            edit(plugin, p, id, sec -> sec.set(path, v));
            open(plugin, p, id);
        }));
    }

    /** Adds {@code minutes} to phase {@code index}'s timer (a phase without one gets one; it never goes below 1 minute). */
    static List<Map<String, Object>> changeTimer(List<Map<?, ?>> phases, int index, long minutes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < phases.size(); i++) {
            Map<String, Object> ph = new LinkedHashMap<>();
            phases.get(i).forEach((k, v) -> ph.put(String.valueOf(k), v));
            if (i == index) {
                Map<String, Object> end = new LinkedHashMap<>();
                if (ph.get("end") instanceof Map<?, ?> em) em.forEach((k, v) -> end.put(String.valueOf(k), v));
                long ms = 0;
                if (end.get("timer") != null) {
                    try {
                        ms = Durations.parse(String.valueOf(end.get("timer")));
                    } catch (IllegalArgumentException ignored) {
                        // a bad timer starts again from 0
                    }
                }
                long mins = Math.max(1, ms / 60_000 + minutes);
                end.put("timer", mins >= 60 && mins % 60 == 0 ? (mins / 60) + "h" : mins + "m");
                ph.put("end", end);
            }
            out.add(ph);
        }
        return out;
    }

    // --- naming in chat ---

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        String id = naming.remove(e.getPlayer().getUniqueId());
        if (id == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        later(plugin, () -> {
            if (!text.equalsIgnoreCase("cancel") && !text.isEmpty()) {
                String name = text.length() > 40 ? text.substring(0, 40) : text;
                edit(plugin, p, id, sec -> sec.set("name", name));
                plugin.messages().send(p, "events.editor-named", "name", name);
            }
            open(plugin, p, id);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        naming.remove(e.getPlayer().getUniqueId());
    }
}
