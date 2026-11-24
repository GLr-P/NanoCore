package io.github.glrp.nanocore.integration;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import io.github.glrp.nanocore.NanoCorePlugin;
import io.github.glrp.nanocore.area.Area;
import io.github.glrp.nanocore.event.EventDef;
import io.github.glrp.nanocore.pshop.PlayerShop;
import org.bukkit.World;

import java.util.Locale;

/**
 * Markers on the BlueMap web map: every player shop, and the running event's area. Only loaded when BlueMap is
 * installed; refreshed every 30 seconds.
 */
public final class BlueMapMarkers {

    private static final String SHOPS = "nanocore-shops";
    private static final String EVENTS = "nanocore-events";

    private final NanoCorePlugin plugin;
    private volatile BlueMapAPI api;

    public BlueMapMarkers(NanoCorePlugin plugin) {
        this.plugin = plugin;
        BlueMapAPI.onEnable(a -> api = a);
        BlueMapAPI.onDisable(a -> api = null);
    }

    /** Rebuilds the marker sets (main thread: reads shops and events, then hands BlueMap plain data). */
    public void update() {
        BlueMapAPI a = api;
        if (a == null) return;
        for (World w : plugin.getServer().getWorlds()) {
            a.getWorld(w).ifPresent(bw -> {
                MarkerSet shops = MarkerSet.builder().label(plugin.messages().raw("bluemap.shops")).defaultHidden(false).build();
                for (PlayerShop s : plugin.playerShops().all()) {
                    if (!s.pos().world().equals(w.getName())) continue;
                    String label = name(s.itemId()) + " · " + price(s) + " (" + plugin.economy().name(s.owner()) + ")";
                    shops.put("shop-" + s.id(), POIMarker.builder().label(label).position(s.pos().x() + 0.5, s.pos().y() + 1.0, s.pos().z() + 0.5)
                            .maxDistance(1500).build());
                }
                MarkerSet events = MarkerSet.builder().label(plugin.messages().raw("bluemap.events")).defaultHidden(false).build();
                EventDef d = plugin.events().current();
                if (d != null && d.area() != null) {
                    plugin.areas().get(d.area()).filter(ar -> ar.world().equals(w.getName())).ifPresent(ar -> {
                        double x = ar.kind() == Area.Kind.CIRCLE ? ar.x1() : (ar.x1() + ar.x2()) / 2;
                        double z = ar.kind() == Area.Kind.CIRCLE ? ar.z1() : (ar.z1() + ar.z2()) / 2;
                        double y = ar.kind() == Area.Kind.CIRCLE ? w.getHighestBlockYAt((int) x, (int) z) + 1 : ar.y1();
                        events.put("event", POIMarker.builder().label(d.name()).position(x, y, z).maxDistance(5000).build());
                    });
                }
                for (BlueMapMap map : bw.getMaps()) {
                    map.getMarkerSets().put(SHOPS, shops);
                    map.getMarkerSets().put(EVENTS, events);
                }
            });
        }
    }

    private String price(PlayerShop s) {
        var eco = plugin.economy();
        if (s.mode().ownerSells() && s.mode().ownerBuys()) return eco.format(s.sellPrice()) + " / buys " + eco.format(s.buyPrice());
        return s.mode().ownerSells() ? eco.format(s.sellPrice()) : "buys " + eco.format(s.buyPrice());
    }

    private static String name(String id) {
        String n = id.replace('_', ' ');
        return n.isEmpty() ? n : n.substring(0, 1).toUpperCase(Locale.ROOT) + n.substring(1);
    }
}
