package io.github.glrp.nanocore.stats;

import io.github.glrp.nanocore.milestone.Milestone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatKeysTest {

    @Test
    void farmedMobsDontCount() {
        assertTrue(StatKeys.isNaturalSpawn("NATURAL"));
        assertTrue(StatKeys.isNaturalSpawn("REINFORCEMENTS"));
        assertTrue(StatKeys.isNaturalSpawn("SLIME_SPLIT"));
        assertFalse(StatKeys.isNaturalSpawn("SPAWNER"));
        assertFalse(StatKeys.isNaturalSpawn("SPAWNER_EGG"));
        assertFalse(StatKeys.isNaturalSpawn("BREEDING"));
        assertFalse(StatKeys.isNaturalSpawn("NETHER_PORTAL")); // gold farms
        assertFalse(StatKeys.isNaturalSpawn(null));
    }

    @Test
    void fishingCategories() {
        assertEquals("fish", StatKeys.fishCategory("cod", false));
        assertEquals("treasure", StatKeys.fishCategory("name_tag", false));
        assertEquals("treasure", StatKeys.fishCategory("fishing_rod", true));
        assertEquals("junk", StatKeys.fishCategory("fishing_rod", false));
        assertEquals("junk", StatKeys.fishCategory("rotten_flesh", false));
    }

    @Test
    void milestoneIds() {
        assertEquals(Milestone.NETHER_ROOF, Milestone.byId(" Nether_Roof ").orElseThrow());
        assertTrue(Milestone.byId("moon").isEmpty());
        assertEquals(6, Milestone.values().length);
    }
}
