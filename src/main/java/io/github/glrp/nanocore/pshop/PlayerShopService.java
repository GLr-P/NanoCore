package io.github.glrp.nanocore.pshop;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.db.Database;
import io.github.glrp.nanocore.economy.Accounts;
import io.github.glrp.nanocore.economy.EconomyService;
import io.github.glrp.nanocore.milestone.Milestone;
import io.github.glrp.nanocore.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chest shops: players turn a chest or barrel into a shop with /pshop. Customers right-click it to buy or sell,
 * which works while the owner is offline. Shops float their item and a label above the chest, and only the owner
 * (or an admin) can open or break them.
 */
public final class PlayerShopService implements Listener {

    public static final long MAX_PRICE = 1_000_000;
    private static final String ADMIN = "nanocore.admin";

    private final NanoCorePlugin plugin;
    private final Database db;
    private final Map<Long, PlayerShop> shops = new LinkedHashMap<>();
    private final Map<PlayerShop.Pos, PlayerShop> byPos = new HashMap<>();
    private final Map<UUID, Long> picking = new HashMap<>();
    private final Map<Long, UUID[]> displays = new HashMap<>();
    private long nextId = 1;
    private int limit = 10;
    private double taxPercent = 2;
    private int pickSeconds = 30;
    private float spin;

