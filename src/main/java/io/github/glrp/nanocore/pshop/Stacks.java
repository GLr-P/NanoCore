package io.github.glrp.nanocore.pshop;

import org.bukkit.inventory.ItemStack;

/** Counting, fitting and moving items of one kind in an inventory's contents array (changed in place). */
final class Stacks {

    private Stacks() {
    }

    static int count(ItemStack[] contents, ItemStack like) {
        int n = 0;
        for (ItemStack s : contents) if (s != null && s.isSimilar(like)) n += s.getAmount();
        return n;
    }

    /** How many more of the item fit. */
    static int room(ItemStack[] contents, ItemStack like) {
        int max = like.getMaxStackSize();
        long n = 0;
        for (ItemStack s : contents) {
            if (s == null || s.isEmpty()) n += max;
            else if (s.isSimilar(like)) n += Math.max(0, max - s.getAmount());
        }
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** Takes up to n items; returns how many were taken. */
    static int remove(ItemStack[] contents, ItemStack like, int n) {
        int left = n;
        for (int i = contents.length - 1; i >= 0 && left > 0; i--) {
            ItemStack s = contents[i];
            if (s == null || !s.isSimilar(like)) continue;
            int take = Math.min(left, s.getAmount());
            left -= take;
            if (take == s.getAmount()) contents[i] = null;
            else s.setAmount(s.getAmount() - take);
        }
        return n - left;
    }

    /** Adds n items, topping up stacks first; returns how many didn't fit. */
    static int add(ItemStack[] contents, ItemStack like, int n) {
        int max = like.getMaxStackSize();
        int left = n;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || !s.isSimilar(like) || s.getAmount() >= max) continue;
            int put = Math.min(left, max - s.getAmount());
            s.setAmount(s.getAmount() + put);
            left -= put;
        }
        for (int i = 0; i < contents.length && left > 0; i++) {
            if (contents[i] != null && !contents[i].isEmpty()) continue;
            int put = Math.min(left, max);
            contents[i] = like.asQuantity(put);
            left -= put;
        }
        return left;
    }
}
