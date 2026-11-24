package io.github.glrp.nanocore.blocks;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockKeyTest {

    @Test
    void roundTripsEveryPositionInAChunkColumn() {
        Set<Integer> seen = new HashSet<>();
        for (int x = -16; x < 16; x++) {
            for (int z = -16; z < 16; z++) {
                for (int y = -64; y < 320; y += 7) {
                    int k = BlockKey.pack(x, y, z);
                    assertEquals(x & 15, BlockKey.localX(k));
                    assertEquals(z & 15, BlockKey.localZ(k));
                    assertEquals(y, BlockKey.y(k));
                    if (x >= 0 && z >= 0) assertTrue(seen.add(k), "duplicate key");
                }
            }
        }
    }

    @Test
    void extremesWork() {
        assertEquals(-2048, BlockKey.y(BlockKey.pack(0, -2048, 0)));
        assertEquals(2047, BlockKey.y(BlockKey.pack(15, 2047, 15)));
        assertThrows(IllegalArgumentException.class, () -> BlockKey.pack(0, 2048, 0));
    }
}
