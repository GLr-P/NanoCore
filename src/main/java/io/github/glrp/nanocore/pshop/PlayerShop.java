package io.github.glrp.nanocore.pshop;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** One chest shop. Price and mode change while it runs; the place and owner don't. */
public final class PlayerShop {

    /** A block position. */
    public record Pos(String world, int x, int y, int z) {
        public static Pos of(Block b) {
            return new Pos(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        }

        public Location center(World w) {
            return new Location(w, x + 0.5, y, z + 0.5);
        }

        @Override
        public String toString() {
            return world + " " + x + " " + y + " " + z;
        }
    }

    final long id;
    final UUID owner;
    final Pos pos;
    ItemStack item;
    ShopTrade.Mode mode;
    /** What customers pay per item. */
    long sellPrice;
    /** What the owner pays customers per item. */
    long buyPrice;
    /** Items per click in the customer window (0 = buttons for 1, 8 and a stack). */
    int clickAmount;
    final long created;

    PlayerShop(long id, UUID owner, Pos pos, ItemStack item, ShopTrade.Mode mode, long sellPrice, long buyPrice, long created) {
        this.id = id;
        this.owner = owner;
        this.pos = pos;
        this.item = item.asOne();
        this.mode = mode;
        this.sellPrice = sellPrice;
        this.buyPrice = buyPrice;
        this.created = created;
    }

    public long id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public Pos pos() {
        return pos;
    }

    public ItemStack item() {
        return item.clone();
    }

    public ShopTrade.Mode mode() {
        return mode;
    }

    public long sellPrice() {
        return sellPrice;
    }

    public long buyPrice() {
        return buyPrice;
    }

    public int clickAmount() {
        return clickAmount;
    }

    public String itemId() {
        return item.getType().getKey().getKey();
    }
}
