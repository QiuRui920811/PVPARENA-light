package com.pvparena.rollback;

import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class RollbackWorker implements Runnable {
    private final JavaPlugin plugin;
    private final World world;
    private final Queue<BlockRollbackTask> queue;
    private final NmsBlockWriter blockWriter;
    private final int maxBlocksPerTick;
    private final CompletableFuture<Void> completion;

    public RollbackWorker(JavaPlugin plugin, World world, Queue<BlockRollbackTask> queue,
                          NmsBlockWriter blockWriter, int maxBlocksPerTick,
                          CompletableFuture<Void> completion) {
        this.plugin = plugin;
        this.world = world;
        this.queue = queue;
        this.blockWriter = blockWriter;
        this.maxBlocksPerTick = Math.max(1, maxBlocksPerTick);
        this.completion = completion;
    }

    public void start() {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> run());
    }

    @Override
    public void run() {
        if (completion.isDone()) {
            return;
        }
        List<BlockRollbackTask> batch = new ArrayList<>(maxBlocksPerTick);
        for (int i = 0; i < maxBlocksPerTick; i++) {
            BlockRollbackTask task = queue.poll();
            if (task == null) {
                break;
            }
            batch.add(task);
        }

        if (batch.isEmpty()) {
            completion.complete(null);
            return;
        }

        Map<Long, Queue<BlockRollbackTask>> byChunk = new HashMap<>();
        for (BlockRollbackTask task : batch) {
            int chunkX = task.x() >> 4;
            int chunkZ = task.z() >> 4;
            long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
            byChunk.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>()).offer(task);
        }

        AtomicInteger remain = new AtomicInteger(byChunk.size());
        for (Map.Entry<Long, Queue<BlockRollbackTask>> entry : byChunk.entrySet()) {
            int chunkX = (int) (entry.getKey() >> 32);
            int chunkZ = (int) (entry.getKey().longValue());
            Queue<BlockRollbackTask> chunkQueue = entry.getValue();
            plugin.getServer().getRegionScheduler().run(plugin, world, chunkX, chunkZ, regionTask -> {
                try {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        world.loadChunk(chunkX, chunkZ, true);
                    }
                    BlockRollbackTask current;
                    while ((current = chunkQueue.poll()) != null) {
                        blockWriter.setBlock(world, current.x(), current.y(), current.z(), current.blockDataString());
                    }
                } finally {
                    if (remain.decrementAndGet() == 0) {
                        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, t -> run(), 1L);
                    }
                }
            });
        }
    }
}
