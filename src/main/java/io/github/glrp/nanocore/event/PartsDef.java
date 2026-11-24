package io.github.glrp.nanocore.event;

import java.util.List;
import java.util.Map;

/**
 * The gameplay parts a phase can use on top of points and rules: mob waves and a boss, build plots, a theme,
 * a collection list, hidden treasure, a race course, a hill to hold, an arena floor to refill, hide and seek,
 * kill streaks, deliveries, a checklist and more. Pure data from events.yml.
 *
 * @param spawnArea   where wave mobs appear (an area name); empty = around the players
 * @param scale       more mobs with more players (count x players/2)
 * @param holdArea    king of the hill: standing here alone scores "event.hold" each second
 * @param outBelow    players below this height are out (spleef)
 * @param revive      everyone who was out is back in at the start of this phase
 * @param survivors   at the end of this phase everyone still in scores "event.survive"
 * @param seekers     hide and seek: this many seekers are picked (once); a seeker's hit turns a hider into a seeker
 * @param blindSeekers seekers can't see or move during this phase (the hiding phase)
 * @param respawnProtectionMs after dying, other players can't hurt you for this long
 * @param shopTax     player shop tax while this phase runs (null = normal)
 * @param breakable   blocks that can be broken even when building is off (spleef snow)
 * @param teleport    at the start: "area" (into the event area), "plot" (to your plot) or null
 */
public record PartsDef(List<Wave> waves, Boss boss, String spawnArea, boolean scale, Plots plots, List<String> themes,
                       Collect collect, Treasure treasure, Race race, String holdArea, Fill fill, Integer outBelow, boolean revive,
                       boolean survivors, int seekers, boolean blindSeekers, Streak streak, long respawnProtectionMs, Deliver deliver,
                       List<String> checklist, Double shopTax, List<String> breakable, String teleport, Lose lose) {

    public static final PartsDef NONE = new PartsDef(List.of(), null, null, false, null, List.of(), null, null, null, null, null, null,
            false, false, 0, false, null, 0, null, List.of(), null, List.of(), null, null);

    public boolean hasWaves() {
        return !waves.isEmpty() || boss != null;
    }

    /** One mob type in a wave: "zombie 10" or "skeleton 5 Bone Archer". */
    public record MobSpec(String type, int count, String name) {
    }

    /**
     * @param health multiplier of normal health   @param damage multiplier of normal damage
     * @param armor  iron, diamond, netherite, gold, chainmail, leather or null   @param weapon item id or null
     * @param effects like "speed" or "strength 1"   @param nextMs next wave after this long (0 = when this one is cleared)
     */
    public record Wave(List<MobSpec> mobs, double health, double damage, String armor, String weapon, List<String> effects, long nextMs) {
    }

    /**
     * @param health     max health in hit points (a zombie has 20)
     * @param abilities  minions, slam, leap, shield, enrage, teleport, pull
     * @param existing   don't spawn: track a mob of this type that's already there (the Ender Dragon)
     */
    public record Boss(String type, String name, double health, List<String> abilities, String minion, boolean existing) {
    }

    /** Square plots of {@code size} blocks with {@code gap} between them, laid out inside a box area. */
    public record Plots(String area, int size, int gap) {
    }

    /** A random list of {@code count} items from the pool; hand the full list in with /event handin. */
    public record Collect(int count, List<String> pool, boolean atBoard) {
    }

    /** {@code chests} chests hidden within {@code radius} blocks of an area's centre (or spawn), each worth coins (+ a trophy). */
    public record Treasure(int chests, int radius, String around, long coins, boolean trophy) {
    }

    /** Areas for the start line, checkpoints in order and the finish. Best time per player wins. */
    public record Race(String start, List<String> checkpoints, String finish) {
    }

    /** Sets every block of a box area to this block when the phase starts. */
    public record Fill(String area, String block) {
    }

    /** From the {@code at}th kill in a row (without dying), every kill gives {@code points} extra. */
    public record Streak(int at, long points) {
    }

    /**
     * /event deliver: items worth points ("event.deliver" per point) and community needs per item.
     * Keys are item ids or tags like "#planks".
     */
    public record Deliver(Map<String, Long> points, Map<String, Long> need, boolean atBoard, boolean storehouse) {
        public boolean has(String key) {
            return points.containsKey(key) || need.containsKey(key);
        }
    }

    /** When the event is lost (no prizes, no unlock): the timer runs out before the waves are cleared, or everyone is out. */
    public record Lose(boolean timer, boolean allDown) {
    }
}
