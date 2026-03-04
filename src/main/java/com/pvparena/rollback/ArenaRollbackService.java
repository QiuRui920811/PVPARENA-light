package com.pvparena.rollback;

import com.pvparena.model.Arena;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ArenaRollbackService {
    private final JavaPlugin plugin;
    private final NmsBlockWriter nmsBlockWriter;
    private final Map<String, ArenaInstance> activeInstances = new ConcurrentHashMap<>();

    public ArenaRollbackService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.nmsBlockWriter = new NmsBlockWriter();
    }

    public CompletableFuture<Void> initializeSession(String sessionId, String arenaId, Arena arena) {
        if (sessionId == null || arenaId == null || arena == null) {
            return CompletableFuture.completedFuture(null);
        }
        RollbackBounds bounds = computeRollbackBounds(arena);
        if (bounds == null) {
            return CompletableFuture.completedFuture(null);
        }
        ArenaChangeRecorder recorder = new ArenaChangeRecorder(
                bounds.world().getName(),
                bounds.minX(), bounds.maxX(),
                bounds.minY(), bounds.maxY(),
                bounds.minZ(), bounds.maxZ());
        activeInstances.put(sessionId, new ArenaInstance(sessionId, arenaId, recorder));
        return CompletableFuture.completedFuture(null);
    }

    public void warmupArenaBaselinesAsync(Collection<Arena> arenas) {
    }

    public void markDirty(String sessionId, Block block) {
        if (sessionId == null || block == null) {
            return;
        }
        ArenaInstance instance = activeInstances.get(sessionId);
        if (instance == null || instance.isFrozen()) {
            return;
        }
        instance.getChangeRecorder().record(block, nmsBlockWriter);
    }

    public void markDirtyOriginal(String sessionId, BlockState originalState) {
        if (sessionId == null || originalState == null) {
            return;
        }
        ArenaInstance instance = activeInstances.get(sessionId);
        if (instance == null || instance.isFrozen()) {
            return;
        }
        instance.getChangeRecorder().recordOriginal(originalState);
    }

    public boolean hasActiveSession(String sessionId) {
        return sessionId != null && !sessionId.isBlank() && activeInstances.containsKey(sessionId);
    }

    public CompletableFuture<Void> rollbackAndClose(String sessionId) {
        ArenaInstance instance = activeInstances.remove(sessionId);
        if (instance == null) {
            return CompletableFuture.completedFuture(null);
        }
        instance.freeze();

        ArenaChangeRecorder recorder = instance.getChangeRecorder();
        if (recorder == null || recorder.size() == 0) {
            return CompletableFuture.completedFuture(null);
        }

        World world = plugin.getServer().getWorld(recorder.getWorldName());
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }

        Map<Long, java.util.ArrayDeque<BlockRollbackTask>> byChunk = recorder.drainGroupedByChunk();
        if (byChunk.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        RollbackTaskQueue queue = new RollbackTaskQueue(
                plugin,
                world,
                byChunk,
                nmsBlockWriter,
                getRollbackBlocksPerTick(),
                getRollbackChunksPerTick(),
                getRollbackMaxNanosPerChunkTick());
        long startedAt = System.nanoTime();
        CompletableFuture<Void> future = queue.start();
        if (isRollbackMetricsLogEnabled()) {
            int changedCount = queue.getTotalBlocks();
            future.whenComplete((v, ex) -> {
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
                String status = ex == null ? "ok" : "error";
                plugin.getLogger().info("[RollbackMetrics] session=" + sessionId
                        + " arena=" + instance.getArenaId()
                        + " status=" + status
                        + " changed=" + changedCount
                        + " queued=" + queue.getTotalBlocks()
                        + " maxPerChunkTick=" + queue.getMaxProcessedPerChunkTick()
                        + " elapsedMs=" + elapsedMs);
            });
        }
        return future;
    }

    public void recoverPendingSnapshots() {
    }

    private int getRollbackBlocksPerTick() {
        return Math.max(50, plugin.getConfig().getInt("rollback.blocks-per-tick", 200));
    }

    private int getRollbackChunksPerTick() {
        return Math.max(1, plugin.getConfig().getInt("rollback.chunks-per-tick", 2));
    }

    private long getRollbackMaxNanosPerChunkTick() {
        return Math.max(200_000L, plugin.getConfig().getLong("rollback.max-nanos-per-chunk-tick", 2_000_000L));
    }

    private boolean isRollbackMetricsLogEnabled() {
        return plugin.getConfig().getBoolean("rollback.metrics-log", false);
    }

    private RollbackBounds computeRollbackBounds(Arena arena) {
        if (arena == null) {
            return null;
        }
        Location minBound = arena.getMinBound();
        Location maxBound = arena.getMaxBound();
        if (minBound == null || maxBound == null || minBound.getWorld() == null || maxBound.getWorld() == null) {
            return null;
        }
        if (!minBound.getWorld().getName().equalsIgnoreCase(maxBound.getWorld().getName())) {
            return null;
        }

        World world = minBound.getWorld();
        int expand = Math.max(0, plugin.getConfig().getInt("rollback.bounds-expand", 3));
        int expandY = Math.max(0, plugin.getConfig().getInt("rollback.bounds-expand-y", Math.max(8, expand)));

        int minX = Math.min(minBound.getBlockX(), maxBound.getBlockX()) - expand;
        int maxX = Math.max(minBound.getBlockX(), maxBound.getBlockX()) + expand;
        int minY = Math.min(minBound.getBlockY(), maxBound.getBlockY()) - expandY;
        int maxY = Math.max(minBound.getBlockY(), maxBound.getBlockY()) + expandY;
        int minZ = Math.min(minBound.getBlockZ(), maxBound.getBlockZ()) - expand;
        int maxZ = Math.max(minBound.getBlockZ(), maxBound.getBlockZ()) + expand;

        minY = Math.max(world.getMinHeight(), minY);
        maxY = Math.min(world.getMaxHeight() - 1, maxY);
        if (minY > maxY) {
            return null;
        }
        return new RollbackBounds(world, minX, maxX, minY, maxY, minZ, maxZ);
    }

    private record RollbackBounds(World world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
    }
}
