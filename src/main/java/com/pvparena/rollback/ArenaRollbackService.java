package com.pvparena.rollback;

import com.pvparena.PvPArenaPlugin;
import com.pvparena.model.Arena;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class ArenaRollbackService {
    private static final int SNAPSHOT_CHUNKS_PER_TICK = 1;

    private final JavaPlugin plugin;
    private final SnapshotStorage snapshotStorage;
    private final NmsBlockWriter nmsBlockWriter;
    private final Map<String, ArenaInstance> activeInstances = new ConcurrentHashMap<>();
    private final Map<String, ArenaSnapshot> arenaBaselines = new ConcurrentHashMap<>();
    private final java.util.Set<String> baselineWarmupInProgress = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> missingBaselineNotice = ConcurrentHashMap.newKeySet();

    public ArenaRollbackService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.snapshotStorage = new SnapshotStorage(plugin);
        this.nmsBlockWriter = new NmsBlockWriter();
    }

    public CompletableFuture<Void> initializeSession(String sessionId, String arenaId, Arena arena) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (sessionId == null || arenaId == null || arena == null) {
            future.complete(null);
            return future;
        }

        Location minBound = arena.getMinBound();
        Location maxBound = arena.getMaxBound();
        if (minBound == null || maxBound == null || minBound.getWorld() == null || maxBound.getWorld() == null) {
            future.complete(null);
            return future;
        }
        if (!minBound.getWorld().getName().equalsIgnoreCase(maxBound.getWorld().getName())) {
            future.complete(null);
            return future;
        }

        World world = minBound.getWorld();
        int minX = Math.min(minBound.getBlockX(), maxBound.getBlockX());
        int maxX = Math.max(minBound.getBlockX(), maxBound.getBlockX());
        int minY = Math.max(world.getMinHeight(), Math.min(minBound.getBlockY(), maxBound.getBlockY()));
        int maxY = Math.min(world.getMaxHeight() - 1, Math.max(minBound.getBlockY(), maxBound.getBlockY()));
        int minZ = Math.min(minBound.getBlockZ(), maxBound.getBlockZ());
        int maxZ = Math.max(minBound.getBlockZ(), maxBound.getBlockZ());

        ArenaSnapshot snapshot = arenaBaselines.get(arenaId);
        if (!isBaselineCompatible(snapshot, world.getName(), minX, maxX, minY, maxY, minZ, maxZ)) {
            if (missingBaselineNotice.add(arenaId)) {
                plugin.getLogger().warning("Arena baseline not ready for " + arenaId + "; skipping rollback for this match to avoid enter lag (warmup continues only from startup queue).");
            }
            future.complete(null);
            return future;
        }

        activeInstances.put(sessionId, new ArenaInstance(sessionId, arenaId, snapshot, new DirtyBlockTracker()));
        if (shouldPersistSessionSnapshotOnEnter()) {
            CompletableFuture.runAsync(() -> {
                try {
                    snapshotStorage.save(sessionId, snapshot);
                } catch (Throwable ex) {
                    plugin.getLogger().warning("Failed to persist session snapshot " + sessionId + ": " + ex.getMessage());
                }
            });
        }
        future.complete(null);
        return future;
    }

    private boolean shouldPersistSessionSnapshotOnEnter() {
        return plugin.getConfig().getBoolean("rollback.persist-session-snapshot-on-enter", false);
    }

    public void warmupArenaBaselinesAsync(Collection<Arena> arenas) {
        if (arenas == null || arenas.isEmpty()) {
            return;
        }
        List<Arena> queue = new ArrayList<>();
        for (Arena arena : arenas) {
            if (arena == null || arena.getId() == null || !arena.isReady()) {
                continue;
            }
            queue.add(arena);
        }
        if (queue.isEmpty()) {
            return;
        }
        runWarmupQueue(queue, 0, getBaselineWarmupDelayTicks());
    }

    private long getBaselineWarmupDelayTicks() {
        return Math.max(1L, plugin.getConfig().getLong("rollback.baseline-warmup-delay-ticks", 40L));
    }

    private void runWarmupQueue(List<Arena> queue, int index, long delayTicks) {
        if (index >= queue.size()) {
            return;
        }
        if (shouldPauseBaselineWarmup()) {
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin,
                    task -> runWarmupQueue(queue, index, getBaselineWarmupPauseTicks()), getBaselineWarmupPauseTicks());
            return;
        }
        Arena arena = queue.get(index);
        warmupArenaBaseline(arena).whenComplete((v, ex) ->
                plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin,
                        task -> runWarmupQueue(queue, index + 1, delayTicks), delayTicks));
    }

    private CompletableFuture<Void> warmupArenaBaseline(Arena arena) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (arena == null || arena.getId() == null) {
            done.complete(null);
            return done;
        }
        String arenaId = arena.getId();
        if (arenaBaselines.containsKey(arenaId) || !baselineWarmupInProgress.add(arenaId)) {
            done.complete(null);
            return done;
        }
        try {
            Location minBound = arena.getMinBound();
            Location maxBound = arena.getMaxBound();
            if (minBound == null || maxBound == null || minBound.getWorld() == null || maxBound.getWorld() == null) {
                done.complete(null);
                return done;
            }
            if (!minBound.getWorld().getName().equalsIgnoreCase(maxBound.getWorld().getName())) {
                done.complete(null);
                return done;
            }

            World world = minBound.getWorld();
            int minX = Math.min(minBound.getBlockX(), maxBound.getBlockX());
            int maxX = Math.max(minBound.getBlockX(), maxBound.getBlockX());
            int minY = Math.max(world.getMinHeight(), Math.min(minBound.getBlockY(), maxBound.getBlockY()));
            int maxY = Math.min(world.getMaxHeight() - 1, Math.max(minBound.getBlockY(), maxBound.getBlockY()));
            int minZ = Math.min(minBound.getBlockZ(), maxBound.getBlockZ());
            int maxZ = Math.max(minBound.getBlockZ(), maxBound.getBlockZ());

            loadOrCreateArenaBaseline(arenaId, world, minX, maxX, minY, maxY, minZ, maxZ)
                    .whenComplete((snapshot, ex) -> {
                        if (ex != null) {
                            plugin.getLogger().warning("Failed to warmup baseline " + arenaId + ": " + ex.getMessage());
                        } else {
                            missingBaselineNotice.remove(arenaId);
                        }
                        done.complete(null);
                    });
        } finally {
            done.whenComplete((v, ex) -> baselineWarmupInProgress.remove(arenaId));
        }
        return done;
    }

    private CompletableFuture<ArenaSnapshot> loadOrCreateArenaBaseline(String arenaId,
                                                                       World world,
                                                                       int minX,
                                                                       int maxX,
                                                                       int minY,
                                                                       int maxY,
                                                                       int minZ,
                                                                       int maxZ) {
        ArenaSnapshot cached = arenaBaselines.get(arenaId);
        if (isBaselineCompatible(cached, world.getName(), minX, maxX, minY, maxY, minZ, maxZ)) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture
                .supplyAsync(() -> loadArenaBaselineIfCompatible(arenaId, world.getName(), minX, maxX, minY, maxY, minZ, maxZ))
                .thenCompose(loaded -> {
                    if (loaded != null) {
                        arenaBaselines.put(arenaId, loaded);
                        return CompletableFuture.completedFuture(loaded);
                    }
                    return captureSnapshotAsync(UUID.nameUUIDFromBytes(arenaId.getBytes()), world, minX, maxX, minY, maxY, minZ, maxZ)
                            .thenApply(captured -> {
                                arenaBaselines.put(arenaId, captured);
                                CompletableFuture.runAsync(() -> {
                                    try {
                                        snapshotStorage.saveArenaBaseline(arenaId, captured);
                                    } catch (Throwable ex) {
                                        plugin.getLogger().warning("Failed to save arena baseline " + arenaId + ": " + ex.getMessage());
                                    }
                                });
                                return captured;
                            });
                });
    }

    private ArenaSnapshot loadArenaBaselineIfCompatible(String arenaId,
                                                        String worldName,
                                                        int minX,
                                                        int maxX,
                                                        int minY,
                                                        int maxY,
                                                        int minZ,
                                                        int maxZ) {
        try {
            ArenaSnapshot baseline = snapshotStorage.loadArenaBaseline(arenaId);
            if (isBaselineCompatible(baseline, worldName, minX, maxX, minY, maxY, minZ, maxZ)) {
                return baseline;
            }
        } catch (Throwable ex) {
            plugin.getLogger().warning("Failed to load arena baseline " + arenaId + ": " + ex.getMessage());
        }
        return null;
    }

    private boolean isBaselineCompatible(ArenaSnapshot snapshot,
                                         String worldName,
                                         int minX,
                                         int maxX,
                                         int minY,
                                         int maxY,
                                         int minZ,
                                         int maxZ) {
        if (snapshot == null || worldName == null) {
            return false;
        }
        return worldName.equalsIgnoreCase(snapshot.getWorldName())
                && snapshot.getMinX() == minX
                && snapshot.getMaxX() == maxX
                && snapshot.getMinY() == minY
                && snapshot.getMaxY() == maxY
                && snapshot.getMinZ() == minZ
                && snapshot.getMaxZ() == maxZ;
    }

    public void markDirty(String sessionId, Block block) {
        if (sessionId == null || block == null) {
            return;
        }
        ArenaInstance instance = activeInstances.get(sessionId);
        if (instance == null) {
            return;
        }
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        if (!instance.getSnapshot().contains(x, y, z)) {
            return;
        }
        instance.getDirtyBlockTracker().markDirty(x, y, z);
    }

    public CompletableFuture<Void> rollbackAndClose(String sessionId) {
        ArenaInstance instance = activeInstances.remove(sessionId);
        if (instance == null) {
            snapshotStorage.delete(sessionId);
            return CompletableFuture.completedFuture(null);
        }
        List<DirtyBlock> dirty = instance.getDirtyBlockTracker().drainAll();
        if (dirty == null || dirty.isEmpty()) {
            snapshotStorage.delete(sessionId);
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> rollback = rollbackSnapshot(instance.getSnapshot(), dirty);
        rollback.whenComplete((rv, ex) -> snapshotStorage.delete(sessionId));
        return rollback;
    }

    public void recoverPendingSnapshots() {
        List<File> snapshotFiles = snapshotStorage.listSnapshotFiles();
        if (snapshotFiles.isEmpty()) {
            return;
        }
        for (File file : snapshotFiles) {
            try {
                ArenaSnapshot snapshot = snapshotStorage.load(file);
                rollbackSnapshot(snapshot, null).whenComplete((rv, ex) -> file.delete());
            } catch (Throwable ex) {
                plugin.getLogger().warning("Failed to recover snapshot file " + file.getName() + ": " + ex.getMessage());
            }
        }
    }

    private CompletableFuture<ArenaSnapshot> captureSnapshotAsync(UUID arenaId, World world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        CompletableFuture<ArenaSnapshot> future = new CompletableFuture<>();
        Map<Long, SectionSnapshot> sections = new LinkedHashMap<>();
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;
        int minSectionY = minY >> 4;
        int maxSectionY = maxY >> 4;
        List<ChunkCaptureTask> chunkTasks = new ArrayList<>();

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                List<Integer> sectionYs = new ArrayList<>();
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    sectionYs.add(sectionY);
                }
                chunkTasks.add(new ChunkCaptureTask(chunkX, chunkZ, sectionYs));
            }
        }

        AtomicInteger cursor = new AtomicInteger(0);
        Runnable runner = new Runnable() {
            @Override
            public void run() {
                if (future.isDone()) {
                    return;
                }
                if (shouldPauseBaselineWarmup()) {
                    plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, t -> run(), getBaselineWarmupPauseTicks());
                    return;
                }
                try {
                    int processed = 0;
                    while (processed < SNAPSHOT_CHUNKS_PER_TICK) {
                        int idx = cursor.getAndIncrement();
                        if (idx >= chunkTasks.size()) {
                            future.complete(new ArenaSnapshot(arenaId, world.getName(), minX, maxX, minY, maxY, minZ, maxZ, sections));
                            return;
                        }
                        ChunkCaptureTask task = chunkTasks.get(idx);
                        world.getChunkAtAsync(task.chunkX(), task.chunkZ(), true).whenComplete((chunk, loadEx) -> {
                            if (future.isDone()) {
                                return;
                            }
                            if (loadEx != null) {
                                future.completeExceptionally(loadEx);
                                return;
                            }
                            plugin.getServer().getRegionScheduler().run(plugin, world, task.chunkX(), task.chunkZ(), regionTask -> {
                                try {
                                    for (Integer sectionY : task.sectionYs()) {
                                        SectionSnapshot section = captureSection(world, task.chunkX(), task.chunkZ(), sectionY, minX, maxX, minY, maxY, minZ, maxZ);
                                        sections.put(ArenaSnapshot.sectionKey(task.chunkX(), task.chunkZ(), sectionY), section);
                                    }
                                    plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, t -> run(), 1L);
                                } catch (Throwable ex) {
                                    future.completeExceptionally(ex);
                                }
                            });
                        });
                        return;
                    }
                    plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> run(), 1L);
                } catch (Throwable ex) {
                    future.completeExceptionally(ex);
                }
            }
        };

        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> runner.run());
        return future;
    }

    private boolean shouldPauseBaselineWarmup() {
        if (!plugin.getConfig().getBoolean("rollback.pause-baseline-warmup-during-match", true)) {
            return false;
        }
        if (!(plugin instanceof PvPArenaPlugin arenaPlugin) || arenaPlugin.getMatchManager() == null) {
            return false;
        }
        return !arenaPlugin.getMatchManager().getActiveMatchSessions().isEmpty();
    }

    private long getBaselineWarmupPauseTicks() {
        return Math.max(10L, plugin.getConfig().getLong("rollback.baseline-warmup-pause-ticks", 40L));
    }

    private SectionSnapshot captureSection(World world, int chunkX, int chunkZ, int sectionY,
                                           int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        short[] indexes = new short[4096];
        List<String> palette = new ArrayList<>();
        Map<String, Short> paletteMap = new HashMap<>();
        for (int localY = 0; localY < 16; localY++) {
            int y = (sectionY << 4) + localY;
            for (int localZ = 0; localZ < 16; localZ++) {
                int z = (chunkZ << 4) + localZ;
                for (int localX = 0; localX < 16; localX++) {
                    int x = (chunkX << 4) + localX;
                    String state;
                    if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
                        state = "minecraft:air";
                    } else {
                        state = nmsBlockWriter.getBlockDataString(world, x, y, z);
                        if (state == null || state.isBlank()) {
                            state = "minecraft:air";
                        }
                    }
                    Short paletteIndex = paletteMap.get(state);
                    if (paletteIndex == null) {
                        paletteIndex = (short) palette.size();
                        paletteMap.put(state, paletteIndex);
                        palette.add(state);
                    }
                    int idx = (localY << 8) | (localZ << 4) | localX;
                    indexes[idx] = paletteIndex;
                }
            }
        }
        return new SectionSnapshot(chunkX, chunkZ, sectionY, indexes, palette.toArray(new String[0]));
    }

    private record ChunkCaptureTask(int chunkX, int chunkZ, List<Integer> sectionYs) {
    }

    private CompletableFuture<Void> rollbackSnapshot(ArenaSnapshot snapshot, List<DirtyBlock> dirtyBlocks) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (snapshot == null) {
            done.complete(null);
            return done;
        }
        World world = plugin.getServer().getWorld(snapshot.getWorldName());
        if (world == null) {
            done.complete(null);
            return done;
        }

        Queue<BlockRollbackTask> queue = new ConcurrentLinkedQueue<>();
        if (dirtyBlocks == null || dirtyBlocks.isEmpty()) {
            for (int x = snapshot.getMinX(); x <= snapshot.getMaxX(); x++) {
                for (int z = snapshot.getMinZ(); z <= snapshot.getMaxZ(); z++) {
                    for (int y = snapshot.getMinY(); y <= snapshot.getMaxY(); y++) {
                        String state = snapshot.resolveBlockDataString(x, y, z);
                        if (state != null) {
                            queue.offer(new BlockRollbackTask(x, y, z, state));
                        }
                    }
                }
            }
        } else {
            for (DirtyBlock dirty : dirtyBlocks) {
                String state = snapshot.resolveBlockDataString(dirty.x(), dirty.y(), dirty.z());
                if (state == null) {
                    continue;
                }
                queue.offer(new BlockRollbackTask(dirty.x(), dirty.y(), dirty.z(), state));
                BlockFace[] neighbors = new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
                for (BlockFace neighbor : neighbors) {
                    int nx = dirty.x() + neighbor.getModX();
                    int ny = dirty.y() + neighbor.getModY();
                    int nz = dirty.z() + neighbor.getModZ();
                    String neighborState = snapshot.resolveBlockDataString(nx, ny, nz);
                    if (neighborState != null) {
                        queue.offer(new BlockRollbackTask(nx, ny, nz, neighborState));
                    }
                }
            }
        }

        RollbackWorker worker = new RollbackWorker(plugin, world, queue, nmsBlockWriter, 200, done);
        worker.start();
        return done;
    }
}
