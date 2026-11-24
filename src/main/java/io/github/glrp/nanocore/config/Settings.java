package io.github.glrp.nanocore.config;

import org.bukkit.configuration.file.FileConfiguration;

/** Values from config.yml, read once per (re)load. */
public record Settings(
        String currencySingular,
        String currencyPlural,
        long startingBalance,
        int netherRoofY,
        int statsSaveSeconds,
        boolean countCreative,
        String databaseFile) {

    public static Settings from(FileConfiguration c) {
        return new Settings(
                c.getString("currency.singular", "coin"),
                c.getString("currency.plural", "coins"),
                Math.max(0, c.getLong("currency.starting-balance", 100)),
                c.getInt("milestones.nether-roof-y", 127),
                Math.max(5, c.getInt("stats.save-every-seconds", 30)),
                c.getBoolean("stats.count-creative", false),
                c.getString("storage.file", "nanocore.db"));
    }
}
