package io.github.glrp.nanocore.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.util.List;
import java.util.UUID;

/**
 * Registers NanoCore as the Vault economy so other plugins see the same balances.
 * Only loaded when VaultUnlocked (or Vault) is installed. Amounts are whole coins.
 */
public final class VaultHook implements Economy {

    private final Plugin plugin;
    private final EconomyService eco;

    private VaultHook(Plugin plugin, EconomyService eco) {
        this.plugin = plugin;
        this.eco = eco;
    }

    public static void register(Plugin plugin, EconomyService eco) {
        Bukkit.getServicesManager().register(Economy.class, new VaultHook(plugin, eco), plugin, ServicePriority.Highest);
        plugin.getLogger().info("Registered as the Vault economy provider");
    }

    private static long whole(double amount) {
        return (long) Math.floor(amount);
    }

    private UUID id(OfflinePlayer p) {
        if (!eco.hasAccount(p.getUniqueId())) eco.ensureAccount(p.getUniqueId(), p.getName() == null ? p.getUniqueId().toString() : p.getName());
        return p.getUniqueId();
    }

    @SuppressWarnings("deprecation")
    private UUID idByName(String name) {
        return eco.findByName(name).orElseGet(() -> id(Bukkit.getOfflinePlayer(name)));
    }

    private EconomyResponse response(Accounts.Result r, double amount, UUID id) {
        return r == Accounts.Result.OK
                ? new EconomyResponse(amount, eco.balance(id), ResponseType.SUCCESS, null)
                : new EconomyResponse(0, eco.balance(id), ResponseType.FAILURE, r.name());
    }

    private EconomyResponse withdraw(UUID id, double amount) {
        return response(eco.withdraw(id, whole(amount), "plugin: withdraw"), whole(amount), id);
    }

    private EconomyResponse deposit(UUID id, double amount) {
        return response(eco.deposit(id, whole(amount), "plugin: deposit"), whole(amount), id);
    }

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "NanoCore has no banks");
    }

    @Override public boolean isEnabled() { return plugin.isEnabled(); }
    @Override public String getName() { return "NanoCore"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 0; }
    @Override public String format(double amount) { return eco.format(whole(amount)); }
    @Override public String currencyNamePlural() { return eco.plural(); }
    @Override public String currencyNameSingular() { return eco.singular(); }

    @Override public boolean hasAccount(String playerName) { return eco.findByName(playerName).isPresent(); }
    @Override public boolean hasAccount(OfflinePlayer player) { return eco.hasAccount(player.getUniqueId()); }
    @Override public boolean hasAccount(String playerName, String worldName) { return hasAccount(playerName); }
    @Override public boolean hasAccount(OfflinePlayer player, String worldName) { return hasAccount(player); }

    @Override public double getBalance(String playerName) { return eco.balance(idByName(playerName)); }
    @Override public double getBalance(OfflinePlayer player) { return eco.balance(player.getUniqueId()); }
    @Override public double getBalance(String playerName, String world) { return getBalance(playerName); }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }

    @Override public boolean has(String playerName, double amount) { return getBalance(playerName) >= amount; }
    @Override public boolean has(OfflinePlayer player, double amount) { return getBalance(player) >= amount; }
    @Override public boolean has(String playerName, String worldName, double amount) { return has(playerName, amount); }
    @Override public boolean has(OfflinePlayer player, String worldName, double amount) { return has(player, amount); }

    @Override public EconomyResponse withdrawPlayer(String playerName, double amount) { return withdraw(idByName(playerName), amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) { return withdraw(id(player), amount); }
    @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) { return withdrawPlayer(playerName, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) { return withdrawPlayer(player, amount); }

    @Override public EconomyResponse depositPlayer(String playerName, double amount) { return deposit(idByName(playerName), amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) { return deposit(id(player), amount); }
    @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) { return depositPlayer(playerName, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) { return depositPlayer(player, amount); }

    @Override public EconomyResponse createBank(String name, String player) { return noBanks(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse deleteBank(String name) { return noBanks(); }
    @Override public EconomyResponse bankBalance(String name) { return noBanks(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBanks(); }
    @Override public List<String> getBanks() { return List.of(); }

    @Override public boolean createPlayerAccount(String playerName) { idByName(playerName); return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player) { id(player); return true; }
    @Override public boolean createPlayerAccount(String playerName, String worldName) { return createPlayerAccount(playerName); }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) { return createPlayerAccount(player); }
}
