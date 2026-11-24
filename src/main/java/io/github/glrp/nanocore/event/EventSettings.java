package io.github.glrp.nanocore.event;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The events section of config.yml. */
public record EventSettings(
        boolean autoEnabled,
        LocalTime announceTime,
        LocalTime startTime,
        int noRepeatDays,
        ZoneId zone,
        List<Integer> reminders,
        EventDef.Prizes prizes,
        String webhookUrl,
        String webhookName) {

    public static EventSettings from(ConfigurationSection s, Consumer<String> warn) {
        if (s == null) s = new MemoryConfiguration();
        LocalTime announce = time(s, "auto.announce-time", "12:00", warn);
        LocalTime start = time(s, "auto.start-time", "19:00", warn);
        ZoneId zone;
        String tz = s.getString("timezone", "");
        try {
            zone = tz == null || tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz);
        } catch (RuntimeException e) {
            warn.accept("events.timezone '" + tz + "' isn't a known time zone; using the server's");
            zone = ZoneId.systemDefault();
        }
        List<Integer> reminders = new ArrayList<>();
        for (int m : s.getIntegerList("reminders")) if (m > 0) reminders.add(m);
        if (!s.contains("reminders")) reminders = List.of(10, 1);
        List<Long> places = s.contains("prizes.places") ? s.getLongList("prizes.places") : List.of(250L, 150L, 100L);
        EventDef.Prizes prizes = new EventDef.Prizes(List.copyOf(places), s.getLong("prizes.participation", 25),
                s.getBoolean("prizes.trophy", true), s.getString("prizes.trophy-item", "gold_ingot"), 0, 0,
                Math.max(0, s.getLong("prizes.active-actions", 20)), 0, 0);
        return new EventSettings(s.getBoolean("auto.enabled", true), announce, start, Math.max(0, s.getInt("auto.no-repeat-days", 5)),
                zone, List.copyOf(reminders), prizes, s.getString("discord.webhook-url", "").trim(), s.getString("discord.username", "Server events"));
    }

    private static LocalTime time(ConfigurationSection s, String key, String def, Consumer<String> warn) {
        try {
            return LocalTime.parse(s.getString(key, def));
        } catch (RuntimeException e) {
            warn.accept("events." + key + " must look like " + def + "; using " + def);
            return LocalTime.parse(def);
        }
    }
}
