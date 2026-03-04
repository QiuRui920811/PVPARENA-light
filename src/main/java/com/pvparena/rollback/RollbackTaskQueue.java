package com.pvparena.rollback;

import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class RollbackTaskQueue {
    private final JavaPlugin plugin;
    private final World world;
    private final NmsBlockWriter writer;
    private final Map<Long, ArrayDeque<BlockRollbackTask>> tasksByChunk;
    private final Queue<Long> chunkOrder;
    private final int maxBlocksPerTick;
    private final int maxChunksPerTick;
    private final long maxNanosPerChunkTick;
    private final int totalBlocks;
    private final AtomicInteger maxProcessedPerChunkTick = new AtomicInteger(0);
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    public RollbackTaskQueue(JavaPlugin plugin,
                             World world,
                             Map<Long, ArrayDeque<BlockRollbackTask>> tasksByChunk,
                             NmsBlockWriter writer,
                             int maxBlocksPerTick,
                             int maxChunksPerTick,
                             long maxNanosPerChunkTick) {
        this.plugin = plugin;
        this.world = world;
        this.writer = writer;
        this.tasksByChunk = new ConcurrentHashMap<>(tasksByChunk);
        this.chunkOrder = new ConcurrentLinkedQueue<>(tasksByChunk.keySet());
        this.maxBlocksPerTick = Math.max(1, maxBlocksPerTick);
        this.maxChunksPerTick = Math.max(1, maxChunksPerTick);
        this.maxNanosPerChunkTick = Math.max(100_000L, maxNanosPerChunkTick);
        int total = 0;
        for (ArrayDeque<BlockRollbackTask> deque : tasksByChunk.values()) {
            if (deque != null) {
                total += deque.size();
            }
        }
        this.totalBlocks = total;
    }

    public CompletableFuture<Void> start() {
        if (tasksByChunk.isEmpty()) {
            completion.complete(null);
            return completion;
        }
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> runTick());
        return completion;
    }

    private void runTick() {
        if (completion.isDone()) {
            return;
        }
        if (tasksByChunk.isEmpty()) {
            completion.complete(null);
            return;
        }

        AtomicInteger pending = new AtomicInteger(0);
        int scheduled = 0;
        while (scheduled < maxChunksPerTick) {
            Long chunkKey = nextChunkKey();
            if (chunkKey == null) {
                break;
            }
            ArrayDeque<BlockRollbackTask> chunkTasks = tasksByChunk.get(chunkKey);
            if (chunkTasks == null || chunkTasks.isEmpty()) {
                tasksByChunk.remove(chunkKey);
                continue;
            }

            int chunkX = ArenaChangeRecorder.unpackChunkX(chunkKey);
            int chunkZ = ArenaChangeRecorder.unpackChunkZ(chunkKey);
            pending.incrementAndGet();
            scheduled++;

            plugin.getServer().getRegionScheduler().run(plugin, world, chunkX, chunkZ, regionTask -> {
                try {
                    processChunkTasks(chunkTasks);
                } finally {
                    if (chunkTasks.isEmpty()) {
                        tasksByChunk.remove(chunkKey);
                    } else {
                        chunkOrder.offer(chunkKey);
                    }
                    if (pending.decrementAndGet() == 0) {
                        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, t -> runTick(), 1L);
                    }
                }
            });
        }

        if (pending.get() == 0) {
            if (tasksByChunk.isEmpty()) {
                completion.complete(null);
            } else {
                plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, t -> runTick(), 1L);
            }
        }
    }

    private Long nextChunkKey() {
        while (true) {
            Long key = chunkOrder.poll();
            if (key == null) {
                return null;
            }
            if (tasksByChunk.containsKey(key)) {
                return key;
            }
        }
    }

    private void processChunkTasks(ArrayDeque<BlockRollbackTask> chunkTasks) {
        long start = System.nanoTime();
        int processed = 0;
        while (processed < maxBlocksPerTick && !chunkTasks.isEmpty()) {
            if (System.nanoTime() - start >= maxNanosPerChunkTick) {
                break;
            }
            BlockRollbackTask task = chunkTasks.pollFirst();
            if (task == null) {
                break;
            }
            writer.setBlock(world, task.x(), task.y(), task.z(), task.blockDataString());
            processed++;
        }
        maxProcessedPerChunkTick.accumulateAndGet(processed, Math::max);
    }

    public int getTotalBlocks() {
        return totalBlocks;
    }

    public int getMaxProcessedPerChunkTick() {
        return maxProcessedPerChunkTick.get();
    }
}
