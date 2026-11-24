package io.github.glrp.nanocore.blocks;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Remembers which blocks players placed, so "mine 1,000 blocks" can't be farmed by placing and breaking.
 * Marks live in each chunk's own data (saved with the world), cached while the chunk is loaded.
 * Pistons carry the mark along; falling blocks (sand, gravel) carry it while they fall.
 */
public final class PlacedBlockTracker implements Listener {

    private final NamespacedKey key;
    private final NamespacedKey fallingKey;
    private final Map<UUID, Map<Long, Set<Integer>>> cache = new HashMap<>();
    private final Map<UUID, Set<Long>> dirty = new HashMap<>();

    public PlacedBlockTracker(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "placed");
        this.fallingKey = new NamespacedKey(plugin, "placed_falling");
    }

    public boolean isPlaced(Block b) {
        Set<Integer> s = marks(b.getChunk(), false);
        return s != null && s.contains(BlockKey.pack(b.getX(), b.getY(), b.getZ()));
    }

    public void mark(Block b) {
        if (marks(b.getChunk(), true).add(BlockKey.pack(b.getX(), b.getY(), b.getZ()))) setDirty(b.getChunk());
    }

    public void unmark(Block b) {
        Set<Integer> s = marks(b.getChunk(), false);
        if (s != null && s.remove(BlockKey.pack(b.getX(), b.getY(), b.getZ()))) setDirty(b.getChunk());
    }

    private Set<Integer> marks(Chunk chunk, boolean create) {
        Map<Long, Set<Integer>> world = cache.computeIfAbsent(chunk.getWorld().getUID(), w -> new HashMap<>());
        Set<Integer> s = world.get(chunk.getChunkKey());
        if (s == null) {
            int[] stored = chunk.getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
            if (stored == null && !create) return null;
            s = new HashSet<>();
            if (stored != null) for (int k : stored) s.add(k);
            world.put(chunk.getChunkKey(), s);
        }
        return s;
    }

    private void write(Chunk chunk) {
        Map<Long, Set<Integer>> world = cache.get(chunk.getWorld().getUID());
        Set<Integer> s = world == null ? null : world.get(chunk.getChunkKey());
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        if (s == null || s.isEmpty()) {
            pdc.remove(key);
        } else {
            int[] arr = new int[s.size()];
            int i = 0;
            for (int k : s) arr[i++] = k;
            pdc.set(key, PersistentDataType.INTEGER_ARRAY, arr);
        }
    }

    private void setDirty(Chunk c) {
        dirty.computeIfAbsent(c.getWorld().getUID(), w -> new HashSet<>()).add(c.getChunkKey());
    }

    /** Writes every changed chunk of a world (or all worlds when null) into the chunk data. */
    public void saveAll(World only) {
        for (World w : org.bukkit.Bukkit.getWorlds()) {
            if (only != null && !only.equals(w)) continue;
            Set<Long> keys = dirty.remove(w.getUID());
            if (keys == null) continue;
            for (long k : keys) {
                int x = (int) k;
                int z = (int) (k >> 32);
                if (w.isChunkLoaded(x, z)) write(w.getChunkAt(x, z));
            }
        }
    }

    // --- events (MONITOR: the stats listener reads marks first, at the same priority but registered earlier) ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e instanceof BlockMultiPlaceEvent multi) {
            multi.getReplacedBlockStates().forEach(s -> mark(s.getBlock()));
        } else {
            mark(e.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        unmark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        unmark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().forEach(this::unmark);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().forEach(this::unmark);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        movePlaced(e.getBlocks(), pistonFacing(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        movePlaced(e.getBlocks(), pistonFacing(e.getBlock()).getOppositeFace());
    }

    private static BlockFace pistonFacing(Block piston) {
        return piston.getBlockData() instanceof Directional d ? d.getFacing() : BlockFace.SELF;
    }

    private void movePlaced(List<Block> blocks, BlockFace dir) {
        if (dir == BlockFace.SELF) return;
        List<Block> placed = new ArrayList<>();
        for (Block b : blocks) if (isPlaced(b)) placed.add(b);
        placed.forEach(this::unmark);
        for (Block b : placed) mark(b.getRelative(dir));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFall(EntityChangeBlockEvent e) {
        if (!(e.getEntity() instanceof FallingBlock fb)) return;
        Block b = e.getBlock();
        if (e.getTo().isAir()) {
            // Starts falling: carry the mark on the entity.
            if (isPlaced(b)) {
                unmark(b);
                fb.getPersistentDataContainer().set(fallingKey, PersistentDataType.BYTE, (byte) 1);
            }
        } else if (fb.getPersistentDataContainer().has(fallingKey, PersistentDataType.BYTE)) {
            mark(b); // lands
        }
    }

    @EventHandler
    public void onUnload(ChunkUnloadEvent e) {
        Chunk c = e.getChunk();
        Set<Long> d = dirty.get(c.getWorld().getUID());
        if (d != null && d.remove(c.getChunkKey())) write(c);
        Map<Long, Set<Integer>> world = cache.get(c.getWorld().getUID());
        if (world != null) world.remove(c.getChunkKey());
    }

    @EventHandler
    public void onWorldSave(WorldSaveEvent e) {
        saveAll(e.getWorld());
    }
}
