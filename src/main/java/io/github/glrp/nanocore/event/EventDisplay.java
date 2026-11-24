package io.github.glrp.nanocore.event;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The event sidebar (one scoreboard per player, so each sees their own score; no numbers on the right edge)
 * and a boss bar. Players go back to the main scoreboard when the event ends.
 */
public final class EventDisplay {

    private static final int MAX_LINES = 15;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private BossBar bar;

    /** Shows the sidebar to one player. Lines past 15 are dropped. */
    public void sidebar(Player p, Component title, List<Component> lines) {
        Scoreboard sb = boards.get(p.getUniqueId());
        Scoreboard current = p.getScoreboard();
        if (sb == null) {
            // Don't take over a scoreboard another plugin gave this player.
            if (current != Bukkit.getScoreboardManager().getMainScoreboard()) return;
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective o = sb.registerNewObjective("nc_event", Criteria.DUMMY, title);
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            o.numberFormat(NumberFormat.blank());
            boards.put(p.getUniqueId(), sb);
        }
        if (current != sb) p.setScoreboard(sb);
        Objective o = sb.getObjective("nc_event");
        if (o == null) return;
        o.displayName(title);
        int n = Math.min(MAX_LINES, lines.size());
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = entry(i);
            if (i < n) {
                Score s = o.getScore(entry);
                s.setScore(MAX_LINES - i);
                s.customName(lines.get(i));
            } else {
                sb.resetScores(entry);
            }
        }
    }

    /** A unique, invisible entry name per line. */
    private static String entry(int i) {
        return "§" + Integer.toHexString(i) + "§r";
    }

    public void bossBar(Iterable<? extends Player> viewers, Component name, float progress, BossBar.Color color) {
        float p = Math.max(0f, Math.min(1f, progress));
        if (bar == null) bar = BossBar.bossBar(name, p, color, BossBar.Overlay.PROGRESS);
        bar.name(name);
        bar.progress(p);
        bar.color(color);
        for (Player v : viewers) v.showBossBar(bar);
    }

    public void forget(Player p) {
        Scoreboard sb = boards.remove(p.getUniqueId());
        if (sb != null && p.getScoreboard() == sb) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        if (bar != null) p.hideBossBar(bar);
    }

    /** Takes everything off everyone's screen. */
    public void clear() {
        for (Player p : Bukkit.getOnlinePlayers()) forget(p);
        boards.clear();
        bar = null;
    }
}