    public PlayerShopService(NanoCorePlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, owner, world, x, y, z, item, mode, sell_price, buy_price, created FROM pshops ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    nextId = Math.max(nextId, id + 1);
                    try {
                        PlayerShop s = new PlayerShop(id, UUID.fromString(rs.getString(2)),
                                new PlayerShop.Pos(rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6)),
                                ItemStack.deserializeBytes(rs.getBytes(7)), ShopTrade.Mode.parse(rs.getString(8)),
                                rs.getLong(9), rs.getLong(10), rs.getLong(11));
                        shops.put(id, s);
                        byPos.put(s.pos, s);
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("Player shop #" + id + " couldn't be read: " + e);
                    }
                }
            }
            nextId = Math.max(nextId, Database.queryLong(c, "SELECT COALESCE(MAX(shop), 0) + 1 FROM pshop_sales", 1));
            return null;
        });
    }

    public void reload(ConfigurationSection c) {
        if (c == null) return;
        limit = Math.max(0, c.getInt("limit", 10));
        taxPercent = Math.max(0, Math.min(100, c.getDouble("tax-percent", 2)));
        pickSeconds = Math.max(5, c.getInt("pick-seconds", 30));
    }

    private Messages msg() {
        return plugin.messages();
    }

    private EconomyService eco() {
        return plugin.economy();
    }

    public int limit() {
        return limit;
    }

    public double taxPercent() {
        return taxPercent;
    }

    // --- lookups ---

    public List<PlayerShop> all() {
        return List.copyOf(shops.values());
    }

    public PlayerShop get(long id) {
        return shops.get(id);
    }

    public List<PlayerShop> owned(UUID owner) {
        List<PlayerShop> list = new ArrayList<>();
        for (PlayerShop s : shops.values()) if (s.owner.equals(owner)) list.add(s);
        return list;
    }

    /** Shops sorted for browsing: by item name, then cheapest first. */
    public List<PlayerShop> browseOrder() {
        List<PlayerShop> list = new ArrayList<>(shops.values());
        list.sort(Comparator.comparing(PlayerShop::itemId)
                .thenComparingLong(s -> s.mode.ownerSells() ? s.sellPrice : Long.MAX_VALUE)
                .thenComparingLong(PlayerShop::id));
        return list;
    }

    /** Chests, trapped chests and barrels can be shops (copper chests can't: copper golems empty them). */
    public static boolean shopType(Block b) {
        Material m = b.getType();
        return m == Material.CHEST || m == Material.TRAPPED_CHEST || m == Material.BARREL;
    }

    /** The shop on this block, or on the other half of its double chest. */
    public PlayerShop shopFor(Block b) {
        if (shops.isEmpty()) return null;
        PlayerShop s = byPos.get(PlayerShop.Pos.of(b));
        if (s != null) return s;
        if (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST) return null;
        return b.getState(false) instanceof Chest c ? shopFor(c.getInventory()) : null;
    }

    public PlayerShop shopFor(Inventory inv) {
        if (shops.isEmpty() || inv == null) return null;
        InventoryHolder h = inv.getHolder(false);
        if (h instanceof DoubleChest dc) {
            PlayerShop s = at(dc.getLeftSide());
            return s != null ? s : at(dc.getRightSide());
        }
        return at(h);
    }

    private PlayerShop at(InventoryHolder h) {
        return h instanceof BlockState bs ? byPos.get(PlayerShop.Pos.of(bs.getBlock())) : null;
    }

    private Block block(PlayerShop s) {
        World w = plugin.getServer().getWorld(s.pos.world());
        return w == null ? null : w.getBlockAt(s.pos.x(), s.pos.y(), s.pos.z());
    }

    private boolean loaded(PlayerShop s) {
        World w = plugin.getServer().getWorld(s.pos.world());
        return w != null && w.isChunkLoaded(s.pos.x() >> 4, s.pos.z() >> 4);
    }

    /** The shop's chest inventory (both halves of a double chest), or null if the chest is gone or unloaded. */
    public Inventory inventory(PlayerShop s) {
        if (!loaded(s)) return null;
        Block b = block(s);
        if (b == null || !shopType(b)) return null;
        return b.getState(false) instanceof Container c ? c.getInventory() : null;
    }

    public Location location(PlayerShop s) {
        World w = plugin.getServer().getWorld(s.pos.world());
        return w == null ? null : s.pos.center(w);
    }

    public int stock(PlayerShop s) {
        Inventory inv = inventory(s);
        return inv == null ? 0 : Stacks.count(inv.getContents(), s.item);
    }

    public int space(PlayerShop s) {
        Inventory inv = inventory(s);
        return inv == null ? 0 : Stacks.room(inv.getContents(), s.item);
    }

    // --- creating, editing, removing ---

    /** "New shop": the next chest or barrel the player right-clicks becomes a shop. Returns an error key or null. */
    public String startPicking(Player p) {
        String err = canCreate(p);
        if (err != null) return err;
        picking.put(p.getUniqueId(), System.currentTimeMillis() + pickSeconds * 1000L);
        return null;
    }

    public int pickSeconds() {
        return pickSeconds;
    }

    private String canCreate(Player p) {
        if (!plugin.milestones().isUnlocked(Milestone.PLAYER_SHOPS)) return "milestones.blocked.player_shops";
        if (!p.hasPermission(ADMIN) && owned(p.getUniqueId()).size() >= limit) return "pshop.limit";
        return null;
    }

    /** Turns the block into a shop selling the item in the player's hand (or the first item in the chest). */
    public PlayerShop create(Player p, Block b) {
        String err = canCreate(p);
        if (err == null && !shopType(b)) err = "pshop.not-container";
        if (err == null && shopFor(b) != null) err = "pshop.already-shop";
        ItemStack item = p.getInventory().getItemInMainHand();
        if (err == null && item.isEmpty() && b.getState(false) instanceof Container c) {
            for (ItemStack s : c.getInventory().getContents()) {
                if (s != null && !s.isEmpty()) {
                    item = s;
                    break;
                }
            }
        }
        if (err == null && item.isEmpty()) err = "pshop.need-item";
        if (err != null) {
            msg().send(p, err, "limit", String.valueOf(limit));
            return null;
        }
        PlayerShop s = new PlayerShop(nextId++, p.getUniqueId(), PlayerShop.Pos.of(b), item, ShopTrade.Mode.SELL, 1, 1, System.currentTimeMillis());
        shops.put(s.id, s);
        byPos.put(s.pos, s);
        byte[] data = s.item.serializeAsBytes();
        db.run(c -> Database.update(c,
                "INSERT INTO pshops (id, owner, world, x, y, z, item, mode, sell_price, buy_price, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                s.id, s.owner.toString(), s.pos.world(), s.pos.x(), s.pos.y(), s.pos.z(), data, s.mode.name(), s.sellPrice, s.buyPrice, s.created));
        plugin.getLogger().info(p.getName() + " made player shop #" + s.id + " (" + s.itemId() + ") at " + s.pos);
        msg().send(p, "pshop.created");
        refreshDisplay(s);
        return s;
    }

    public void setMode(PlayerShop s, ShopTrade.Mode mode) {
        s.mode = mode;
        save(s);
    }

    public void setSellPrice(PlayerShop s, long price) {
        s.sellPrice = ShopTrade.clampPrice(price, MAX_PRICE);
        save(s);
    }

    public void setBuyPrice(PlayerShop s, long price) {
        s.buyPrice = ShopTrade.clampPrice(price, MAX_PRICE);
        save(s);
    }

    /** Changes what the shop trades. Items of the old kind stay in the chest for the owner to take out. */
    public void setItem(PlayerShop s, ItemStack item) {
        if (item == null || item.isEmpty()) return;
        s.item = item.asOne();
        removeDisplay(s.id);
        save(s);
        refreshDisplay(s);
    }

    private void save(PlayerShop s) {
        if (!shops.containsKey(s.id)) return;
        byte[] data = s.item.serializeAsBytes();
        String mode = s.mode.name();
        long sell = s.sellPrice, buy = s.buyPrice;
        db.run(c -> Database.update(c, "UPDATE pshops SET item = ?, mode = ?, sell_price = ?, buy_price = ? WHERE id = ?", data, mode, sell, buy, s.id));
        refreshDisplay(s);
    }

    public void delete(PlayerShop s, String why) {
        if (shops.remove(s.id) == null) return;
        byPos.remove(s.pos);
        removeDisplay(s.id);
        db.run(c -> Database.update(c, "DELETE FROM pshops WHERE id = ?", s.id));
        plugin.getLogger().info("Player shop #" + s.id + " (" + eco().name(s.owner) + ", " + s.itemId() + ") at " + s.pos + " removed: " + why);
    }

    // --- trading ---

    public ShopTrade.Result buy(Player p, PlayerShop s, int wanted) {
        Inventory chest = inventory(s);
        if (chest == null) return new ShopTrade.Result(ShopTrade.Status.OUT_OF_STOCK, 0, 0, 0, 0);
        ItemStack[] stock = chest.getContents();
        ItemStack[] bag = p.getInventory().getStorageContents();
        ShopTrade.Result r = ShopTrade.customerBuys(s.mode, s.sellPrice, wanted, Stacks.count(stock, s.item), Stacks.room(bag, s.item),
                eco().balance(p.getUniqueId()), taxPercent);
        if (!r.ok()) return r;
        if (eco().withdraw(p.getUniqueId(), r.total(), "shop #" + s.id + " buy") != Accounts.Result.OK) {
            return new ShopTrade.Result(ShopTrade.Status.CANT_AFFORD, 0, 0, 0, 0);
        }
        if (r.paid() > 0) eco().deposit(s.owner, r.paid(), "shop #" + s.id + " sale");
        Stacks.remove(stock, s.item, r.amount());
        Stacks.add(bag, s.item, r.amount());
        chest.setContents(stock);
        p.getInventory().setStorageContents(bag);
        record(s, p, "sold", r.amount(), r.paid());
        Player owner = plugin.getServer().getPlayer(s.owner);
        if (owner != null && !owner.equals(p)) {
            owner.sendMessage(msg().rich("pshop.owner-sold", "player", p.getName(), "amount", String.valueOf(r.amount()),
                    "item", name(s.item), "paid", eco().format(r.paid())));
        }
        refreshDisplay(s);
        return r;
    }

    public ShopTrade.Result sell(Player p, PlayerShop s, int wanted) {
        Inventory chest = inventory(s);
        if (chest == null) return new ShopTrade.Result(ShopTrade.Status.SHOP_FULL, 0, 0, 0, 0);
        ItemStack[] stock = chest.getContents();
        ItemStack[] bag = p.getInventory().getStorageContents();
        ShopTrade.Result r = ShopTrade.customerSells(s.mode, s.buyPrice, wanted, Stacks.count(bag, s.item), Stacks.room(stock, s.item),
                eco().balance(s.owner), taxPercent);
        if (!r.ok()) return r;
        if (eco().withdraw(s.owner, r.total(), "shop #" + s.id + " bought") != Accounts.Result.OK) {
            return new ShopTrade.Result(ShopTrade.Status.OWNER_CANT_AFFORD, 0, 0, 0, 0);
        }
        if (r.paid() > 0) eco().deposit(p.getUniqueId(), r.paid(), "shop #" + s.id + " sold to");
        Stacks.remove(bag, s.item, r.amount());
        Stacks.add(stock, s.item, r.amount());
        chest.setContents(stock);
        p.getInventory().setStorageContents(bag);
        record(s, p, "bought", r.amount(), r.total());
        Player owner = plugin.getServer().getPlayer(s.owner);
        if (owner != null && !owner.equals(p)) {
            owner.sendMessage(msg().rich("pshop.owner-bought", "player", p.getName(), "amount", String.valueOf(r.amount()),
                    "item", name(s.item), "total", eco().format(r.total())));
        }
        refreshDisplay(s);
        return r;
    }

    public int carried(Player p, PlayerShop s) {
        return Stacks.count(p.getInventory().getStorageContents(), s.item);
    }

    /** Logs a sale; it counts as seen when the owner is online to see the chat line. */
    private void record(PlayerShop s, Player customer, String kind, int amount, long coins) {
        int seen = plugin.getServer().getPlayer(s.owner) != null ? 1 : 0;
        long now = System.currentTimeMillis();
        String owner = s.owner.toString(), who = customer.getUniqueId().toString(), item = s.itemId();
        db.run(c -> Database.update(c,
                "INSERT INTO pshop_sales (time, shop, owner, customer, kind, item, amount, coins, seen) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                now, s.id, owner, who, kind, item, amount, coins, seen));
    }

    public static Component name(ItemStack item) {
        var meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) return meta.displayName();
        return Component.translatable(item.getType().translationKey());
    }

    // --- floating item and label ---

    /** Every second: shows the item and label over loaded shops, and drops shops whose chest is gone. */
    public void tick() {
        spin = (spin + (float) (Math.PI / 2)) % (float) (Math.PI * 2);
        for (PlayerShop s : new ArrayList<>(shops.values())) {
            if (!loaded(s)) {
                displays.remove(s.id); // non-persistent displays went away with the chunk
                continue;
            }
            Block b = block(s);
            if (b == null || !shopType(b)) {
                delete(s, "no chest there any more");
                continue;
            }
            refreshDisplay(s);
        }
    }

    private void refreshDisplay(PlayerShop s) {
        if (!loaded(s)) return;
        World w = plugin.getServer().getWorld(s.pos.world());
        UUID[] ids = displays.get(s.id);
        Entity item = ids == null ? null : plugin.getServer().getEntity(ids[0]);
        Entity text = ids == null ? null : plugin.getServer().getEntity(ids[1]);
        Location base = s.pos.center(w);
        if (!(item instanceof ItemDisplay id) || !id.isValid()) {
            if (item != null) item.remove();
            item = w.spawn(base.clone().add(0, 1.15, 0), ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemStack(s.item);
                d.setBillboard(Display.Billboard.FIXED);
            });
        }
        if (!(text instanceof TextDisplay td) || !td.isValid()) {
            if (text != null) text.remove();
            text = w.spawn(base.clone().add(0, 1.7, 0), TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setDefaultBackground(false);
                d.setShadowed(true);
            });
        }
        displays.put(s.id, new UUID[]{item.getUniqueId(), text.getUniqueId()});
        ItemDisplay idp = (ItemDisplay) item;
        idp.setInterpolationDelay(0);
        idp.setInterpolationDuration(20);
        idp.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(spin, 0, 1, 0), new Vector3f(0.5f), new AxisAngle4f()));
        ((TextDisplay) text).text(label(s));
    }

    public Component label(PlayerShop s) {
        Component name = name(s.item);
        List<Component> lines = new ArrayList<>();
        lines.add(msg().rich("pshop.label-item", "item", name));
        if (s.mode.ownerSells()) {
            int stock = stock(s);
            lines.add(stock > 0 ? msg().msg("pshop.label-sells", "price", eco().format(s.sellPrice), "stock", String.valueOf(stock))
                    : msg().msg("pshop.label-out"));
        }
        if (s.mode.ownerBuys()) {
            boolean buying = space(s) > 0 && eco().balance(s.owner) >= s.buyPrice && s.buyPrice > 0;
            lines.add(buying ? msg().msg("pshop.label-buys", "price", eco().format(s.buyPrice)) : msg().msg("pshop.label-not-buying"));
        }
        lines.add(msg().msg("pshop.label-owner", "player", eco().name(s.owner)));
        return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines);
    }

    private void removeDisplay(long id) {
        UUID[] ids = displays.remove(id);
        if (ids == null) return;
        for (UUID u : ids) {
            Entity e = plugin.getServer().getEntity(u);
            if (e != null) e.remove();
        }
    }

    public void removeDisplays() {
        for (Long id : new ArrayList<>(displays.keySet())) removeDisplay(id);
    }

    // --- listeners ---

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        Long until = picking.get(p.getUniqueId());
        if (until != null) {
            if (until < System.currentTimeMillis()) {
                picking.remove(p.getUniqueId());
            } else if (shopType(b)) {
                e.setCancelled(true);
                picking.remove(p.getUniqueId());
                PlayerShop s = create(p, b);
                if (s != null) later(() -> PlayerShopMenus.editor(plugin, p, s));
                return;
            }
        }
        PlayerShop s = shopFor(b);
        if (s == null) return;
        boolean owner = s.owner.equals(p.getUniqueId());
        if (owner && !p.isSneaking()) return; // owners open their chest to stock it
        e.setCancelled(true);
        if (owner || (p.isSneaking() && p.hasPermission(ADMIN))) later(() -> PlayerShopMenus.editor(plugin, p, s));
        else later(() -> PlayerShopMenus.customer(plugin, p, s));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        PlayerShop s = shopFor(e.getInventory());
        if (s == null || s.owner.equals(e.getPlayer().getUniqueId()) || e.getPlayer().hasPermission(ADMIN)) return;
        e.setCancelled(true);
        msg().send(e.getPlayer(), "pshop.protected", "player", eco().name(s.owner));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        PlayerShop exact = byPos.get(PlayerShop.Pos.of(e.getBlock()));
        PlayerShop s = exact != null ? exact : shopFor(e.getBlock());
        if (s == null) return;
        Player p = e.getPlayer();
        if (!s.owner.equals(p.getUniqueId()) && !p.hasPermission(ADMIN)) {
            e.setCancelled(true);
            msg().send(p, "pshop.protected", "player", eco().name(s.owner));
            return;
        }
        if (exact != null) {
            delete(exact, "broken by " + p.getName());
            msg().send(p, "pshop.removed");
        }
    }

    /** No one else can place a chest next to a shop chest (it would join the shop's double chest). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Material m = e.getBlockPlaced().getType();
        if (shops.isEmpty() || (m != Material.CHEST && m != Material.TRAPPED_CHEST) || e.getPlayer().hasPermission(ADMIN)) return;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            PlayerShop s = shopFor(e.getBlockPlaced().getRelative(f));
            if (s != null && !s.owner.equals(e.getPlayer().getUniqueId())) {
                e.setCancelled(true);
                msg().send(e.getPlayer(), "pshop.protected", "player", eco().name(s.owner));
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!shops.isEmpty()) e.blockList().removeIf(b -> shopFor(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!shops.isEmpty()) e.blockList().removeIf(b -> shopFor(b) != null);
    }

    /** Hoppers and hopper carts can't take from or put into a shop. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (shops.isEmpty()) return;
        if (shopFor(e.getSource()) != null || shopFor(e.getDestination()) != null) e.setCancelled(true);
    }

    /** Owners who were offline get a summary of what their shops did. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String id = p.getUniqueId().toString();
        db.submit(c -> {
            long[] out = new long[4]; // sold items, coins in, bought items, coins out
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT kind, SUM(amount), SUM(coins) FROM pshop_sales WHERE owner = ? AND seen = 0 GROUP BY kind")) {
                Database.bind(ps, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int at = rs.getString(1).equals("sold") ? 0 : 2;
                        out[at] = rs.getLong(2);
                        out[at + 1] = rs.getLong(3);
                    }
                }
            }
            Database.update(c, "UPDATE pshop_sales SET seen = 1 WHERE owner = ? AND seen = 0", id);
            return out;
        }).thenAccept(out -> {
            if (out[0] == 0 && out[2] == 0) return;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                if (out[0] > 0) msg().send(p, "pshop.away-sold", "amount", String.valueOf(out[0]), "coins", eco().format(out[1]));
                if (out[2] > 0) msg().send(p, "pshop.away-bought", "amount", String.valueOf(out[2]), "coins", eco().format(out[3]));
            }, 60L);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        picking.remove(e.getPlayer().getUniqueId());
    }

    private void later(Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }

}
