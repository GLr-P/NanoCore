package io.github.glrp.nanocore.area;

import io.github.glrp.nanocore.db.Database;
import org.bukkit.Location;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Named areas, saved in the database. */
public final class AreaService {

    private final Database db;
    private final Map<String, Area> areas = new ConcurrentHashMap<>();

    public AreaService(Database db) {
        this.db = db;
        db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name, world, kind, x1, y1, z1, x2, y2, z2, radius FROM areas");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Area a = new Area(rs.getString(1), rs.getString(2), Area.Kind.valueOf(rs.getString(3)),
                            rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getDouble(9), rs.getDouble(10));
                    areas.put(key(a.name()), a);
                }
            }
            return null;
        });
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public Optional<Area> get(String name) {
        return Optional.ofNullable(areas.get(key(name)));
    }

    public List<Area> all() {
        List<Area> list = new ArrayList<>(areas.values());
        list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return list;
    }

    public void save(Area a) {
        areas.put(key(a.name()), a);
        db.run(c -> Database.update(c,
                "INSERT INTO areas (name, world, kind, x1, y1, z1, x2, y2, z2, radius) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (name) DO UPDATE SET world = excluded.world, kind = excluded.kind, x1 = excluded.x1, y1 = excluded.y1, "
                        + "z1 = excluded.z1, x2 = excluded.x2, y2 = excluded.y2, z2 = excluded.z2, radius = excluded.radius",
                a.name(), a.world(), a.kind().name(), a.x1(), a.y1(), a.z1(), a.x2(), a.y2(), a.z2(), a.radius()));
    }

    public boolean delete(String name) {
        Area a = areas.remove(key(name));
        if (a == null) return false;
        db.run(c -> Database.update(c, "DELETE FROM areas WHERE name = ?", a.name()));
        return true;
    }

    public boolean inside(String areaName, Location l) {
        Area a = areas.get(key(areaName));
        return a != null && a.contains(l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
    }
}
