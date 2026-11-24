package io.github.glrp.nanocore.shop;

/**
 * One item the admin shop buys.
 *
 * @param item     item id without namespace, for example "diamond"
 * @param price    coins paid per item
 * @param requires milestone id that must be unlocked first, or null
 */
public record SellPrice(String item, long price, String requires) {
}
