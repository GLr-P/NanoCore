package io.github.glrp.nanocore.quest;

/**
 * One quest on the board (or a player's rerolled replacement).
 *
 * @param id       unique for this board period, e.g. "2026-12-05/medium-2"
 * @param amount   how many are asked for, in shown units (blocks, items, mobs)
 * @param reward   coins (for a community quest: the whole pot)
 * @param owner    null for a board quest everyone sees; the player's UUID (as text) for a personal reroll
 * @param replaces for a personal reroll: the id of the board quest it stands in for
 */
public record Quest(String id, Tier tier, String template, String variant, long amount, long reward, String owner, String replaces) {

    public Quest withId(String newId) {
        return new Quest(newId, tier, template, variant, amount, reward, owner, replaces);
    }

    public Quest personal(String ownerId, String replacedId) {
        return new Quest(id, tier, template, variant, amount, reward, ownerId, replacedId);
    }
}
