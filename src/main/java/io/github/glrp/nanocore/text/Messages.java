package io.github.glrp.nanocore.text;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Player-facing text from messages.yml (with the built-in file as fallback for missing keys). */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final JavaPlugin plugin;
    private YamlConfiguration file;
    private YamlConfiguration defaults;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) plugin.saveResource("messages.yml", false);
        file = YamlConfiguration.loadConfiguration(f);
        var in = plugin.getResource("messages.yml");
        defaults = in == null ? new YamlConfiguration()
                : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    public String raw(String key) {
        String s = file.getString(key);
        if (s == null) s = defaults.getString(key);
        return s == null ? key : s;
    }

    /** A list of lines (like the guide's pages), falling back to the built-in file. */
    public List<String> rawList(String key) {
        List<String> l = file.getStringList(key);
        return l.isEmpty() ? defaults.getStringList(key) : l;
    }

    /** Placeholders as name/value pairs: msg("economy.paid", "player", "Sam", "amount", "5 coins"). */
    public Component msg(String key, String... pairs) {
        return MM.deserialize(raw(key), resolver(pairs));
    }

    /** Same as msg but without italics, for item names and lore. */
    public Component item(String key, String... pairs) {
        return msg(key, pairs).decoration(TextDecoration.ITALIC, false);
    }

    /** Like msg, but a value can also be a Component (for example a translated item name). */
    public Component rich(String key, Object... pairs) {
        List<TagResolver> list = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String name = String.valueOf(pairs[i]);
            list.add(pairs[i + 1] instanceof Component c ? Placeholder.component(name, c) : Placeholder.unparsed(name, String.valueOf(pairs[i + 1])));
        }
        return MM.deserialize(raw(key), TagResolver.resolver(list));
    }

    /** rich without italics, for item names and lore. */
    public Component richItem(String key, Object... pairs) {
        return rich(key, pairs).decoration(TextDecoration.ITALIC, false);
    }

    /** Item lore from one message: each "\n" in it starts a new line (lore can't wrap by itself). */
    public List<Component> lines(String key, String... pairs) {
        List<Component> out = new ArrayList<>();
        for (String line : raw(key).split("\n")) out.add(MM.deserialize(line, resolver(pairs)).decoration(TextDecoration.ITALIC, false));
        return out;
    }

    public void send(Audience to, String key, String... pairs) {
        to.sendMessage(msg(key, pairs));
    }

    public static Component parse(String miniMessage, String... pairs) {
        return MM.deserialize(miniMessage, resolver(pairs));
    }

    private static TagResolver resolver(String... pairs) {
        List<TagResolver> list = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) list.add(Placeholder.unparsed(pairs[i], pairs[i + 1]));
        return TagResolver.resolver(list);
    }
}
