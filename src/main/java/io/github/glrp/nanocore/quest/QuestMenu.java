package io.github.glrp.nanocore.quest;

import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.gui.Menu;
import io.github.glrp.nanocore.shop.DailyLimit;
import io.github.glrp.nanocore.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The quest window. Rows 2-4 hold the easy, medium and hard quests, the community quest sits below them.
 * Clicking a quest opens its page with buttons (take, reroll, hand in, claim, abandon), which also works on Bedrock.
 * Away from a board the window only shows progress; taking, handing in and claiming happen at a board.
 */
public final class QuestMenu {

    /** Where each tier's row starts, and the community quest's slot. */
    static final int[] TIER_ROW = {10, 19, 28};
    static final int COMMUNITY_SLOT = 40;
    static final int INFO_SLOT = 4;
    /** Detail page buttons. */
    static final int ACTION_LEFT = 11, ACTION_MID = 13, ACTION_RIGHT = 15, BACK = 22;

    private QuestMenu() {
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    public static void open(NanoCorePlugin plugin, Player p, boolean atBoard) {
        QuestService qs = plugin.quests();
        Messages msg = plugin.messages();
        UUID id = p.getUniqueId();
        Menu menu = new Menu(6, msg.msg("quests.menu-title"));

        List<Component> info = new ArrayList<>();
        info.add(msg.item("quests.info-refresh", "time", DailyLimit.format(qs.untilRefresh())));
        info.add(msg.item("quests.info-active", "active", String.valueOf(qs.activeCount(id)), "max", String.valueOf(qs.settings().maxActive())));
        if (!atBoard) info.add(msg.item("quests.info-away"));
        menu.set(INFO_SLOT, Menu.item(Material.WRITABLE_BOOK, msg.item("quests.info-title"), info));

        Tier[] tiers = {Tier.EASY, Tier.MEDIUM, Tier.HARD};
        Material[] labels = {Material.LIME_DYE, Material.YELLOW_DYE, Material.RED_DYE};
        int[] used = new int[3];
        for (int t = 0; t < 3; t++) {
            menu.set(TIER_ROW[t] - 1, Menu.item(labels[t], msg.item("quests.tier." + tiers[t].id()),
                    List.of(msg.item("quests.tier-rewards", "range", range(plugin, tiers[t])))));
        }
        for (Quest q : qs.view(id)) {
            int t = q.tier().ordinal();
            if (t > 2 || used[t] >= 7) continue;
            int slot = TIER_ROW[t] + used[t]++;
            menu.set(slot, icon(plugin, p, q), e -> later(plugin, () -> detail(plugin, p, q.id(), atBoard)));
        }
        Quest c = qs.community();
        if (c != null) menu.set(COMMUNITY_SLOT, communityIcon(plugin, p, c), e -> later(plugin, () -> detail(plugin, p, c.id(), atBoard)));
        menu.frame().open(p);
    }

    private static String range(NanoCorePlugin plugin, Tier t) {
        Range r = plugin.quests().settings().rewards().get(t);
        return r == null ? "" : fmt(r.min()) + "-" + fmt(r.max()) + " " + plugin.economy().plural();
    }

    /** A quest's menu item: name with amount, reward, and where the player is with it. */
    static ItemStack icon(NanoCorePlugin plugin, Player p, Quest q) {
        QuestService qs = plugin.quests();
        Messages msg = plugin.messages();
        QuestTemplate.Resolved r = qs.resolve(q);
        PlayerQuests pq = qs.player(p.getUniqueId());
        PlayerQuests.State state = pq.state(q.id());
        List<Component> lore = new ArrayList<>();
        lore.add(msg.item("quests.tier-line", "tier", msg.raw("quests.tier." + q.tier().id())));
        lore.add(msg.item("quests.reward-line", "reward", plugin.economy().format(q.reward())));
        if (q.owner() != null) lore.add(msg.item("quests.rerolled-line"));
        if (r.objective() == QuestTemplate.Objective.DELIVER) lore.add(msg.item("quests.deliver-line"));
        String key = state == null ? "quests.state-available" : switch (state) {
            case ACTIVE -> "quests.state-active";
            case DONE -> "quests.state-done";
            case CLAIMED -> "quests.state-claimed";
        };
        lore.add(msg.item(key, "progress", fmt(qs.shown(q, pq.progress(q.id()))), "amount", fmt(q.amount())));
        Material m = Material.matchMaterial(r.icon());
        if (state == PlayerQuests.State.CLAIMED) m = Material.GRAY_DYE;
        ItemStack it = Menu.item(m == null ? Material.PAPER : m, msg.item("quests.quest-name", "quest", r.name(), "amount", fmt(q.amount())), lore);
        if (state == PlayerQuests.State.DONE) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return it;
    }

    static ItemStack communityIcon(NanoCorePlugin plugin, Player p, Quest q) {
        QuestService qs = plugin.quests();
        Messages msg = plugin.messages();
        QuestTemplate.Resolved r = qs.resolve(q);
        List<Component> lore = new ArrayList<>();
        lore.add(msg.item("quests.community-line"));
        lore.add(msg.item("quests.community-progress", "progress", fmt(qs.shown(q, qs.communityProgress())), "amount", fmt(q.amount())));
        lore.add(msg.item("quests.community-yours", "progress", fmt(qs.shown(q, qs.contribution(p.getUniqueId())))));
        lore.add(msg.item("quests.community-pot", "pot", plugin.economy().format(q.reward()), "min", plugin.economy().format(qs.settings().communityMinShare())));
        if (r.objective() == QuestTemplate.Objective.DELIVER) lore.add(msg.item("quests.deliver-line"));
        lore.add(qs.communityDone() ? msg.item("quests.community-finished") : msg.item("quests.community-ends", "time", DailyLimit.format(qs.untilWeekEnds())));
        Material m = Material.matchMaterial(r.icon());
        ItemStack it = Menu.item(m == null ? Material.PAPER : m, msg.item("quests.quest-name", "quest", r.name(), "amount", fmt(q.amount())), lore);
        it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return it;
    }

    /** One quest's page with the buttons that apply to it right now. */
    static void detail(NanoCorePlugin plugin, Player p, String questId, boolean atBoard) {
        QuestService qs = plugin.quests();
        Messages msg = plugin.messages();
        Quest q = qs.find(p.getUniqueId(), questId);
        if (q == null) {
            open(plugin, p, atBoard); // the board changed meanwhile
            return;
        }
        QuestTemplate.Resolved r = qs.resolve(q);
        Menu menu = new Menu(3, msg.msg("quests.detail-title"));
        boolean deliver = r.objective() == QuestTemplate.Objective.DELIVER;
        if (q.tier() == Tier.COMMUNITY) {
            menu.set(4, communityIcon(plugin, p, q));
            if (deliver && !qs.communityDone()) {
                if (atBoard) menu.set(ACTION_MID, button(Material.HOPPER, msg.item("quests.button-deliver", "item", r.name())), e -> act(plugin, p, q, "deliver", atBoard));
                else menu.set(ACTION_MID, button(Material.GRAY_DYE, msg.item("quests.button-away")));
            }
        } else {
            menu.set(4, icon(plugin, p, q));
            PlayerQuests.State state = qs.player(p.getUniqueId()).state(q.id());
            if (state == null) {
                if (atBoard) {
                    menu.set(ACTION_LEFT, button(Material.LIME_CONCRETE, msg.item("quests.button-take")), e -> act(plugin, p, q, "take", atBoard));
                    long cost = qs.settings().rerollCost();
                    menu.set(ACTION_RIGHT, button(Material.ORANGE_CONCRETE, msg.item("quests.button-reroll", "cost", plugin.economy().format(cost))),
                            e -> act(plugin, p, q, "reroll", atBoard));
                } else {
                    menu.set(ACTION_MID, button(Material.GRAY_DYE, msg.item("quests.button-away")));
                }
            } else if (state == PlayerQuests.State.ACTIVE) {
                if (deliver) {
                    if (atBoard) menu.set(ACTION_LEFT, button(Material.HOPPER, msg.item("quests.button-deliver", "item", r.name())), e -> act(plugin, p, q, "deliver", atBoard));
                    else menu.set(ACTION_LEFT, button(Material.GRAY_DYE, msg.item("quests.button-away")));
                }
                menu.set(ACTION_RIGHT, button(Material.RED_CONCRETE, msg.item("quests.button-abandon")), e -> act(plugin, p, q, "abandon", atBoard));
            } else if (state == PlayerQuests.State.DONE) {
                if (atBoard) menu.set(ACTION_MID, button(Material.GOLD_INGOT, msg.item("quests.button-claim", "reward", plugin.economy().format(q.reward()))),
                        e -> act(plugin, p, q, "claim", atBoard));
                else menu.set(ACTION_MID, button(Material.GRAY_DYE, msg.item("quests.button-away")));
            }
        }
        menu.set(BACK, button(Material.ARROW, msg.item("quests.button-back")), e -> later(plugin, () -> open(plugin, p, atBoard)));
        menu.frame().open(p);
    }

    private static ItemStack button(Material m, Component name) {
        return Menu.item(m, name, List.of());
    }

    private static void act(NanoCorePlugin plugin, Player p, Quest q, String action, boolean atBoard) {
        later(plugin, () -> {
            QuestService qs = plugin.quests();
            Messages msg = plugin.messages();
            String name = qs.resolve(q).name();
            switch (action) {
                case "take" -> {
                    switch (qs.take(p, q)) {
                        case OK -> msg.send(p, "quests.taken", "quest", name);
                        case FULL -> msg.send(p, "quests.too-many", "max", String.valueOf(qs.settings().maxActive()));
                        default -> msg.send(p, "quests.not-available");
                    }
                }
                case "reroll" -> {
                    switch (qs.reroll(p, q)) {
                        case OK -> msg.send(p, "quests.rerolled", "quest", name, "cost", plugin.economy().format(qs.settings().rerollCost()));
                        case NOT_ENOUGH -> msg.send(p, "quests.reroll-money", "cost", plugin.economy().format(qs.settings().rerollCost()));
                        case NONE -> msg.send(p, "quests.reroll-none");
                        default -> msg.send(p, "quests.not-available");
                    }
                }
                case "abandon" -> msg.send(p, qs.abandon(p, q) ? "quests.abandoned" : "quests.not-available", "quest", name);
                case "deliver" -> {
                    int n = qs.deliver(p, q);
                    if (n > 0) msg.send(p, "quests.delivered", "count", String.valueOf(n), "quest", name);
                    else msg.send(p, "quests.deliver-none");
                }
                case "claim" -> {
                    if (qs.claim(p, q) != QuestService.Result.OK) msg.send(p, "quests.not-available");
                }
                default -> {
                }
            }
            open(plugin, p, atBoard);
        });
    }

    private static void later(NanoCorePlugin plugin, Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }
}
