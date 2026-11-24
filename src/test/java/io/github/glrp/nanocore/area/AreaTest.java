package io.github.glrp.nanocore.area;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaTest {

    @Test
    void boxIncludesBothCornerBlocksInAnyOrder() {
        Area a = Area.box("town", "world", 10, 60, 10, 0, 70, 0);
        assertTrue(a.contains("world", 0, 60, 0));
        assertTrue(a.contains("world", 10.9, 70.5, 10.9));
        assertFalse(a.contains("world", 11, 65, 5));
        assertFalse(a.contains("world", -0.1, 65, 5));
        assertFalse(a.contains("world_nether", 5, 65, 5));
    }

    @Test
    void circleIgnoresHeight() {
        Area a = Area.circle("hunt", "world", 0, 0, 500);
        assertTrue(a.contains("world", 300, -60, 400));
        assertTrue(a.contains("world", 0, 300, 0));
        assertFalse(a.contains("world", 400, 64, 400));
    }
}
