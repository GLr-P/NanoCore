package io.github.glrp.nanocore.area;

/**
 * A named part of a world: a box between two corners, or a circle (any height) around a centre.
 * Used for safe zones, event areas, town, plots.
 */
public record Area(String name, String world, Kind kind,
                   double x1, double y1, double z1, double x2, double y2, double z2, double radius) {

    public enum Kind { BOX, CIRCLE }

    public static Area box(String name, String world, double ax, double ay, double az, double bx, double by, double bz) {
        return new Area(name, world, Kind.BOX,
                Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
                Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz), 0);
    }

    public static Area circle(String name, String world, double cx, double cz, double radius) {
        return new Area(name, world, Kind.CIRCLE, cx, 0, cz, 0, 0, 0, radius);
    }

    /** Block coordinates: a box includes both corner blocks. */
    public boolean contains(String world, double x, double y, double z) {
        if (!this.world.equals(world)) return false;
        return switch (kind) {
            case BOX -> x >= x1 && x < x2 + 1 && y >= y1 && y < y2 + 1 && z >= z1 && z < z2 + 1;
            case CIRCLE -> {
                double dx = x - x1;
                double dz = z - z1;
                yield dx * dx + dz * dz <= radius * radius;
            }
        };
    }

    public String describe() {
        return switch (kind) {
            case BOX -> String.format("%s box %d,%d,%d to %d,%d,%d", world, (int) x1, (int) y1, (int) z1, (int) x2, (int) y2, (int) z2);
            case CIRCLE -> String.format("%s circle at %d,%d radius %d", world, (int) x1, (int) z1, (int) radius);
        };
    }
}
