package io.github.glrp.nanocore.blocks;

/**
 * Packs a block's position inside its chunk into one int: x (4 bits), z (4 bits), y + 2048 (12 bits).
 * Covers y from -2048 to 2047, far beyond any Minecraft world height.
 */
public final class BlockKey {

    private BlockKey() {
    }

    public static int pack(int blockX, int y, int blockZ) {
        if (y < -2048 || y > 2047) throw new IllegalArgumentException("y out of range: " + y);
        return (blockX & 15) | ((blockZ & 15) << 4) | ((y + 2048) << 8);
    }

    public static int localX(int key) {
        return key & 15;
    }

    public static int localZ(int key) {
        return (key >> 4) & 15;
    }

    public static int y(int key) {
        return (key >>> 8) - 2048;
    }
}
